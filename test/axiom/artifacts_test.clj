(ns axiom.artifacts-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [axiom.fixtures :as f]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.store :as store])
  (:import (java.io File)
           (java.nio.file Files)
           (java.sql DriverManager)))

(defn- temp-db []
  (let [file (File/createTempFile "axiom-artifacts-test" ".db")]
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

(defn- error-data [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- scenario-inputs [n t]
  {:event/id (str "evt-" n) :stream/id "synthetic-project" :dedup/key (str "submit-" n)
   :producer "synthetic-runner" :observed/time t :ingested/time (+ t 5)
   :scenario (assoc f/scenario :now (+ 1100 n))})

(defn- append-scenarios!
  [handle n]
  (loop [prev nil done [] i 0]
    (if (= i n)
      done
      (let [stored (store/append! handle (ledger/record-scenario prev (scenario-inputs i (* i 100))))]
        (recur stored (conj done stored) (inc i))))))

(defn- utf8 [^String s] (.getBytes s "UTF-8"))

(defn- test-digest [^String s] (model/sha256-bytes (utf8 s)))

(defn- artifact-input
  ([digest size] (artifact-input digest size {}))
  ([digest size overrides]
   (merge {:artifact/digest digest
           :artifact/media-type "text/plain"
           :artifact/size-bytes size
           :artifact/location "file:///tmp/synthetic-artifact.txt"}
          overrides)))

(def ^:private generous-bounds {:max/artifact-bytes 1000000 :max/retained-artifacts 1000})

(defn- sql-tamper!
  "Direct-SQL helper for tamper tests: applies f to a fresh connection."
  [path f]
  (Class/forName "org.sqlite.JDBC")
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (try
      (.setAutoCommit conn false)
      (f conn)
      (.commit conn)
      (finally (.close conn)))))

(deftest sha256-bytes-known-vectors
  (testing "well-known SHA-256 vectors, exact bytes"
    (is (= "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (model/sha256-bytes (byte-array 0))))
    (is (= "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
           (model/sha256-bytes (utf8 "abc")))))
  (testing "raw bytes, not the 0001 canonical EDN encoding"
    (is (not= (model/digest "abc") (model/sha256-bytes (utf8 "abc")))))
  (testing "exact bytes: a trailing newline changes the digest"
    (is (not= (model/sha256-bytes (utf8 "x")) (model/sha256-bytes (utf8 "x\n"))))
    (is (not= (model/sha256-bytes (utf8 "")) (model/sha256-bytes (utf8 "\n")))))
  (testing "non-byte-array input is invalid"
    (is (= :invalid (error-kind #(model/sha256-bytes "abc"))))
    (is (= :invalid (error-kind #(model/sha256-bytes nil))))))

(deftest sha256-bytes-matches-sha256sum
  (doseq [content ["synthetic artifact bytes"
                   "synthetic artifact bytes\n"
                   "line one\nline two\n"]]
    (let [file (File/createTempFile "axiom-sha256" ".bin")]
      (try
        (Files/write (.toPath file) (utf8 content) (into-array java.nio.file.OpenOption []))
        (let [{:keys [exit out]} (shell/sh "sha256sum" (.getPath file))
              expected (str "sha256:" (first (clojure.string/split out #"\s+")))]
          (is (zero? exit))
          (is (= expected (model/sha256-bytes (Files/readAllBytes (.toPath file))))
              (str "content: " (pr-str content))))
        (finally (.delete file))))))

(deftest record-and-read-artifact-roundtrip
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (is (= store/supported-schema-version (:schema/version handle)))
        (let [[e0 e1 e2] (append-scenarios! handle 3)
              digest (test-digest "artifact-content")
              recorded (store/record-artifact!
                        handle generous-bounds
                        (artifact-input digest 16
                                        {:artifact/media-type "application/json"
                                         :artifact/location "file:///tmp/synthetic.json"
                                         :artifact/recorded-seq (:seq e2)
                                         :artifact/bytes (utf8 "artifact-content")}))
              expected {:artifact/digest digest
                        :artifact/media-type "application/json"
                        :artifact/size-bytes 16
                        :artifact/retention :retained
                        :artifact/location "file:///tmp/synthetic.json"
                        :artifact/recorded-seq 2}]
          (testing "record returns the normalized artifact map"
            (is (= expected recorded)))
          (testing "read returns the identical map"
            (is (= expected (store/read-artifact handle digest))))
          (testing "media type, size and location recorded exactly"
            (let [row (store/read-artifact handle digest)]
              (is (= "application/json" (:artifact/media-type row)))
              (is (= 16 (:artifact/size-bytes row)))
              (is (= "file:///tmp/synthetic.json" (:artifact/location row))))))
        (testing "media type defaults to application/octet-stream"
          (let [digest (test-digest "no-media-type")
                recorded (store/record-artifact!
                          handle generous-bounds
                          (dissoc (artifact-input digest 13) :artifact/media-type))]
            (is (= "application/octet-stream" (:artifact/media-type recorded)))
            (is (= "application/octet-stream"
                   (:artifact/media-type (store/read-artifact handle digest))))))
        (testing "retention defaults to :retained; strings normalize to keywords"
          (let [digest (test-digest "string-retention")]
            (store/record-artifact!
             handle generous-bounds
             (artifact-input digest 16 {:artifact/retention "expired"}))
            (is (= :expired (:artifact/retention (store/read-artifact handle digest))))))
        (testing "nil recorded-seq reads back as nil"
          (let [digest (test-digest "unrecorded")]
            (store/record-artifact! handle generous-bounds (artifact-input digest 10))
            (is (nil? (:artifact/recorded-seq (store/read-artifact handle digest))))))
        (testing "unknown digest reads as nil"
          (is (nil? (store/read-artifact handle (test-digest "absent")))))
        (testing "list-artifacts returns rows ordered by digest"
          (let [digests (mapv :artifact/digest (store/list-artifacts handle))]
            (is (= (sort digests) digests))
            (is (= 4 (count digests)))))
        (finally (store/close! handle))))))

(deftest record-artifact-input-validation
  (with-db [path]
    (let [handle (store/open! path {:create true})
          bad (fn [artifact] (error-kind #(store/record-artifact! handle generous-bounds artifact)))
          good-digest (test-digest "ok")]
      (try
        (testing "digest format"
          (is (= :invalid (bad (artifact-input "sha256:zzz" 2))))
          (is (= :invalid (bad (artifact-input "not-a-digest" 2))))
          (is (= :invalid (bad (artifact-input nil 2))))
          (is (= :invalid (bad (artifact-input (str "sha256:" (apply str (repeat 64 "G"))) 2)))))
        (testing "media type: non-blank, restricted charset"
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/media-type ""}))))
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/media-type "   "}))))
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/media-type "text plain"}))))
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/media-type nil})))))
        (testing "size: non-negative integer"
          (is (= :invalid (bad (artifact-input good-digest -1))))
          (is (= :invalid (bad (artifact-input good-digest 2.5))))
          (is (= :invalid (bad (artifact-input good-digest nil)))))
        (testing "retention: enum only"
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/retention :deleted}))))
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/retention "bogus"})))))
        (testing "location: non-blank"
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/location ""}))))
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/location nil})))))
        (testing "recorded-seq: nil or non-negative integer"
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/recorded-seq -1}))))
          (is (= :invalid (bad (artifact-input good-digest 2 {:artifact/recorded-seq "2"})))))
        (testing "unknown fields rejected"
          (is (= :invalid (bad (assoc (artifact-input good-digest 2) :artifact/nope 1)))))
        (testing "bytes cross-check: digest mismatch is invalid with a named reason"
          (let [data (error-data #(store/record-artifact!
                                   handle generous-bounds
                                   (assoc (artifact-input good-digest 2)
                                          :artifact/bytes (utf8 "different bytes"))))]
            (is (= :invalid (:axiom/error data)))
            (is (= :digest-mismatch (:reason data)))))
        (testing "bytes cross-check: size mismatch is invalid with a named reason"
          (let [bs (utf8 "exactly-ten!")
                data (error-data #(store/record-artifact!
                                   handle generous-bounds
                                   {:artifact/digest (model/sha256-bytes bs)
                                    :artifact/size-bytes 999
                                    :artifact/location "file:///tmp/x"
                                    :artifact/bytes bs}))]
            (is (= :invalid (:axiom/error data)))
            (is (= :size-mismatch (:reason data)))))
        (testing "bounds shape"
          (is (= :invalid (error-kind #(store/record-artifact!
                                        handle {:max/artifact-bytes 10}
                                        (artifact-input good-digest 2)))))
          (is (= :invalid (error-kind #(store/record-artifact!
                                        handle {:max/artifact-bytes -1 :max/retained-artifacts 1}
                                        (artifact-input good-digest 2)))))
          (is (= :invalid (error-kind #(store/record-artifact! handle nil
                                                               (artifact-input good-digest 2))))))
        (finally (store/close! handle))))))

