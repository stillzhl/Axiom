(ns axiom.policy-test
  "Tests for spec 0005 T2 (`axiom.policy`): approved-policy loading,
   SHA-256 digest pinning over the canonical EDN encoding,
   `:governance/policy-approved` lookup with `:supersedes` chains,
   structural rejection of candidate-branch policy sources, and the
   `policy-approve` governance event shape.

   All policies, events, SHAs and identities are synthetic and
   invented (`synth-*`). No real repository identities, no live
   credentials, no network."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.policy :as policy]
            [axiom.gate :as gate]
            [axiom.model :as model]))

;; ------------------------------------------------------------------
;; Synthetic fixtures (invented only)

(def ^:private sha-head (apply str (repeat 40 "c")))

(def ^:private policy-content
  {:policy/id "synth-enforce-v1"
   :policy/gates [{:gate/id :ci
                    :gate/check-run-id 9001
                    :gate/workflow-identity ".github/workflows/synth-ci.yml"
                    :gate/required true}]})

(def ^:private candidate-ref
  {:candidate/branch "synth-feature"
   :candidate/head sha-head
   :candidate/base (apply str (repeat 40 "b"))})

(defn- ledger-descriptor []
  {:source/type :ledger :source/policy-id "synth-enforce-v1"})

(defn- approval-event
  [digest & {:keys [event-id policy-id supersedes approved-at]
             :or {event-id "evt-approve-1" policy-id "synth-enforce-v1"
                  supersedes nil approved-at 1000}}]
  {:event/id event-id
   :event/kind :governance/policy-approved
   :governance/policy-id policy-id
   :governance/digest digest
   :governance/supersedes supersedes
   :governance/approver "synth-owner"
   :governance/approved-at approved-at})

;; ------------------------------------------------------------------
;; Digest pinning

