(ns axiom.gate-test
  "Adversarial and boundary tests for spec 0005 T1 (`axiom.gate`).

   Every repository, SHA, login, workflow and digest is invented:
   `synth-org/synth-repo`, synthetic 40-hex SHAs, `synth-*`
   identities. No real repository identities, no live credentials,
   no network. The observations here stand in for adapter-validated
   input: the fixtures explicitly set the `:observation/current?`
   and `:observation/synthetic-fixture?` flags the pure port reads."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.gate :as gate]))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-base (apply str (repeat 40 "b")))
(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-tree (apply str (repeat 40 "d")))
(def ^:private sha-other (apply str (repeat 40 "e")))
(def ^:private policy-digest (str "sha256:" (apply str (repeat 64 "a"))))
(def ^:private evaluator "synth-evaluator")

(defn- policy
  [& {:keys [gates reserved approval]
      :or {approval "evt-approve-1" reserved []}}]
  {:policy/id "synth-enforce-v1"
   :policy/digest policy-digest
   :policy/approval-event-id approval
   :policy/reserved-paths reserved
   :policy/gates (or gates [{:gate/id :ci
                             :gate/check-run-id 9001
                             :gate/workflow-identity ".github/workflows/synth-ci.yml"
                             :gate/required true}
                            {:gate/id :lint
                             :gate/check-run-id 9002
                             :gate/workflow-identity ".github/workflows/synth-lint.yml"
                             :gate/required true}])})

(defn- run
  [& {:keys [id workflow title conclusion base head trust trust-marks publication]
      :or {id 9001 workflow ".github/workflows/synth-ci.yml"
           title "synth-ci / build" conclusion :success
           base sha-base head sha-head trust :trust/provider-observed}}]
  (cond-> {:check/id id
           :check/workflow-identity workflow
           :check/title title
           :check/conclusion conclusion
           :check/base base
           :check/head head
           :check/trust trust}
    trust-marks (assoc :check/trust-marks trust-marks)
    publication (assoc :check/publication publication)))

(defn- obs
  [& {:keys [runs changed current? trust fixture? base head tree]
      :or {base sha-base head sha-head tree sha-tree
           current? true fixture? false
           trust :trust/provider-authenticated}}]
  (cond-> {:observation/repo "synth-org/synth-repo"
           :observation/pr 7
           :observation/base base
           :observation/head head
           :observation/tree tree
           :observation/current? current?
           :observation/synthetic-fixture? fixture?
           :observation/trust trust}
    (some? runs) (assoc :observation/check-runs runs)
    (some? changed) (assoc :observation/changed-files changed)))

(defn- capability
  [& {:keys [mode trusted] :or {mode :enforcement trusted evaluator}}]
  {:capability/mode mode :capability/trusted-evaluator trusted})

(defn- lint-run
  "The second required gate's run; satisfied by default."
  [& {:keys [conclusion] :or {conclusion :success}}]
  (run :id 9002 :workflow ".github/workflows/synth-lint.yml"
       :title "synth-lint" :conclusion conclusion))

;; ------------------------------------------------------------------
;; Happy path and trust-mark issuance

(deftest allow-when-all-gates-satisfied
  (let [d (gate/evaluate (policy) (obs :runs [(run) (lint-run)]) evaluator (capability))]
    (is (= :allow (:gate/decision d)))
    (is (= {:candidate/repo "synth-org/synth-repo" :candidate/pr 7
            :candidate/base sha-base :candidate/head sha-head
            :candidate/tree sha-tree}
           (:gate/candidate d)))
    (is (= evaluator (:gate/evaluator d)))
    (is (= {:policy/id "synth-enforce-v1" :policy/digest policy-digest
            :policy/approval-event-id "evt-approve-1"}
           (:gate/policy d)))
    (is (= #{:ci :lint} (set (map :gate/id (:gate/reasons d)))))
    (is (every? #(= :satisfied (:gate/outcome %)) (:gate/reasons d)))))

(deftest remote-ci-issued-only-by-trusted-evaluator-in-enforcement
  (testing "issued when evaluator is the configured trusted evaluator"
    (let [d (gate/evaluate (policy) (obs :runs [(run) (lint-run)]) evaluator (capability))]
      (is (contains? (:gate/trust d) :trust/remote-ci))
      ;; earlier marks pass through unchanged, never rewritten
      (is (contains? (:gate/trust d) :trust/provider-observed))
      (is (contains? (:gate/trust d) :trust/provider-authenticated))))
  (testing "not issued when the evaluator is not the configured one"
    (let [d (gate/evaluate (policy) (obs :runs [(run) (lint-run)])
                           "synth-impostor" (capability))]
      (is (= :allow (:gate/decision d)))
      (is (not (contains? (:gate/trust d) :trust/remote-ci)))))
  (testing "not issued in advisory mode"
    (let [d (gate/evaluate (policy) (obs :runs [(run) (lint-run)])
                           evaluator (capability :mode :advisory))]
      (is (= :allow (:gate/decision d)))
      (is (not (contains? (:gate/trust d) :trust/remote-ci)))))
  (testing "not issued for synthetic fixtures"
    (let [d (gate/evaluate (policy) (obs :runs [(run) (lint-run)] :fixture? true)
                           evaluator (capability))]
      (is (= :allow (:gate/decision d)))
      (is (not (contains? (:gate/trust d) :trust/remote-ci)))))
  (testing "not issued for a stale observation"
    (let [d (gate/evaluate (policy) (obs :runs [(run) (lint-run)] :current? false)
                           evaluator (capability))]
      (is (not (contains? (:gate/trust d) :trust/remote-ci))))))

(deftest non-required-gates-do-not-block
  (let [p (policy :gates [{:gate/id :ci :gate/check-run-id 9001
                           :gate/workflow-identity ".github/workflows/synth-ci.yml"
                           :gate/required true}
                          {:gate/id :optional-docs :gate/check-run-id 9999
                           :gate/workflow-identity ".github/workflows/docs.yml"
                           :gate/required false}])
        d (gate/evaluate p (obs :runs [(run)]) evaluator (capability))]
    (is (= :allow (:gate/decision d)))
    (is (= #{:ci} (set (map :gate/id (:gate/reasons d)))))))

;; ------------------------------------------------------------------
;; Anti-bypass classes (R4)

(deftest renamed-check-does-not-satisfy
  (testing "same display title, different provider-assigned id"
    (let [d (gate/evaluate (policy)
                           (obs :runs [(run :id 7777 :title "synth-ci / build")
                                       (lint-run)])
                           evaluator (capability))]
      (is (= :defer (:gate/decision d)))
      (is (some #(and (= :ci (:gate/id %))
                      (= :unknown (:gate/outcome %))
                      (= :no-matching-check-run (:gate/reason %)))
                (:gate/reasons d)))))
  (testing "same id, different workflow identity"
    (let [d (gate/evaluate (policy)
                           (obs :runs [(run :workflow ".github/workflows/impostor.yml")
                                       (lint-run)])
                           evaluator (capability))]
      (is (= :defer (:gate/decision d)))
      (is (some #(= :no-matching-check-run (:gate/reason %))
                (filter #(= :ci (:gate/id %)) (:gate/reasons d))))))
  (testing "display titles are never used as gate keys, even hostile ones"
    ;; A run whose title literally claims the gate id still cannot
    ;; satisfy it without the provider-assigned identity.
    (let [d (gate/evaluate (policy)
                           (obs :runs [(run :id 31337 :title ":ci")
                                       (lint-run)])
                           evaluator (capability))]
      (is (not= :allow (:gate/decision d))))))

(deftest omitted-verification-defers-never-allows
  (testing "no check runs at all"
    (let [d (gate/evaluate (policy) (obs) evaluator (capability))]
      (is (= :defer (:gate/decision d)))
      (is (every? #(= :unknown (:gate/outcome %)) (:gate/reasons d)))
      (is (every? #(= :no-matching-check-run (:gate/reason %)) (:gate/reasons d)))))
  (testing "one required gate omitted"
    (let [d (gate/evaluate (policy) (obs :runs [(run)]) evaluator (capability))]
      (is (= :defer (:gate/decision d))))))

(deftest stale-success-denies
  (let [d (gate/evaluate (policy)
                         (obs :runs [(run :head sha-other) (lint-run)])
                         evaluator (capability))]
    (is (= :deny (:gate/decision d)))
    (is (some #(and (= :ci (:gate/id %))
                    (= :stale (:gate/outcome %))
                    (= :stale-check-run (:gate/reason %)))
              (:gate/reasons d))))
  (testing "stale base also denies"
    (let [d (gate/evaluate (policy)
                           (obs :runs [(run :base sha-other) (lint-run)])
                           evaluator (capability))]
      (is (= :deny (:gate/decision d))))))

(deftest failed-check-denies
  (let [d (gate/evaluate (policy)
                         (obs :runs [(run :conclusion :failure) (lint-run)])
                         evaluator (capability))]
    (is (= :deny (:gate/decision d)))
    (is (some #(and (= :ci (:gate/id %))
                    (= :failed (:gate/outcome %))
                    (= :check-failed (:gate/reason %)))
              (:gate/reasons d)))))

(deftest policy-path-touch-denies
  (let [p (policy :reserved ["policies/" ".axiom/policy.edn"])
        d (gate/evaluate p
                         (obs :runs [(run) (lint-run)]
                              :changed ["src/a.clj" "policies/synth-enforce-v1.edn"])
                         evaluator (capability))]
    (is (= :deny (:gate/decision d)))
    (is (some #(and (= :policy-integrity (:gate/id %))
                    (= :violated (:gate/outcome %))
                    (= :policy-path-touched-by-candidate (:gate/reason %)))
              (:gate/reasons d))))
  (testing "changes outside reserved paths do not trigger"
    (let [p (policy :reserved ["policies/"])
          d (gate/evaluate p
                           (obs :runs [(run) (lint-run)]
                                :changed ["src/a.clj" "docs/notes.md"])
                           evaluator (capability))]
      (is (= :allow (:gate/decision d)))))
  (testing "no reserved paths configured: nothing to violate"
    (let [d (gate/evaluate (policy)
                           (obs :runs [(run) (lint-run)]
                                :changed ["policies/synth-enforce-v1.edn"])
                           evaluator (capability))]
      (is (= :allow (:gate/decision d))))))

(deftest forged-remote-ci-denies
  (testing "claimed mark with no publication reference"
    (let [d (gate/evaluate (policy)
                           (obs :runs [(run :trust-marks [:trust/remote-ci]) (lint-run)])
                           evaluator (capability))]
      (is (= :deny (:gate/decision d)))
      (is (some #(and (= :ci (:gate/id %))
                      (= :forged (:gate/outcome %))
                      (= :forged-trust-mark (:gate/reason %)))
                (:gate/reasons d)))))
  (testing "claimed mark bound to the wrong evaluator"
    (let [d (gate/evaluate (policy)
                           (obs :runs [(run :trust-marks [:trust/remote-ci]
                                            :publication {:publication/evaluator "synth-impostor"
                                                          :publication/run-id "run-1"})
                                       (lint-run)])
                           evaluator (capability))]
      (is (= :deny (:gate/decision d)))))
  (testing "claimed mark with a valid evaluator-bound publication passes"
    (let [d (gate/evaluate (policy)
                           (obs :runs [(run :trust-marks [:trust/remote-ci]
                                            :publication {:publication/evaluator evaluator
                                                          :publication/run-id "run-1"})
                                       (lint-run)])
                           evaluator (capability))]
      (is (= :allow (:gate/decision d))))))

;; ------------------------------------------------------------------
;; Malformed and unknown inputs can never admit actions

(deftest missing-policy-approval-is-invalid
  (let [d (gate/evaluate (policy :approval nil)
                         (obs :runs [(run) (lint-run)])
                         evaluator (capability))]
    (is (= :invalid (:gate/decision d)))
    (is (= :missing-policy-approval (:gate/invalid-reason d)))
    (is (not= :allow (:gate/decision d)))))

(deftest malformed-inputs-are-invalid-never-allow
  (let [good-policy (policy)
        good-obs (obs :runs [(run) (lint-run)])
        good-cap (capability)
        cases {"nil policy" [(constantly nil) (constantly good-obs)
                             (constantly evaluator) (constantly good-cap)]
               "policy with bad digest" [(fn [] (assoc good-policy :policy/digest "nope"))
                                          (constantly good-obs)
                                          (constantly evaluator) (constantly good-cap)]
               "policy with malformed gate" [(fn [] (assoc good-policy :policy/gates
                                                           [{:gate/id "ci-by-string"}]))
                                              (constantly good-obs)
                                              (constantly evaluator) (constantly good-cap)]
               "nil observation" [(constantly good-policy) (constantly nil)
                                   (constantly evaluator) (constantly good-cap)]
               "observation with bad head SHA" [(constantly good-policy)
                                                (fn [] (assoc good-obs :observation/head "zzz"))
                                                (constantly evaluator) (constantly good-cap)]
               "blank evaluator id" [(constantly good-policy) (constantly good-obs)
                                      (constantly "") (constantly good-cap)]
               "nil capability" [(constantly good-policy) (constantly good-obs)
                                 (constantly evaluator) (constantly nil)]
               "capability with unknown mode" [(constantly good-policy) (constantly good-obs)
                                               (constantly evaluator)
                                               (fn [] (assoc good-cap :capability/mode :turbo))]}]
    (doseq [[label [pf of ef cf]] cases]
      (testing label
        (let [d (gate/evaluate (pf) (of) (ef) (cf))]
          (is (= :invalid (:gate/decision d)) label)
          (is (some? (:gate/invalid-reason d)) label))))))

(deftest quarantine-candidate-strings-never-drive-decisions
  (testing "hostile display titles cannot satisfy, rename, or relax gates"
    (let [titles ["synth-ci / build"
                  ":ci"
                  "policy:satisfied"
                  (apply str (repeat 500 "x"))]
          runs (mapv #(run :id 4242 :title %) titles)]
      (doseq [title titles]
        (let [d (gate/evaluate (policy)
                               (obs :runs [(run :id 4242 :title title) (lint-run)])
                               evaluator (capability))]
          (is (not= :allow (:gate/decision d))
              (str "title must not admit: " (subs title 0 (min 20 (count title)))))))))
  (testing "candidate identity is echoed as data, never used as a key"
    (let [d (gate/evaluate (policy) (obs :runs [(run) (lint-run)]) evaluator (capability))]
      (is (= "synth-org/synth-repo" (get-in d [:gate/candidate :candidate/repo]))))))

;; ------------------------------------------------------------------
;; Unresolved-policy decisions (wiring for axiom.policy)

(deftest decision-for-unresolved-defers-every-gate
  (let [resolution {:policy/resolution :deferred
                    :policy/reason :no-approved-policy
                    :policy/id "synth-enforce-v1"
                    :policy/digest policy-digest
                    :policy/content {:policy/gates [{:gate/id :ci
                                                     :gate/check-run-id 9001
                                                     :gate/workflow-identity ".github/workflows/synth-ci.yml"
                                                     :gate/required true}
                                                    {:gate/id :lint
                                                     :gate/check-run-id 9002
                                                     :gate/workflow-identity ".github/workflows/synth-lint.yml"
                                                     :gate/required true}]}}
        candidate {:candidate/repo "synth-org/synth-repo" :candidate/pr 7
                   :candidate/base sha-base :candidate/head sha-head
                   :candidate/tree sha-tree}
        d (gate/decision-for-unresolved resolution candidate evaluator)]
    (is (= :defer (:gate/decision d)))
    (is (= 2 (count (:gate/reasons d))))
    (is (every? #(= :no-approved-policy (:gate/reason %)) (:gate/reasons d)))
    (is (every? #(= :defer (:gate/outcome %)) (:gate/reasons d)))
    (is (= policy-digest (get-in d [:gate/policy :policy/digest])))))

(deftest decision-for-invalid-resolution-is-invalid
  (let [candidate {:candidate/repo "synth-org/synth-repo" :candidate/pr 7
                   :candidate/base sha-base :candidate/head sha-head
                   :candidate/tree sha-tree}
        d (gate/decision-for-unresolved
           {:policy/resolution :invalid :policy/reason :policy-source-is-candidate-ref}
           candidate evaluator)]
    (is (= :invalid (:gate/decision d)))
    (is (= :policy-source-is-candidate-ref (:gate/invalid-reason d)))))
