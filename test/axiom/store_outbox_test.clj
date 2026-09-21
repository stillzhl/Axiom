(ns axiom.store-outbox-test
  "Tests for spec 0006 T4 (the 0002-deferred action outbox): intent
   records through the 0002 append path (`axiom.store` transactional
   outbox operations), idempotency-key dedup, supervisor-only
   fencing-token transitions, and crash-safe reconciliation against
   a fake provider double (query-observable, so exactly-once and
   query-not-retry are machine-checked).

   Every identity, token and digest is invented (`synth-*`). No real
   repository identities, no live credentials, no network."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.execute :as execute]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.store :as store])
  (:import (java.io File)))

(defn- temp-db []
  (let [file (File/createTempFile "axiom-outbox-test" ".db")]
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

;; ------------------------------------------------------------------
;; Fake provider double: writes are observable effects keyed by the
;; idempotency key; queries and executions are counted separately so
;; "resolved by query, not by re-execution" is machine-checked.

(defn- fake-provider []
  (atom {:effects {} :queries [] :executions []}))

(defn- provider-query! [provider key]
  (swap! provider update :queries conj key)
  (contains? (:effects @provider) key))

(defn- provider-execute! [provider key effect]
  (swap! provider (fn [p] (-> p
                              (update :executions conj key)
                              (assoc-in [:effects key] effect))))
  effect)

;; ------------------------------------------------------------------
;; Fixtures

(defn- lease-fixture [handle task-id token]
  (store/acquire-lease!
   handle {:lease/task-id task-id
           :lease/worker-id "synth-worker-1"
           :lease/token token
           :lease/expires-at 9000
           :lease/issued-by "synth-evaluator-1"
           :record/producer "synth-evaluator-1"
           :record/observed-time 1000
           :record/ingested-time 1001}
   1000))

(defn- intent-input [token]
  {:outbox/task-id "synth-task-1"
   :outbox/action :action/publish-pr
   :outbox/payload {:pr/title "synth: docs tweak"}
   :outbox/fencing-token token
   :outbox/issued-by "synth-evaluator-1"
   :record/producer "synth-evaluator-1"
   :record/observed-time 1000
   :record/ingested-time 1001})

(defn- transition-input [key to token]
  (cond-> {:outbox/idempotency-key key
           :outbox/to-state to
           :outbox/fencing-token token
           :outbox/issued-by "synth-evaluator-1"
           :record/producer "synth-evaluator-1"
           :record/observed-time 1000
           :record/ingested-time 1001}
    (= :failed to) (assoc :outbox/reason :provider-rejected)
    (= :uncertain to) (assoc :outbox/detail "submit timed out")
    (= :executed to) (assoc :outbox/provider-ref "synth-pr-1")))

(defn- outbox-event-kinds [handle]
  (->> (store/read-range handle 0 Long/MAX_VALUE)
       (filter #(= :outbox (get-in % [:payload :record/kind])))
       (mapv #(get-in % [:payload :outbox/event :event/kind]))))

(defn- ledger-inputs [n]
  {:event/id (str "evt-" n)
   :stream/id "synth-outbox-stream"
   :dedup/key (str "dedup-" n)
   :producer "synth-evaluator-1"
   :observed/time 1000
   :ingested/time 1001})

;; ------------------------------------------------------------------
;; Ledger validation

(deftest outbox-events-validate-strictly
  (testing "every :outbox/* kind records a valid envelope"
    (doseq [[n event] (map-indexed
                       vector
                       [{:event/kind :outbox/intent-recorded
                         :outbox/idempotency-key "outbox/synth-task-1/publish-pr/sha256:aa"
                         :outbox/task-id "synth-task-1"
                         :outbox/action :action/publish-pr
                         :outbox/payload {:pr/title "synth"}
                         :outbox/fencing-token "synth-token-1"
                         :outbox/issued-by "synth-evaluator-1"
                         :outbox/attempt 0}
                        {:event/kind :outbox/executed
                         :outbox/idempotency-key "outbox/synth-task-1/publish-pr/sha256:aa"
                         :outbox/fencing-token "synth-token-1"
                         :outbox/issued-by "synth-evaluator-1"
                         :outbox/provider-ref "synth-pr-1"
                         :outbox/attempt 0}
                        {:event/kind :outbox/failed
                         :outbox/idempotency-key "outbox/synth-task-1/publish-pr/sha256:aa"
                         :outbox/fencing-token "synth-token-1"
                         :outbox/issued-by "synth-evaluator-1"
                         :outbox/reason :provider-rejected
                         :outbox/attempt 0}
                        {:event/kind :outbox/uncertain
                         :outbox/idempotency-key "outbox/synth-task-1/publish-pr/sha256:aa"
                         :outbox/fencing-token "synth-token-1"
                         :outbox/issued-by "synth-evaluator-1"
                         :outbox/detail "submit timed out"
                         :outbox/attempt 0}])]
      (let [env (ledger/record-outbox nil (assoc (ledger-inputs n) :outbox-event event))]
        (is (= :outbox (get-in env [:payload :record/kind])))
        (is (= event (get-in env [:payload :outbox/event])))
        (is (= (model/digest (:payload env)) (:payload/digest env)))
        (is (nil? (error-kind #(ledger/stored-envelope! (assoc env :seq n))))))))
  (testing "unknown kinds, missing fields and unknown fields are :invalid"
    (let [base {:event/kind :outbox/intent-recorded
                :outbox/idempotency-key "outbox/k"
                :outbox/task-id "synth-task-1"
                :outbox/action :action/publish-pr
                :outbox/payload {:pr/title "synth"}
                :outbox/fencing-token "synth-token-1"
                :outbox/issued-by "synth-evaluator-1"
                :outbox/attempt 0}]
      (doseq [event [(assoc base :event/kind :outbox/vanished)
                     (dissoc base :outbox/idempotency-key)
                     (dissoc base :outbox/fencing-token)
                     (assoc base :outbox/action "publish-pr")
                     (assoc base :outbox/payload "not-a-map")
                     (assoc base :outbox/unknown-field 1)
                     (assoc base :outbox/attempt -1)
                     {:event/kind :outbox/executed
                      :outbox/idempotency-key "outbox/k"
                      :outbox/issued-by "synth-evaluator-1"}]]
        (is (= :invalid
               (error-kind #(ledger/record-outbox nil (assoc (ledger-inputs 0) :outbox-event event))))))))
  (testing "outbox events contribute zero 0001 world events"
    (let [env (ledger/record-outbox
               nil (assoc (ledger-inputs 0)
                          :outbox-event {:event/kind :outbox/intent-recorded
                                         :outbox/idempotency-key "outbox/k"
                                         :outbox/task-id "synth-task-1"
                                         :outbox/action :action/publish-pr
                                         :outbox/payload {}
                                         :outbox/fencing-token "synth-token-1"
                                         :outbox/issued-by "synth-evaluator-1"
                                         :outbox/attempt 0}))]
      (is (= [] (ledger/extract-events (assoc env :seq 0)))))))

;; ------------------------------------------------------------------
;; Store operations

(deftest record-and-transition-through-store
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (lease-fixture handle "synth-task-1" "synth-token-1")
        (let [rec (store/record-intent! handle (intent-input "synth-token-1") 1000)
              key (:outbox/idempotency-key (:outbox/intent rec))]
          (is (true? (:outbox/ok rec)))
          (is (= :intent-recorded (:outbox/state (:outbox/intent rec))))
          (is (integer? (:outbox/seq rec)))
          (testing "the live projection shows the intent"
            (is (= :intent-recorded (:outbox/state (get (store/outbox-state handle) key)))))
          (testing "supervisor transitions move it; the chain stays valid"
            (let [tr (store/transition-intent!
                      handle (transition-input key :executed "synth-token-1") 1000)]
              (is (true? (:outbox/ok tr)))
              (is (= :executed (:outbox/state (get (store/outbox-state handle) key)))))
            (is (= [:outbox/intent-recorded :outbox/executed]
                   (outbox-event-kinds handle)))
            (is (:chain/valid? (ledger/verify-chain (store/read-range handle 0 10))))
            (testing "all outbox events for the intent share the task's stream"
              (let [outbox-envs (filter #(= :outbox (get-in % [:payload :record/kind]))
                                        (store/read-range handle 0 10))]
                (is (= ["task-stream/synth-task-1" "task-stream/synth-task-1"]
                       (mapv :stream/id outbox-envs)))))))
        (finally (store/close! handle))))))

(deftest duplicate-submission-deduplicated
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (lease-fixture handle "synth-task-1" "synth-token-1")
        (let [first (store/record-intent! handle (intent-input "synth-token-1") 1000)
              again (store/record-intent! handle (intent-input "synth-token-1") 1000)]
          (is (true? (:outbox/ok first)))
          (is (false? (:outbox/ok again)))
          (is (= :duplicate-intent (:outbox/reason again)))
          (is (= (:outbox/intent first) (:outbox/existing again)))
          (testing "exactly one :outbox/intent-recorded event was appended"
            (is (= [:outbox/intent-recorded] (outbox-event-kinds handle)))))
        (finally (store/close! handle))))))

(deftest concurrent-duplicate-submissions-yield-one-intent
  (with-db [path]
    (let [setup (store/open! path {:create true})]
      (try
        (lease-fixture setup "synth-task-1" "synth-token-1")
        (finally (store/close! setup)))
      (testing "two concurrent submissions yield exactly one recorded intent"
        (let [h1 (store/open! path {:create false})
              h2 (store/open! path {:create false})
              start (promise)
              input (intent-input "synth-token-1")
              f1 (future (deref start) (store/record-intent! h1 input 1000))
              f2 (future (deref start) (store/record-intent! h2 input 1000))]
          (deliver start true)
          (let [r1 @f1 r2 @f2
                oks (filter :outbox/ok [r1 r2])
                dups (remove :outbox/ok [r1 r2])]
            (is (= 1 (count oks)))
            (is (= 1 (count dups)))
            (is (= :duplicate-intent (:outbox/reason (first dups))))
            (testing "exactly one :outbox/intent-recorded event was recorded"
              (is (= [:outbox/intent-recorded] (outbox-event-kinds h1)))))
          (store/close! h1)
          (store/close! h2))))))

(deftest executing-is-transient-at-the-store-layer
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (lease-fixture handle "synth-task-1" "synth-token-1")
        (let [rec (store/record-intent! handle (intent-input "synth-token-1") 1000)
              key (:outbox/idempotency-key (:outbox/intent rec))
              res (store/transition-intent!
                   handle (transition-input key :executing "synth-token-1") 1000)]
          (is (true? (:outbox/ok res)))
          (is (true? (:outbox/transient res)))
          (testing "no ledger event was appended for the transient state"
            (is (= [:outbox/intent-recorded] (outbox-event-kinds handle)))
            (is (= :intent-recorded (:outbox/state (get (store/outbox-state handle) key))))))
        (finally (store/close! handle))))))

(deftest stale-token-cannot-move-intents
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (lease-fixture handle "synth-task-1" "synth-token-1")
        (let [rec (store/record-intent! handle (intent-input "synth-token-1") 1000)
              key (:outbox/idempotency-key (:outbox/intent rec))]
          ;; Rotate the fencing token: the worker's old token is now stale.
          (store/renew-lease!
           handle {:lease/task-id "synth-task-1"
                   :lease/worker-id "synth-worker-1"
                   :lease/token "synth-token-2"
                   :lease/presented-token "synth-token-1"
                   :lease/expires-at 9000
                   :lease/issued-by "synth-evaluator-1"
                   :record/producer "synth-evaluator-1"
                   :record/observed-time 1000
                   :record/ingested-time 1001}
           1000)
          (testing "a transition with the superseded token is denied and appends nothing"
            (let [denied (store/transition-intent!
                          handle (transition-input key :executed "synth-token-1") 1000)]
              (is (false? (:outbox/ok denied)))
              (is (= :stale-fencing-token (:outbox/reason denied)))))
          (is (= [:outbox/intent-recorded] (outbox-event-kinds handle)))
          (testing "the current token still moves the intent"
            (let [ok (store/transition-intent!
                      handle (transition-input key :executed "synth-token-2") 1000)]
              (is (true? (:outbox/ok ok))))))
        (finally (store/close! handle))))))

;; ------------------------------------------------------------------
;; Crash-safe reconciliation (R7 acceptance)

(deftest crash-after-external-success-reconciles-exactly-once
  (with-db [path]
    (let [handle (store/open! path {:create true})
          provider (fake-provider)]
      (try
        (lease-fixture handle "synth-task-1" "synth-token-1")
        (let [rec (store/record-intent! handle (intent-input "synth-token-1") 1000)
              key (:outbox/idempotency-key (:outbox/intent rec))]
          ;; The supervisor performs the provider write...
          (provider-execute! provider key {:pr "synth-pr-1"})
          ;; ...and crashes before recording the outcome. On restart the
          ;; supervisor replays the outbox from the ledger prefix.
          (let [restarted (store/outbox-state handle)]
            (is (= :intent-recorded (:outbox/state (get restarted key)))
                "no terminal event was recorded before the crash")
            ;; Reconcile: query the provider (idempotent), feed the
            ;; answers to the pure planner, apply the plan.
            (let [provider-state {key {:provider/effect-present? (provider-query! provider key)}}
                  plan (execute/reconcile-outbox restarted provider-state)]
              (is (= :mark-executed (:reconcile/action (get plan key))))
              (let [tr (store/transition-intent!
                        handle (transition-input key :executed "synth-token-1") 1000)]
                (is (true? (:outbox/ok tr))))))
          (testing "exactly-once: one provider write, one provider query, no re-execution"
            (is (= 1 (count (:executions @provider))))
            (is (= 1 (count (:queries @provider))))
            (is (= [:outbox/intent-recorded :outbox/executed] (outbox-event-kinds handle))))
          (testing "a second reconciliation is a no-op"
            (is (= {} (execute/reconcile-outbox
                       (store/outbox-state handle)
                       {key {:provider/effect-present? true}})))))
        (finally (store/close! handle))))))

(deftest uncertain-intent-resolved-by-query-not-blind-retry
  (with-db [path]
    (let [handle (store/open! path {:create true})
          provider (fake-provider)]
      (try
        (lease-fixture handle "synth-task-1" "synth-token-1")
        (let [rec (store/record-intent! handle (intent-input "synth-token-1") 1000)
              key (:outbox/idempotency-key (:outbox/intent rec))]
          ;; The submit times out after the write actually succeeded.
          (provider-execute! provider key {:pr "synth-pr-1"})
          (let [unc (store/transition-intent!
                     handle (transition-input key :uncertain "synth-token-1") 1000)]
            (is (true? (:outbox/ok unc))))
          ;; Reconcile: the query — not a blind retry — resolves it.
          (let [provider-state {key {:provider/effect-present? (provider-query! provider key)}}
                plan (execute/reconcile-outbox (store/outbox-state handle) provider-state)]
            (is (= :mark-executed (:reconcile/action (get plan key))))
            (let [tr (store/transition-intent!
                      handle (transition-input key :executed "synth-token-1") 1000)]
              (is (true? (:outbox/ok tr)))))
          (testing "the effect was never re-executed: queries 1, executions 1"
            (is (= 1 (count (:queries @provider))))
            (is (= 1 (count (:executions @provider))))))
        (finally (store/close! handle))))))

(deftest uncertain-intent-without-effect-is-redriven-after-query
  (with-db [path]
    (let [handle (store/open! path {:create true})
          provider (fake-provider)]
      (try
        (lease-fixture handle "synth-task-1" "synth-token-1")
        (let [rec (store/record-intent! handle (intent-input "synth-token-1") 1000)
              key (:outbox/idempotency-key (:outbox/intent rec))]
          ;; The submit times out and the write never happened.
          (let [unc (store/transition-intent!
                     handle (transition-input key :uncertain "synth-token-1") 1000)]
            (is (true? (:outbox/ok unc))))
          ;; Reconcile: the query proves the effect missing, so the
          ;; intent is re-driven as a fresh attempt under the same
          ;; idempotency key — the query, not a blind retry, decided.
          (let [provider-state {key {:provider/effect-present? (provider-query! provider key)}}
                plan (execute/reconcile-outbox (store/outbox-state handle) provider-state)]
            (is (= :re-drive (:reconcile/action (get plan key))))
            (let [re (store/transition-intent!
                      handle (transition-input key :intent-recorded "synth-token-1") 1000)]
              (is (true? (:outbox/ok re)))
              (is (= 2 (:outbox/attempts (:outbox/intent re))))))
          ;; Now the fresh drive executes — the first and only write.
          (provider-execute! provider key {:pr "synth-pr-2"})
          (let [tr (store/transition-intent!
                    handle (transition-input key :executed "synth-token-1") 1000)]
            (is (true? (:outbox/ok tr))))
          (testing "one query preceded the re-drive; exactly one execution, never a duplicate intent"
            (is (= 1 (count (:queries @provider))))
            (is (= 1 (count (:executions @provider))))
            (is (= [:outbox/intent-recorded :outbox/uncertain
                    :outbox/intent-recorded :outbox/executed]
                   (outbox-event-kinds handle)))))
        (finally (store/close! handle))))))
