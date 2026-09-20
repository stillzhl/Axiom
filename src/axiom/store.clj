(ns axiom.store
  "SQLite adapter for the durable ledger (spec 0002). This is the only
   namespace that touches the database file. All chain logic, validation
   and reduction live in the pure axiom.ledger port; this namespace only
   maps envelopes and snapshots to rows, runs transactions and applies
   numbered forward-only migrations."
  (:require [axiom.contract :as contract]
            [axiom.ledger :as ledger]
            [axiom.model :as model])
  (:import (java.io File)
           (java.sql Connection DriverManager PreparedStatement ResultSet SQLException)))

(def supported-schema-version ledger/supported-schema-version)

(def ^:private base-ddl
  ["CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)"
   "CREATE TABLE IF NOT EXISTS events (
      seq INTEGER PRIMARY KEY,
      event_id TEXT NOT NULL UNIQUE,
      schema_version INTEGER NOT NULL,
      stream_id TEXT NOT NULL,
      dedup_key TEXT NOT NULL UNIQUE,
      producer TEXT NOT NULL,
      observed_time INTEGER NOT NULL,
      ingested_time INTEGER NOT NULL,
      candidate_id TEXT NOT NULL,
      payload_digest TEXT NOT NULL,
      prev_hash TEXT NOT NULL,
      payload TEXT NOT NULL)"
   "CREATE TABLE IF NOT EXISTS snapshots (
      seq INTEGER PRIMARY KEY,
      reducer_version TEXT NOT NULL,
      world_digest TEXT NOT NULL,
      head_hash TEXT NOT NULL,
      world TEXT NOT NULL)"])

;; Numbered, forward-only, transactional migrations. A migration may add
;; tables, columns or indexes; it must never rewrite stored event payloads.
(def ^:private migrations
  {2 ["CREATE INDEX IF NOT EXISTS idx_events_stream_seq ON events(stream_id, seq)"]})

(defn- operational! [message data]
  (ledger/operational! message data))

(defn- ^Connection connect! [path]
  (try
    (Class/forName "org.sqlite.JDBC")
    (DriverManager/getConnection (str "jdbc:sqlite:" path))
    (catch Exception e
      (operational! "Cannot open ledger database" {:path path :cause (str e)}))))

(defn- exec! [^Connection conn ^String sql]
  (with-open [stmt (.createStatement conn)]
    (.execute stmt sql)))

(defn- query-one [^Connection conn ^String sql params]
  (with-open [stmt (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject ^PreparedStatement stmt (inc i) p))
    (with-open [^ResultSet rs (.executeQuery ^PreparedStatement stmt)]
      (when (.next rs)
        (let [meta (.getMetaData rs)]
          (into {} (for [i (range 1 (inc (.getColumnCount meta)))]
                     [(keyword (.getColumnLabel meta i)) (.getObject rs i)])))))))

(defn- with-tx [^Connection conn f]
  (let [prev (.getAutoCommit conn)]
    (.setAutoCommit conn false)
    (try
      (let [result (f)]
        (.commit conn)
        result)
      (catch Exception e
        (try (.rollback conn) (catch Exception _))
        (throw e))
      (finally (.setAutoCommit conn prev)))))

(defn- table-exists? [^Connection conn table]
  (boolean (:name (query-one conn "SELECT name FROM sqlite_master WHERE type='table' AND name=?" [table]))))

(defn- read-schema-version [^Connection conn]
  (when (table-exists? conn "schema_version")
    (let [row (query-one conn "SELECT version FROM schema_version LIMIT 1" [])]
      (when row (int (:version row))))))

(defn- init-schema! [^Connection conn]
  (with-tx conn
    (fn []
      (doseq [ddl base-ddl] (exec! conn ddl))
      (with-open [stmt (.prepareStatement conn "INSERT INTO schema_version (version) VALUES (1)")]
        (.executeUpdate stmt)))))

(defn- apply-migration! [^Connection conn version statements]
  (with-tx conn
    (fn []
      (doseq [sql statements] (exec! conn sql))
      (with-open [stmt (.prepareStatement conn "UPDATE schema_version SET version=?")]
        (.setInt stmt 1 version)
        (.executeUpdate stmt)))))

