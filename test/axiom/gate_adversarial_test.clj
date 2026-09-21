;; T7 adversarial corpus interpreter.
;;
;; Runs every case of `examples/synthetic-gate/adversarial-corpus.edn`
;; through the production namespaces and asserts the independently
;; specified expected outcome. The interpreter resolves the corpus
;; "SHA:*" / "DIGEST:*" symbols mechanically, builds the structural
;; envelopes around corpus inputs (policy-approval events, ledger
;; envelopes, checks adapter + fake), and asserts expectations --
;; it never shapes expectations itself.
;;
;; No network, no credentials: the :checks layer only talks to the
;; in-memory fake checks API, and the :cli layer only runs with the
;; offline fixture hook that production ships for this purpose.

(ns axiom.gate-adversarial-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [axiom.adapters.checks :as checks-adapter]
            [axiom.capability :as capability]
            [axiom.cli :as cli]
            [axiom.contract :as contract]
            [axiom.gate :as gate]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.policy :as policy]))

(def ^:private corpus
  (contract/read-data
   (slurp "examples/synthetic-gate/adversarial-corpus.edn")))

(def ^:private symbols
  {"SHA:base" (apply str (repeat 40 "b"))
   "SHA:head" (apply str (repeat 40 "c"))
   "SHA:tree" (apply str (repeat 40 "d"))
   "SHA:stale" (apply str (repeat 40 "e"))
   "SHA:other" (apply str (repeat 40 "f"))
   "DIGEST:a" (str "sha256:" (apply str (repeat 64 "a")))
   "DIGEST:b" (str "sha256:" (apply str (repeat 64 "b")))})

(defn- resolve-symbols
  [x]
  (walk/postwalk (fn [y] (if (string? y) (get symbols y y) y)) x))

(defn- deep-merge
  [a b]
  (cond (and (map? a) (map? b)) (merge-with deep-merge a b)
        :else b))

(defn- layered
  "Full replacement when the case gives one, else base template + patch."
  [template patch full]
  (resolve-symbols (if (some? full) full (deep-merge template (or patch {})))))

(defn- base-capability
  []
  (resolve-symbols (:corpus/capability corpus)))

(defn- base-decision
  []
  (resolve-symbols (:corpus/decision corpus)))

(defn- base-candidate
  []
  (resolve-symbols (:corpus/candidate corpus)))

;; -----------------------------------------------------------------
;; :gate layer

