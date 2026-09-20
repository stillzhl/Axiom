(ns axiom.checks-test
  "Tests for spec 0005 T4 (`axiom.adapters.checks`).

   All provider interaction goes through `fake-checks-api`: an
   in-memory, inspectable fake that records every attempted write.
   Zero network — no test opens a socket, and the real
   `github-checks-api` is never constructed here. All repositories,
   SHAs, logins and digests are invented (`synth-*`); no real
   identities, no live credentials."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [axiom.adapters.checks :as checks]
            [axiom.capability :as cap]))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-base (apply str (repeat 40 "b")))
(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-tree (apply str (repeat 40 "d")))
(def ^:private sha-other (apply str (repeat 40 "e")))
(def ^:private policy-digest (str "sha256:" (apply str (repeat 64 "a"))))
(def ^:private policy-digest-2 (str "sha256:" (apply str (repeat 64 "f"))))
(def ^:private evaluator-id "synth-evaluator")

(defn- candidate
  [& {:keys [repo pr base head tree]
      :or {repo "synth-org/synth-repo" pr 7
           base sha-base head sha-head tree sha-tree}}]
  {:candidate/repo repo :candidate/pr pr
   :candidate/base base :candidate/head head :candidate/tree tree})

(defn- decision
  [& {:keys [d evaluator candidate* policy reasons]
      :or {d :allow evaluator evaluator-id}}]
  {:gate/decision d
   :gate/reasons (or reasons
                     [{:gate/id :ci :gate/outcome :satisfied :gate/reason :check-satisfied}
                      {:gate/id :lint :gate/outcome :satisfied :gate/reason :check-satisfied}])
   :gate/candidate (or candidate* (candidate))
   :gate/evaluator evaluator
   :gate/policy (or policy {:policy/id "synth-enforce-v1"
                            :policy/digest policy-digest
                            :policy/approval-event-id "evt-approve-1"})
   :gate/trust #{:trust/provider-authenticated}})

(defn- enforcing-capability
  []
  (cap/compute-capability {:capability/trusted-evaluator evaluator-id
                           :capability/checks-write? true
                           :capability/protection-readable? true
                           :capability/protections-configurable? true}))

(defn- advisory-capability
  []
  (cap/compute-capability {:capability/trusted-evaluator evaluator-id
                           :capability/checks-write? false
                           :capability/protection-readable? true
                           :capability/protections-configurable? true}))

(defn- setup
  "Builds {:adapter ... :fake ...} against the in-memory fake."
  [capability]
  (let [fake (checks/fake-checks-api)]
    {:fake fake
     :adapter (checks/construct! {:capability capability
                                  :evaluator/id evaluator-id
                                  :checks/owner "synth-org"
                                  :checks/repo "synth-repo"
                                  :checks/api (:checks/api fake)})}))

(defn- operational?
  [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e
         (= :operational (:axiom/error (ex-data e))))))

(defn- invalid?
  [f]
  (try (f) false
       (catch clojure.lang.ExceptionInfo e
         (= :invalid (:axiom/error (ex-data e))))))

;; ------------------------------------------------------------------
;; Constructor refusal (pure; no network)

(deftest constructable-predicate
  (testing "a passing R8 record with the matching evaluator is constructable"
    (is (true? (checks/constructable? (enforcing-capability) evaluator-id))))
  (testing "advisory mode is not constructable"
    (is (false? (checks/constructable? (advisory-capability) evaluator-id))))
  (testing "a record claiming enforcement without the three true answers is not constructable"
    (is (false? (checks/constructable? {:capability/mode :enforcement
                                        :capability/trusted-evaluator evaluator-id
                                        :capability/checks-write? true
                                        :capability/protection-readable? true
                                        :capability/protections-configurable? false}
                                       evaluator-id))))
  (testing "evaluator mismatch is not constructable"
    (is (false? (checks/constructable? (enforcing-capability) "synth-impostor"))))
  (testing "missing capability is not constructable"
    (is (false? (checks/constructable? nil evaluator-id))))
  (testing "malformed capability is not constructable"
    (is (false? (checks/constructable? {:capability/mode :bogus} evaluator-id)))))

(deftest construct-refuses-without-passing-capability
  (testing "advisory capability: operational refusal, no network touched"
    (let [fake (checks/fake-checks-api)]
      (is (operational? #(checks/construct! {:capability (advisory-capability)
                                             :evaluator/id evaluator-id
                                             :checks/owner "synth-org"
                                             :checks/repo "synth-repo"
                                             :checks/api (:checks/api fake)})))
      (is (empty? @(:checks/attempts fake)))))
  (testing "missing capability"
    (is (operational? #(checks/construct! {:capability nil
                                           :evaluator/id evaluator-id
                                           :checks/owner "synth-org"
                                           :checks/repo "synth-repo"
                                           :checks/api (:checks/api (checks/fake-checks-api))}))))
  (testing "evaluator mismatch"
    (is (operational? #(checks/construct! {:capability (enforcing-capability)
                                           :evaluator/id "synth-impostor"
                                           :checks/owner "synth-org"
                                           :checks/repo "synth-repo"
                                           :checks/api (:checks/api (checks/fake-checks-api))})))))

