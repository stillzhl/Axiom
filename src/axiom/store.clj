(ns axiom.store
  "SQLite adapter for the durable ledger (specs 0002, 0003). This is the only
   namespace that touches the database file. All chain logic, validation
   and reduction live in the pure axiom.ledger port; this namespace only
   maps envelopes, snapshots and artifact rows to tables, runs
   transactions and applies numbered forward-only migrations."
  (:require [axiom.contract :as contract]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [clojure.string :as str])
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
  {2 ["CREATE INDEX IF NOT EXISTS idx_events_stream_seq ON events(stream_id, seq)"]
   ;; Spec 0003 R3: artifact digest metadata. Rows are marked, never
   ;; deleted; the digest is the row identity (content identity, not trust).
   3 ["CREATE TABLE artifacts (
        digest TEXT PRIMARY KEY,
        media_type TEXT NOT NULL,
        size_bytes INTEGER NOT NULL,
        retention TEXT NOT NULL,
        location TEXT NOT NULL,
        recorded_seq INTEGER NOT NULL)"]})

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

(defn- query-all [^Connection conn ^String sql params]
  (with-open [stmt (.prepareStatement conn sql)]
    (doseq [[i p] (map-indexed vector params)] (.setObject ^PreparedStatement stmt (inc i) p))
    (with-open [^ResultSet rs (.executeQuery ^PreparedStatement stmt)]
      (loop [acc []]
        (if (.next rs)
          (let [meta (.getMetaData rs)]
            (recur (conj acc (into {} (for [i (range 1 (inc (.getColumnCount meta)))]
                                       [(keyword (.getColumnLabel meta i)) (.getObject rs i)])))))
          acc)))))

;; ---------------------------------------------------------------------------
;; Artifacts (spec 0003 R3)
;;
;; The artifacts table records content digests, media types, sizes,
;; retention statuses and location references for files digested with
;; axiom.model/sha256-bytes (SHA-256 over exact raw bytes). The digest is
;; content identity only — it does not establish that a trustworthy
;; producer created the bytes. The ledger stores digests, never binary
;; payloads. Rows are marked (retained/expired/superseded), never deleted,
;; so past decisions stay reproducible from bundles.

(def default-media-type
  "Media type recorded when the caller supplies none. A content label,
   never a trust statement."
  "application/octet-stream")

(def ^:private retention-statuses #{:retained :expired :superseded})

;; recorded_seq sentinel for artifacts recorded without a ledger event.
;; The column is NOT NULL per the design DDL; nil reads back as nil.
(def ^:private unrecorded-seq -1)

(defn- artifact-digest? [value]
  (and (string? value) (boolean (re-matches #"sha256:[0-9a-f]{64}" value))))

(defn- media-type? [value]
  ;; Non-blank validated label with a restricted character set, in the
  ;; shape of the existing id? predicate (plus '+' for suffixes such as
  ;; application/atom+xml). Content label, never a trust statement.
  (and (string? value)
       (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._/+.-]{0,127}" value))))

(defn- non-negative-long? [value]
  (and (integer? value) (<= 0 value Long/MAX_VALUE)))

(defn- retention-status
  "Normalizes a keyword or string retention value to a keyword, or nil."
  [value]
  (cond (keyword? value) (when (contains? retention-statuses value) value)
        (string? value) (let [k (keyword value)]
                          (when (contains? retention-statuses k) k))))

(def ^:private artifact-fields
  #{:artifact/digest :artifact/media-type :artifact/size-bytes :artifact/retention
    :artifact/location :artifact/recorded-seq :artifact/bytes})

(defn- validate-artifact-input! [artifact]
  (when-not (map? artifact)
    (model/invalid! "Artifact must be a map" {}))
  (when-let [unknown (seq (remove artifact-fields (keys artifact)))]
    (model/invalid! "Unknown artifact fields" {:unknown (vec unknown)}))
  (let [digest (:artifact/digest artifact)]
    (when-not (artifact-digest? digest)
      (model/invalid! "Invalid artifact digest" {:digest digest})))
  (let [media-type (get artifact :artifact/media-type default-media-type)]
    (when-not (media-type? media-type)
      (model/invalid! "Invalid artifact media type" {:media-type media-type})))
  (let [size (:artifact/size-bytes artifact)]
    (when-not (non-negative-long? size)
      (model/invalid! "Invalid artifact size" {:size-bytes size})))
  (when-not (retention-status (get artifact :artifact/retention :retained))
    (model/invalid! "Invalid artifact retention status"
                    {:retention (:artifact/retention artifact)}))
  (let [location (:artifact/location artifact)]
    (when-not (and (string? location) (not (str/blank? location)))
      (model/invalid! "Invalid artifact location" {:location location})))
  (let [recorded-seq (get artifact :artifact/recorded-seq nil)]
    (when-not (or (nil? recorded-seq) (non-negative-long? recorded-seq))
      (model/invalid! "Invalid artifact recorded-seq" {:recorded-seq recorded-seq})))
  ;; Optional boundary cross-check: when the caller supplies the exact
  ;; bytes, the digest and size are verified against them here. A mismatch
  ;; is an input problem, not an operational failure.
  (let [bs (:artifact/bytes artifact)]
    (when (some? bs)
      (when-not (bytes? bs)
        (model/invalid! "Artifact bytes must be a byte array"
                        {:value-type (str (type bs))}))
      (when-not (= (:artifact/digest artifact) (model/sha256-bytes ^bytes bs))
        (model/invalid! "Artifact digest does not match supplied bytes"
                        {:reason :digest-mismatch :digest (:artifact/digest artifact)}))
      (when-not (= (long (:artifact/size-bytes artifact)) (alength ^bytes bs))
        (model/invalid! "Artifact size does not match supplied bytes"
                        {:reason :size-mismatch
                         :size-bytes (:artifact/size-bytes artifact)
                         :byte-count (alength ^bytes bs)}))))
  artifact)

(def ^:private bound-keys #{:max/artifact-bytes :max/retained-artifacts})

(defn- validate-bounds! [bounds]
  (when-not (map? bounds)
    (model/invalid! "Retention bounds must be a map" {}))
  (doseq [k bound-keys]
    (let [v (get bounds k ::missing)]
      (when-not (non-negative-long? v)
        (model/invalid! "Retention bound must be a non-negative integer"
                        {:bound k :value (when-not (= ::missing v) v)}))))
  bounds)

(defn- normalize-artifact-row! [artifact]
  (validate-artifact-input! artifact)
  {:digest (:artifact/digest artifact)
   :media-type (get artifact :artifact/media-type default-media-type)
   :size-bytes (long (:artifact/size-bytes artifact))
   :retention (name (retention-status (get artifact :artifact/retention :retained)))
   :location (:artifact/location artifact)
   :recorded-seq (if-some [s (:artifact/recorded-seq artifact)] (long s) unrecorded-seq)})

(defn- row->artifact
  "Read-back validation of a stored artifact row. Stored corruption is an
   operational failure detected on read, never silently normalized.
   Checks: digest is a well-formed sha256:<64hex> identity; media_type is
   a non-blank restricted-charset label; size_bytes is a non-negative
   integer; retention is one of retained|expired|superseded; location is a
   non-blank string; recorded_seq is an integer >= -1 (-1 reads back as
   nil, meaning no recording event). Content itself cannot be re-verified
   here: the ledger stores digests, never payload bytes, so a tampered
   digest that is still well-formed sha256:<64hex> is indistinguishable
   from a legitimately recorded different artifact at the row level —
   content-level checking belongs at the record boundary (the optional
   :artifact/bytes cross-check) and in caller-held bytes."
  [row]
  (let [digest (:digest row)
        media-type (:media_type row)
        size (:size_bytes row)
        retention (:retention row)
        location (:location row)
        recorded-seq (:recorded_seq row)]
    (when-not (artifact-digest? digest)
      (operational! "Stored artifact digest is malformed"
                    {:reason :malformed-artifact-row :column :digest}))
    (when-not (media-type? media-type)
      (operational! "Stored artifact media type is malformed"
                    {:reason :malformed-artifact-row :column :media_type}))
    (when-not (non-negative-long? size)
      (operational! "Stored artifact size is malformed"
                    {:reason :malformed-artifact-row :column :size_bytes}))
    (when-not (contains? #{"retained" "expired" "superseded"} retention)
      (operational! "Stored artifact retention status is malformed"
                    {:reason :malformed-artifact-row :column :retention}))
    (when-not (and (string? location) (not (str/blank? location)))
      (operational! "Stored artifact location is malformed"
                    {:reason :malformed-artifact-row :column :location}))
    (when-not (and (integer? recorded-seq) (<= unrecorded-seq recorded-seq Long/MAX_VALUE))
      (operational! "Stored artifact recorded-seq is malformed"
                    {:reason :malformed-artifact-row :column :recorded_seq}))
    {:artifact/digest digest
     :artifact/media-type media-type
     :artifact/size-bytes (long size)
     :artifact/retention (keyword retention)
     :artifact/location location
     :artifact/recorded-seq (when (>= (long recorded-seq) 0) (long recorded-seq))}))

(def ^:private artifact-columns
  "digest, media_type, size_bytes, retention, location, recorded_seq")

(declare read-artifact)

(defn record-artifact!
  "Records an artifact row. artifact is a map with:
     :artifact/digest        required, sha256:<64hex> (content identity)
     :artifact/media-type    optional validated label, default
                             application/octet-stream
     :artifact/size-bytes    required, non-negative integer
     :artifact/retention     optional :retained|:expired|:superseded
                             (keyword or string), default :retained
     :artifact/location      required, non-blank location reference
     :artifact/recorded-seq  optional ledger seq of the recording event,
                             or nil (default) for unrecorded — stored as
                             the -1 sentinel, reads back as nil
     :artifact/bytes         optional exact bytes; when supplied the
                             digest and size are cross-checked against
                             them (mismatch is :invalid)
   bounds is the caller-declared retention bounds map
   {:max/artifact-bytes N :max/retained-artifacts M}. Exceeding either
   bound is an operational failure with the bound and the reason named —
   never a truncated success. A duplicate digest is rejected
   deterministically (:duplicate / :duplicate-artifact-digest). The
   retained-artifacts bound counts rows with retention 'retained';
   expired and superseded rows do not count. Insert and bound checks run
   in one transaction. Returns the recorded artifact map."
  [handle bounds artifact]
  (validate-bounds! bounds)
  (let [row (normalize-artifact-row! artifact)
        max-bytes (long (:max/artifact-bytes bounds))
        max-retained (long (:max/retained-artifacts bounds))
        ^Connection conn (:connection handle)]
    (when (> (:size-bytes row) max-bytes)
      (operational! "Artifact payload exceeds the declared byte bound"
                    {:reason :max-artifact-bytes-exceeded
                     :bound max-bytes :actual (:size-bytes row)
                     :digest (:digest row)}))
    (try
      (with-tx conn
        (fn []
          (try
            (with-open [stmt (.prepareStatement conn
                              (str "INSERT INTO artifacts (" artifact-columns ") "
                                   "VALUES (?, ?, ?, ?, ?, ?)"))]
              (doseq [[i p] (map-indexed vector
                                         [(:digest row) (:media-type row) (:size-bytes row)
                                          (:retention row) (:location row) (:recorded-seq row)])]
                (.setObject ^PreparedStatement stmt (inc i) p))
              (.executeUpdate stmt))
            (catch SQLException e
              (if (re-find #"UNIQUE constraint failed: artifacts\.digest" (str (.getMessage e)))
                (throw (ex-info "Duplicate artifact digest rejected"
                                {:axiom/error :duplicate
                                 :reason :duplicate-artifact-digest
                                 :digest (:digest row)}))
                (throw e))))
          (let [retained (long (:n (query-one conn
                                     "SELECT COUNT(*) AS n FROM artifacts WHERE retention='retained'"
                                     [])))]
            (when (> retained max-retained)
              (operational! "Retained artifact count exceeds the declared bound"
                            {:reason :max-retained-artifacts-exceeded
                             :bound max-retained :actual retained
                             :digest (:digest row)})))
          ;; Re-read the committed row so the returned map has passed the
          ;; same read-back validation as any later read.
          (row->artifact (query-one conn
                           (str "SELECT " artifact-columns " FROM artifacts WHERE digest=?")
                           [(:digest row)]))))
      (catch clojure.lang.ExceptionInfo e (throw e))
      (catch SQLException e
        (operational! "Artifact record failed" {:cause (str e)})))))

(defn mark-artifact!
  "Sets the retention status of the artifact row for digest to
   :retained, :expired or :superseded (keyword or string). Rows are
   marked, never deleted, so past decisions stay reproducible. Returns
   the updated artifact map. A well-formed but unknown digest is invalid
   input (:unknown-artifact)."
  [handle digest retention]
  (when-not (artifact-digest? digest)
    (model/invalid! "Invalid artifact digest" {:digest digest}))
  (let [status (retention-status retention)]
    (when-not status
      (model/invalid! "Invalid artifact retention status" {:retention retention}))
    (let [^Connection conn (:connection handle)
          updated (with-open [stmt (.prepareStatement conn
                                     "UPDATE artifacts SET retention=? WHERE digest=?")]
                    (.setString ^PreparedStatement stmt 1 (name status))
                    (.setString ^PreparedStatement stmt 2 digest)
                    (.executeUpdate stmt))]
      (when (zero? updated)
        (model/invalid! "Unknown artifact digest"
                        {:reason :unknown-artifact :digest digest}))
      (read-artifact handle digest))))

(defn read-artifact
  "Reads the artifact row for digest, or nil when no such row exists.
   The stored row passes read-back validation; a malformed column is an
   operational failure detected on read."
  [handle digest]
  (when-not (artifact-digest? digest)
    (model/invalid! "Invalid artifact digest" {:digest digest}))
  (let [^Connection conn (:connection handle)
        row (query-one conn
               (str "SELECT " artifact-columns " FROM artifacts WHERE digest=?")
               [digest])]
    (when row (row->artifact row))))

(defn list-artifacts
  "Lists artifact rows ordered by digest, each validated on read.
   Optional filter {:retention :retained|:expired|:superseded}."
  ([handle] (list-artifacts handle {}))
  ([handle opts]
   (let [filter-status (when (contains? opts :retention)
                         (or (retention-status (:retention opts))
                             (model/invalid! "Invalid artifact retention filter"
                                             {:retention (:retention opts)})))
         ^Connection conn (:connection handle)
         [sql params] (if filter-status
                        [(str "SELECT " artifact-columns
                              " FROM artifacts WHERE retention=? ORDER BY digest")
                         [(name filter-status)]]
                        [(str "SELECT " artifact-columns " FROM artifacts ORDER BY digest")
                         []])]
     (mapv row->artifact (query-all conn sql params)))))