(deftest retention-bounds-enforced
  (with-db [path]
    (let [handle (store/open! path {:create true})
          bounds {:max/artifact-bytes 10 :max/retained-artifacts 2}
          rec (fn [name n] (store/record-artifact!
                            handle bounds
                            (artifact-input (test-digest name) n)))]
      (try
        (testing "oversize payload is an operational failure naming bound and reason"
          (let [data (error-data #(rec "too-big" 11))]
            (is (= :operational (:axiom/error data)))
            (is (= :max-artifact-bytes-exceeded (:reason data)))
            (is (= 10 (:bound data)))
            (is (= 11 (:actual data))))
          (is (nil? (store/read-artifact handle (test-digest "too-big")))))
        (testing "a payload exactly at the bound is allowed"
          (is (= 10 (:artifact/size-bytes (rec "at-bound" 10)))))
        (testing "over-count is an operational failure naming bound and reason"
          (rec "second" 3)
          (let [data (error-data #(rec "third" 3))]
            (is (= :operational (:axiom/error data)))
            (is (= :max-retained-artifacts-exceeded (:reason data)))
            (is (= 2 (:bound data)))
            (is (= 3 (:actual data))))
          (is (nil? (store/read-artifact handle (test-digest "third"))))
          (is (= 2 (count (store/list-artifacts handle {:retention :retained})))))
        (testing "expired and superseded rows do not count toward the cap"
          (store/mark-artifact! handle (test-digest "at-bound") :expired)
          (store/mark-artifact! handle (test-digest "second") :superseded)
          (is (= 0 (count (store/list-artifacts handle {:retention :retained}))))
          (is (= 3 (:artifact/size-bytes (rec "third" 3))))
          (is (= 1 (count (store/list-artifacts handle {:retention :retained})))))
        (finally (store/close! handle))))))

(deftest mark-artifact-lifecycle
  (with-db [path]
    (let [handle (store/open! path {:create true})
          digest (test-digest "lifecycle")]
      (try
        (store/record-artifact! handle generous-bounds (artifact-input digest 9))
        (testing "mark expired: row remains and stays queryable"
          (let [updated (store/mark-artifact! handle digest :expired)]
            (is (= :expired (:artifact/retention updated)))
            (is (= :expired (:artifact/retention (store/read-artifact handle digest))))
            (is (= [digest] (mapv :artifact/digest
                                  (store/list-artifacts handle {:retention :expired}))))
            (is (empty? (store/list-artifacts handle {:retention :retained})))))
        (testing "mark superseded via string; mark back to retained"
          (is (= :superseded (:artifact/retention (store/mark-artifact! handle digest "superseded"))))
          (is (= :retained (:artifact/retention (store/mark-artifact! handle digest :retained)))))
        (testing "marking is not deletion: full row still reads back"
          (let [row (store/read-artifact handle digest)]
            (is (= digest (:artifact/digest row)))
            (is (= 9 (:artifact/size-bytes row)))
            (is (= "file:///tmp/synthetic-artifact.txt" (:artifact/location row)))))
        (testing "unknown digest is invalid input with a named reason"
          (let [data (error-data #(store/mark-artifact! handle (test-digest "absent") :expired))]
            (is (= :invalid (:axiom/error data)))
            (is (= :unknown-artifact (:reason data)))))
        (testing "malformed digest and bad status are invalid"
          (is (= :invalid (error-kind #(store/mark-artifact! handle "bogus" :expired))))
          (is (= :invalid (error-kind #(store/mark-artifact! handle digest :deleted))))
          (is (= :invalid (error-kind #(store/list-artifacts handle {:retention :deleted})))))
        (finally (store/close! handle))))))

(deftest duplicate-digest-rejected
  (with-db [path]
    (let [handle (store/open! path {:create true})
          digest (test-digest "dup")]
      (try
        (store/record-artifact! handle generous-bounds (artifact-input digest 3))
        (testing "duplicate digest is a deterministic duplicate rejection"
          (let [data (error-data #(store/record-artifact!
                                   handle generous-bounds (artifact-input digest 3)))]
            (is (= :duplicate (:axiom/error data)))
            (is (= :duplicate-artifact-digest (:reason data)))
            (is (= digest (:digest data)))))
        (testing "duplicate wins over the retention cap: still :duplicate at cap"
          (let [data (error-data #(store/record-artifact!
                                   handle {:max/artifact-bytes 100 :max/retained-artifacts 1}
                                   (artifact-input digest 3)))]
            (is (= :duplicate (:axiom/error data)))
            (is (= :duplicate-artifact-digest (:reason data)))))
        (testing "the original row is untouched"
          (let [row (store/read-artifact handle digest)]
            (is (= 3 (:artifact/size-bytes row)))
            (is (= :retained (:artifact/retention row)))))
        (finally (store/close! handle))))))

(deftest tampered-row-detected-on-read
  (with-db [path]
    (let [handle (store/open! path {:create true})
          digests (mapv (fn [name]
                          (let [d (test-digest name)]
                            (store/record-artifact! handle generous-bounds (artifact-input d 4))
                            d))
                        ["tamper-digest" "tamper-retention" "tamper-size" "tamper-media"])]
      (store/close! handle)
      ;; Tamper one column per row, directly in SQL.
      (sql-tamper! path (fn [conn]
                          (with-open [s1 (.prepareStatement conn
                                           "UPDATE artifacts SET digest='sha256:zzz' WHERE digest=?")]
                            (.setString s1 1 (nth digests 0)) (.executeUpdate s1))
                          (with-open [s2 (.prepareStatement conn
                                           "UPDATE artifacts SET retention='bogus' WHERE digest=?")]
                            (.setString s2 1 (nth digests 1)) (.executeUpdate s2))
                          (with-open [s3 (.prepareStatement conn
                                           "UPDATE artifacts SET size_bytes=-5 WHERE digest=?")]
                            (.setString s3 1 (nth digests 2)) (.executeUpdate s3))
                          (with-open [s4 (.prepareStatement conn
                                           "UPDATE artifacts SET media_type='' WHERE digest=?")]
                            (.setString s4 1 (nth digests 3)) (.executeUpdate s4))))
      (let [reopened (store/open! path)]
        (try
          ;; A malformed stored digest cannot be looked up: the lookup
          ;; itself is malformed input (:invalid). It is detected on read
          ;; by list-artifacts, which validates every row (:operational).
          (testing "tampered digest: malformed lookup is invalid input"
            (is (= :invalid (error-kind #(store/read-artifact reopened "sha256:zzz")))))
          (doseq [[lookup column label] [[(nth digests 1) :retention "retention"]
                                         [(nth digests 2) :size_bytes "size"]
                                         [(nth digests 3) :media_type "media type"]]]
            (testing (str "tampered " label " detected on read")
              (let [data (error-data #(store/read-artifact reopened lookup))]
                (is (= :operational (:axiom/error data)))
                (is (= :malformed-artifact-row (:reason data)))
                (is (= column (:column data))))))
          (testing "list-artifacts detects tampered rows"
            (let [data (error-data #(store/list-artifacts reopened))]
              (is (= :operational (:axiom/error data)))
              (is (= :malformed-artifact-row (:reason data)))))
          (finally (store/close! reopened))))))
  (testing "tampered digest column is named when it is the first bad row scanned"
    (with-db [path]
      (let [handle (store/open! path {:create true})
            digest (test-digest "only-digest-tamper")]
        (store/record-artifact! handle generous-bounds (artifact-input digest 4))
        (store/close! handle)
        (sql-tamper! path (fn [conn]
                            (with-open [s (.prepareStatement conn
                                            "UPDATE artifacts SET digest='sha256:zzz' WHERE digest=?")]
                              (.setString s 1 digest)
                              (.executeUpdate s)))))
      (let [reopened (store/open! path)
            data (error-data #(store/list-artifacts reopened))]
        (store/close! reopened)
        (is (= :operational (:axiom/error data)))
        (is (= :malformed-artifact-row (:reason data)))
        (is (= :digest (:column data))))))
  (testing "documented limit: a tampered digest that stays well-formed
            sha256:<64hex> is indistinguishable from a different artifact
            at the row level — the ledger stores digests, never bytes"
    (with-db [path]
      (let [handle (store/open! path {:create true})
            digest (test-digest "wellformed-tamper")
            other (test-digest "other-content")]
        (store/record-artifact! handle generous-bounds (artifact-input digest 4))
        (store/close! handle)
        (sql-tamper! path (fn [conn]
                            (with-open [stmt (.prepareStatement conn
                                              "UPDATE artifacts SET digest=? WHERE digest=?")]
                              (.setString stmt 1 other)
                              (.setString stmt 2 digest)
                              (.executeUpdate stmt))))
        (let [reopened (store/open! path)]
          (try
            (is (some? (store/read-artifact reopened other)))
            (is (nil? (store/read-artifact reopened digest)))
            (finally (store/close! reopened))))))))

(defn- raw-event-payloads
  "Raw payload TEXT bytes as stored, for byte-identity assertions."
  [path]
  (Class/forName "org.sqlite.JDBC")
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (try
      (with-open [stmt (.prepareStatement conn "SELECT payload FROM events ORDER BY seq")]
        (with-open [rs (.executeQuery stmt)]
          (loop [acc []]
            (if (.next rs) (recur (conj acc (.getString rs 1))) acc))))
      (finally (.close conn)))))

(defn- table-exists-sql? [path table]
  (Class/forName "org.sqlite.JDBC")
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (try
      (with-open [stmt (.prepareStatement conn
                         "SELECT name FROM sqlite_master WHERE type='table' AND name=?")]
        (.setString stmt 1 table)
        (with-open [rs (.executeQuery stmt)]
          (.next rs)))
      (finally (.close conn)))))

(defn- replay-decisions
  "Replay report decisions and world digest over the full stored prefix."
  [handle]
  (let [envs (store/read-range handle 0 100)
        report (ledger/replay-report {:source "synthetic" :envelopes envs
                                      :snapshot nil
                                      :schema/version (:schema/version handle)})]
    {:decisions (:decisions report)
     :world/digest (:world/digest report)
     :chain (ledger/verify-chain envs)}))

(deftest migration-v1-to-v3-events-untouched
  (with-db [path]
    (let [handle (store/open! path {:create true :migrate false})]
      (is (= 1 (:schema/version handle)))
      (is (not (table-exists-sql? path "artifacts")))
      (let [envs (append-scenarios! handle 3)
            before-payloads (raw-event-payloads path)
            before-digests (mapv :payload/digest envs)
            before-world (model/digest (ledger/ledger-world envs))
            before-replay (replay-decisions handle)]
        (store/close! handle)
        (testing "v1 migrates forward through v2/v3 to the supported version"
          (let [reopened (store/open! path {:create false :migrate true})]
            (try
              (is (= store/supported-schema-version (:schema/version reopened)))
              (testing "stored event payloads are byte-identical across migration"
                (is (= before-payloads (raw-event-payloads path)))
                (let [after (store/read-range reopened 0 100)]
                  (is (= before-digests (mapv :payload/digest after)))
                  (is (= before-world (model/digest (ledger/ledger-world after))))
                  (is (:chain/valid? (ledger/verify-chain after)))))
              (testing "replay report decisions unchanged across migration"
                (let [after-replay (replay-decisions reopened)]
                  ;; The report's :ledger schema/version legitimately moves
                  ;; 1 -> 3; decisions and world digest must not.
                  (is (= (:decisions before-replay) (:decisions after-replay)))
                  (is (= (:world/digest before-replay) (:world/digest after-replay)))
                  (is (every? :reproduced? (:decisions after-replay)))))
              (testing "sequential application: v2 index and v3 artifacts table exist"
                (is (table-exists-sql? path "artifacts"))
                (Class/forName "org.sqlite.JDBC")
                (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
                  (try
                    (with-open [stmt (.prepareStatement conn
                                      "SELECT name FROM sqlite_master WHERE type='index' AND name=?")]
                      (.setString stmt 1 "idx_events_stream_seq")
                      (with-open [rs (.executeQuery stmt)]
                        (is (.next rs))))
                    (finally (.close conn)))))
              (testing "artifacts can be recorded after migration"
                (let [digest (test-digest "post-migration")
                      recorded (store/record-artifact!
                                reopened generous-bounds (artifact-input digest 7))]
                  (is (= digest (:artifact/digest recorded)))
                  (is (= digest (:artifact/digest (store/read-artifact reopened digest))))))
              (finally (store/close! reopened)))))))))

(deftest migration-v2-to-v3-events-untouched
  (with-db [path]
    (let [handle (store/open! path {:create true :migrate false})]
      (is (= 1 (:schema/version handle)))
      (let [envs (append-scenarios! handle 2)
            before-payloads (raw-event-payloads path)
            before-digests (mapv :payload/digest envs)
            before-world (model/digest (ledger/ledger-world envs))
            before-replay (replay-decisions handle)]
        (store/close! handle)
        ;; Replicate the v1->v2 migration exactly (covering index + version
        ;; bump), producing a genuine v2 ledger on disk.
        (sql-tamper! path (fn [conn]
                            (with-open [s1 (.createStatement conn)]
                              (.execute s1 "CREATE INDEX IF NOT EXISTS idx_events_stream_seq ON events(stream_id, seq)"))
                            (with-open [s2 (.prepareStatement conn "UPDATE schema_version SET version=2")]
                              (.executeUpdate s2))))
        (testing "a v2 ledger migrates forward to the supported version"
          (let [reopened (store/open! path {:create false :migrate true})]
            (try
              (is (= store/supported-schema-version (:schema/version reopened)))
              (testing "stored event payloads are byte-identical across migration"
                (is (= before-payloads (raw-event-payloads path)))
                (let [after (store/read-range reopened 0 100)]
                  (is (= before-digests (mapv :payload/digest after)))
                  (is (= before-world (model/digest (ledger/ledger-world after))))
                  (is (:chain/valid? (ledger/verify-chain after)))))
              (testing "replay report decisions unchanged across migration"
                (let [after-replay (replay-decisions reopened)]
                  (is (= (:decisions before-replay) (:decisions after-replay)))
                  (is (= (:world/digest before-replay) (:world/digest after-replay)))
                  (is (every? :reproduced? (:decisions after-replay)))))
              (testing "the artifacts table exists and accepts rows"
                (is (table-exists-sql? path "artifacts"))
                (let [digest (test-digest "v2-migrated")]
                  (store/record-artifact! reopened generous-bounds (artifact-input digest 7))
                  (is (= 7 (:artifact/size-bytes (store/read-artifact reopened digest))))))
              (finally (store/close! reopened)))))))))
