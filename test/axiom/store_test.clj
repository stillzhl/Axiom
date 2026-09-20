(ns axiom.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.fixtures :as f]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.store :as store])
  (:import (java.io File)
           (java.sql DriverManager)))

(defn- temp-db []
  (let [file (File/createTempFile "axiom-ledger-test" ".db")]
    (.delete file)
    (.getPath file)))

(defn- delete-db! [path]
  (doseq [suffix ["" "-wal" "-shm" "-journal"]]
    (.delete (File. (str path suffix)))))

(defmacro ^:private with-db [[path-sym] & body]
  `(let [~path-sym (temp-db)]
     (try ~@body (finally (delete-db! ~path-sym)))))

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(defn- error-reason [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- scenario-inputs [n t]
  {:event/id (str "evt-" n) :stream/id "synthetic-project" :dedup/key (str "submit-" n)
   :producer "synthetic-runner" :observed/time t :ingested/time (+ t 5)
   :scenario (assoc f/scenario :now (+ 1100 n))})

(defn- append-scenarios!
  "Appends n scenario envelopes, tracking the head. Returns stored envelopes."
  [handle n]
  (loop [prev nil done [] i 0]
    (if (= i n)
      done
      (let [stored (store/append! handle (ledger/record-scenario prev (scenario-inputs i (* i 100))))]
        (recur stored (conj done stored) (inc i))))))

(deftest open-create-close
  (with-db [path]
    (testing "missing file without :create is invalid input"
      (is (= :invalid (error-kind #(store/open! path)))))
    (testing "create initializes the schema and migrates to supported"
      (let [handle (store/open! path {:create true})]
        (try
          (is (= store/supported-schema-version (:schema/version handle)))
          (is (= {:schema/version store/supported-schema-version :event/count 0 :head/hash ""}
                 (store/ledger-identity handle)))
          (is (nil? (store/head handle)))
          (is (nil? (store/take-snapshot! handle)))
          (finally (store/close! handle)))))
    (testing "a non-database file is an operational failure"
      (spit path "this is not sqlite")
      (is (= :operational (error-kind #(store/open! path {:create false})))))))

(deftest append-assigns-sequence
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (let [[e0 e1 e2] (append-scenarios! handle 3)]
          (is (= [0 1 2] (mapv :seq [e0 e1 e2])))
          (is (= 3 (:event/count (store/ledger-identity handle))))
          (is (= 2 (:seq (store/head handle))))
          (let [read (store/read-range handle 0 2)]
            (is (= ["evt-0" "evt-1" "evt-2"] (mapv :event/id read)))
            (is (:chain/valid? (ledger/verify-chain read))))
          (testing "read-range bounds are validated"
            (is (= :invalid (error-kind #(store/read-range handle -1 2))))
            (is (= :invalid (error-kind #(store/read-range handle 2 1))))))
        (finally (store/close! handle))))))

(deftest duplicate-append-rejected
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (let [[e0] (append-scenarios! handle 1)
              redeliver (fn [event-id dedup-key]
                          (ledger/record-scenario
                           e0 (assoc (scenario-inputs 99 100)
                                     :event/id event-id :dedup/key dedup-key)))]
          (testing "reused event id is rejected"
            (is (= :duplicate (error-kind #(store/append! handle (redeliver "evt-0" "submit-99")))))
            (is (= :duplicate-event-id (error-reason #(store/append! handle (redeliver "evt-0" "submit-99"))))))
          (testing "redelivered dedup key is rejected deterministically"
            (is (= :duplicate-dedup-key (error-reason #(store/append! handle (redeliver "evt-99" "submit-0"))))))
          (testing "rejected appends leave the ledger unchanged"
            (is (= 1 (:event/count (store/ledger-identity handle))))
            (is (:chain/valid? (ledger/verify-chain (store/read-range handle 0 10))))))
        (finally (store/close! handle))))))

(deftest stale-prev-hash-rejected
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (let [[e0 e1] (append-scenarios! handle 2)
              stale (ledger/record-scenario e0 (scenario-inputs 99 100))]
          (is (= :invalid (error-kind #(store/append! handle stale))))
          (is (= 2 (:event/count (store/ledger-identity handle)))))
        (finally (store/close! handle))))))

(deftest interrupted-append-leaves-no-phantom
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (store/append! handle (ledger/record-scenario nil (scenario-inputs 0 0)))
      (store/close! handle))
    (testing "a writer that dies mid-transaction leaves no committed event"
      (Class/forName "org.sqlite.JDBC")
      (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
        (try
          (.setAutoCommit conn false)
          (with-open [stmt (.prepareStatement conn
                            "INSERT INTO events (seq, event_id, schema_version, stream_id, dedup_key,
                                                producer, observed_time, ingested_time, candidate_id,
                                                payload_digest, prev_hash, payload)
                             VALUES (1, 'evt-crash', 1, 's', 'crash-key', 'p', 1, 1, 'sha256:0', 'sha256:0', '', '{}')")]
            (.executeUpdate stmt))
          ;; No commit: the connection is closed with the transaction open,
          ;; exactly like a writer killed mid-append.
          (finally (.close conn))))
      (let [handle (store/open! path)]
        (try
          (let [envs (store/read-range handle 0 100)]
            (is (= 1 (count envs)))
            (is (= "evt-0" (:event/id (first envs))))
            (is (:chain/valid? (ledger/verify-chain envs))))
          ;; The ledger still appends cleanly afterwards.
          (let [[e0] (store/read-range handle 0 0)
                e1 (store/append! handle (ledger/record-scenario e0 (scenario-inputs 1 100)))]
            (is (= 1 (:seq e1)))
            (is (= 2 (:event/count (store/ledger-identity handle)))))
          (finally (store/close! handle)))))))

(deftest snapshot-replay-equivalence-generated
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        ;; Deterministic pseudo-random stream: scenario records and single
        ;; events with shuffled observed times and duplicate deliveries.
        (let [seeds (iterate (fn [x] (mod (+ (* 1103515245 x) 12345) 2147483648)) 42)
              prev (atom nil)
              stored (atom [])
              append-next! (fn [env] (let [s (store/append! handle env)]
                                       (reset! prev s) (swap! stored conj s) s))]
          (doseq [[i seed] (map-indexed vector (take 14 seeds))]
            (let [observed (mod seed 5000)
                  inputs (scenario-inputs i observed)]
              (cond
                (< (mod seed 10) 6) (append-next! (ledger/record-scenario @prev inputs))
                (< (mod seed 10) 8) (append-next!
                                     (ledger/record-event
                                      @prev {:event/id (str "evt-ev-" i) :stream/id "synthetic-project"
                                             :dedup/key (str "submit-ev-" i) :producer "synthetic-runner"
                                             :observed/time observed :ingested/time (+ observed 3)
                                             :candidate/id (model/candidate-id f/candidate)
                                             :event (assoc f/evidence :id (str "ev-" i))}))
                :else (append-next!
                       (ledger/record-event
                        @prev {:event/id (str "evt-cl-" i) :stream/id "synthetic-project"
                               :dedup/key (str "submit-cl-" i) :producer "synthetic-runner"
                               :observed/time observed :ingested/time (+ observed 3)
                               :candidate/id (model/candidate-id f/candidate)
                               :event (assoc f/claim :id (str "claim-" i))})))))
          ;; Duplicate deliveries are rejected, not merged.
          (let [n (count @stored)]
            (is (= :duplicate-dedup-key
                   (error-reason #(store/append! handle
                                                 (ledger/record-scenario
                                                  @prev (assoc (scenario-inputs 999 1)
                                                               :event/id "evt-redeliver"
                                                               :dedup/key "submit-0"))))))
            (is (= n (:event/count (store/ledger-identity handle)))))
          ;; Snapshot at a boundary; restore + replay must equal full replay.
          (let [envs @stored
                boundary 5
                snap (ledger/build-snapshot (subvec (vec envs) 0 (inc boundary)))
                _ (store/store-snapshot! handle snap)
                loaded (store/latest-snapshot handle)
                full-digest (model/digest (ledger/ledger-world envs))
                restored-digest (model/digest (ledger/restore-world loaded (subvec (vec envs) (inc boundary))))]
            (is (= (:snapshot/seq snap) (:snapshot/seq loaded)))
            ;; The snapshot digest covers its prefix, not the full ledger.
            (is (= (:world/digest snap)
                   (model/digest (ledger/ledger-world (subvec (vec envs) 0 (inc boundary))))))
            (is (= full-digest restored-digest))
            (testing "take-snapshot! covers the head"
              (let [head-snap (store/take-snapshot! handle)]
                (is (= (dec (count envs)) (:snapshot/seq head-snap)))
                (is (= full-digest (:world/digest head-snap)))))))
        (finally (store/close! handle))))))

(deftest schema-migration-v1-to-v2
  (with-db [path]
    (let [handle (store/open! path {:create true :migrate false})]
      (is (= 1 (:schema/version handle)))
      (let [envs (append-scenarios! handle 2)
            before-world (model/digest (ledger/ledger-world envs))]
        (store/close! handle)
        (testing "migrate! advances forward-only to the supported version"
          (let [reopened (store/open! path {:create false :migrate true})]
            (try
              (is (= store/supported-schema-version (:schema/version reopened)))
              (testing "stored payloads are byte-identical across the migration"
                (let [after (store/read-range reopened 0 10)]
                  (is (= (mapv :payload/digest envs) (mapv :payload/digest after)))
                  (is (= (mapv #(model/digest (:payload %)) envs)
                         (mapv #(model/digest (:payload %)) after)))
                  (is (= before-world (model/digest (ledger/ledger-world after))))
                  (is (:chain/valid? (ledger/verify-chain after)))))
              (testing "the v2 covering index exists"
                (Class/forName "org.sqlite.JDBC")
                (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
                  (try
                    (with-open [stmt (.prepareStatement conn
                                      "SELECT name FROM sqlite_master WHERE type='index' AND name=?")]
                      (.setString stmt 1 "idx_events_stream_seq")
                      (with-open [rs (.executeQuery stmt)]
                        (is (.next rs))))
                    (finally (.close conn)))))
              (finally (store/close! reopened)))))))))

(deftest newer-schema-than-code-is-operational
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (store/close! handle))
    (Class/forName "org.sqlite.JDBC")
    (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
      (try
        (with-open [stmt (.prepareStatement conn "UPDATE schema_version SET version=999")]
          (.executeUpdate stmt))
        (finally (.close conn))))
    (is (= :operational (error-kind #(store/open! path))))))
