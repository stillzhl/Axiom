(ns axiom.execute-lease-test
  "Tests for spec 0006 T3 (`axiom.execute` lease logic): the pure
   current-leases projection, the acquire/renew/release/revoke
   decisions, and fencing-token checks.

   Every identity and token is invented (`synth-*`). No real
   repository identities, no live credentials, no network, no I/O —
   these tests never touch the database."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.execute :as execute]
            [axiom.ledger :as ledger]))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(defn- acquire-input
  [task-id token expires-at]
  {:lease/task-id task-id
   :lease/worker-id "synth-worker-1"
   :lease/token token
   :lease/expires-at expires-at
   :lease/issued-by "synth-evaluator-1"})

(defn- acquired-event
  [task-id token expires-at]
  (:lease/event (execute/acquire-lease {} (acquire-input task-id token expires-at))))

(defn- task []
  {:task/id "synth-task-1"
   :task/class :task-class/standard
   :task/state :task/accepted
   :task/scope {:scope/path-prefixes #{"docs/" "examples/"}}
   :task/capabilities {:capability/read-file true
                       :capability/write-file #{"docs/" "examples/"}
                       :capability/run-tests true
                       :capability/shell false}
   :task/pinned-recipe ["./scripts/check"]
   :task/obligations []})

(defn- proposal [fencing-token]
  {:proposal/task-id "synth-task-1"
   :proposal/worker-id "synth-worker-1"
   :proposal/fencing-token fencing-token
   :proposal/actions [{:action/kind :action/write-file
                       :action/path "docs/guide.md"
                       :action/content-digest (str "sha256:" (apply str (repeat 64 "a")))
                       :action/capability :capability/write-file}]})

;; ------------------------------------------------------------------
;; current-leases projection

(deftest current-leases-projection
  (testing "acquire installs; renewal rotates only on token match; release/revoke/expire clear"
    (let [events [(acquired-event "synth-task-1" "synth-token-1" 2000)
                  (acquired-event "synth-task-2" "synth-token-9" 2000)]]
      (is (= {} (execute/current-leases [] 1000)))
      (let [leases (execute/current-leases events 1000)]
        (is (= #{"synth-task-1" "synth-task-2"} (set (keys leases))))
        (is (= "synth-token-1" (get-in leases ["synth-task-1" :lease/token]))))
      (testing "renewal with the current token rotates token and expiry"
        (let [renewed (:lease/event
                       (execute/renew-lease
                        (execute/current-leases events 1000)
                        {:lease/task-id "synth-task-1"
                         :lease/presented-token "synth-token-1"
                         :lease/token "synth-token-2"
                         :lease/expires-at 3000
                         :lease/issued-by "synth-evaluator-1"}))
              leases (execute/current-leases (conj events renewed) 1000)]
          (is (= "synth-token-2" (get-in leases ["synth-task-1" :lease/token])))
          (is (= 3000 (get-in leases ["synth-task-1" :lease/expires-at])))
          (is (= "synth-token-9" (get-in leases ["synth-task-2" :lease/token])))))
      (testing "renewal with a stale token is never applied"
        (let [bogus {:event/kind :lease/renewed
                     :lease/task-id "synth-task-1"
                     :lease/worker-id "synth-worker-1"
                     :lease/presented-token "synth-token-WRONG"
                     :lease/token "synth-token-2"
                     :lease/expires-at 3000
                     :lease/issued-by "synth-evaluator-1"}
              leases (execute/current-leases (conj events bogus) 1000)]
          (is (= "synth-token-1" (get-in leases ["synth-task-1" :lease/token])))
          (is (= 2000 (get-in leases ["synth-task-1" :lease/expires-at])))))
      (testing "release, revocation and expiry clear the lease"
        (doseq [clear-event [{:event/kind :lease/released
                             :lease/task-id "synth-task-1"
                             :lease/worker-id "synth-worker-1"
                             :lease/presented-token "synth-token-1"
                             :lease/issued-by "synth-evaluator-1"}
                            {:event/kind :lease/revoked
                             :lease/task-id "synth-task-1"
                             :lease/issued-by "synth-evaluator-1"
                             :lease/reason :lease-lost}
                            {:event/kind :lease/expired
                             :lease/task-id "synth-task-1"
                             :lease/token "synth-token-1"
                             :lease/issued-by "synth-evaluator-1"}]]
          (let [leases (execute/current-leases (conj events clear-event) 1000)]
            (is (not (contains? leases "synth-task-1")))
            (is (contains? leases "synth-task-2")))))
      (testing "expiry is computed from recorded time: now >= expires-at is absent"
        (let [leases (execute/current-leases events 2000)]
          (is (= {} leases)))
        (let [leases (execute/current-leases events 1999)]
          (is (= 2 (count leases)))))
      (testing "unknown event kinds are ignored by the fold"
        (let [leases (execute/current-leases
                      (conj events {:event/kind :lease/vanished
                                    :lease/task-id "synth-task-1"})
                      1000)]
          (is (= "synth-token-1" (get-in leases ["synth-task-1" :lease/token]))))))))

;; ------------------------------------------------------------------
;; Pure transition decisions

(deftest acquire-lease-decisions
  (testing "free task acquires; held task denies; malformed never produces an event"
    (let [ok (execute/acquire-lease {} (acquire-input "synth-task-1" "synth-token-1" 2000))]
      (is (true? (:lease/ok ok)))
      (is (= :lease/acquired (get-in ok [:lease/event :event/kind])))
      (is (= "synth-token-1" (get-in ok [:lease/event :lease/token]))))
    (let [held (execute/current-leases [(acquired-event "synth-task-1" "synth-token-1" 2000)] 1000)
          denied (execute/acquire-lease held (acquire-input "synth-task-1" "synth-token-2" 2000))]
      (is (false? (:lease/ok denied)))
      (is (= :task-already-leased (:lease/reason denied)))
      (is (not (contains? denied :lease/event))))
    (doseq [bad [(dissoc (acquire-input "synth-task-1" "synth-token-1" 2000) :lease/token)
                 (assoc (acquire-input "synth-task-1" "synth-token-1" 2000)
                        :lease/expires-at -1)
                 (assoc (acquire-input "synth-task-1" "synth-token-1" 2000)
                        :lease/worker-id "")
                 nil]]
      (let [res (execute/acquire-lease {} bad)]
        (is (false? (:lease/ok res)))
        (is (= :malformed (:lease/reason res)))))))

(deftest renew-lease-decisions
  (testing "matching token rotates; wrong token is stale; missing lease denies"
    (let [leases (execute/current-leases [(acquired-event "synth-task-1" "synth-token-1" 2000)] 1000)
          ok (execute/renew-lease leases {:lease/task-id "synth-task-1"
                                          :lease/presented-token "synth-token-1"
                                          :lease/token "synth-token-2"
                                          :lease/expires-at 3000
                                          :lease/issued-by "synth-evaluator-1"})]
      (is (true? (:lease/ok ok)))
      (is (= :lease/renewed (get-in ok [:lease/event :event/kind])))
      (is (= "synth-token-1" (get-in ok [:lease/event :lease/presented-token])))
      (is (= "synth-token-2" (get-in ok [:lease/event :lease/token]))))
    (let [leases (execute/current-leases [(acquired-event "synth-task-1" "synth-token-1" 2000)] 1000)
          stale (execute/renew-lease leases {:lease/task-id "synth-task-1"
                                             :lease/presented-token "synth-token-OLD"
                                             :lease/token "synth-token-2"
                                             :lease/expires-at 3000
                                             :lease/issued-by "synth-evaluator-1"})]
      (is (false? (:lease/ok stale)))
      (is (= :stale-fencing-token (:lease/reason stale)))
      (is (not (contains? stale :lease/event))))
    (let [res (execute/renew-lease {} {:lease/task-id "synth-task-1"
                                       :lease/presented-token "synth-token-1"
                                       :lease/token "synth-token-2"
                                       :lease/expires-at 3000
                                       :lease/issued-by "synth-evaluator-1"})]
      (is (= :no-lease-held (:lease/reason res))))))

(deftest release-lease-decisions
  (testing "matching token releases; wrong token is stale"
    (let [leases (execute/current-leases [(acquired-event "synth-task-1" "synth-token-1" 2000)] 1000)
          ok (execute/release-lease leases {:lease/task-id "synth-task-1"
                                            :lease/presented-token "synth-token-1"
                                            :lease/issued-by "synth-evaluator-1"})]
      (is (true? (:lease/ok ok)))
      (is (= :lease/released (get-in ok [:lease/event :event/kind]))))
    (let [leases (execute/current-leases [(acquired-event "synth-task-1" "synth-token-1" 2000)] 1000)
          stale (execute/release-lease leases {:lease/task-id "synth-task-1"
                                               :lease/presented-token "synth-token-WRONG"
                                               :lease/issued-by "synth-evaluator-1"})]
      (is (= :stale-fencing-token (:lease/reason stale)))
      (is (not (contains? stale :lease/event))))))

(deftest revoke-lease-decisions
  (testing "revocation is evaluator-initiated: no token required, reason required"
    (let [leases (execute/current-leases [(acquired-event "synth-task-1" "synth-token-1" 2000)] 1000)
          ok (execute/revoke-lease leases {:lease/task-id "synth-task-1"
                                           :lease/issued-by "synth-evaluator-1"
                                           :lease/reason :worker-compromised})]
      (is (true? (:lease/ok ok)))
      (is (= :lease/revoked (get-in ok [:lease/event :event/kind])))
      (is (= :worker-compromised (get-in ok [:lease/event :lease/reason]))))
    (let [res (execute/revoke-lease {} {:lease/task-id "synth-task-1"
                                        :lease/issued-by "synth-evaluator-1"
                                        :lease/reason :worker-compromised})]
      (is (= :no-lease-held (:lease/reason res))))
    (let [leases (execute/current-leases [(acquired-event "synth-task-1" "synth-token-1" 2000)] 1000)
          bad (execute/revoke-lease leases {:lease/task-id "synth-task-1"
                                            :lease/issued-by "synth-evaluator-1"
                                            :lease/reason "not-a-keyword"})]
      (is (= :malformed (:lease/reason bad))))))

;; ------------------------------------------------------------------
;; Fencing

(deftest fencing-token-checks
  (testing "match passes; missing lease and mismatch deny with named reasons"
    (let [leases (execute/current-leases [(acquired-event "synth-task-1" "synth-token-1" 2000)] 1000)]
      (is (nil? (execute/check-fencing-token leases "synth-task-1" "synth-token-1")))
      (is (= :stale-fencing-token
             (execute/check-fencing-token leases "synth-task-1" "synth-token-OLD")))
      (is (= :no-lease-held
             (execute/check-fencing-token leases "synth-task-9" "synth-token-1"))))))

(deftest stale-token-denies-proposal
  (testing "after token rotation, a proposal citing the superseded token is denied"
    (let [acquired (acquired-event "synth-task-1" "synth-token-1" 2000)
          renewed (:lease/event
                   (execute/renew-lease
                    (execute/current-leases [acquired] 1000)
                    {:lease/task-id "synth-task-1"
                     :lease/presented-token "synth-token-1"
                     :lease/token "synth-token-2"
                     :lease/expires-at 3000
                     :lease/issued-by "synth-evaluator-1"}))
          leases (execute/current-leases [acquired renewed] 1000)
          current (get leases "synth-task-1")
          stale-res (execute/evaluate-proposal (task) (proposal "synth-token-1") current)
          fresh-res (execute/evaluate-proposal (task) (proposal "synth-token-2") current)]
      (is (= :deny (:proposal/decision stale-res)))
      (is (= :stale-fencing-token (:proposal/reason stale-res)))
      (is (= :admit (:proposal/decision fresh-res))))))

(deftest task-classes-single-definition
  (testing "the execution port re-exports the ledger's task classes"
    (is (= ledger/task-classes execute/task-classes))
    (is (= #{:task-class/standard :task-class/self-modifying} execute/task-classes))))
