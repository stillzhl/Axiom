(ns axiom.execute-outbox-test
  "Tests for spec 0006 T4 (`axiom.execute` outbox logic): the pure
   idempotency-key derivation, the outbox projection, the
   record/transition decisions (dedup, fencing, supervisor-only,
   state machine) and the pure crash-recovery reconciliation plan.

   Every identity, token and digest is invented (`synth-*`). No real
   repository identities, no live credentials, no network, no I/O —
   these tests never touch the database."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.execute :as execute]))

;; ------------------------------------------------------------------
;; Synthetic fixtures (invented identities only)

(defn- leases []
  {"synth-task-1" {:lease/task-id "synth-task-1"
                   :lease/worker-id "synth-worker-1"
                   :lease/token "synth-token-1"
                   :lease/expires-at 9000
                   :lease/issued-by "synth-evaluator-1"}})

(defn- intent-input []
  {:outbox/task-id "synth-task-1"
   :outbox/action :action/publish-pr
   :outbox/payload {:pr/title "synth: docs tweak"
                    :pr/patch-digest (str "sha256:" (apply str (repeat 64 "a")))}
   :outbox/fencing-token "synth-token-1"
   :outbox/issued-by "synth-evaluator-1"})

(defn- recorded-key []
  (execute/outbox-idempotency-key "synth-task-1" :action/publish-pr
                                  (:outbox/payload (intent-input))))

(defn- record! []
  (:outbox/event (execute/record-intent {} (leases) (intent-input))))

(defn- transition-input [key to]
  {:outbox/idempotency-key key
   :outbox/to-state to
   :outbox/fencing-token "synth-token-1"
   :outbox/issued-by "synth-evaluator-1"})

;; ------------------------------------------------------------------
;; Idempotency key

(deftest idempotency-key-derivation
  (testing "the key is deterministic and content-addressed"
    (let [k1 (execute/outbox-idempotency-key "synth-task-1" :action/publish-pr {:a 1})
          k2 (execute/outbox-idempotency-key "synth-task-1" :action/publish-pr {:a 1})]
      (is (= k1 k2))
      (is (string? k1))))
  (testing "task, action and payload each discriminate the key"
    (let [base (execute/outbox-idempotency-key "synth-task-1" :action/publish-pr {:a 1})]
      (is (not= base (execute/outbox-idempotency-key "synth-task-2" :action/publish-pr {:a 1})))
      (is (not= base (execute/outbox-idempotency-key "synth-task-1" :action/run-tests {:a 1})))
      (is (not= base (execute/outbox-idempotency-key "synth-task-1" :action/publish-pr {:a 2}))))))

;; ------------------------------------------------------------------
;; Projection

(deftest outbox-projection
  (testing "the fold tracks state and attempts over the event prefix"
    (let [e1 (record!)
          intents (execute/outbox-intents [e1])
          key (recorded-key)
          intent (get intents key)]
      (is (= :intent-recorded (:outbox/state intent)))
      (is (= 1 (:outbox/attempts intent)))
      (is (= :action/publish-pr (:outbox/action intent)))
      (testing "a terminal event moves the state; unknown kinds are ignored"
        (let [exec (:outbox/event
                    (execute/transition-intent intents (leases)
                                               (assoc (transition-input key :executed)
                                                      :outbox/provider-ref "synth-pr-1")))
              intents2 (execute/outbox-intents [e1 exec])]
          (is (= :executed (:outbox/state (get intents2 key))))
          (is (= "synth-pr-1" (:outbox/provider-ref (get intents2 key))))
          (is (= :intent-recorded
                 (:outbox/state (get (execute/outbox-intents
                                      [e1 {:event/kind :outbox/vanished
                                           :outbox/idempotency-key key}])
                                     key)))))))))

;; ------------------------------------------------------------------
;; record-intent decisions

(deftest record-intent-decisions
  (testing "a well-formed intent records with attempt 0"
    (let [res (execute/record-intent {} (leases) (intent-input))]
      (is (true? (:outbox/ok res)))
      (is (= :outbox/intent-recorded (get-in res [:outbox/event :event/kind])))
      (is (= (recorded-key) (get-in res [:outbox/event :outbox/idempotency-key])))
      (is (= 0 (get-in res [:outbox/event :outbox/attempt])))))
  (testing "resubmission of the same idempotency key deduplicates — never a second execution"
    (let [intents (execute/outbox-intents [(record!)])
          res (execute/record-intent intents (leases) (intent-input))]
      (is (false? (:outbox/ok res)))
      (is (= :duplicate-intent (:outbox/reason res)))
      (is (= :intent-recorded (:outbox/state (:outbox/existing res))))
      (is (not (contains? res :outbox/event)))))
  (testing "resubmission after terminal states still deduplicates"
    (let [e1 (record!)
          intents (execute/outbox-intents [e1])
          exec (:outbox/event
                (execute/transition-intent intents (leases) (transition-input (recorded-key) :executed)))
          intents2 (execute/outbox-intents [e1 exec])
          res (execute/record-intent intents2 (leases) (intent-input))]
      (is (= :duplicate-intent (:outbox/reason res)))
      (is (= :executed (:outbox/state (:outbox/existing res))))))
  (testing "fencing: no lease, stale token"
    (is (= :no-lease-held (:outbox/reason (execute/record-intent {} {} (intent-input)))))
    (is (= :stale-fencing-token
           (:outbox/reason (execute/record-intent
                            {} (leases)
                            (assoc (intent-input) :outbox/fencing-token "synth-token-OLD"))))))
  (testing "supervisor-only: the issuer must be the lease's evaluator"
    (let [res (execute/record-intent {} (leases)
                                     (assoc (intent-input)
                                            :outbox/issued-by "synth-worker-1"))]
      (is (false? (:outbox/ok res)))
      (is (= :not-supervisor (:outbox/reason res)))
      (is (not (contains? res :outbox/event)))))
  (testing "malformed input never produces an event"
    (doseq [bad [(dissoc (intent-input) :outbox/task-id)
                 (assoc (intent-input) :outbox/action "publish-pr")
                 (assoc (intent-input) :outbox/payload "not-a-map")
                 (assoc (intent-input) :outbox/fencing-token "")
                 nil]]
      (let [res (execute/record-intent {} (leases) bad)]
        (is (false? (:outbox/ok res)))
        (is (= :malformed (:outbox/reason res)))))))

;; ------------------------------------------------------------------
;; transition-intent decisions

(deftest transition-intent-decisions
  (testing "the durable moves record events; :executing is transient and appends nothing"
    (let [e1 (record!)
          intents (execute/outbox-intents [e1])
          key (recorded-key)]
      (doseq [[to kind] [[:executed :outbox/executed]
                         [:failed :outbox/failed]
                         [:uncertain :outbox/uncertain]]]
        (let [res (execute/transition-intent intents (leases)
                                             (assoc (transition-input key to)
                                                    :outbox/reason :provider-timeout
                                                    :outbox/detail "submit timed out"))]
          (is (true? (:outbox/ok res)) (str to))
          (is (= kind (get-in res [:outbox/event :event/kind])) (str to))))
      (let [res (execute/transition-intent intents (leases) (transition-input key :executing))]
        (is (true? (:outbox/ok res)))
        (is (true? (:outbox/transient res)))
        (is (not (contains? res :outbox/event))))))
  (testing "re-drive carries the intent identity forward and bumps the attempt"
    (let [e1 (record!)
          key (recorded-key)
          intents (execute/outbox-intents [e1])
          unc (:outbox/event (execute/transition-intent intents (leases)
                                                        (transition-input key :uncertain)))
          intents2 (execute/outbox-intents [e1 unc])
          res (execute/transition-intent intents2 (leases) (transition-input key :intent-recorded))]
      (is (true? (:outbox/ok res)))
      (is (= :outbox/intent-recorded (get-in res [:outbox/event :event/kind])))
      (is (= 1 (get-in res [:outbox/event :outbox/attempt])))
      (is (= (:outbox/payload (intent-input)) (get-in res [:outbox/event :outbox/payload])))))
  (testing "illegal moves are denied with named reasons"
    (let [e1 (record!)
          key (recorded-key)
          intents (execute/outbox-intents [e1])
          exec (:outbox/event (execute/transition-intent intents (leases)
                                                          (transition-input key :executed)))
          intents2 (execute/outbox-intents [e1 exec])]
      (is (= :unknown-intent
             (:outbox/reason (execute/transition-intent intents (leases)
                                                        (transition-input "outbox/nope" :executed)))))
      (let [res (execute/transition-intent intents2 (leases) (transition-input key :uncertain))]
        (is (false? (:outbox/ok res)))
        (is (= :illegal-transition (:outbox/reason res)))
        (is (= :executed (:outbox/from res))))))
  (testing "a stale worker cannot move intents; a non-supervisor cannot either"
    (let [intents (execute/outbox-intents [(record!)])
          key (recorded-key)]
      (is (= :stale-fencing-token
             (:outbox/reason (execute/transition-intent
                              intents (leases)
                              (assoc (transition-input key :executed)
                                     :outbox/fencing-token "synth-token-OLD")))))
      (is (= :not-supervisor
             (:outbox/reason (execute/transition-intent
                              intents (leases)
                              (assoc (transition-input key :executed)
                                     :outbox/issued-by "synth-worker-1")))))
      (is (= :no-lease-held
             (:outbox/reason (execute/transition-intent intents {}
                                                        (transition-input key :executed))))))))

;; ------------------------------------------------------------------
;; reconcile-outbox

(deftest reconcile-outbox-plan
  (testing "executed intents are left alone; failed are reported as blockers"
    (let [e1 (record!)
          key (recorded-key)
          intents (execute/outbox-intents [e1])
          exec (:outbox/event (execute/transition-intent intents (leases)
                                                          (transition-input key :executed)))
          intents-exec (execute/outbox-intents [e1 exec])
          fail (:outbox/event (execute/transition-intent intents (leases)
                                                          (assoc (transition-input key :failed)
                                                                 :outbox/reason :provider-rejected)))
          intents-fail (execute/outbox-intents [e1 fail])]
      (is (= {} (execute/reconcile-outbox intents-exec {key {:provider/effect-present? true}})))
      (is (= {:reconcile/action :report-blocker}
             (select-keys (get (execute/reconcile-outbox intents-fail {}) key)
                          [:reconcile/action])))))
  (testing "an intent with no terminal state and no provider answer asks for a query first"
    (let [intents (execute/outbox-intents [(record!)])
          plan (execute/reconcile-outbox intents {})]
      (is (= :query-provider (:reconcile/action (get plan (recorded-key)))))))
  (testing "crash after external success reconciles to mark-executed — never re-executed"
    (let [intents (execute/outbox-intents [(record!)])
          plan (execute/reconcile-outbox
                intents {(recorded-key) {:provider/effect-present? true}})]
      (is (= :mark-executed (:reconcile/action (get plan (recorded-key)))))))
  (testing "an uncertain intent with no effect present is re-driven only after the query"
    (let [e1 (record!)
          key (recorded-key)
          intents (execute/outbox-intents [e1])
          unc (:outbox/event (execute/transition-intent intents (leases)
                                                        (transition-input key :uncertain)))
          intents2 (execute/outbox-intents [e1 unc])
          plan (execute/reconcile-outbox intents2 {key {:provider/effect-present? false}})]
      (is (= :re-drive (:reconcile/action (get plan key))))))
  (testing "an uncertain intent whose effect exists is marked executed, not re-driven"
    (let [e1 (record!)
          key (recorded-key)
          intents (execute/outbox-intents [e1])
          unc (:outbox/event (execute/transition-intent intents (leases)
                                                        (transition-input key :uncertain)))
          intents2 (execute/outbox-intents [e1 unc])
          plan (execute/reconcile-outbox intents2 {key {:provider/effect-present? true}})]
      (is (= :mark-executed (:reconcile/action (get plan key)))))))
