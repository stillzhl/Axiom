(ns axiom.store-lease-test
  "Tests for spec 0006 T3: lease events through the 0002 append path
   (`axiom.store` transactional lease operations) — the schema v5
   migration, the single-holder invariant (including two concurrent
   acquires), fencing-token denial, expiry, idempotent retry, and
   sidecar replay-equivalence.

   Every identity, token and digest is invented (`synth-*`). No real
   repository identities, no live credentials, no network."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.execute :as execute]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.store :as store])
  (:import (java.io File)
           (java.sql DriverManager)))

(defn- temp-db []
  (let [file (File/createTempFile "axiom-lease-test" ".db")]
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

(defn- lease-input
  [task-id token expires-at]
  {:lease/task-id task-id
   :lease/worker-id "synth-worker-1"
   :lease/token token
   :lease/expires-at expires-at
   :lease/issued-by "synth-evaluator-1"
   :record/producer "synth-evaluator-1"
   :record/observed-time 1000
   :record/ingested-time 1001})

(defn- table-exists? [path table]
  (Class/forName "org.sqlite.JDBC")
  (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (try
      (with-open [stmt (.prepareStatement conn
                                          "SELECT name FROM sqlite_master WHERE type='table' AND name=?")]
        (.setString stmt 1 table)
        (with-open [rs (.executeQuery stmt)]
          (boolean (.next rs))))
      (finally (.close conn)))))

(defn- lease-event-kinds [handle]
  (->> (store/read-range handle 0 Long/MAX_VALUE)
       (filter #(= :lease (get-in % [:payload :record/kind])))
       (mapv #(get-in % [:payload :lease/event :event/kind]))))

;; ------------------------------------------------------------------
;; Schema v5 migration

(deftest schema-v5-migration
  (with-db [path]
    (testing "a fresh ledger opens at schema v5 with the current_leases sidecar"
      (let [handle (store/open! path {:create true})]
        (try
          (is (= 5 (:schema/version handle)))
          (is (= store/supported-schema-version (:schema/version handle)))
          (finally (store/close! handle))))
      (is (table-exists? path "current_leases"))))
  (with-db [path]
    (testing "v1 -> v5 forward-only migration keeps stored payloads byte-identical"
      (let [handle (store/open! path {:create true :migrate false})]
        (is (= 1 (:schema/version handle)))
        (let [env (ledger/record-task
                   nil {:event/id "evt-0" :stream/id "synth-task-stream"
                        :dedup/key "dedup-0" :producer "synth-evaluator-1"
                        :observed/time 1000 :ingested/time 1001
                        :task-event {:event/kind :task/accepted
                                     :task/id "synth-task-1"
                                     :task/class :task-class/standard
                                     :task/evaluator "synth-evaluator-1"}})
              stored (store/append! handle env)
              before-digest (:payload/digest stored)]
          (store/close! handle)
          (let [reopened (store/open! path {:create false :migrate true})]
            (try
              (is (= 5 (:schema/version reopened)))
              (let [after (store/read-range reopened 0 10)]
                (is (= [before-digest] (mapv :payload/digest after)))
                (is (:chain/valid? (ledger/verify-chain after))))
              (is (table-exists? path "current_leases"))
              (finally (store/close! reopened)))))))))

;; ------------------------------------------------------------------
;; Acquire / renew / release / revoke through the store

(deftest acquire-installs-lease-and-event
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (let [res (store/acquire-lease! handle (lease-input "synth-task-1" "synth-token-1" 2000) 1000)]
          (is (true? (:lease/ok res)))
          (is (= "synth-token-1" (get-in res [:lease/lease :lease/token])))
          (is (= "synth-worker-1" (get-in res [:lease/lease :lease/worker-id])))
          (is (integer? (:lease/seq res)))
          (is (= (:lease/seq res) (get-in res [:lease/lease :lease/acquired-seq]))))
        (testing "the current lease is visible"
          (let [lease (store/current-lease handle "synth-task-1" 1000)]
            (is (= "synth-token-1" (:lease/token lease)))
            (is (= {"synth-task-1" lease} (store/current-leases handle 1000)))))
        (testing "a second acquire on the live lease is denied — one event only"
          (let [denied (store/acquire-lease! handle (lease-input "synth-task-1" "synth-token-2" 2000) 1000)]
            (is (false? (:lease/ok denied)))
            (is (= :task-already-leased (:lease/reason denied))))
          (is (= [:lease/acquired] (lease-event-kinds handle))))
        (testing "malformed input is denied and appends nothing"
          (let [res (store/acquire-lease!
                     handle
                     (dissoc (lease-input "synth-task-2" "synth-token-9" 2000)
                             :lease/token)
                     1000)]
            (is (false? (:lease/ok res)))
            (is (= :malformed (:lease/reason res))))
          (is (= [:lease/acquired] (lease-event-kinds handle))))
        (finally (store/close! handle))))))

(deftest renew-rotates-and-stale-token-denied
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (store/acquire-lease! handle (lease-input "synth-task-1" "synth-token-1" 2000) 1000)
        (testing "renewal with a wrong token is denied and appends nothing"
          (let [denied (store/renew-lease!
                        handle
                        (assoc (lease-input "synth-task-1" "synth-token-2" 3000)
                               :lease/presented-token "synth-token-WRONG")
                        1000)]
            (is (false? (:lease/ok denied)))
            (is (= :stale-fencing-token (:lease/reason denied))))
          (is (= [:lease/acquired] (lease-event-kinds handle))))
        (testing "renewal with the current token rotates token and expiry"
          (let [res (store/renew-lease!
                     handle
                     (assoc (lease-input "synth-task-1" "synth-token-2" 3000)
                            :lease/presented-token "synth-token-1")
                     1000)]
            (is (true? (:lease/ok res)))
            (is (= "synth-token-2" (get-in res [:lease/lease :lease/token])))
            (is (= 3000 (get-in res [:lease/lease :lease/expires-at])))
            (is (= [:lease/acquired :lease/renewed] (lease-event-kinds handle)))))
        (testing "the superseded token is now stale"
          (let [leases (store/current-leases handle 1000)]
            (is (= :stale-fencing-token
                   (execute/check-fencing-token leases "synth-task-1" "synth-token-1")))
            (is (nil? (execute/check-fencing-token leases "synth-task-1" "synth-token-2")))))
        (finally (store/close! handle))))))

