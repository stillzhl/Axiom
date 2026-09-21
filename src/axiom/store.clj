(ns axiom.store
  "SQLite adapter for the durable ledger (specs 0002, 0003, 0005 T3, 0006 T3). This is the only
   namespace that touches the database file. All chain logic, validation
   and reduction live in the pure axiom.ledger port; lease transition
   decisions live in the pure axiom.execute port; this namespace only
   maps envelopes, snapshots, artifact and lease rows to tables, runs
   transactions and applies numbered forward-only migrations."
  (:require [axiom.contract :as contract]
            [axiom.execute :as execute]
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
        recorded_seq INTEGER NOT NULL)"]
   ;; Spec 0005 T3: covering index for producer-ordered scans (gate
   ;; decision / governance audit queries). Additive only; never
   ;; rewrites stored event payloads.
   4 ["CREATE INDEX IF NOT EXISTS idx_events_producer_seq ON events(producer, seq)"]
   ;; Spec 0006 T3: materialized current-lease projection. The events
   ;; table remains the source of truth; this sidecar exists so the
   ;; single-holder lease invariant is enforced by a uniqueness
   ;; constraint inside the same transaction that appends the lease
   ;; event — two concurrent acquires serialize to exactly one
   ;; success. The sidecar is a pure function of the event prefix
   ;; (see rebuild-leases!); replay-equivalence of the ledger is
   ;; unaffected. Additive only; never rewrites stored event
   ;; payloads.
   5 ["CREATE TABLE IF NOT EXISTS current_leases (
        task_id TEXT PRIMARY KEY,
        worker_id TEXT NOT NULL,
        token TEXT NOT NULL,
        expires_at INTEGER NOT NULL,
        issued_by TEXT NOT NULL,
        acquired_seq INTEGER NOT NULL)"]})

(defn- operational! [message data]
  (ledger/operational! message data))

(defn- ^Connection connect! [path]
  (try
    (Class/forName "org.sqlite.JDBC")
    (let [^Connection conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
      ;; Concurrent lease acquirers must serialize on the write lock
      ;; rather than fail fast: a bounded busy timeout turns lock
      ;; contention into waiting, so two concurrent acquires resolve
      ;; to exactly one success via the uniqueness constraint.
      (with-open [stmt (.createStatement conn)]
        (.execute stmt "PRAGMA busy_timeout=5000"))
      conn)
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

(defn- with-immediate-tx
  "Runs f in a `BEGIN IMMEDIATE` transaction: the RESERVED write
   lock is taken up front, before any read. A concurrent writer
   blocks on the lock (bounded by the connection's busy_timeout)
   instead of deadlocking on a SHARED->RESERVED upgrade, which
   SQLite reports as an immediate SQLITE_BUSY that the busy
   handler cannot smooth over. Used by the lease operations, whose
   read-check-then-write shape races under concurrency."
  [^Connection conn f]
  (with-open [begin (.createStatement conn)]
    (.execute begin "BEGIN IMMEDIATE"))
  (try
    (let [result (f)]
      (with-open [commit (.createStatement conn)]
        (.execute commit "COMMIT"))
      result)
    (catch Exception e
      (try (with-open [rb (.createStatement conn)]
             (.execute rb "ROLLBACK"))
           (catch Exception _))
      (throw e))))

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

(defn- append-in-tx!
  "Appends a validated envelope inside an ambient transaction,
   assigning the next sequence number. The envelope's :prev/hash must
   match the current head (stale writes are rejected). Duplicate
   event IDs and duplicate deduplication keys are rejected
   deterministically. Returns the stored envelope with :seq."
  [^Connection conn envelope]
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
      (assoc envelope :seq seq))))

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
      (with-tx conn (fn [] (append-in-tx! conn envelope)))
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

(defn media-type?
  "A media type is a non-blank validated label with a restricted
   character set, in the shape of the existing id? predicate (plus '+'
   for suffixes such as application/atom+xml). Content label, never a
   trust statement. Pure; reused by the `digest` CLI for --media-type
   validation."
  [value]
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

;; ------------------------------------------------------------------
;; Leases (spec 0006 T3)
;;
;; Transactional lease operations over the `:lease/*` event kinds.
;; The events table is the source of truth; `current_leases` is a
;; materialized projection maintained in the same transaction as
;; each lease event append, with a uniqueness constraint on
;; task_id enforcing the single-holder invariant. Two concurrent
;; acquires serialize on the write lock (bounded busy_timeout, see
;; connect!): the loser hits the uniqueness constraint and is
;; denied with :task-already-leased — never a second lease.
;;
;; Every function takes `now` — the supervisor's recorded time, an
;; integer — because expiry is computed from recorded time, never
;; from a trusted wall clock. Pure transition decisions come from
;; `axiom.execute`; this namespace only runs them inside
;; transactions and maps rows.

(defn- head-envelope-in-tx [^Connection conn]
  (let [head (query-one conn
                        (str "SELECT " envelope-columns " FROM events ORDER BY seq DESC LIMIT 1") [])]
    (when head (row->envelope head))))

(defn- lease-row->lease [row]
  {:lease/task-id (:task_id row)
   :lease/worker-id (:worker_id row)
   :lease/token (:token row)
   :lease/expires-at (long (:expires_at row))
   :lease/issued-by (:issued_by row)
   :lease/acquired-seq (long (:acquired_seq row))})

(defn- read-current-leases
  "Reads the current_leases sidecar inside an ambient transaction,
   expiry-filtered at `now`. Returns {task-id lease-record}."
  [^Connection conn now]
  (let [rows (query-all conn
                        (str "SELECT task_id, worker_id, token, expires_at, issued_by, acquired_seq"
                             " FROM current_leases")
                        [])]
    (into {}
          (comp (map lease-row->lease)
                (filter (fn [lease] (< now (:lease/expires-at lease))))
                (map (fn [lease] [(:lease/task-id lease) lease])))
          rows)))

(defn current-leases
  "The live current-lease projection from the sidecar,
   expiry-filtered at recorded time `now`. Read-only."
  [handle now]
  (read-current-leases (:connection handle) now))

(defn current-lease
  "The live lease for one task at recorded time `now`, or nil.
   Read-only."
  [handle task-id now]
  (get (current-leases handle now) task-id))

(defn- insert-lease-row! [^Connection conn lease]
  (with-open [stmt (.prepareStatement conn
                                      (str "INSERT INTO current_leases"
                                           " (task_id, worker_id, token, expires_at, issued_by, acquired_seq)"
                                           " VALUES (?,?,?,?,?,?)"))]
    (.setString ^PreparedStatement stmt 1 (:lease/task-id lease))
    (.setString ^PreparedStatement stmt 2 (:lease/worker-id lease))
    (.setString ^PreparedStatement stmt 3 (:lease/token lease))
    (.setLong ^PreparedStatement stmt 4 (long (:lease/expires-at lease)))
    (.setString ^PreparedStatement stmt 5 (:lease/issued-by lease))
    (.setLong ^PreparedStatement stmt 6 (long (:lease/acquired-seq lease)))
    (.executeUpdate stmt)))

(defn- update-lease-row! [^Connection conn task-id token expires-at issued-by]
  (with-open [stmt (.prepareStatement conn
                                      (str "UPDATE current_leases"
                                           " SET token=?, expires_at=?, issued_by=?"
                                           " WHERE task_id=?"))]
    (.setString ^PreparedStatement stmt 1 token)
    (.setLong ^PreparedStatement stmt 2 (long expires-at))
    (.setString ^PreparedStatement stmt 3 issued-by)
    (.setString ^PreparedStatement stmt 4 task-id)
    (.executeUpdate stmt)))

(defn- delete-lease-row! [^Connection conn task-id]
  (with-open [stmt (.prepareStatement conn
                                      "DELETE FROM current_leases WHERE task_id=?")]
    (.setString ^PreparedStatement stmt 1 task-id)
    (.executeUpdate stmt)))

(defn- default-dedup-key [event]
  (str "lease/" (name (:event/kind event)) "/"
       (:lease/task-id event) "/"
       (or (:lease/token event) (:lease/presented-token event) "none")))

(defn- lease-envelope-inputs
  "Builds the `ledger/record-lease` inputs from the decided lease
   event and the caller's envelope overrides. `:record/producer`,
   `:record/observed-time` and `:record/ingested-time` are required;
   the event id, stream id and dedup key default deterministically
   from the event — the dedup key makes a crash-retry of the same
   attempt idempotent instead of a second lease."
  [lease-event input]
  (let [task-id (:lease/task-id lease-event)]
    {:event/id (or (:record/event-id input)
                   (str "lease-event/" task-id "/"
                        (name (:event/kind lease-event)) "/"
                        (or (:lease/token lease-event)
                            (:lease/presented-token lease-event))))
     :stream/id (or (:record/stream-id input) (str "task-stream/" task-id))
     :dedup/key (or (:record/dedup-key input) (default-dedup-key lease-event))
     :producer (:record/producer input)
     :observed/time (:record/observed-time input)
     :ingested/time (:record/ingested-time input)
     :lease-event lease-event}))

(defn- unique-lease-violation? [^SQLException e]
  (boolean (re-find #"UNIQUE constraint failed: current_leases\.task_id"
                    (str (.getMessage e)))))

(defn acquire-lease!
  "Transactional lease acquire. In one transaction: decides via
   `axiom.execute/acquire-lease` against the in-transaction
   sidecar, installs the sidecar row, and appends the
   `:lease/acquired` event through the 0002 path (transactional
   sequence, hash chain, dedup).

   Returns `{:lease/ok true, :lease/lease <lease-record>,
   :lease/seq <event seq>}`. A live lease on the task denies with
   `{:lease/ok false, :lease/reason :task-already-leased}` —
   including the concurrent-acquire loser, via the uniqueness
   constraint. A retry of the same attempt (same task and token)
   returns the live lease with `:lease/duplicate? true` instead of
   a second lease. Malformed input returns `{:lease/ok false,
   :lease/reason :malformed}` and nothing is appended.

   `input` carries the `:lease/*` fields
   (`:lease/task-id`, `:lease/worker-id`, `:lease/token`,
   `:lease/expires-at`, `:lease/issued-by`) plus the `:record/*`
   envelope overrides (`:record/producer`,
   `:record/observed-time`, `:record/ingested-time` required;
   `:record/event-id`, `:record/stream-id`, `:record/dedup-key`
   optional). `now` is the supervisor's recorded time."
  [handle input now]
  (let [^Connection conn (:connection handle)]
    (try
      (with-immediate-tx conn
        (fn []
          (let [live (read-current-leases conn now)
                task-id (:lease/task-id input)
                existing (get live task-id)]
            (cond
              ;; Idempotent crash-retry: the same attempt already
              ;; holds the lease — return it, never a second lease.
              (and existing (= (:lease/token existing) (:lease/token input)))
              {:lease/ok true :lease/duplicate? true :lease/lease existing}

              :else
              (let [decision (execute/acquire-lease live input)]
                (if (not (:lease/ok decision))
                  decision
                  (let [event (:lease/event decision)
                        envelope (ledger/record-lease (head-envelope-in-tx conn)
                                                      (lease-envelope-inputs event input))
                        ;; Clear any stale (expired) row first; a live
                        ;; row would have denied above. The uniqueness
                        ;; constraint then admits exactly one
                        ;; concurrent acquirer.
                        _ (delete-lease-row! conn task-id)
                        stored (append-in-tx! conn envelope)
                        lease {:lease/task-id (:lease/task-id event)
                               :lease/worker-id (:lease/worker-id event)
                               :lease/token (:lease/token event)
                               :lease/expires-at (:lease/expires-at event)
                               :lease/issued-by (:lease/issued-by event)
                               :lease/acquired-seq (:seq stored)}]
                    (insert-lease-row! conn lease)
                    {:lease/ok true :lease/lease lease
                     :lease/seq (:seq stored)})))))))
      (catch SQLException e
        (if (unique-lease-violation? e)
          {:lease/ok false :lease/reason :task-already-leased}
          (throw e)))
      (catch clojure.lang.ExceptionInfo e
        (if (= :duplicate (:axiom/error (ex-data e)))
          ;; Backstop: the dedup key shows this exact attempt was
          ;; already recorded. Return the live lease — never a
          ;; second one.
          (let [lease (current-lease handle (:lease/task-id input) now)]
            {:lease/ok true :lease/duplicate? true :lease/lease lease})
          (throw e))))))

(defn renew-lease!
  "Transactional lease renewal. The presented token must match the
   task's current lease; renewal rotates the fencing token and
   extends the expiry. A wrong token is denied with
   `:stale-fencing-token` and nothing is appended. `input` carries
   `:lease/task-id`, `:lease/presented-token`, `:lease/token` (the
   new token), `:lease/expires-at`, `:lease/issued-by`, plus the
   `:record/*` envelope overrides."
  [handle input now]
  (let [^Connection conn (:connection handle)]
    (with-immediate-tx conn
      (fn []
        (let [live (read-current-leases conn now)
              decision (execute/renew-lease live input)]
          (if (not (:lease/ok decision))
            decision
            (let [event (:lease/event decision)
                  envelope (ledger/record-lease (head-envelope-in-tx conn)
                                                (lease-envelope-inputs event input))
                  stored (append-in-tx! conn envelope)
                  task-id (:lease/task-id event)]
              (update-lease-row! conn task-id (:lease/token event)
                                 (:lease/expires-at event)
                                 (:lease/issued-by event))
              {:lease/ok true
               :lease/lease (assoc (get live task-id)
                                   :lease/token (:lease/token event)
                                   :lease/expires-at (:lease/expires-at event)
                                   :lease/issued-by (:lease/issued-by event))
               :lease/seq (:seq stored)})))))))

(defn release-lease!
  "Transactional lease release. The worker presents its current
   fencing token; a mismatch is denied with `:stale-fencing-token`
   and nothing is appended. `input` carries `:lease/task-id`,
   `:lease/presented-token`, plus the `:record/*` envelope
   overrides (`:lease/issued-by` defaults to `:record/producer`)."
  [handle input now]
  (let [^Connection conn (:connection handle)]
    (with-immediate-tx conn
      (fn []
        (let [live (read-current-leases conn now)
              full-input (update input :lease/issued-by
                                 #(or % (:record/producer input)))
              decision (execute/release-lease live full-input)]
          (if (not (:lease/ok decision))
            decision
            (let [event (:lease/event decision)
                  envelope (ledger/record-lease (head-envelope-in-tx conn)
                                                (lease-envelope-inputs event full-input))
                  stored (append-in-tx! conn envelope)]
              (delete-lease-row! conn (:lease/task-id event))
              {:lease/ok true :lease/seq (:seq stored)})))))))

(defn revoke-lease!
  "Transactional lease revocation. Evaluator/authorizer-initiated:
   requires the issuing identity and a named reason, never a
   fencing token. `input` carries `:lease/task-id`,
   `:lease/issued-by`, `:lease/reason`, plus the `:record/*`
   envelope overrides."
  [handle input now]
  (let [^Connection conn (:connection handle)]
    (with-immediate-tx conn
      (fn []
        (let [live (read-current-leases conn now)
              decision (execute/revoke-lease live input)]
          (if (not (:lease/ok decision))
            decision
            (let [event (:lease/event decision)
                  envelope (ledger/record-lease (head-envelope-in-tx conn)
                                                (lease-envelope-inputs event input))
                  stored (append-in-tx! conn envelope)]
              (delete-lease-row! conn (:lease/task-id event))
              {:lease/ok true :lease/seq (:seq stored)})))))))

(defn expire-leases!
  "Appends `:lease/expired` events for sidecar rows whose expiry is
   at or before `now`, clearing the rows, all in one transaction.
   `input` supplies the `:record/*` envelope overrides shared by
   the expiry events (`:lease/issued-by` defaults to
   `:record/producer`). Returns the vector of expired task ids."
  [handle input now]
  (let [^Connection conn (:connection handle)]
    (with-immediate-tx conn
      (fn []
        (let [rows (query-all conn
                              (str "SELECT task_id, worker_id, token, expires_at, issued_by, acquired_seq"
                                   " FROM current_leases WHERE expires_at <= ?")
                              [now])
              issued-by (or (:lease/issued-by input) (:record/producer input))]
          (mapv (fn [row]
                  (let [lease (lease-row->lease row)
                        task-id (:lease/task-id lease)
                        event {:event/kind :lease/expired
                               :lease/task-id task-id
                               :lease/token (:lease/token lease)
                               :lease/issued-by issued-by}
                        envelope (ledger/record-lease (head-envelope-in-tx conn)
                                                      (lease-envelope-inputs event input))]
                    (delete-lease-row! conn task-id)
                    (append-in-tx! conn envelope)
                    task-id))
                rows))))))

(defn rebuild-leases!
  "Rebuilds the current_leases sidecar from the events table. The
   sidecar is a pure function of the event prefix, so truncating
   and re-folding must reproduce it exactly — this proves the
   projection's replay-equivalence. Returns the rebuilt
   {task-id lease} map."
  [handle now]
  (let [^Connection conn (:connection handle)
        envs (read-range handle 0 Long/MAX_VALUE)]
    (with-immediate-tx conn
      (fn []
        (exec! conn "DELETE FROM current_leases")
        (let [events (map (fn [env]
                            (assoc (get-in env [:payload :lease/event])
                                   :lease/acquired-seq (:seq env)))
                          (filter #(= :lease (get-in % [:payload :record/kind]))
                                  envs))
              rebuilt (execute/current-leases events now)]
          (doseq [[_ lease] rebuilt]
            (insert-lease-row! conn lease))
          rebuilt)))))

;; ------------------------------------------------------------------
;; Action outbox (spec 0006 T4)
;;
;; Transactional outbox operations over the `:outbox/*` event kinds.
;; The events table is the source of truth; the current outbox state
;; is the pure fold `axiom.execute/outbox-intents` over the event
;; prefix (`outbox-state`), so crash recovery is replay: on restart
;; the supervisor re-reads the prefix and reconciles with
;; `axiom.execute/reconcile-outbox`.
;;
;; Every operation runs in a `BEGIN IMMEDIATE` transaction: the
;; write lock is taken before the read, so a read-check-then-write
;; race (two supervisors recording the same idempotency key)
;; serializes — the second sees the first's committed intent and
;; deduplicates instead of recording a second execution.
;;
;; Fencing (T3): recording and transitioning intents requires the
;; task's current fencing token — a stale worker cannot move
;; intents. Supervisor-only: `:outbox/issued-by` must equal the
;; lease's `:lease/issued-by` (the evaluator identity); the worker
;; never records or transitions intents itself.
;;
;; Every function takes `now` — the supervisor's recorded time —
;; for symmetry with the lease operations.

(defn- outbox-events
  "The `:outbox/*` event maps in ledger order — the replay
   primitive everything outbox folds from."
  [handle]
  (map :outbox/event
       (map :payload
            (filter #(= :outbox (get-in % [:payload :record/kind]))
                    (read-range handle 0 Long/MAX_VALUE)))))

(defn outbox-state
  "The live outbox projection: {idempotency-key intent} folded over
   the `:outbox/*` events in ledger order. Read-only; this is the
   replay primitive the supervisor uses on restart before
   reconciling."
  [handle]
  (execute/outbox-intents (outbox-events handle)))

(defn- outbox-envelope-inputs
  "Builds the `ledger/record-outbox` inputs from the decided outbox
   event and the caller's envelope overrides. `:record/producer`,
   `:record/observed-time` and `:record/ingested-time` are required;
   the event id, stream id and dedup key default deterministically
   from the event — the dedup key makes a crash-retry of the same
   recording idempotent at the 0002 layer as well. `task-id` is the
   intent's task (terminal events don't carry it on the event map,
   so the caller resolves it from the existing intent)."
  [outbox-event input task-id]
  (let [key (:outbox/idempotency-key outbox-event)]
    {:event/id (or (:record/event-id input)
                   ;; The raw idempotency key is not a valid 0002
                   ;; event id (it contains ':'), so derive a
                   ;; deterministic id from its digest.
                   (str "outbox-event/"
                        (subs (model/digest (str key "/" (name (:event/kind outbox-event))
                                                 "/attempt-" (:outbox/attempt outbox-event 0)))
                              7)))
     :stream/id (or (:record/stream-id input)
                    (str "task-stream/" task-id))
     :dedup/key (or (:record/dedup-key input)
                    ;; The raw idempotency key is not a valid 0002
                    ;; dedup key (it contains ':'), so the key's
                    ;; digest stands in. The attempt is part of the
                    ;; dedup key so a re-drive (or a second
                    ;; uncertainty marking after a re-drive) is a
                    ;; distinct 0002 record, while a crash-retry of
                    ;; the same recording is still idempotent.
                    (str "outbox/" (subs (model/digest key) 7) "/"
                         (name (:event/kind outbox-event))
                         "/attempt-" (:outbox/attempt outbox-event 0)))
     :producer (:record/producer input)
     :observed/time (:record/observed-time input)
     :ingested/time (:record/ingested-time input)
     :outbox-event outbox-event}))

(defn record-intent!
  "Transactional intent recording. In one transaction: decides via
   `axiom.execute/record-intent` against the in-transaction outbox
   fold and the current-leases sidecar, and appends the
   `:outbox/intent-recorded` event through the 0002 path
   (transactional sequence, hash chain, dedup).

   Returns `{:outbox/ok true, :outbox/intent <intent>,
   :outbox/seq <event seq>}`. A resubmission of the same
   idempotency key returns `{:outbox/ok false, :outbox/reason
   :duplicate-intent, :outbox/existing <intent>}` — the existing
   intent, never a second execution. A stale fencing token denies
   with `:stale-fencing-token`; a non-supervisor issuer with
   `:not-supervisor`; malformed input with `:malformed` — and
   nothing is appended.

   `input` carries `:outbox/task-id`, `:outbox/action` (keyword),
   `:outbox/payload` (map), `:outbox/fencing-token` and
   `:outbox/issued-by` (the supervisor/evaluator identity), plus
   the `:record/*` envelope overrides (`:record/producer`,
   `:record/observed-time`, `:record/ingested-time` required)."
  [handle input now]
  (let [^Connection conn (:connection handle)]
    (with-immediate-tx conn
      (fn []
        (let [events (outbox-events handle)
              intents (execute/outbox-intents events)
              leases (read-current-leases conn now)
              decision (execute/record-intent intents leases input)]
          (if (not (:outbox/ok decision))
            decision
            (let [event (:outbox/event decision)
                  envelope (ledger/record-outbox (head-envelope-in-tx conn)
                                                 (outbox-envelope-inputs
                                                  event input (:outbox/task-id input)))
                  stored (append-in-tx! conn envelope)
                  key (:outbox/idempotency-key event)]
              {:outbox/ok true
               :outbox/intent (get (execute/outbox-intents (concat events [event])) key)
               :outbox/seq (:seq stored)})))))))

(defn transition-intent!
  "Transactional supervisor-only intent transition. In one
   transaction: decides via `axiom.execute/transition-intent`
   against the in-transaction outbox fold and the current-leases
   sidecar, and appends the resulting `:outbox/*` event through the
   0002 path.

   The fencing token must match the task's current lease — a stale
   worker's transition is denied with `:stale-fencing-token` and
   nothing is appended. Unknown intents, illegal moves and
   non-supervisor issuers are denied with named reasons. A move to
   `:executing` is transient (supervisor-local, never recorded):
   it returns `{:outbox/ok true, :outbox/transient true}` and
   appends nothing.

   `input` carries `:outbox/idempotency-key`,
   `:outbox/to-state`, `:outbox/fencing-token`,
   `:outbox/issued-by`, plus optional `:outbox/reason`
   (`:failed`), `:outbox/detail` (`:uncertain`) and
   `:outbox/provider-ref` (`:executed`), and the `:record/*`
   envelope overrides."
  [handle input now]
  (let [^Connection conn (:connection handle)]
    (with-immediate-tx conn
      (fn []
        (let [events (outbox-events handle)
              intents (execute/outbox-intents events)
              leases (read-current-leases conn now)
              decision (execute/transition-intent intents leases input)]
          (cond
            (not (:outbox/ok decision))
            decision

            (:outbox/transient decision)
            (dissoc decision :outbox/event)

            :else
            (let [event (:outbox/event decision)
                  key (:outbox/idempotency-key event)
                  envelope (ledger/record-outbox
                            (head-envelope-in-tx conn)
                            (outbox-envelope-inputs
                             event input (:outbox/task-id (get intents key))))
                  stored (append-in-tx! conn envelope)]
              {:outbox/ok true
               :outbox/intent (get (execute/outbox-intents (concat events [event])) key)
               :outbox/seq (:seq stored)})))))))
