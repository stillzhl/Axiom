(ns axiom.gate-ledger-test
  "Ledger integration tests for spec 0005 T3: `:decision/gate-evaluation`
   and `:governance/*` payload kinds through the 0002 append path.

   Every repository, SHA, login, workflow and digest is invented:
   `synth-org/synth-repo`, synthetic 40-hex SHAs, `synth-*`
   identities. No real repository identities, no live credentials,
   no network."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.fixtures :as f]
            [axiom.gate :as gate]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.policy :as policy]
            [axiom.store :as store])
  (:import (java.io File)
           (java.sql DriverManager)))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-base (apply str (repeat 40 "b")))
(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-tree (apply str (repeat 40 "d")))
(def ^:private policy-digest (str "sha256:" (apply str (repeat 64 "a"))))
(def ^:private evaluator "synth-evaluator")
(def ^:private owner "synth-owner")

(defn- policy-fixture []
  {:policy/id "synth-enforce-v1"
   :policy/digest policy-digest
   :policy/approval-event-id "evt-approve-1"
   :policy/reserved-paths []
   :policy/gates [{:gate/id :ci
                   :gate/check-run-id 9001
                   :gate/workflow-identity ".github/workflows/synth-ci.yml"
                   :gate/required true}
                  {:gate/id :lint
                   :gate/check-run-id 9002
                   :gate/workflow-identity ".github/workflows/synth-lint.yml"
                   :gate/required true}]})

(defn- run-fixture
  [& {:keys [id workflow conclusion]
      :or {id 9001 workflow ".github/workflows/synth-ci.yml" conclusion :success}}]
  {:check/id id
   :check/workflow-identity workflow
   :check/title "synth-ci / build"
   :check/conclusion conclusion
   :check/base sha-base
   :check/head sha-head
   :check/trust :trust/provider-observed})

(defn- obs-fixture
  [& {:keys [runs trust] :or {trust :trust/provider-authenticated}}]
  (cond-> {:observation/repo "synth-org/synth-repo"
           :observation/pr 7
           :observation/base sha-base
           :observation/head sha-head
           :observation/tree sha-tree
           :observation/current? true
           :observation/synthetic-fixture? false
           :observation/trust trust}
    (some? runs) (assoc :observation/check-runs runs)))

(defn- capability-fixture
  [& {:keys [mode] :or {mode :enforcement}}]
  {:capability/mode mode :capability/trusted-evaluator evaluator})

(defn- allow-decision []
  (gate/evaluate (policy-fixture)
                 (obs-fixture :runs [(run-fixture)
                                     (run-fixture :id 9002
                                                  :workflow ".github/workflows/synth-lint.yml")])
                 evaluator
                 (capability-fixture)))

(defn- deny-decision []
  ;; Advisory mode: no :trust/remote-ci is issued, so no publication
  ;; reference is required.
  (gate/evaluate (policy-fixture)
                 (obs-fixture :runs [(run-fixture :conclusion :failure)
                                     (run-fixture :id 9002
                                                  :workflow ".github/workflows/synth-lint.yml")])
                 evaluator
                 (capability-fixture :mode :advisory)))

(defn- approval-event-fixture
  [& {:keys [event-id] :or {event-id "evt-approve-1"}}]
  (policy/policy-approve-event {:policy/id "synth-enforce-v1"
                                :policy/gates [{:gate/id :ci}]}
                               owner
                               {:event-id event-id
                                :policy-id "synth-enforce-v1"
                                :approved-at 100}))

(defn- base-inputs [n]
  {:event/id (str "evt-" n) :stream/id "synthetic-0005" :dedup/key (str "submit-" n)
   :producer "synthetic-check" :observed/time (* n 100) :ingested/time (+ (* n 100) 5)})

;; ------------------------------------------------------------------
;; Store helpers