(deftest release-and-revoke-clear-lease
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (store/acquire-lease! handle (lease-input "synth-task-1" "synth-token-1" 2000) 1000)
        (testing "release with a wrong token is denied"
          (let [denied (store/release-lease!
                        handle
                        (assoc (lease-input "synth-task-1" "synth-token-1" 2000)
                               :lease/presented-token "synth-token-WRONG")
                        1000)]
            (is (= :stale-fencing-token (:lease/reason denied)))))
        (testing "release with the current token clears the lease"
          (let [res (store/release-lease!
                     handle
                     (assoc (lease-input "synth-task-1" "synth-token-1" 2000)
                            :lease/presented-token "synth-token-1")
                     1000)]
            (is (true? (:lease/ok res)))
            (is (nil? (store/current-lease handle "synth-task-1" 1000)))
            (is (= [:lease/acquired :lease/released]
                   (lease-event-kinds handle)))))
        (testing "revocation is evaluator-initiated and clears the lease"
          (store/acquire-lease! handle (lease-input "synth-task-1" "synth-token-3" 2000) 1000)
          (let [res (store/revoke-lease!
                     handle
                     {:lease/task-id "synth-task-1"
                      :lease/issued-by "synth-evaluator-1"
                      :lease/reason :worker-compromised
                      :record/producer "synth-evaluator-1"
                      :record/observed-time 1000
                      :record/ingested-time 1001}
                     1000)]
            (is (true? (:lease/ok res)))
            (is (nil? (store/current-lease handle "synth-task-1" 1000)))
            (is (= [:lease/acquired :lease/released :lease/acquired :lease/revoked]
                   (lease-event-kinds handle))))
          (testing "revoking a lease that is gone denies"
            (let [denied (store/revoke-lease!
                          handle
                          {:lease/task-id "synth-task-1"
                           :lease/issued-by "synth-evaluator-1"
                           :lease/reason :worker-compromised
                           :record/producer "synth-evaluator-1"
                           :record/observed-time 1000
                           :record/ingested-time 1001}
                          1000)]
              (is (= :no-lease-held (:lease/reason denied))))))
        (finally (store/close! handle))))))

(deftest expiry-and-expire-leases
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (store/acquire-lease! handle (lease-input "synth-task-1" "synth-token-1" 2000) 1000)
        (testing "an expired lease is absent from the projection without any event"
          (is (some? (store/current-lease handle "synth-task-1" 1999)))
          (is (nil? (store/current-lease handle "synth-task-1" 2000)))
          (is (= [:lease/acquired] (lease-event-kinds handle))))
        (testing "expire-leases! records :lease/expired and frees the task"
          (let [expired (store/expire-leases!
                         handle
                         {:record/producer "synth-evaluator-1"
                          :record/observed-time 2000
                          :record/ingested-time 2001}
                         2000)]
            (is (= ["synth-task-1"] expired))
            (is (= [:lease/acquired :lease/expired] (lease-event-kinds handle)))
            (testing "the task is free for reassignment after expiry"
              (let [res (store/acquire-lease!
                         handle (lease-input "synth-task-1" "synth-token-2" 4000) 2000)]
                (is (true? (:lease/ok res)))
                (is (= "synth-token-2"
                       (:lease/token (store/current-lease handle "synth-task-1" 2000))))))))
        (finally (store/close! handle))))))

