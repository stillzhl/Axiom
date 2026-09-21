(ns axiom.gate-cli-test
  "Tests for spec 0005 T6 (CLI: gate / publish-check / policy-approve).

   The CLI is driven in fixture mode (AXIOM_GATE_FIXTURES hook
   redefined to synthetic fixtures built below), so no test touches
   the network and no test mutates provider state: `publish-check`
   publishes through the in-memory fake Checks API only. Every
   repository, SHA, login and workflow is invented (`synth-*`); no
   real repository identities, no live credentials."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.cli :as cli]
            [axiom.model :as model]
            [axiom.policy :as policy]
            [axiom.store :as store]
            [clojure.string :as str])
  (:import (java.io File)))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-base (apply str (repeat 40 "b")))
(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-tree (apply str (repeat 40 "d")))
(def ^:private sha-stale (apply str (repeat 40 "e")))
(def ^:private evaluator "synth-evaluator")

(defn- run
  [& {:keys [id workflow conclusion base head]
      :or {id 9001 workflow ".github/workflows/synth-ci.yml"
           conclusion :success base sha-base head sha-head}}]
  {:check/id id
   :check/workflow-identity workflow
   :check/title "synth check"
   :check/conclusion conclusion
   :check/base base
   :check/head head
   :check/trust :trust/provider-authenticated})

(defn- obs
  [& {:keys [pr runs changed-files]
      :or {pr 7 changed-files ["src/synth.clj"]}}]
  {:observation/repo "synth-org/synth-repo"
   :observation/pr pr
   :observation/base sha-base
   :observation/head sha-head
   :observation/tree sha-tree
   :observation/changed-files changed-files
   :observation/check-runs (vec runs)
   :observation/current? true
   :observation/synthetic-fixture? true
   :observation/trust :trust/provider-authenticated})

(defn- policy-content []
  {:policy/id "synth-enforce-v1"
   :policy/reserved-paths ["policies/"]
   :policy/gates [{:gate/id :ci
                   :gate/check-run-id 9001
                   :gate/workflow-identity ".github/workflows/synth-ci.yml"
                   :gate/required true}
                  {:gate/id :lint
                   :gate/check-run-id 9002
                   :gate/workflow-identity ".github/workflows/synth-lint.yml"
                   :gate/required true}]})

(defn- advisory-capability []
  {:capability/mode :advisory
   :capability/trusted-evaluator evaluator
   :capability/checks-write? false
   :capability/protection-readable? true
   :capability/protections-configurable? false
   :capability/advisory-reasons [:no-checks-write :protections-unconfigurable]})

(defn- enforcement-capability []
  {:capability/mode :enforcement
   :capability/trusted-evaluator evaluator
   :capability/checks-write? true
   :capability/protection-readable? true
   :capability/protections-configurable? true
   :capability/advisory-reasons []})

(defn- fixtures
  [& {:keys [capability approval? observations]
      :or {capability (advisory-capability) approval? true}}]
  (cond-> {:fixture/evaluator-id evaluator
           :fixture/policy-source {:source/type :pinned-path
                                   :source/policy-id "synth-enforce-v1"}
           :fixture/policy-content (policy-content)
           :fixture/capability capability
           :fixture/observations
           {"synth-org/synth-repo"
            (or observations
                {7 (obs :runs [(run) (run :id 9002
                                          :workflow ".github/workflows/synth-lint.yml")])
                 9 (obs :pr 9 :runs [(run)])
                 10 (obs :pr 10 :runs [(run :head sha-stale)
                                       (run :id 9002
                                            :workflow ".github/workflows/synth-lint.yml")])})}}
    approval? (assoc :fixture/policy-approval
                     {:approver "synth-owner"
                      :event-id "evt-synth-approve-1"
                      :approved-at 1750000000000})))