(deftest content-digest-is-sha256-over-canonical-edn
  (let [d (policy/content-digest policy-content)]
    (is (= (model/digest policy-content) d))
    (is (re-matches #"sha256:[0-9a-f]{64}" d))
    (testing "key order does not change the digest (canonical encoding)"
      (is (= d (policy/content-digest (into {} (reverse (seq policy-content)))))))
    (testing "any content change changes the digest"
      (is (not= d (policy/content-digest (assoc policy-content :policy/extra true)))))))

;; ------------------------------------------------------------------
;; Resolution: the happy path

(deftest resolve-ok-with-recorded-approval
  (let [digest (policy/content-digest policy-content)
        events [(approval-event digest)]
        r (policy/resolve (ledger-descriptor) policy-content events candidate-ref)]
    (is (= :ok (:policy/resolution r)))
    (is (= "synth-enforce-v1" (:policy/id r)))
    (is (= digest (:policy/digest r)))
    (is (= "evt-approve-1" (:policy/approval-event-id r)))
    (is (= policy-content (:policy/content r)))))

(deftest resolve-defers-without-approval-event
  (let [r (policy/resolve (ledger-descriptor) policy-content [] candidate-ref)]
    (is (= :deferred (:policy/resolution r)))
    (is (= :no-approved-policy (:policy/reason r)))
    (is (= (policy/content-digest policy-content) (:policy/digest r)))))

(deftest resolve-defers-for-approval-of-another-digest
  (let [other (policy/content-digest (assoc policy-content :policy/gates []))
        r (policy/resolve (ledger-descriptor) policy-content
                          [(approval-event other)] candidate-ref)]
    (is (= :deferred (:policy/resolution r)))
    (is (= :no-approved-policy (:policy/reason r)))))

(deftest resolve-defers-when-approval-revoked
  (let [digest (policy/content-digest policy-content)
        events [(approval-event digest)
                {:event/id "evt-revoke-1"
                 :event/kind :governance/policy-revoked
                 :governance/policy-id "synth-enforce-v1"
                 :governance/digest digest
                 :governance/revoker "synth-owner"
                 :governance/revoked-at 2000}]
        r (policy/resolve (ledger-descriptor) policy-content events candidate-ref)]
    (is (= :deferred (:policy/resolution r)))
    (is (= :policy-approval-revoked (:policy/reason r)))))

(deftest approval-chain-walks-supersedes
  (let [v1-content (assoc policy-content :policy/revision 1)
        v2-content (assoc policy-content :policy/revision 2)
        d1 (policy/content-digest v1-content)
        d2 (policy/content-digest v2-content)
        events [(approval-event d1 :event-id "evt-approve-v1" :approved-at 1000)
                (approval-event d2 :event-id "evt-approve-v2" :supersedes d1
                                :approved-at 2000)]
        chain (policy/approval-chain events "synth-enforce-v1" d2)]
    (is (= ["evt-approve-v2" "evt-approve-v1"] (mapv :event/id chain)))
    (is (= d1 (:governance/supersedes (first chain))))
    (is (nil? (:governance/supersedes (second chain))))
    (testing "no approval for the digest: no chain"
      (is (nil? (policy/approval-chain events "synth-enforce-v1" "sha256:ffff"))))))

(deftest latest-approval-wins
  (let [digest (policy/content-digest policy-content)
        events [(approval-event digest :event-id "evt-old" :approved-at 1000)
                (approval-event digest :event-id "evt-new" :approved-at 2000)]
        r (policy/resolve (ledger-descriptor) policy-content events candidate-ref)]
    (is (= "evt-new" (:policy/approval-event-id r)))))

;; ------------------------------------------------------------------
;; Resolution: structural rejections

(deftest candidate-branch-policy-source-is-refused-structurally
  (let [descriptors {"candidate-branch type"
                     {:source/type :candidate-branch :source/policy-id "synth-enforce-v1"}
                     "candidate-ref type"
                     {:source/type :candidate-ref :source/policy-id "synth-enforce-v1"}
                     "candidate-branch flag"
                     {:source/type :ledger :source/policy-id "synth-enforce-v1"
                      :source/candidate-branch true}
                     "ref equal to candidate head SHA"
                     {:source/type :ledger :source/policy-id "synth-enforce-v1"
                      :source/ref sha-head}
                     "ref equal to candidate branch name"
                     {:source/type :ledger :source/policy-id "synth-enforce-v1"
                      :source/ref "synth-feature"}}]
    (doseq [[label descriptor] descriptors]
      (testing label
        (let [r (policy/resolve descriptor policy-content [] candidate-ref)]
          (is (= :invalid (:policy/resolution r)) label)
          (is (= :policy-source-is-candidate-ref (:policy/reason r)) label))))))

(deftest structural-reject-tolerates-unrelated-refs
  (let [r (policy/resolve {:source/type :ledger :source/policy-id "synth-enforce-v1"
                           :source/ref "main"}
                          policy-content
                          [(approval-event (policy/content-digest policy-content))]
                          candidate-ref)]
    (is (= :ok (:policy/resolution r)))))

(deftest malformed-descriptors-are-invalid
  (doseq [descriptor [nil "ledger" [] 42]]
    (let [r (policy/resolve descriptor policy-content [] candidate-ref)]
      (is (= :invalid (:policy/resolution r)))
      (is (= :malformed-policy-source (:policy/reason r))))))

(deftest policy-id-mismatch-is-invalid
  (let [r (policy/resolve {:source/type :ledger :source/policy-id "synth-other"}
                          policy-content [] candidate-ref)]
    (is (= :invalid (:policy/resolution r)))
    (is (= :policy-id-mismatch (:policy/reason r)))))

(deftest missing-policy-id-is-invalid
  (let [r (policy/resolve {:source/type :ledger}
                          (dissoc policy-content :policy/id) [] candidate-ref)]
    (is (= :invalid (:policy/resolution r)))
    (is (= :missing-policy-id (:policy/reason r)))))

(deftest non-map-policy-content-is-invalid
  (let [r (policy/resolve (ledger-descriptor) "not-a-policy" [] candidate-ref)]
    (is (= :invalid (:policy/resolution r)))
    (is (= :malformed-policy-content (:policy/reason r)))))

;; ------------------------------------------------------------------
;; policy-approve event construction

(deftest policy-approve-event-shape
  (let [e (policy/policy-approve-event policy-content "synth-owner"
                                       {:event-id "evt-approve-9"
                                        :policy-id "synth-enforce-v1"
                                        :supersedes nil
                                        :approved-at 4242})]
    (is (= "evt-approve-9" (:event/id e)))
    (is (= :governance/policy-approved (:event/kind e)))
    (is (= "synth-enforce-v1" (:governance/policy-id e)))
    (is (= (policy/content-digest policy-content) (:governance/digest e)))
    (is (nil? (:governance/supersedes e)))
    (is (= "synth-owner" (:governance/approver e)))
    (is (= 4242 (:governance/approved-at e))))
  (testing "the constructed event resolves as an approval"
    (let [e (policy/policy-approve-event policy-content "synth-owner"
                                         {:event-id "evt-approve-10"
                                          :policy-id "synth-enforce-v1"
                                          :approved-at 5000})
          r (policy/resolve (ledger-descriptor) policy-content [e] candidate-ref)]
      (is (= :ok (:policy/resolution r)))
      (is (= "evt-approve-10" (:policy/approval-event-id r)))))
  (testing "blank approver identity is refused"
    (is (thrown? clojure.lang.ExceptionInfo
                 (policy/policy-approve-event policy-content " "
                                              {:event-id "evt-x"
                                               :policy-id "synth-enforce-v1"})))))

;; ------------------------------------------------------------------
;; End-to-end wiring: resolution -> gate decision

(deftest unresolved-policy-defers-every-gate-never-allows
  (let [resolution (policy/resolve (ledger-descriptor) policy-content [] candidate-ref)
        candidate {:candidate/repo "synth-org/synth-repo" :candidate/pr 7
                   :candidate/base (apply str (repeat 40 "b"))
                   :candidate/head sha-head
                   :candidate/tree (apply str (repeat 40 "d"))}
        d (gate/decision-for-unresolved resolution candidate "synth-evaluator")]
    (is (= :deferred (:policy/resolution resolution)))
    (is (= :defer (:gate/decision d)))
    (is (every? #(= :no-approved-policy (:gate/reason %)) (:gate/reasons d)))
    (is (not= :allow (:gate/decision d)))))

(deftest approved-policy-feeds-gate-evaluation
  (let [digest (policy/content-digest policy-content)
        resolution (policy/resolve (ledger-descriptor) policy-content
                                   [(approval-event digest)] candidate-ref)
        gate-policy (assoc policy-content
                           :policy/digest (:policy/digest resolution)
                           :policy/approval-event-id (:policy/approval-event-id resolution)
                           :policy/reserved-paths [])
        observation {:observation/repo "synth-org/synth-repo"
                     :observation/pr 7
                     :observation/base (apply str (repeat 40 "b"))
                     :observation/head sha-head
                     :observation/tree (apply str (repeat 40 "d"))
                     :observation/current? true
                     :observation/synthetic-fixture? true
                     :observation/trust :trust/provider-authenticated
                     :observation/check-runs [{:check/id 9001
                                               :check/workflow-identity ".github/workflows/synth-ci.yml"
                                               :check/title "synth-ci / build"
                                               :check/conclusion :success
                                               :check/base (apply str (repeat 40 "b"))
                                               :check/head sha-head
                                               :check/trust :trust/provider-observed}]}
        d (gate/evaluate gate-policy observation "synth-evaluator"
                         {:capability/mode :enforcement
                          :capability/trusted-evaluator "synth-evaluator"})]
    (is (= :allow (:gate/decision d)))
    (is (= (:policy/approval-event-id resolution)
           (get-in d [:gate/policy :policy/approval-event-id])))))
