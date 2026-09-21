(ns axiom.capability-test
  "Tests for spec 0005 T5 (`axiom.capability`, plus the pure
   `axiom.gate/report-bypassed` outcome).

   Pure logic only: no I/O, no network. All repositories, SHAs,
   logins and identities are invented (`synth-*`); no real
   identities, no live credentials."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.capability :as cap]
            [axiom.gate :as gate]
            [axiom.ledger :as ledger]))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-merge (apply str (repeat 40 "d")))
(def ^:private evaluator-id "synth-evaluator")

(defn- answers
  [& {:keys [write readable configurable trusted]
      :or {write true readable true configurable true trusted evaluator-id}}]
  {:capability/trusted-evaluator trusted
   :capability/checks-write? write
   :capability/protection-readable? readable
   :capability/protections-configurable? configurable})

(defn- invalid?
  [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e
         (= :invalid (:axiom/error (ex-data e))))))

;; ------------------------------------------------------------------
;; R8 capability computation

(deftest compute-capability-enforcement
  (let [c (cap/compute-capability (answers))]
    (is (= :enforcement (:capability/mode c)))
    (is (= evaluator-id (:capability/trusted-evaluator c)))
    (is (true? (:capability/checks-write? c)))
    (is (true? (:capability/protection-readable? c)))
    (is (true? (:capability/protections-configurable? c)))
    (is (= [] (:capability/advisory-reasons c)))
    (is (true? (cap/enforcing? c)))
    (is (false? (cap/advisory? c)))))

(deftest compute-capability-advisory
  (testing "any single no forces advisory with a named reason"
    (doseq [[k reason] [[:capability/checks-write? :no-checks-write]
                        [:capability/protection-readable? :protection-unreadable]
                        [:capability/protections-configurable? :protections-unconfigurable]]]
      (let [c (cap/compute-capability (assoc (answers) k false))]
        (is (= :advisory (:capability/mode c)) (str k))
        (is (= [reason] (:capability/advisory-reasons c)) (str k))
        (is (false? (cap/enforcing? c)) (str k))
        (is (true? (cap/advisory? c)) (str k)))))
  (testing "multiple nos name every failed answer"
    (let [c (cap/compute-capability (answers :write false :readable false))]
      (is (= :advisory (:capability/mode c)))
      (is (= [:no-checks-write :protection-unreadable]
             (:capability/advisory-reasons c)))))
  (testing "a non-boolean answer is not a yes"
    (let [c (cap/compute-capability (assoc (answers) :capability/checks-write? "yes"))]
      (is (= :advisory (:capability/mode c)))
      (is (= [:no-checks-write] (:capability/advisory-reasons c)))
      (is (false? (:capability/checks-write? c)))))
  (testing "a missing answer is not a yes"
    (let [c (cap/compute-capability (dissoc (answers) :capability/protection-readable?))]
      (is (= :advisory (:capability/mode c)))
      (is (= [:protection-unreadable] (:capability/advisory-reasons c))))))

