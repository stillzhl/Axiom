(ns axiom.ledger-0006-test
  "Tests for spec 0006 T3: `:task/*` and `:lease/*` record kinds
   through the 0002 append path — strict per-kind validation in
   `axiom.ledger/record-task` and `axiom.ledger/record-lease`, and
   the 0001-world invariance (task/lease events are provenance and
   contribute zero 0001 events).

   Every identity, digest and token is invented (`synth-*`). No real
   repository identities, no live credentials, no network."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.ledger :as ledger]
            [axiom.model :as model]))

(def ^:private digest-a (str "sha256:" (apply str (repeat 64 "a"))))

(defn- inputs [n]
  {:event/id (str "evt-" n)
   :stream/id "synth-task-stream"
   :dedup/key (str "dedup-" n)
   :producer "synth-evaluator-1"
   :observed/time 1000
   :ingested/time 1001})

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

;; ------------------------------------------------------------------
;; :task/* events

(deftest task-events-record
  (testing "every :task/* kind records a valid envelope"
    (doseq [[n event] (map-indexed
                       vector
                       [{:event/kind :task/accepted
                         :task/id "synth-task-1"
                         :task/class :task-class/standard
                         :task/evaluator "synth-evaluator-1"}
                        {:event/kind :task/context-prepared
                         :task/id "synth-task-1"
                         :task/evaluator "synth-evaluator-1"
                         :task/context-digest digest-a}
                        {:event/kind :task/completed
                         :task/id "synth-task-1"
                         :task/evaluator "synth-evaluator-1"}
                        {:event/kind :task/blocked
                         :task/id "synth-task-1"
                         :task/evaluator "synth-evaluator-1"
                         :task/blockers [:budget-exhausted :lease-lost]}
                        {:event/kind :task/cancelled
                         :task/id "synth-task-1"
                         :task/evaluator "synth-evaluator-1"}])]
      (let [env (ledger/record-task nil (assoc (inputs n) :task-event event))]
        (is (= :task (get-in env [:payload :record/kind])))
        (is (= event (get-in env [:payload :task/event])))
        (is (= (model/digest (:payload env)) (:payload/digest env)))
        (is (nil? (error-kind #(ledger/stored-envelope! (assoc env :seq n)))))))))

(deftest task-events-reject-malformed
  (testing "unknown kinds, bad classes, missing identities and unknown fields are :invalid"
    (let [base {:event/kind :task/accepted
                :task/id "synth-task-1"
                :task/class :task-class/standard
                :task/evaluator "synth-evaluator-1"}]
      (doseq [event [(assoc base :event/kind :task/vanished)
                     (assoc base :task/class :task-class/rogue)
                     (dissoc base :task/evaluator)
                     (dissoc base :task/id)
                     (assoc base :task/unknown-field 1)
                     (assoc base :task/id "")
                     {:event/kind :task/context-prepared
                      :task/id "synth-task-1"
                      :task/evaluator "synth-evaluator-1"
                      :task/context-digest "not-a-digest"}
                     {:event/kind :task/blocked
                      :task/id "synth-task-1"
                      :task/evaluator "synth-evaluator-1"
                      :task/blockers ["budget-exhausted"]}]]
        (is (= :invalid
               (error-kind #(ledger/record-task nil (assoc (inputs 0) :task-event event))))
            (str "expected :invalid for " event))))))

(deftest task-accepted-capability-grant
  (testing "a well-formed grant records; a malformed grant is :invalid"
    (let [base {:event/kind :task/accepted
                :task/id "synth-task-1"
                :task/class :task-class/standard
                :task/evaluator "synth-evaluator-1"}
          grant {:capability/read-file true
                 :capability/write-file #{"docs/" "examples/"}
                 :capability/run-tests true
                 :capability/shell false}
          recorded (ledger/record-task
                    nil (assoc (inputs 0) :task-event
                               (assoc base :task/capabilities grant)))]
      (is (= grant (get-in recorded [:payload :task/event :task/capabilities]))))
    (let [base {:event/kind :task/accepted
                :task/id "synth-task-1"
                :task/class :task-class/standard
                :task/evaluator "synth-evaluator-1"}]
      (doseq [grant [;; unknown capability: the set is closed
                     {:capability/launch-missiles true}
                     ;; write-file must be a non-empty set of path strings
                     {:capability/write-file true}
                     {:capability/write-file #{}}
                     {:capability/write-file #{"docs/" 7}}
                     ;; boolean capabilities must be booleans
                     {:capability/shell "yes"}
                     {:capability/run-tests 1}
                     ;; not a map at all
                     [:capability/shell]]]
        (is (= :invalid
               (error-kind #(ledger/record-task
                             nil (assoc (inputs 1) :task-event
                                        (assoc base :task/capabilities grant)))))
            (str "expected :invalid for grant " (pr-str grant)))))))

;; ------------------------------------------------------------------
;; :lease/* events

(defn- acquired [overrides]
  (merge {:event/kind :lease/acquired
          :lease/task-id "synth-task-1"
          :lease/worker-id "synth-worker-1"
          :lease/token "synth-token-1"
          :lease/expires-at 2000
          :lease/issued-by "synth-evaluator-1"}
         overrides))

(deftest lease-events-record
  (testing "every :lease/* kind records a valid envelope"
    (doseq [[n event] (map-indexed
                       vector
                       [(acquired {})
                        {:event/kind :lease/renewed
                         :lease/task-id "synth-task-1"
                         :lease/worker-id "synth-worker-1"
                         :lease/presented-token "synth-token-1"
                         :lease/token "synth-token-2"
                         :lease/expires-at 3000
                         :lease/issued-by "synth-evaluator-1"}
                        {:event/kind :lease/released
                         :lease/task-id "synth-task-1"
                         :lease/worker-id "synth-worker-1"
                         :lease/presented-token "synth-token-2"
                         :lease/issued-by "synth-evaluator-1"}
                        {:event/kind :lease/expired
                         :lease/task-id "synth-task-1"
                         :lease/token "synth-token-2"
                         :lease/issued-by "synth-evaluator-1"}
                        {:event/kind :lease/revoked
                         :lease/task-id "synth-task-1"
                         :lease/issued-by "synth-evaluator-1"
                         :lease/reason :lease-lost}])]
      (let [env (ledger/record-lease nil (assoc (inputs n) :lease-event event))]
        (is (= :lease (get-in env [:payload :record/kind])))
        (is (= event (get-in env [:payload :lease/event])))
        (is (= (model/digest (:payload env)) (:payload/digest env)))
        (is (nil? (error-kind #(ledger/stored-envelope! (assoc env :seq n)))))))))

(deftest lease-events-reject-malformed
  (testing "unknown kinds, missing tokens, bad expiries and tokenless revocations are :invalid"
    (doseq [event [(assoc (acquired {}) :event/kind :lease/seized)
                   (dissoc (acquired {}) :lease/token)
                   (assoc (acquired {}) :lease/token "")
                   (assoc (acquired {}) :lease/expires-at -1)
                   (assoc (acquired {}) :lease/expires-at 1.5)
                   (dissoc (acquired {}) :lease/issued-by)
                   (assoc (acquired {}) :lease/stray-field 1)
                   {:event/kind :lease/renewed
                    :lease/task-id "synth-task-1"
                    :lease/worker-id "synth-worker-1"
                    :lease/token "synth-token-2"
                    :lease/expires-at 3000
                    :lease/issued-by "synth-evaluator-1"}
                   {:event/kind :lease/revoked
                    :lease/task-id "synth-task-1"
                    :lease/issued-by "synth-evaluator-1"
                    :lease/reason "lease-lost"}]]
      (is (= :invalid
             (error-kind #(ledger/record-lease nil (assoc (inputs 0) :lease-event event))))
          (str "expected :invalid for " event)))))

(deftest unknown-record-kind-still-rejected
  (testing "the new kinds do not open the record-kind set"
    (let [env (ledger/record-task nil (assoc (inputs 0)
                                             :task-event {:event/kind :task/accepted
                                                          :task/id "synth-task-1"
                                                          :task/class :task-class/standard
                                                          :task/evaluator "synth-evaluator-1"}))
          forged (assoc-in env [:payload :record/kind] :task/sneaky)]
      (is (= :invalid (error-kind #(ledger/validate-envelope! forged)))))))

;; ------------------------------------------------------------------
;; 0001-world invariance

(deftest task-and-lease-events-are-provenance
  (testing "task/lease envelopes contribute zero 0001 events: the world fold is unchanged"
    (let [task-env (ledger/record-task
                    nil (assoc (inputs 0)
                               :task-event {:event/kind :task/accepted
                                            :task/id "synth-task-1"
                                            :task/class :task-class/standard
                                            :task/evaluator "synth-evaluator-1"}))
          lease-env (ledger/record-lease
                     nil (assoc (inputs 1) :lease-event (acquired {})))
          stored [(assoc task-env :seq 0) (assoc lease-env :seq 1)]]
      (is (= [] (ledger/extract-events (assoc task-env :seq 0))))
      (is (= [] (ledger/extract-events (assoc lease-env :seq 1))))
      (is (= (model/digest (ledger/ledger-world []))
             (model/digest (ledger/ledger-world stored)))))))