(defmacro ^:private with-fixtures
  "Runs body with the AXIOM_GATE_FIXTURES hook redefined to the given
   fixture map (nil disables the hook)."
  [fx & body]
  `(with-redefs [axiom.cli/gate-fixture-fn (fn [] ~fx)]
     ~@body))

(defn- temp-db []
  (let [file (File/createTempFile "axiom-gate-cli-test" ".db")]
    (.delete file)
    (.getPath file)))

(defn- delete-db! [path]
  (doseq [suffix ["" "-wal" "-shm" "-journal"]]
    (.delete (File. (str path suffix)))))

(defmacro ^:private with-db [[path-sym] & body]
  `(let [~path-sym (temp-db)]
     (try ~@body (finally (delete-db! ~path-sym)))))

(defn- temp-edn [value]
  (let [file (File/createTempFile "axiom-gate-cli-policy" ".edn")]
    (spit file (model/edn-str value))
    (.getPath file)))

;; ------------------------------------------------------------------
;; gate: pure evaluation, EDN decision on stdout

(deftest gate-allow-valid-report
  (with-fixtures (fixtures)
    (let [{:keys [exit output]} (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "7"])]
      (is (= 0 exit))
      (is (= :allow (:gate/decision output)))
      (is (= evaluator (:gate/evaluator output)))
      (is (= "synth-enforce-v1" (get-in output [:gate/policy :policy/id])))
      (is (= (policy/content-digest (policy-content))
             (get-in output [:gate/policy :policy/digest])))
      (is (= "evt-synth-approve-1"
             (get-in output [:gate/policy :policy/approval-event-id])))
      (is (= {:candidate/repo "synth-org/synth-repo" :candidate/pr 7
              :candidate/base sha-base :candidate/head sha-head :candidate/tree sha-tree}
             (:gate/candidate output)))
      (is (contains? (:gate/trust output) :trust/provider-authenticated))
      ;; gate never mutates provider state: the report carries no
      ;; :checks/ namespaced keys by construction (the adapter is
      ;; never even constructed on this path).
      (is (not (str/includes? (pr-str output) ":checks/"))))))