(defn- temp-db []
  (let [file (File/createTempFile "axiom-gate-ledger-test" ".db")]
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

(defn- error-reason [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- append-governance!
  "Appends a governance event through the store, tracking the head."
  [handle prev event n]
  (store/append! handle
                 (ledger/record-governance prev (assoc (base-inputs n)
                                                      :governance-event event))))

(defn- append-decision!
  "Appends a gate decision through the store, tracking the head."
  [handle prev decision publication n]
  (store/append! handle
                 (ledger/record-gate-decision prev (assoc (base-inputs n)
                                                         :decision decision
                                                         :publication publication))))

;; ------------------------------------------------------------------
;; T3 acceptance: round-trip and verbatim replay

(deftest gate-decision-round-trips-verbatim
  (with-db [path]
    (let [handle (store/open! path {:create true})
          decision (allow-decision)
          publication {:publication/evaluator evaluator
                       :publication/run-id "synth-run-1"
                       :publication/published-at 200}
          _ (is (contains? (:gate/trust decision) :trust/remote-ci)
                "the allow decision carries :trust/remote-ci, so a publication is required")
          g1 (append-governance! handle nil (approval-event-fixture) 1)
          stored (append-decision! handle g1 decision publication 2)
          reread (store/read-range handle 0 1)]
      (try
        (is (= 2 (count reread)))
        (is (= :decision/gate-evaluation (get-in stored [:payload :record/kind])))
        (is (= decision (get-in stored [:payload :decision]))
            "the recorded decision is byte-identical to the gate output")
        (is (= decision (get-in (second reread) [:payload :decision]))
            "read-back reproduces the recorded decision verbatim")
        (is (= policy-digest (get-in stored [:payload :decision :gate/policy :policy/digest])))
        (is (= evaluator (get-in stored [:payload :decision :gate/evaluator])))
        (is (= publication (get-in stored [:payload :decision/publication])))
        (finally (store/close! handle))))))

;; ------------------------------------------------------------------
;; T3 acceptance: forged :trust/remote-ci is :invalid at validation

(deftest forged-remote-ci-rejected-at-validation
  (let [decision (allow-decision)
        _ (is (contains? (:gate/trust decision) :trust/remote-ci))
        inputs (assoc (base-inputs 1) :decision decision)]
    (testing "no publication reference"
      (is (= :invalid (error-kind #(ledger/record-gate-decision nil inputs)))
          "a remote-ci claim without a publication reference is :invalid"))
    (testing "publication bound to the wrong evaluator"
      (is (= :invalid (error-kind #(ledger/record-gate-decision nil
                                    (assoc inputs :publication
                                           {:publication/evaluator "synth-impostor"
                                            :publication/run-id "synth-run-9"})))))
          "a remote-ci claim with a mismatched evaluator is :invalid")
    (testing "promoted from :trust/local-diagnostic"
      ;; A hand-crafted decision whose evidence was only
      ;; local-diagnostic but which claims :trust/remote-ci, with no
      ;; publication: a promotion forgery.
      (let [forged (assoc decision :gate/trust #{:trust/local-diagnostic :trust/remote-ci})]
        (is (= :invalid (error-kind #(ledger/record-gate-decision
                                      nil (assoc inputs :decision forged)))))))
    (testing "the append boundary rejects the forgery too"
      (with-db [path]
        (let [handle (store/open! path {:create true})]
          (try
            (is (= :invalid
                   (error-kind #(store/append! handle
                                 (ledger/record-gate-decision nil inputs))))
                "store/append! never persists a forged trust mark")
            (is (= 0 (:event/count (store/ledger-identity handle)))
                "the rejected envelope left no trace")
            (finally (store/close! handle))))))))

;; ------------------------------------------------------------------
;; T3 acceptance: missing/mismatched role identities are :invalid

(deftest missing-role-identities-are-invalid
  (testing "governance approval without an authorizer"
    (let [event (dissoc (approval-event-fixture) :governance/approver)]
      (is (= :invalid (error-kind #(ledger/record-governance nil
                                     (assoc (base-inputs 1) :governance-event event)))))))
  (testing "governance approval without a digest"
    (let [event (dissoc (approval-event-fixture) :governance/digest)]
      (is (= :invalid (error-kind #(ledger/record-governance nil
                                     (assoc (base-inputs 1) :governance-event event)))))))
  (testing "unknown governance kind"
    (let [event (assoc (approval-event-fixture) :event/kind :governance/decree)]
      (is (= :invalid (error-kind #(ledger/record-governance nil
                                     (assoc (base-inputs 1) :governance-event event)))))))
  (testing "admin bypass without an actor or reason"
    (doseq [event [{:event/kind :governance/admin-bypass
                    :governance/reason "hotfix"}
                   {:event/kind :governance/admin-bypass
                    :governance/actor "synth-admin"}]]
      (is (= :invalid (error-kind #(ledger/record-governance nil
                                     (assoc (base-inputs 1) :governance-event event)))))))
  (testing "gate decision without an evaluator identity"
    (let [decision (dissoc (deny-decision) :gate/evaluator)]
      (is (= :invalid (error-kind #(ledger/record-gate-decision nil
                                     (assoc (base-inputs 1) :decision decision)))))))
  (testing "gate decision without a policy-approval reference"
    ;; A run that cannot name its policy approval is :invalid (R2):
    ;; the unresolved-policy defer shape carries a nil approval id.
    (let [decision (assoc (deny-decision) :gate/policy
                          {:policy/id "synth-enforce-v1"
                           :policy/digest policy-digest
                           :policy/approval-event-id nil})]
      (is (= :invalid (error-kind #(ledger/record-gate-decision nil
                                     (assoc (base-inputs 1) :decision decision))))))))