(deftest concurrent-acquire-single-winner
  (with-db [path]
    (let [setup (store/open! path {:create true})]
      (store/close! setup)
      (testing "two concurrent acquires yield exactly one lease"
        (let [h1 (store/open! path {:create false})
              h2 (store/open! path {:create false})
              start (promise)
              f1 (future (deref start)
                         (store/acquire-lease!
                          h1 (lease-input "synth-task-1" "synth-token-A" 2000) 1000))
              f2 (future (deref start)
                         (store/acquire-lease!
                          h2 (lease-input "synth-task-1" "synth-token-B" 2000) 1000))]
          (deliver start true)
          (let [r1 @f1 r2 @f2
                oks (filter :lease/ok [r1 r2])
                denied (remove :lease/ok [r1 r2])]
            (is (= 1 (count oks)))
            (is (= 1 (count denied)))
            (is (= :task-already-leased (:lease/reason (first denied))))
            (testing "exactly one :lease/acquired event was recorded"
              (is (= [:lease/acquired] (lease-event-kinds h1))))
            (testing "the sidecar holds the winner's lease"
              (let [lease (store/current-lease h1 "synth-task-1" 1000)]
                (is (= (:lease/token (:lease/lease (first oks)))
                       (:lease/token lease))))))
          (store/close! h1)
          (store/close! h2))))))

(deftest idempotent-retry-returns-live-lease
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (let [first (store/acquire-lease!
                     handle (lease-input "synth-task-1" "synth-token-1" 2000) 1000)
              retry (store/acquire-lease!
                     handle (lease-input "synth-task-1" "synth-token-1" 2000) 1000)]
          (testing "a crash-retry of the same attempt returns the live lease"
            (is (true? (:lease/ok retry)))
            (is (true? (:lease/duplicate? retry)))
            (is (= (:lease/lease first) (:lease/lease retry))))
          (testing "no second :lease/acquired event was recorded"
            (is (= [:lease/acquired] (lease-event-kinds handle)))))
        (finally (store/close! handle))))))

(deftest stale-worker-fenced-from-records
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (store/acquire-lease! handle (lease-input "synth-task-1" "synth-token-1" 2000) 1000)
        (store/renew-lease!
         handle
         (assoc (lease-input "synth-task-1" "synth-token-2" 3000)
                :lease/presented-token "synth-token-1")
         1000)
        (testing "any worker record with a superseded token is denied"
          (let [leases (store/current-leases handle 1000)]
            (is (= :stale-fencing-token
                   (execute/check-fencing-token leases "synth-task-1" "synth-token-1")))
            (is (nil? (execute/check-fencing-token leases "synth-task-1" "synth-token-2")))))
        (finally (store/close! handle))))))

(deftest rebuild-leases-matches-sidecar
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (store/acquire-lease! handle (lease-input "synth-task-1" "synth-token-1" 2000) 1000)
        (store/renew-lease!
         handle
         (assoc (lease-input "synth-task-1" "synth-token-2" 3000)
                :lease/presented-token "synth-token-1")
         1000)
        (store/acquire-lease! handle (lease-input "synth-task-2" "synth-token-9" 5000) 1000)
        (let [before (store/current-leases handle 1000)]
          (is (= 2 (count before)))
          (testing "truncating and rebuilding the sidecar reproduces it exactly"
            (Class/forName "org.sqlite.JDBC")
            (let [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
              (try
                (with-open [stmt (.createStatement conn)]
                  (.execute stmt "DELETE FROM current_leases"))
                (finally (.close conn))))
            (is (= {} (store/current-leases handle 1000)))
            (let [rebuilt (store/rebuild-leases! handle 1000)]
              (is (= before rebuilt))
              (is (= before (store/current-leases handle 1000))))))
        (finally (store/close! handle))))))

(deftest lease-events-preserve-chain-and-world
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (let [task-env (ledger/record-task
                        nil {:event/id "evt-task-0" :stream/id "synth-task-stream"
                             :dedup/key "dedup-task-0" :producer "synth-evaluator-1"
                             :observed/time 1000 :ingested/time 1001
                             :task-event {:event/kind :task/accepted
                                          :task/id "synth-task-1"
                                          :task/class :task-class/standard
                                          :task/evaluator "synth-evaluator-1"}})
              s1 (store/append! handle task-env)
              lease-env (ledger/record-lease
                         s1 {:event/id "evt-lease-0" :stream/id "synth-task-stream"
                             :dedup/key "dedup-lease-0" :producer "synth-evaluator-1"
                             :observed/time 1000 :ingested/time 1001
                             :lease-event {:event/kind :lease/acquired
                                           :lease/task-id "synth-task-1"
                                           :lease/worker-id "synth-worker-1"
                                           :lease/token "synth-token-1"
                                           :lease/expires-at 2000
                                           :lease/issued-by "synth-evaluator-1"}})
              s2 (store/append! handle lease-env)
              envs (store/read-range handle 0 10)]
          (testing "hash chain verifies over the mixed prefix"
            (is (:chain/valid? (ledger/verify-chain envs))))
          (testing "replay-equivalence: snapshots cover the head and the world is unchanged"
            (let [snap (store/take-snapshot! handle)]
              (is (= 1 (:snapshot/seq snap)))
              (is (= (model/digest (ledger/ledger-world []))
                     (:world/digest snap)))))
          (testing "task lifecycle events record through the plain append path"
            (is (= :task (get-in s1 [:payload :record/kind])))
            (is (= :lease (get-in s2 [:payload :record/kind])))))
        (finally (store/close! handle))))))