(deftest gate-defer-omitted-verification
  (with-fixtures (fixtures)
    (let [{:keys [exit output]} (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "9"])]
      (is (= 0 exit))
      (is (= :defer (:gate/decision output)))
      (is (some #(= :no-matching-check-run (:gate/reason %)) (:gate/reasons output))))))

(deftest gate-deny-stale-success
  (with-fixtures (fixtures)
    (let [{:keys [exit output]} (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "10"])]
      (is (= 0 exit))
      (is (= :deny (:gate/decision output)))
      (is (some #(= :stale-check-run (:gate/reason %)) (:gate/reasons output))))))

(deftest gate-invalid-input-exits-4
  (with-fixtures (fixtures)
    (testing "malformed repo slug"
      (is (= 4 (:exit (cli/run ["gate" "--repo" "not-a-slug" "--pr" "7"])))))
    (testing "unknown PR"
      (is (= 4 (:exit (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "99"])))))
    (testing "malformed policy digest"
      (is (= 4 (:exit (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "7"
                                "--policy" "not-a-digest"])))))
    (testing "missing --pr"
      (is (= 4 (:exit (cli/run ["gate" "--repo" "synth-org/synth-repo"])))))))

(deftest gate-no-observation-source-exits-5
  (with-fixtures nil
    (is (= 5 (:exit (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "7"]))))))

(deftest gate-unapproved-digest-defers-with-named-reason
  (with-fixtures (fixtures :approval? false)
    (let [{:keys [exit output]} (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "7"])]
      (is (= 0 exit) "an unapproved digest is a valid report, never allow")
      (is (= :defer (:gate/decision output)))
      (is (seq (:gate/reasons output)))
      (is (every? #(= :no-approved-policy (:gate/reason %)) (:gate/reasons output))))))

(deftest gate-requested-digest-pin
  (with-fixtures (fixtures)
    (let [digest (policy/content-digest (policy-content))]
      (testing "matching pin evaluates normally"
        (let [{:keys [exit output]} (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "7"
                                              "--policy" digest])]
          (is (= 0 exit))
          (is (= :allow (:gate/decision output)))))
      (testing "a pin the deployment cannot satisfy is invalid input"
        (is (= 4 (:exit (cli/run ["gate" "--repo" "synth-org/synth-repo" "--pr" "7"
                                          "--policy" (str "sha256:" (apply str (repeat 64 "b")))]))))))))

;; ------------------------------------------------------------------
;; publish-check: capability check, then publication or advisory report

(deftest publish-check-advisory-zero-writes
  (with-fixtures (fixtures)
    (let [{:keys [exit output]} (cli/run ["publish-check" "--repo" "synth-org/synth-repo" "--pr" "7"])]
      (is (= 0 exit))
      (is (= :advisory (:report/mode output)))
      (is (= "publish-check" (:report/command output)))
      (is (= 0 (:report/provider-writes output)))
      (is (false? (:report/enforcement-claimed output)))
      (is (= [:no-checks-write :protections-unconfigurable]
             (:report/advisory-reasons output)))
      (is (= :allow (get-in output [:report/evaluation :gate/decision])))
      ;; Zero provider writes: no checks-API attempt is recorded.
      (is (not (str/includes? (pr-str output) ":checks/create-run")))
      ;; Without a ledger the report says so honestly.
      (is (false? (get-in output [:report/ledger :recorded?]))))))

(deftest publish-check-advisory-records-without-publication
  (with-db [db]
    (with-fixtures (fixtures)
      (let [{:keys [exit output]} (cli/run ["publish-check" "--repo" "synth-org/synth-repo"
                                           "--pr" "7" "--ledger" db])]
        (is (= 0 exit))
        (is (= :advisory (:report/mode output)))
        (is (true? (get-in output [:report/ledger :recorded?])))
        (is (false? (get-in output [:report/ledger :publication?])))
        (let [handle (store/open! db {:create false :migrate false})]
          (try
            (let [envelopes (store/read-range handle 0 0)
                  payload (:payload (first envelopes))]
              (is (= :decision/gate-evaluation (:record/kind payload)))
              (is (= :allow (get-in payload [:decision :gate/decision])))
              ;; Advisory recording carries no publication reference:
              ;; there was no provider publication.
              (is (nil? (:decision/publication payload))))
            (finally (store/close! handle))))))))

(deftest publish-check-advisory-ledger-env-fallback
  (with-db [db]
    (with-fixtures (fixtures)
      (with-redefs [axiom.cli/gate-ledger-env (fn [] db)]
        (let [{:keys [exit output]} (cli/run ["publish-check" "--repo" "synth-org/synth-repo"
                                             "--pr" "7"])]
          (is (= 0 exit))
          (is (= :advisory (:report/mode output)))
          (is (true? (get-in output [:report/ledger :recorded?])))
          (let [handle (store/open! db {:create false :migrate false})]
            (try
              (let [envelopes (store/read-range handle 0 0)]
                (is (= 1 (count envelopes)))
                (is (= :allow (get-in (first envelopes) [:payload :decision :gate/decision]))))
              (finally (store/close! handle)))))))))

(deftest publish-check-enforcement-publishes-through-fake
  (with-db [db]
    (with-fixtures (fixtures :capability (enforcement-capability))
      (let [{:keys [exit output]} (cli/run ["publish-check" "--repo" "synth-org/synth-repo"
                                            "--pr" "7" "--ledger" db])]
        (is (= 0 exit))
        (is (= :fake-checks-api (:publish/api output)))
        (is (true? (get-in output [:publish/result :checks/published])))
        (is (= :created (get-in output [:publish/result :checks/mode])))
        (is (re-matches #"sha256:[0-9a-f]{64}"
                        (get-in output [:publish/result :checks/decision-digest]))
            "the full decision digest travels with the published run")
        (is (str/starts-with? (get-in output [:publish/result :checks/external-id]) "axiom-gate/"))
        (is (= [:checks/create-run] (mapv :attempt/op (:publish/attempts output))))
        ;; The publication is recorded through the 0002 append path.
        (is (true? (get-in output [:publish/ledger :recorded?])))
        (let [handle (store/open! db {:create false :migrate false})]
          (try
            (let [envelopes (store/read-range handle 0 0)
                  payload (:payload (first envelopes))]
              (is (= :decision/gate-evaluation (:record/kind payload)))
              (is (= :allow (get-in payload [:decision :gate/decision])))
              (is (= evaluator (get-in payload [:decision/publication :publication/evaluator]))))
            (finally (store/close! handle))))))))

(deftest publish-check-invalid-and-operational-exits
  (with-fixtures (fixtures)
    (testing "unknown PR is invalid input"
      (is (= 4 (:exit (cli/run ["publish-check" "--repo" "synth-org/synth-repo" "--pr" "99"]))))))
  (with-fixtures (fixtures :approval? false)
    (testing "missing policy approval is operational: nothing un-pinned is published"
      (is (= 5 (:exit (cli/run ["publish-check" "--repo" "synth-org/synth-repo" "--pr" "7"]))))))
  (with-fixtures (fixtures :capability (assoc (enforcement-capability)
                                              :capability/trusted-evaluator "synth-other"))
    (testing "capability check failure (evaluator mismatch) is operational"
      (is (= 5 (:exit (cli/run ["publish-check" "--repo" "synth-org/synth-repo" "--pr" "7"])))))))

;; ------------------------------------------------------------------
;; policy-approve: governance approval events in the ledger

(deftest policy-approve-records-approval
  (with-db [db]
    (let [path (temp-edn (policy-content))]
      (try
        (let [{:keys [exit output]} (cli/run ["policy-approve" "--policy" path
                                              "--approver" "synth-owner"
                                              "--ledger" db])]
          (is (= 0 exit))
          (is (true? (:approved? output)))
          (let [event (:governance/event output)]
            (is (= :governance/policy-approved (:event/kind event)))
            (is (= "synth-owner" (:governance/approver event)))
            (is (= "synth-enforce-v1" (:governance/policy-id event)))
            (is (= (policy/content-digest (policy-content)) (:governance/digest event)))
            (is (nil? (:governance/supersedes event))))
          ;; The event is really in the ledger.
          (let [handle (store/open! db {:create false :migrate false})]
            (try
              (let [payload (:payload (first (store/read-range handle 0 0)))]
                (is (= :governance (:record/kind payload)))
                (is (= :governance/policy-approved
                       (get-in payload [:governance/event :event/kind]))))
              (finally (store/close! handle)))))
        (finally (.delete (File. ^String path)))))))

(deftest policy-approve-supersedes-prior-approval
  (with-db [db]
    (let [v1 (temp-edn (policy-content))
          v2 (temp-edn (assoc (policy-content) :policy/note "synth revision 2"))]
      (try
        (let [r1 (cli/run ["policy-approve" "--policy" v1 "--approver" "synth-owner" "--ledger" db])
              r2 (cli/run ["policy-approve" "--policy" v2 "--approver" "synth-owner" "--ledger" db])]
          (is (= 0 (:exit r1)))
          (is (= 0 (:exit r2)))
          (is (= (get-in r1 [:output :governance/event :governance/digest])
                 (get-in r2 [:output :governance/event :governance/supersedes]))
              "the v2 approval supersedes the v1 digest"))
        (finally
          (.delete (File. ^String v1))
          (.delete (File. ^String v2)))))))

(deftest policy-approve-exits
  (with-db [db]
    (let [path (temp-edn (policy-content))]
      (try
        (testing "duplicate approval of the same digest is operational"
          (is (= 0 (:exit (cli/run ["policy-approve" "--policy" path "--approver" "synth-owner"
                                            "--ledger" db]))))
          (is (= 5 (:exit (cli/run ["policy-approve" "--policy" path "--approver" "synth-owner"
                                            "--ledger" db])))))
        (testing "unreadable policy file is invalid input"
          (is (= 4 (:exit (cli/run ["policy-approve" "--policy" "/tmp/axiom-no-such-policy.edn"
                                            "--approver" "synth-owner" "--ledger" db])))))
        (testing "missing approver is invalid input"
          (is (= 4 (:exit (cli/run ["policy-approve" "--policy" path "--ledger" db])))))
        (testing "no ledger configured is operational"
          (with-redefs [axiom.cli/gate-ledger-env (fn [] nil)]
            (is (= 5 (:exit (cli/run ["policy-approve" "--policy" path
                                               "--approver" "synth-owner"]))))))
        (finally (.delete (File. ^String path)))))))