;; ------------------------------------------------------------------
;; T3 acceptance: 0002 dedup invariants hold for the new kinds

(deftest duplicate-governance-events-dedup
  (with-db [path]
    (let [handle (store/open! path {:create true})]
      (try
        (let [head (store/append! handle
                         (ledger/record-governance
                           nil
                           (assoc (base-inputs 1)
                                  :event/id "evt-dup-1"
                                  :governance-event
                                  (approval-event-fixture :event-id "evt-dup-1"))))]
          (testing "same event id"
            (let [retry (fn [] (store/append! handle
                                 (ledger/record-governance
                                   head
                                   (assoc (base-inputs 2)
                                          :event/id "evt-dup-1"
                                          :governance-event
                                          (approval-event-fixture :event-id "evt-dup-1")))))]
              (is (= :duplicate (error-kind retry)))
              (is (= :duplicate-event-id (error-reason retry)))))
          (testing "same dedup key, different event id"
            ;; (base-inputs 1) reuses dedup key "submit-1"; the event
            ;; id is fresh, so the dedup-key UNIQUE constraint fires.
            (let [retry (fn [] (store/append! handle
                                 (ledger/record-governance
                                   head
                                   (assoc (base-inputs 1)
                                          :event/id "evt-dup-2"
                                          :governance-event
                                          (approval-event-fixture :event-id "evt-dup-2")))))]
              (is (= :duplicate (error-kind retry)))
              (is (= :duplicate-dedup-key (error-reason retry)))))
          (is (= 1 (:event/count (store/ledger-identity handle)))
              "duplicates never grew the ledger"))
        (finally (store/close! handle))))))

;; ------------------------------------------------------------------
;; T3 acceptance: snapshots and replay with the new kinds

(defn- seed-mixed-ledger!
  "Seeds a ledger with a scenario, a governance approval, an allow
   gate decision (with publication) and an admin bypass. Returns the
   stored envelopes."
  [handle]
  (let [s1 (store/append! handle
             (ledger/record-scenario nil
               (assoc (base-inputs 0) :scenario (assoc f/scenario :now 1100))))
        g1 (append-governance! handle s1 (approval-event-fixture :event-id "evt-gov-a") 1)
        d1 (append-decision! handle g1 (allow-decision)
                             {:publication/evaluator evaluator
                              :publication/run-id "synth-run-1"
                              :publication/published-at 200}
                             2)
        g2 (append-governance! handle d1
             {:event/kind :governance/admin-bypass
              :event/id "evt-gov-b"
              :governance/actor "synth-admin"
              :governance/reason "hotfix deploy during incident"
              :governance/bypassed-at 500}
             3)]
    [s1 g1 d1 g2]))

(deftest snapshot-replay-equivalence-with-new-kinds
  (with-db [path]
    (let [handle (store/open! path {:create true})
          [s1 g1 d1 g2] (seed-mixed-ledger! handle)]
      (try
        (let [envs (store/read-range handle 0 3)]
          (testing "new kinds contribute no 0001 world events"
            (is (= [] (ledger/extract-events g1)))
            (is (= [] (ledger/extract-events d1)))
            (is (= [] (ledger/extract-events g2)))
            (is (= (model/digest (ledger/ledger-world [s1]))
                   (model/digest (ledger/ledger-world envs)))
                "0001/0002 decision bytes are unchanged by the new kinds"))
          (testing "snapshot covers the mixed prefix and verifies"
            (let [snap (ledger/build-snapshot envs)]
              (is (= 3 (:snapshot/seq snap)))
              (is (true? (ledger/verify-snapshot! snap envs)))
              (is (= (model/digest (ledger/ledger-world envs))
                     (model/digest (ledger/restore-world snap [])))
                  "restore + replay equals full replay (replay equivalence)"))))
        (finally (store/close! handle))))))