(defn migrate!
  "Applies pending numbered migrations up to the supported version.
   Returns the resulting schema version."
  [handle]
  (let [^Connection conn (:connection handle)
        current (or (read-schema-version conn)
                    (operational! "Ledger has no schema version" {:path (:path handle)}))]
    (when (> current supported-schema-version)
      (operational! "Ledger schema is newer than supported"
                    {:ledger-version current :supported supported-schema-version}))
    (doseq [v (sort (filter #(> % current) (keys migrations)))]
      (apply-migration! conn v (get migrations v)))
    (let [final (or (read-schema-version conn) current)]
      (assoc handle :schema/version final))))

(defn open!
  "Opens a ledger. Options: :create (create the schema if absent),
   :migrate (default true; apply pending migrations). A missing file with
   :create false is invalid input; a schema newer than supported is an
   operational failure."
  ([path] (open! path {}))
  ([path {:keys [create migrate] :or {create false migrate true}}]
   (let [file (File. ^String path)]
     (when (and (not (.exists file)) (not create))
       (model/invalid! "Ledger file does not exist" {:path path}))
     (let [fresh (or (not (.exists file)) (zero? (.length file)))
           ^Connection conn (connect! path)]
       (try
         (exec! conn "PRAGMA journal_mode=WAL")
         (exec! conn "PRAGMA busy_timeout=5000")
         (cond
           (and fresh create) (init-schema! conn)
           (and fresh (not create)) (do (.close conn)
                                        (model/invalid! "Not a ledger database" {:path path}))
           :else (when-not (table-exists? conn "schema_version")
                   (.close conn)
                   (operational! "Not a ledger database" {:path path :reason :missing-schema-version})))
         (let [version (or (read-schema-version conn)
                           (operational! "Ledger has no schema version" {:path path}))]
           (when (> version supported-schema-version)
             (.close conn)
             (operational! "Ledger schema is newer than supported"
                           {:path path :ledger-version version :supported supported-schema-version}))
           (let [handle {:connection conn :path path :schema/version version}]
             (if (and migrate (< version supported-schema-version))
               (migrate! handle)
               handle)))
         (catch clojure.lang.ExceptionInfo e (try (.close conn) (catch Exception _)) (throw e))
         (catch Exception e (try (.close conn) (catch Exception _))
                            (operational! "Cannot open ledger database" {:path path :cause (str e)})))))))

(defn close!
  "Closes the ledger connection."
  [handle]
  (when-let [^Connection conn (:connection handle)]
    (try (.close conn) (catch Exception _)))
  nil)

(def ^:private envelope-columns
  "seq, event_id, schema_version, stream_id, dedup_key, producer,
   observed_time, ingested_time, candidate_id, payload_digest, prev_hash, payload")

(defn- row->envelope [row]
  (let [payload (try (contract/read-data (:payload row))
                     (catch clojure.lang.ExceptionInfo e
                       (operational! "Stored payload is not valid EDN"
                                     {:seq (:seq row) :cause (.getMessage e)})))
        envelope {:seq (int (:seq row))
                  :event/id (:event_id row)
                  :schema/version (int (:schema_version row))
                  :stream/id (:stream_id row)
                  :dedup/key (:dedup_key row)
                  :producer (:producer row)
                  :observed/time (long (:observed_time row))
                  :ingested/time (long (:ingested_time row))
                  :candidate/id (:candidate_id row)
                  :payload/digest (:payload_digest row)
                  :prev/hash (:prev_hash row)
                  :payload payload}]
    (when (not= (:payload/digest envelope) (model/digest payload))
      (operational! "Stored payload digest mismatch" {:seq (:seq envelope)}))
    (ledger/stored-envelope! envelope)))

(defn- insert-envelope! [^Connection conn seq envelope]
  (with-open [stmt (.prepareStatement conn
                    (str "INSERT INTO events (" envelope-columns ") "
                         "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"))]
    (let [params [seq (:event/id envelope) (:schema/version envelope)
                  (:stream/id envelope) (:dedup/key envelope) (:producer envelope)
                  (long (:observed/time envelope)) (long (:ingested/time envelope))
                  (:candidate/id envelope) (:payload/digest envelope)
                  (:prev/hash envelope) (model/edn-str (:payload envelope))]]
      (doseq [[i p] (map-indexed vector params)] (.setObject ^PreparedStatement stmt (inc i) p))
      (.executeUpdate stmt))))

(defn- duplicate-reason [^SQLException e]
  (let [message (str (.getMessage e))]
    (cond (re-find #"UNIQUE constraint failed: events\.event_id" message) :duplicate-event-id
          (re-find #"UNIQUE constraint failed: events\.dedup_key" message) :duplicate-dedup-key
          :else nil)))

(defn append!
  "Appends a validated envelope, assigning the next sequence number inside
   a single transaction. The envelope's :prev/hash must match the current
   head (stale writes are rejected). Duplicate event IDs and duplicate
   deduplication keys are rejected deterministically. Returns the stored
   envelope with :seq."
  [handle envelope]
  (ledger/validate-envelope! envelope)
  (let [^Connection conn (:connection handle)]
    (try
      (with-tx conn
        (fn []
          (let [head (query-one conn
                       (str "SELECT " envelope-columns " FROM events ORDER BY seq DESC LIMIT 1") [])
                head-env (when head (row->envelope head))
                expected-prev (if head-env (ledger/chain-digest head-env) "")]
            (when (not= expected-prev (:prev/hash envelope))
              (model/invalid! "Envelope prev-hash does not match ledger head"
                              {:expected expected-prev :actual (:prev/hash envelope)}))
            (let [seq (if head-env (inc (int (:seq head-env))) 0)]
              (try
                (insert-envelope! conn seq envelope)
                (catch SQLException e
                  (if-let [reason (duplicate-reason e)]
                    (throw (ex-info "Duplicate ledger append rejected"
                                    {:axiom/error :duplicate :reason reason
                                     :event/id (:event/id envelope)}))
                    (throw e))))
              (assoc envelope :seq seq)))))
      (catch clojure.lang.ExceptionInfo e (throw e))
      (catch SQLException e
        (operational! "Ledger append failed" {:cause (str e)})))))

(defn read-range
  "Reads stored envelopes with from <= seq <= through, in sequence order."
  [handle from through]
  (when (or (not (integer? from)) (not (integer? through)) (< from 0) (< through 0))
    (model/invalid! "Invalid read range" {:from from :through through}))
  (when (< through from)
    (model/invalid! "Invalid read range: through is before from" {:from from :through through}))
  (let [^Connection conn (:connection handle)
        rows (with-open [stmt (.prepareStatement conn
                               (str "SELECT " envelope-columns " FROM events "
                                    "WHERE seq >= ? AND seq <= ? ORDER BY seq"))]
               (.setLong ^PreparedStatement stmt 1 (long from))
               (.setLong ^PreparedStatement stmt 2 (long through))
               (with-open [^ResultSet rs (.executeQuery ^PreparedStatement stmt)]
                 (loop [acc []]
                   (if (.next rs)
                     (let [meta (.getMetaData rs)]
                       (recur (conj acc (into {} (for [i (range 1 (inc (.getColumnCount meta)))]
                                                              [(keyword (.getColumnLabel meta i)) (.getObject rs i)])))))
                     acc))))]
    (mapv row->envelope rows)))

(defn head
  "Returns {:seq :head/hash} of the latest event, or nil for an empty ledger."
  [handle]
  (let [^Connection conn (:connection handle)
        row (query-one conn (str "SELECT " envelope-columns " FROM events ORDER BY seq DESC LIMIT 1") [])]
    (when row
      (let [env (row->envelope row)]
        {:seq (:seq env) :head/hash (ledger/chain-digest env)}))))

(defn ledger-identity
  "Ledger identity for reports: schema version, head hash, event count."
  [handle]
  (let [^Connection conn (:connection handle)
        row (query-one conn "SELECT COUNT(*) AS n, COALESCE(MAX(seq), -1) AS max_seq FROM events" [])
        h (head handle)]
    {:schema/version (:schema/version handle)
     :event/count (int (:n row))
     :head/hash (or (:head/hash h) "")}))

(defn- snapshot->row [snapshot]
  {:seq (:snapshot/seq snapshot)
   :reducer_version (:reducer/version snapshot)
   :world_digest (:world/digest snapshot)
   :head_hash (:head/hash snapshot)
   :world (model/edn-str (:world snapshot))})

(defn- row->snapshot [row]
  {:snapshot/seq (int (:seq row))
   :reducer/version (:reducer_version row)
   :world/digest (:world_digest row)
   :head/hash (:head_hash row)
   :world (try (contract/read-data (:world row))
               (catch clojure.lang.ExceptionInfo e
                 (operational! "Stored snapshot world is not valid EDN"
                               {:seq (:seq row) :cause (.getMessage e)})))})

(defn store-snapshot!
  "Stores a snapshot map (see axiom.ledger/build-snapshot)."
  [handle snapshot]
  (let [^Connection conn (:connection handle)
        row (snapshot->row snapshot)]
    (with-open [stmt (.prepareStatement conn
                      "INSERT OR REPLACE INTO snapshots (seq, reducer_version, world_digest, head_hash, world)
                       VALUES (?, ?, ?, ?, ?)")]
      (doseq [[i p] (map-indexed vector [(:seq row) (:reducer_version row) (:world_digest row)
                                         (:head_hash row) (:world row)])]
        (.setObject ^PreparedStatement stmt (inc i) p))
      (.executeUpdate stmt)))
  snapshot)

(defn latest-snapshot
  "Returns the snapshot covering the highest sequence, or nil."
  [handle]
  (let [^Connection conn (:connection handle)
        row (query-one conn "SELECT seq, reducer_version, world_digest, head_hash, world
                             FROM snapshots ORDER BY seq DESC LIMIT 1" [])]
    (when row (row->snapshot row))))

(defn take-snapshot!
  "Reduces events 0..head, stores the snapshot and returns it.
   Returns nil for an empty ledger."
  [handle]
  (let [h (head handle)]
    (when h
      (let [envelopes (read-range handle 0 (:seq h))
            snapshot (ledger/build-snapshot envelopes)]
        (store-snapshot! handle snapshot)
        snapshot))))