(defn- run-gate-case [c]
  (let [expected (:case/expected c)
        policy (layered (:corpus/policy corpus)
                        (:case/policy-patch c)
                        (:case/policy c))
        observation (layered (:corpus/observation corpus)
                             (:case/observation-patch c)
                             (:case/observation c))
        capability (layered (:corpus/capability corpus)
                            (:case/capability-patch c)
                            (:case/capability c))
        decision (gate/evaluate policy observation
                                (:case/evaluator c) capability)]
    (is (= (:gate/decision expected) (:gate/decision decision)))
    (when-let [reason (:gate/reason expected)]
      (is (some #(= reason (:gate/reason %)) (:gate/reasons decision))
          (str "expected reason not present: " reason)))
    (when (contains? expected :bypass/actor)
      (is (= (:bypass/actor expected)
             (get-in decision [:gate/bypass :bypass/actor]))))))

(defn- run-gate-bypass-case [c]
  (let [expected (:case/expected c)
        decision (layered (:corpus/decision corpus)
                          (:case/decision-patch c)
                          (:case/decision c))
        bypass (resolve-symbols (:case/bypass c))
        reported (gate/report-bypassed decision bypass)]
    (is (= (:gate/decision expected) (:gate/decision reported)))
    (when-let [actor (:bypass/actor expected)]
      (is (= actor (get-in reported [:gate/bypass :bypass/actor]))))))

;; -----------------------------------------------------------------
;; :policy layer

(defn- run-policy-case [c]
  (let [expected (:case/expected c)
        content (resolve-symbols (:case/policy-content c))
        descriptor (resolve-symbols (:case/descriptor c))
        candidate-ref (resolve-symbols (:case/candidate-ref c))
        digest (policy/content-digest content)
        events (case (:case/approval c)
                 :absent []
                 :revoked [(policy/policy-approve-event
                            content "synth-owner"
                            {:event-id "evt-corpus-approve-1"
                             :policy-id (:policy/id content)
                             :approved-at 1})
                           {:event/kind :governance/policy-revoked
                            :governance/digest digest
                            :governance/approver "synth-owner"
                            :event/id "evt-corpus-revoke-1"}]
                 [(policy/policy-approve-event
                   content "synth-owner"
                   {:event-id "evt-corpus-approve-1"
                    :policy-id (:policy/id content)
                    :approved-at 1})])
        resolution (policy/resolve descriptor content events candidate-ref)]
    (is (= (:policy/resolution expected) (:policy/resolution resolution)))
    (is (= (:policy/reason expected) (:policy/reason resolution)))))

;; -----------------------------------------------------------------
;; :ledger layer

(def ^:private base-inputs
  {:event/id "evt-corpus-1"
   :stream/id "corpus"
   :dedup/key "corpus-1"
   :producer "corpus"
   :observed/time 1
   :ingested/time 2})

(defn- assert-invalid [f]
  (let [thrown? (try (f) false
                     (catch clojure.lang.ExceptionInfo e
                       (is (= :invalid (:axiom/error (ex-data e))))
                       true))]
    (is thrown? "expected a model/invalid! throw")))

(defn- run-ledger-case [c]
  (let [expected (:case/expected c)]
    (case (:case/op c)
      :record-governance
      (let [event (resolve-symbols (:case/event c))]
        (if (:invalid? expected)
          (assert-invalid #(ledger/record-governance nil (assoc base-inputs :governance-event event)))
          (let [env (ledger/record-governance nil (assoc base-inputs :governance-event event))]
            (is (= event (get-in env [:payload :governance/event]))))))
      :record-decision
      (let [decision (layered (:corpus/decision corpus)
                              (:case/decision-patch c)
                              (:case/decision c))
            publication (when-not (= :absent (:case/publication c))
                          (resolve-symbols (:case/publication c)))]
        (if (:invalid? expected)
          (assert-invalid #(ledger/record-gate-decision
                            nil (assoc base-inputs :decision decision
                                       :publication publication)))
          (let [env (ledger/record-gate-decision
                     nil (assoc base-inputs :decision decision
                                :publication publication))]
            (is (= decision (get-in env [:payload :decision])))))))))

;; -----------------------------------------------------------------
;; :checks layer

(defn- enforcement-capability []
  {:capability/mode :enforcement
   :capability/trusted-evaluator "synth-evaluator"
   :capability/checks-write? true
   :capability/protection-readable? true
   :capability/protections-configurable? true
   :capability/advisory-reasons []})

(defn- run-checks-case [c]
  (let [expected (:case/expected c)
        capability (layered (:corpus/capability corpus)
                            (:case/capability-patch c)
                            (:case/capability c))
        fake (checks-adapter/fake-checks-api)
        fake-api (:checks/api fake)]
    (case (:case/op c)
      :construct
      (let [refused? (try
                       (checks-adapter/construct!
                        {:capability capability
                         :evaluator/id (or (:case/constructor-evaluator c)
                                           "synth-evaluator")
                         :checks/owner "synth-org"
                         :checks/repo "synth-repo"
                         :checks/api fake-api})
                       nil
                       (catch clojure.lang.ExceptionInfo e
                         (ex-data e)))]
        (is (some? refused?) "expected construct! to refuse")
        (is (= :operational (:axiom/error refused?)))
        (is (= (:refusal expected) (:checks/refusal refused?))))

      :publish
      (let [decision (layered (:corpus/decision corpus)
                              (:case/decision-patch c)
                              (:case/decision c))
            candidate (layered (:corpus/candidate corpus)
                               (:case/candidate-patch c)
                               (:case/candidate c))
            adapter (checks-adapter/construct!
                     {:capability (enforcement-capability)
                      :evaluator/id "synth-evaluator"
                      :checks/owner "synth-org"
                      :checks/repo "synth-repo"
                      :checks/api fake-api})
            outcome (try
                      {:result (checks-adapter/publish! adapter decision candidate)}
                      (catch clojure.lang.ExceptionInfo e
                        {:thrown (ex-data e)}))]
        (cond
          (or (:refusal expected) (:mismatch expected))
          (let [thrown (:thrown outcome)
                kind (or (:refusal expected) (:mismatch expected))
                slot (if (:refusal expected) :checks/refusal :checks/mismatch)]
            (is (some? thrown) "expected publish! to refuse")
            (is (= :operational (:axiom/error thrown)))
            (is (= kind (get thrown slot)))
            (is (= (:attempts expected) @(:checks/attempts fake))))

          (:invalid? expected)
          (do (is (some? (:thrown outcome)) "expected publish! to reject")
              (is (= :invalid (:axiom/error (:thrown outcome))))
              (is (= (:attempts expected) @(:checks/attempts fake))))

          :else
          (do (is (nil? (:thrown outcome))
                  (str "unexpected throw: " (:thrown outcome)))
              (is (true? (:published? expected)))
              (let [attempts @(:checks/attempts fake)
                    bodies (map :attempt/body attempts)
                    haystack (pr-str bodies)]
                (doseq [s (:summary-contains expected)]
                  (is (.contains ^String haystack ^String s)
                      (str "summary missing: " s)))
                (doseq [s (:summary-absent expected)]
                  (is (not (.contains ^String haystack ^String s))
                      (str "summary leaked: " s)))))))

      :publish-twice
      (let [decision (base-decision)
            candidate (base-candidate)
            adapter (checks-adapter/construct!
                     {:capability (enforcement-capability)
                      :evaluator/id "synth-evaluator"
                      :checks/owner "synth-org"
                      :checks/repo "synth-repo"
                      :checks/api fake-api})
            first (checks-adapter/publish! adapter decision candidate)
            second (checks-adapter/publish! adapter decision candidate)
            attempts @(:checks/attempts fake)]
        (is (= (:published? expected)
               [(:checks/published first) (:checks/published second)]))
        (is (= (:modes expected) (map :checks/mode [first second])))
        (is (= (:attempt-ops expected) (map :attempt/op attempts)))
        (is (= (:run-count expected) (count @(:checks/runs fake))))
        (is (= (:external-ids-equal expected)
               (= (:checks/external-id first)
                  (:checks/external-id second))))))))
;; :capability layer

(defn- run-capability-case [c]
  (let [expected (:case/expected c)]
    (case (:case/op c)
      :compute
      (let [cap (capability/compute-capability (resolve-symbols (:case/answers c)))]
        (is (= (:mode expected) (:capability/mode cap)))
        (is (= (:reasons expected) (:capability/advisory-reasons cap))))
      :advisory-report
      (let [report (capability/advisory-report (base-capability) (base-decision))]
        (is (= (:mode expected) (:report/mode report)))
        (is (= (:provider-writes expected) (:report/provider-writes report)))
        (is (= (:enforcement-claimed expected) (:report/enforcement-claimed report)))))))

;; -----------------------------------------------------------------
;; :cli layer

(defn- cli-fixture [variant]
  (let [base (resolve-symbols (:corpus/observation corpus))
        run1 (first (:observation/check-runs base))
        runs (:observation/check-runs base)
        stale-runs (mapv #(assoc % :check/head (get symbols "SHA:stale")) runs)
        obs (fn [pr rs] (resolve-symbols
                         (assoc base :observation/pr pr :observation/check-runs rs)))]
    {:fixture/evaluator-id "synth-evaluator"
     :fixture/policy-source {:source/type :pinned-path
                             :source/policy-id "synth-enforce-v1"}
     :fixture/policy-content
     {:policy/id "synth-enforce-v1"
      :policy/gates [{:gate/id :ci
                      :gate/check-run-id 9001
                      :gate/workflow-identity ".github/workflows/synth-ci.yml"
                      :gate/required true}
                     {:gate/id :lint
                      :gate/check-run-id 9002
                      :gate/workflow-identity ".github/workflows/synth-lint.yml"
                      :gate/required true}]}
     :fixture/capability (case variant
                           "enforcement" (enforcement-capability)
                           (base-capability))
     :fixture/observations {"synth-org/synth-repo"
                            {7 (obs 7 runs)
                             9 (obs 9 [run1])
                             10 (obs 10 stale-runs)}}
     :fixture/policy-approval (when-not (= "no-approval" variant)
                                {:approver "synth-owner"
                                 :event-id "evt-approve-1"
                                 :approved-at 1})}))

(defn- run-cli-case [c]
  (let [expected (:case/expected c)
        fixture (cli-fixture (:case/fixture c))
        {:keys [exit output]} (with-redefs [axiom.cli/gate-fixture-fn (fn [] fixture)]
                               (cli/run (:case/argv c)))
        printed (binding [*print-namespace-maps* false] (pr-str output))]
    (is (= (:exit expected) exit))
    (doseq [s (:output-contains expected)]
      (is (.contains printed s) (str "output missing: " s)))))

;; -----------------------------------------------------------------
;; driver

(def ^:private required-bypass-classes
  #{:policy-edit :renamed-check :omitted-verification :stale-success
    :verifier-config-replacement :forged-remote-ci :forged-producer-claim
    :duplicate-publication :prompt-override :quarantine-violation
    :capability-failure :protection-change})

(deftest adversarial-corpus
  (doseq [c (:corpus/cases corpus)]
    (testing (str (:case/id c) " [" (:case/class c) "]")
      (case (:case/layer c)
        :gate (run-gate-case c)
        :gate-bypass (run-gate-bypass-case c)
        :policy (run-policy-case c)
        :ledger (run-ledger-case c)
        :checks (run-checks-case c)
        :capability (run-capability-case c)
        :cli (run-cli-case c)
        (is false (str "unknown layer: " (:case/layer c)))))))

(deftest bypass-classes-covered
  (let [classes (set (map :case/class (:corpus/cases corpus)))]
    (doseq [k required-bypass-classes]
      (is (contains? classes k) (str "T7 bypass class not covered: " k)))))

(deftest bypass-classes-never-allow
  (doseq [c (:corpus/cases corpus)
          :when (and (= :gate (:case/layer c))
                     (contains? required-bypass-classes (:case/class c)))]
    (is (not= :allow (:gate/decision (:case/expected c)))
        (str "bypass-class case must never expect :allow: " (:case/id c)))))