(deftest replay-prefix-reproduces-decisions-verbatim
  (with-db [path]
    (let [handle (store/open! path {:create true})
          _ (seed-mixed-ledger! handle)
          envs (store/read-range handle 0 3)
          prefix (subvec envs 0 3)
          report (ledger/replay-report {:source "synthetic-0005" :envelopes prefix
                                        :snapshot nil :schema/version 4})]
      (try
        (let [gate-decisions (:gate-decisions report)
              governance (:governance report)]
          (is (= 1 (count gate-decisions)))
          (is (= true (:reproduced? (first gate-decisions)))
              "replay of a prefix reproduces the recorded decision")
          (is (= policy-digest (:policy/digest (first gate-decisions)))
              "the recorded policy digest travels with the replayed decision")
          (is (= (get-in (nth envs 2) [:payload :decision])
                 (:decision (first gate-decisions)))
              "the replayed decision is the stored decision, verbatim")
          (is (= [:governance/policy-approved] (mapv :event/kind governance))
              "the prefix replay carries the governance events verbatim")
          (is (every? :reproduced? governance)))
        (finally (store/close! handle))))))

;; ------------------------------------------------------------------
;; T3 acceptance: forward-only schema v4 migration

(deftest schema-v4-migration
  (testing "a fresh ledger is created at v4"
    (with-db [path]
      (let [handle (store/open! path {:create true})]
        (try
          (is (= 4 (:schema/version handle)))
          (is (= ledger/supported-schema-version 4))
          (finally (store/close! handle))))))
  (testing "a v3 ledger migrates forward to v4 without touching payloads"
    (with-db [path]
      (let [handle (store/open! path {:create true})
            _ (seed-mixed-ledger! handle)
            before (mapv :payload/digest (store/read-range handle 0 3))]
        (store/close! handle)
        ;; Simulate a v3 ledger: drop the v4 index and the version row.
        (with-open [conn (DriverManager/getConnection (str "jdbc:sqlite:" path))]
          (with-open [stmt (.createStatement conn)]
            (.execute stmt "DROP INDEX IF EXISTS idx_events_producer_seq")
            (.execute stmt "UPDATE schema_version SET version=3")))
        (let [migrated (store/open! path {:create false})]
          (try
            (is (= 4 (:schema/version migrated)))
            (is (= before (mapv :payload/digest (store/read-range migrated 0 3)))
                "migration never rewrites stored payloads")
            (is (= 4 (:event/count (store/ledger-identity migrated))))
            (finally (store/close! migrated))))))))

;; ------------------------------------------------------------------
;; T3 acceptance: bundles carry the new kinds through the 0002 path

(deftest bundle-round-trip-with-new-kinds
  (with-db [path]
    (let [handle (store/open! path {:create true})
          _ (seed-mixed-ledger! handle)
          envs (store/read-range handle 0 3)]
      (try
        (let [bundle (ledger/export-bundle-data {:engine "axiom-test"
                                                 :envelopes envs
                                                 :snapshot nil
                                                 :schema/version 4})
              reread (ledger/read-bundle-data bundle)]
          (is (= (:bundle/digest bundle) (:bundle/digest reread)))
          (is (= 1 (count (filter #(= :decision/gate-evaluation
                                            (get-in % [:payload :record/kind]))
                                         (:events reread)))))
          (is (= 2 (count (filter #(= :governance
                                            (get-in % [:payload :record/kind]))
                                         (:events reread))))))
        (finally (store/close! handle))))))

(deftest governance-kinds-record-and-read-back
  (with-db [path]
    (let [handle (store/open! path {:create true})
          events [(approval-event-fixture :event-id "evt-gov-1")
                  {:event/kind :governance/verifier-config-approved
                   :event/id "evt-gov-2"
                   :governance/digest (str "sha256:" (apply str (repeat 64 "b")))
                   :governance/authorizer owner
                   :governance/approved-at 300}
                  {:event/kind :governance/protection-changed
                   :event/id "evt-gov-3"
                   :governance/repo "synth-org/synth-repo"
                   :governance/digest (str "sha256:" (apply str (repeat 64 "c")))
                   :governance/authorizer owner
                   :governance/changed-at 400
                   :governance/reason "require synth-ci before merge"}
                  {:event/kind :governance/admin-bypass
                   :event/id "evt-gov-4"
                   :governance/actor "synth-admin"
                   :governance/reason "hotfix deploy during incident"
                   :governance/bypassed-at 500}]]
      (try
        (loop [prev nil i 0]
          (when (< i (count events))
            (recur (append-governance! handle prev (nth events i) (inc i)) (inc i))))
        (let [reread (store/read-range handle 0 3)]
          (is (= 4 (count reread)))
          (is (= (map :event/kind events)
                 (map #(get-in % [:payload :governance/event :event/kind]) reread)))
          (is (= :governance (get-in (first reread) [:payload :record/kind])))
          (is (= events (mapv #(get-in % [:payload :governance/event]) reread))
              "every governance event round-trips verbatim"))
        (finally (store/close! handle))))))