(deftest construct-accepts-passing-capability
  (let [{:keys [adapter]} (setup (enforcing-capability))]
    (is (true? (:checks/adapter adapter)))
    (is (= evaluator-id (:evaluator/id adapter)))
    (is (= "synth-org" (:checks/owner adapter)))
    (is (= "synth-repo" (:checks/repo adapter)))))

;; ------------------------------------------------------------------
;; Publication binding and idempotency (fake; zero network)

(deftest publication-binds-identities
  (let [{:keys [adapter fake]} (setup (enforcing-capability))
        d (decision)
        report (checks/publish! adapter d (candidate))]
    (testing "the publish report names the external id, run and conclusion"
      (is (true? (:checks/published report)))
      (is (= :created (:checks/mode report)))
      (is (= :success (:checks/conclusion report)))
      (is (some? (:checks/run-id report)))
      (is (string? (:checks/external-id report))))
    (testing "exactly one write attempt was recorded, a create"
      (is (= 1 (count @(:checks/attempts fake))))
      (is (= :checks/create-run (:attempt/op (first @(:checks/attempts fake))))))
    (testing "the stored run is bound to the exact candidate identity"
      (let [run (get @(:checks/runs fake) (:checks/external-id report))]
        (is (some? run))
        (is (= sha-head (:check/head-sha run)))
        (is (= "synth-org" (:check/owner run)))
        (is (= "synth-repo" (:check/repo run)))))
    (testing "the run output summary names evaluator, policy digest and candidate"
      (let [run (get @(:checks/runs fake) (:checks/external-id report))]
        (is (str/includes? (:check/summary run) evaluator-id))
        (is (str/includes? (:check/summary run) policy-digest))
        (is (str/includes? (:check/summary run) "synth-org/synth-repo"))
        (is (str/includes? (:check/summary run) sha-head))))
    (testing "decision digest travels in the run body for audit"
      (let [run (get @(:checks/runs fake) (:checks/external-id report))
            attempt (first @(:checks/attempts fake))]
        (is (= (:checks/decision-digest (:attempt/body attempt)) (:check/decision-digest run)))
        (is (= (:checks/decision-digest (:attempt/body attempt))
               (:checks/decision-digest report)))))))

(deftest republish-is-idempotent
  (let [{:keys [adapter fake]} (setup (enforcing-capability))
        d (decision)
        first-report (checks/publish! adapter d (candidate))
        second-report (checks/publish! adapter d (candidate))]
    (testing "both publishes address the same external id"
      (is (= (:checks/external-id first-report) (:checks/external-id second-report))))
    (testing "the second publish updates; no duplicate logical run"
      (is (= :updated (:checks/mode second-report)))
      (is (= (:checks/run-id first-report) (:checks/run-id second-report)))
      (is (= 1 (count @(:checks/runs fake)))))
    (testing "the fake recorded both attempted writes: create then update"
      (is (= [:checks/create-run :checks/update-run]
             (mapv :attempt/op @(:checks/attempts fake)))))
    (testing "a different policy digest is a different logical run"
      (let [d2 (decision :policy {:policy/id "synth-enforce-v1"
                                  :policy/digest policy-digest-2
                                  :policy/approval-event-id "evt-approve-2"})
            third (checks/publish! adapter d2 (candidate))]
        (is (not= (:checks/external-id first-report) (:checks/external-id third)))
        (is (= 2 (count @(:checks/runs fake))))))))