(deftest compute-capability-invalid-input
  (testing "missing trusted evaluator"
    (is (invalid? #(cap/compute-capability (answers :trusted nil)))))
  (testing "blank trusted evaluator"
    (is (invalid? #(cap/compute-capability (answers :trusted " ")))))
  (testing "non-map answers"
    (is (invalid? #(cap/compute-capability nil)))))

;; ------------------------------------------------------------------
;; Advisory-mode reporting

(deftest advisory-report-shape
  (let [c (cap/compute-capability (answers :write false))
        d {:gate/decision :allow
           :gate/reasons []
           :gate/candidate {:candidate/repo "synth-org/synth-repo"}
           :gate/evaluator evaluator-id
           :gate/policy {:policy/digest "sha256:aa"}
           :gate/trust #{}}
        report (cap/advisory-report c d)]
    (testing "the evaluation is produced and recorded"
      (is (= :advisory (:report/mode report)))
      (is (= d (:report/evaluation report))))
    (testing "zero provider writes, enforcement never claimed"
      (is (= 0 (:report/provider-writes report)))
      (is (false? (:report/enforcement-claimed report))))
    (testing "the failed answers are named"
      (is (= [:no-checks-write] (:report/advisory-reasons report)))))
  (testing "an enforcement record is not an advisory report"
    (is (invalid? #(cap/advisory-report (cap/compute-capability (answers))
                                        {:gate/decision :allow}))))
  (testing "a non-decision is not reportable"
    (is (invalid? #(cap/advisory-report (cap/compute-capability (answers :write false))
                                        {:not :a-decision})))))

;; ------------------------------------------------------------------
;; Branch-protection observation extension

(deftest protection-observation-shape
  (let [p (cap/protection-observation
           {:protection/required-checks [{:check/id 9001} {:check/id 9002 :check/title "ignored display name"}]
            :protection/required-approval-count 2
            :protection/dismiss-stale-reviews? true
            :protection/enforce-admins? false})]
    (testing "required checks are by check-run id; display titles are dropped"
      (is (= [{:check/id 9001} {:check/id 9002}] (:protection/required-checks p))))
    (is (= 2 (:protection/required-approval-count p)))
    (is (true? (:protection/dismiss-stale-reviews? p)))
    (is (false? (:protection/enforce-admins? p))))
  (testing "zero required approvals is a valid shape"
    (is (= 0 (:protection/required-approval-count
              (cap/protection-observation
               {:protection/required-checks []
                :protection/required-approval-count 0
                :protection/dismiss-stale-reviews? false
                :protection/enforce-admins? false})))))
  (testing "malformed shapes are invalid"
    (is (invalid? #(cap/protection-observation
                    {:protection/required-checks [{:check/id "nine"}]
                     :protection/required-approval-count 1
                     :protection/dismiss-stale-reviews? true
                     :protection/enforce-admins? true})))
    (is (invalid? #(cap/protection-observation
                    {:protection/required-checks [{:check/id 9001}]
                     :protection/required-approval-count -1
                     :protection/dismiss-stale-reviews? true
                     :protection/enforce-admins? true})))
    (is (invalid? #(cap/protection-observation
                    {:protection/required-checks [{:check/id 9001}]
                     :protection/required-approval-count 1
                     :protection/dismiss-stale-reviews? "yes"
                     :protection/enforce-admins? true})))))

;; ------------------------------------------------------------------
;; Merge-group candidate identity

(deftest merge-group-candidate-shape
  (let [c (cap/merge-group-candidate
           {:candidate/merge-group-head sha-merge
            :candidate/grouped-prs [{:candidate/repo "synth-org/synth-repo" :candidate/pr 7}
                                    {:candidate/repo "synth-org/synth-repo" :candidate/pr 9}]})]
    (is (= :merge-group (:candidate/kind c)))
    (is (= sha-merge (:candidate/merge-group-head c)))
    (is (= [{:candidate/repo "synth-org/synth-repo" :candidate/pr 7}
            {:candidate/repo "synth-org/synth-repo" :candidate/pr 9}]
           (:candidate/grouped-prs c))))
  (testing "malformed shapes are invalid"
    (is (invalid? #(cap/merge-group-candidate
                    {:candidate/merge-group-head "not-a-sha"
                     :candidate/grouped-prs [{:candidate/repo "synth-org/synth-repo" :candidate/pr 7}]})))
    (is (invalid? #(cap/merge-group-candidate
                    {:candidate/merge-group-head sha-merge
                     :candidate/grouped-prs []})))
    (is (invalid? #(cap/merge-group-candidate
                    {:candidate/merge-group-head sha-merge
                     :candidate/grouped-prs [{:candidate/repo "not-a-slug" :candidate/pr 7}]})))))

;; ------------------------------------------------------------------
;; Admin bypasses and the :bypassed gate outcome

(deftest admin-bypass-event-shape
  (let [e (cap/admin-bypass-event {:bypass/actor "synth-owner"
                                   :bypass/reason "emergency hotfix"})]
    (is (= :governance/admin-bypass (:event/kind e)))
    (is (= "synth-owner" (:governance/actor e)))
    (is (= "emergency hotfix" (:governance/reason e)))
    ;; The constructed event is exactly what the ledger validates,
    ;; so it can be recorded verbatim.
    (let [env (ledger/record-governance
               nil {:event/id "evt-synth-bypass-1"
                    :stream/id "governance" :dedup/key "bypass-1"
                    :producer "synth-owner"
                    :observed/time 1 :ingested/time 2
                    :governance-event (assoc e :event/id "evt-synth-bypass-1")})]
      (is (= (assoc e :event/id "evt-synth-bypass-1")
             (get-in env [:payload :governance/event]))))
  (testing "actor and reason are both required"
    (is (invalid? #(cap/admin-bypass-event {:bypass/actor "" :bypass/reason "r"})))
    (is (invalid? #(cap/admin-bypass-event {:bypass/actor "a" :bypass/reason nil}))))))

(deftest report-bypassed-outcome
  (let [bypass (cap/admin-bypass-event {:bypass/actor "synth-owner"
                                        :bypass/reason "emergency hotfix"})
        decision {:gate/decision :allow
                  :gate/reasons [{:gate/id :ci :gate/outcome :satisfied :gate/reason :check-satisfied}]
                  :gate/candidate {:candidate/repo "synth-org/synth-repo"}
                  :gate/evaluator evaluator-id
                  :gate/policy {:policy/digest "sha256:aa"}
                  :gate/trust #{}}]
    (testing "a bypassed gate reports :bypassed, never :allow"
      (let [r (gate/report-bypassed decision bypass)]
        (is (= :bypassed (:gate/decision r)))
        (is (not= :allow (:gate/decision r)))
        (is (= {:bypass/actor "synth-owner" :bypass/reason "emergency hotfix"}
               (:gate/bypass r)))
        (is (some #(= :admin-bypass-observed (:gate/reason %)) (:gate/reasons r)))
        ;; the original reasons are preserved
        (is (some #(= :ci (:gate/id %)) (:gate/reasons r)))))
    (testing "a denied gate bypassed is still :bypassed, not :allow"
      (let [r (gate/report-bypassed (assoc decision :gate/decision :deny) bypass)]
        (is (= :bypassed (:gate/decision r)))))
    (testing "an :invalid decision stays :invalid: a bypass cannot validate an evaluation that never ran"
      (let [r (gate/report-bypassed (assoc decision :gate/decision :invalid) bypass)]
        (is (= :invalid (:gate/decision r)))
        (is (nil? (:gate/bypass r)))))
    (testing "a bypass event without actor and reason is malformed"
      (is (invalid? #(gate/report-bypassed decision {:bypass/actor "synth-owner"})))
      (is (invalid? #(gate/report-bypassed decision {:bypass/reason "r"}))))))