(deftest external-id-encodes-the-idempotency-triple
  (let [c (candidate)
        gs [:ci :lint]]
    (testing "deterministic"
      (is (= (checks/external-id-for c gs policy-digest)
             (checks/external-id-for c gs policy-digest))))
    (testing "gate-set order does not matter"
      (is (= (checks/external-id-for c gs policy-digest)
             (checks/external-id-for c [:lint :ci] policy-digest))))
    (testing "candidate, gate set and policy digest each move the id"
      (is (not= (checks/external-id-for c gs policy-digest)
                (checks/external-id-for (candidate :head sha-other) gs policy-digest)))
      (is (not= (checks/external-id-for c gs policy-digest)
                (checks/external-id-for c [:ci] policy-digest)))
      (is (not= (checks/external-id-for c gs policy-digest)
                (checks/external-id-for c gs policy-digest-2))))))

;; ------------------------------------------------------------------
;; Identity mismatches are operational failures, never silent

(deftest candidate-identity-mismatch-is-operational
  (let [{:keys [adapter fake]} (setup (enforcing-capability))
        d (decision)]
    (testing "a run bound to a different head SHA than the evaluation input"
      (is (operational? #(checks/publish! adapter d (candidate :head sha-other)))))
    (testing "a run bound to a different repo than the evaluation input"
      (is (operational? #(checks/publish! adapter d (candidate :repo "synth-org/other-repo")))))
    (testing "no write was attempted after the mismatch"
      (is (empty? @(:checks/attempts fake))))
    (testing "a candidate from outside the configured target repository"
      (is (operational? #(checks/publish! adapter (decision :candidate* (candidate :repo "evil-org/evil-repo"))
                                            (candidate :repo "evil-org/evil-repo")))))))

(deftest evaluator-identity-mismatch-is-operational
  (let [{:keys [adapter fake]} (setup (enforcing-capability))]
    (testing "a decision produced by another identity is not a valid enforcement record"
      (is (operational? #(checks/publish! adapter (decision :evaluator "synth-impostor")
                                            (candidate)))))
    (testing "no write was attempted"
      (is (empty? @(:checks/attempts fake))))))

(deftest invalid-decision-cannot-publish
  (let [{:keys [adapter fake]} (setup (enforcing-capability))]
    (testing "an :invalid decision is refused as invalid input"
      (is (invalid? #(checks/publish! adapter (decision :d :invalid) (candidate)))))
    (testing "a malformed decision is refused"
      (is (invalid? #(checks/publish! adapter {:gate/decision :allow} (candidate)))))
    (testing "no write was attempted"
      (is (empty? @(:checks/attempts fake))))))

;; ------------------------------------------------------------------
;; Decision -> conclusion mapping

(deftest conclusion-mapping
  (let [{:keys [adapter fake]} (setup (enforcing-capability))]
    (is (= :failure (:checks/conclusion (checks/publish! adapter (decision :d :deny) (candidate)))))
    (is (= :neutral (:checks/conclusion (checks/publish! adapter (decision :d :defer) (candidate)))))))

;; ------------------------------------------------------------------
;; Advisory fallback: evaluation produced, zero provider writes

(deftest advisory-fallback-performs-zero-writes
  (let [fake (checks/fake-checks-api)
        capability (advisory-capability)
        d (decision)]
    (testing "the adapter refuses to construct in advisory mode"
      (is (operational? #(checks/construct! {:capability capability
                                             :evaluator/id evaluator-id
                                             :checks/owner "synth-org"
                                             :checks/repo "synth-repo"
                                             :checks/api (:checks/api fake)}))))
    (testing "the advisory report carries the evaluation and claims no enforcement"
      (let [report (cap/advisory-report capability d)]
        (is (= :advisory (:report/mode report)))
        (is (= d (:report/evaluation report)))
        (is (= 0 (:report/provider-writes report)))
        (is (false? (:report/enforcement-claimed report)))
        (is (= [:no-checks-write] (:report/advisory-reasons report)))))
    (testing "the fake saw no attempted writes"
      (is (empty? @(:checks/attempts fake))))))

;; ------------------------------------------------------------------
;; Structural guarantee: the only mutation ops are check-run publication

(deftest only-check-run-publication-mutates
  (testing "the fake exposes exactly the publication op set — no merge/label/branch ops"
    (let [fake (checks/fake-checks-api)]
      (is (= #{:checks/list-runs :checks/create-run! :checks/update-run!}
             (set (keys (:checks/api fake)))))
      (is (every? #{:checks/create-run :checks/update-run}
                   (map :attempt/op @(:checks/attempts fake)))))))
