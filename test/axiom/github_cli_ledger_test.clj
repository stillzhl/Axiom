(ns axiom.github-cli-ledger-test
  "Tests for spec 0004 T5 (CLI: observe-github / check-pr) and T6
   (ledger integration of GitHub observations).

   The CLI is driven in fixture mode (AXIOM_GITHUB_FIXTURES hook
   redefined to synthetic fixtures), so no test touches the network.
   Every repository, SHA, login, workflow and token is invented:
   `synth-org/synth-repo`, synthetic 40-hex SHAs, `synth-*` logins,
   `synth-token-*` credentials. No real repository identities, no live
   credentials."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.adapters.github :as gh-adapter]
            [axiom.cli :as cli]
            [axiom.github :as gh]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.store :as store]
            [clojure.string :as str])
  (:import (java.io File)))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-base (apply str (repeat 40 "b")))
(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-other (apply str (repeat 40 "d")))

(def ^:private api "https://api.github.com")
(def ^:private user-url (str api "/user"))
(def ^:private repo-api (str api "/repos/synth-org/synth-repo"))
(def ^:private pr-url (str repo-api "/pulls/7"))
(def ^:private files-url (str pr-url "/files?per_page=100"))
(def ^:private runs-url (str repo-api "/actions/runs?per_page=100"))
(def ^:private run101-url (str repo-api "/actions/runs/101"))
(def ^:private run101-attempts-url (str run101-url "/attempts?per_page=100"))
(def ^:private run101-jobs-url (str run101-url "/jobs?per_page=100"))
(def ^:private job201-url (str repo-api "/actions/jobs/201"))
(def ^:private job201-attempts-url (str job201-url "/attempts?per_page=100"))
(def ^:private reviews-url (str pr-url "/reviews?per_page=100"))
(def ^:private artifacts-url (str repo-api "/actions/artifacts?per_page=100"))

(defn- resp
  "A fixture response map."
  [status body]
  {:fetch/status status :fetch/headers {} :fetch/body body})

(defn- pr-body []
  {"number" 7 "title" "Synthetic change" "state" "open" "draft" false
   "merged" false "mergeable" true "merge_state_status" "clean"
   "base" {"sha" sha-base}
   "head" {"sha" sha-head
           "repo" {"owner" {"login" "synth-org"} "name" "synth-repo"}}})

(defn- run-body []
  {"id" 101 "name" "synth-ci" "path" ".github/workflows/synth-ci.yml"
   "event" "pull_request" "actor" {"login" "synth-actor"}
   "head_sha" sha-head "conclusion" "success"
   "attempts_url" run101-attempts-url "jobs_url" run101-jobs-url
   "url" run101-url})

(defn- job-body []
  {"id" 201 "name" "build" "run_id" 101
   "conclusion" "success" "run_attempt" 1
   "attempts_url" job201-attempts-url "url" job201-url})

(defn- attempt-body [url n]
  {"attempt_number" n "conclusion" "success" "url" (str url "/attempts/" n)})

(defn- review-body []
  {"id" 301 "user" {"login" "synth-reviewer"} "state" "APPROVED"
   "commit_id" sha-head "submitted_at" "2026-09-20T12:00:00Z"
   "url" (str pr-url "/reviews/301")})

(defn- artifact-body []
  {"name" "synth-dist"
   "digest" (str "sha256:" (apply str (repeat 64 "a")))
   "expires_at" "2026-10-20T12:00:00Z"
   "url" (str repo-api "/actions/artifacts/401")})

(defn- base-fixtures
  "The complete happy-path fixture set: one PR, one changed file, one
   run with one job, one approval on the head SHA, one artifact."
  []
  {pr-url (resp 200 (pr-body))
   files-url (resp 200 [{"filename" "src/a.clj" "status" "modified"}])
   runs-url (resp 200 {"total_count" 1 "workflow_runs" [(run-body)]})
   run101-attempts-url (resp 200 [(attempt-body run101-url 1)])
   run101-jobs-url (resp 200 {"total_count" 1 "jobs" [(job-body)]})
   job201-attempts-url (resp 200 [(attempt-body job201-url 1)])
   reviews-url (resp 200 [(review-body)])
   artifacts-url (resp 200 {"total_count" 1 "artifacts" [(artifact-body)]})})

(defn- observe-opts
  "Adapter options with an injected fixture fetch and no real sleeping."
  [fixtures & [extra]]
  (merge {:owner "synth-org" :repo "synth-repo" :pr 7
          :env {}
          :fetch-fn (:fetch/fn (gh-adapter/fixture-fetch fixtures))
          :sleep-fn (fn [_])
          :clock-fn (fn [] 1750000000000)}
         extra))

(defn- with-cli-fixtures
  "Runs thunk with the CLI's fixture-mode hook rebound to the given
   fixtures and the adapter environment rebound to env (default: {}
   for anonymous observation). No network is touched.

   The hook builds a FRESH fixture fetch on every call, so each CLI
   invocation observes from pristine fixtures; fixture responses are
   single-use within one observation (consumed by the adapter), but
   exhaustion never leaks across CLI calls."
  [fixtures thunk & [env]]
  (with-redefs [axiom.cli/github-fixture-fetch-fn
                (fn [] (:fetch/fn (gh-adapter/fixture-fetch fixtures)))
                axiom.cli/github-env (fn [] (or env {}))]
    (thunk)))

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(defn- error-reason [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(defn- temp-db []
  (let [file (File/createTempFile "axiom-ledger-gh-test" ".db")]
    (.delete file)
    (.getPath file)))

(defn- delete-db! [path]
  (doseq [suffix ["" "-wal" "-shm" "-journal"]]
    (.delete (File. (str path suffix)))))

(defn- base-inputs [i]
  {:event/id (str "evt-gh-" i) :stream/id "synthetic-0004"
   :dedup/key (str "submit-gh-" i) :producer "synthetic-check"
   :observed/time (* i 1000) :ingested/time (+ (* i 1000) 5)})

;; ------------------------------------------------------------------
;; T5: CLI argument parsing and exit codes

(deftest observe-github-arg-validation
  (testing "malformed arguments are exit 4 without touching the network"
    (is (= 4 (:exit (cli/run ["observe-github"]))))
    (is (= 4 (:exit (cli/run ["observe-github" "--repo" "synth-org/synth-repo"]))))
    (is (= 4 (:exit (cli/run ["observe-github" "--repo" "not-a-slug" "--pr" "7"]))))
    (is (= 4 (:exit (cli/run ["observe-github" "--repo" "a/b/c" "--pr" "7"]))))
    (is (= 4 (:exit (cli/run ["observe-github" "--repo" "" "--pr" "7"]))))
    (is (= 4 (:exit (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "abc"]))))
    (is (= 4 (:exit (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "7" "--bogus" "x"]))))
    (is (= 4 (:exit (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "7" "--sha" "nothex"]))))
    (is (= 4 (:exit (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "7" "--repo" "other/x" "--pr" "8"]))))
    (doseq [result [(cli/run ["observe-github" "--repo" "not-a-slug" "--pr" "7"])
                    (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "abc"])]]
      (is (= 4 (:exit result)))
      (is (= :invalid (:error (:output result)))))
    (let [result (cli/run ["observe-github"])]
      (is (= 4 (:exit result)))
      (is (= :usage (:error (:output result))))))
  (testing "an unreadable token file is exit 4 (invalid input), before any network"
    (let [result (cli/run ["observe-github" "--repo" "synth-org/synth-repo"
                           "--pr" "7" "--token-file" "/nonexistent/synth-token"])]
      (is (= 4 (:exit result)))
      (is (= :invalid (:error (:output result))))))
  (testing "check-pr argument validation mirrors observe-github"
    (is (= 4 (:exit (cli/run ["check-pr"]))))
    (is (= 4 (:exit (cli/run ["check-pr" "--repo" "synth-org/synth-repo"]))))
    (is (= 4 (:exit (cli/run ["check-pr" "--repo" "not-a-slug" "--pr" "7"]))))
    (is (= 4 (:exit (cli/run ["check-pr" "--repo" "synth-org/synth-repo" "--pr" "7" "--sha" sha-head]))))
    (is (= 4 (:exit (cli/run ["check-pr" "--repo" "synth-org/synth-repo"
                              "--pr" "7" "--token-file" "/nonexistent/synth-token"]))))))

(deftest observe-github-fixture-mode
  (with-cli-fixtures (base-fixtures)
    (fn []
      (testing "a complete fixture observation is exit 0 with provider trust marks"
        (let [{:keys [exit output]} (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "7"])]
          (is (= 0 exit))
          (is (= :complete (:observation/status output)))
          (is (= :github-observation (:observation/kind output)))
          (is (= :trust/provider-observed (:trust output)))
          (is (not= :trust/remote-ci (:trust output)))
          (is (= sha-head (get-in output [:subject :git/head])))
          (is (= sha-base (get-in output [:subject :git/base])))
          (is (true? (get-in output [:value :changes :pagination/complete])))))
      (testing "--sha pins the expected head SHA: match is 0, mismatch is 4"
        (is (= 0 (:exit (cli/run ["observe-github" "--repo" "synth-org/synth-repo"
                                  "--pr" "7" "--sha" sha-head]))))
        (let [result (cli/run ["observe-github" "--repo" "synth-org/synth-repo"
                               "--pr" "7" "--sha" sha-other])]
          (is (= 4 (:exit result)))
          (is (= :invalid (:error (:output result)))))))))

(deftest observe-github-authenticated-fixture-mode
  (testing "a token resolves identity once and marks provider-authenticated"
    (let [fixtures (assoc (base-fixtures) user-url (resp 200 {"login" "synth-user"}))]
      (with-cli-fixtures fixtures
        (fn []
          (let [{:keys [exit output]} (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "7"])]
            (is (= 0 exit))
            (is (= :trust/provider-authenticated (:trust output)))
            (is (= "synth-user" (get-in output [:producer :github/login])))
            (is (true? (get-in output [:producer :producer/authenticated?])))
            (is (not= :trust/remote-ci (:trust output)))))
        {"AXIOM_GITHUB_TOKEN" "synth-token-1"}))))

(deftest observe-github-operational-failures
  (testing "a terminal API failure (404 on the PR) is exit 5 with a named reason"
    (with-cli-fixtures {pr-url (resp 404 {})}
      (fn []
        (let [{:keys [exit output]} (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "7"])]
          (is (= 5 exit))
          (is (= :operational (:error output)))))))
  (testing "an :observation/incomplete observation maps to exit 5 (CLI mapping)"
    (let [incomplete (gh/build-observation
                      {:observation/status :observation/incomplete
                       :producer {:producer/id "axiom-github-observer"
                                  :producer/authenticated? false
                                  :github/login nil}
                       :trust :trust/provider-observed
                       :subject {:github/owner "synth-org" :github/repo "synth-repo"
                                 :github/pr 7 :git/base sha-base :git/head sha-head
                                 :github/head-repo {:github/owner "synth-org" :github/repo "synth-repo"}}
                       :value {:pr/record {:github/pr-title "t" :github/pr-state :open
                                           :github/draft? false :github/merge-state :clean
                                           :github/mergeable? true :evidence/url pr-url}
                               :changes {:pagination/complete false :pagination/pages 1 :changes []}
                               :runs {:pagination/complete false :pagination/pages 0 :runs []}
                               :reviews {:pagination/complete false :pagination/pages 0 :reviews []}
                               :artifacts {:pagination/complete false :pagination/pages 0 :artifacts []}}
                       :provenance {:api/urls [pr-url] :api/etags {pr-url nil}
                                   :rate-limit {:rate-limit/limit nil :rate-limit/remaining nil
                                                :rate-limit/reset nil}
                                   :http/client {:http/timeout-ms 30000
                                                 :http/user-agent "axiom-github-observer/0.1.0-offline"
                                                 :http/api-version "2022-11-28"}}
                       :scope {:github/owner "synth-org" :github/repo "synth-repo" :github/pr 7
                               :revisions {:base sha-base :head sha-head}}
                       :observation/failing-step {:collection :changes :page 2
                                                 :reason "synthetic page failure"
                                                 :github/run-id nil}})]
      (with-redefs [gh-adapter/observe! (fn [_] incomplete)]
        (let [obs-result (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "7"])
              check-result (cli/run ["check-pr" "--repo" "synth-org/synth-repo" "--pr" "7"])]
          (is (= 5 (:exit obs-result)))
          (is (= :observation/incomplete (:observation/status (:output obs-result))))
          (is (= :changes (get-in obs-result [:output :observation/failing-step :collection])))
          (is (= 5 (:exit check-result))))))))

(deftest check-pr-fixture-mode
  (with-cli-fixtures (base-fixtures)
    (fn []
      (testing "check-pr emits the advisory report with per-gate outcomes and evidence links"
        (let [{:keys [exit output]} (cli/run ["check-pr" "--repo" "synth-org/synth-repo" "--pr" "7"])]
          (is (= 0 exit))
          (is (= :check-pr (:check/kind output)))
          (is (true? (:check/complete-observation? output)))
          (is (= "synth-org" (get-in output [:subject :github/owner])))
          (is (= sha-head (get-in output [:subject :git/head])))
          (is (= sha-base (get-in output [:subject :git/base])))
          (let [gates (into {} (map (fn [g] [(:gate/id g) g])) (:gates output))]
            (is (= #{:pr-identity :approvals :merge-state} (set (keys gates))))
            (is (= :pass (:gate/outcome (:pr-identity gates))))
            (is (= :pass (:gate/outcome (:approvals gates))))
            (is (= :pass (:gate/outcome (:merge-state gates))))
            (doseq [[_ gate] gates]
              (is (seq (:gate/evidence gate)))
              (doseq [ev (:gate/evidence gate)]
                (is (str/starts-with? (:evidence/url ev) api))
                (is (= 40 (count (:evidence/sha ev)))))))
          (is (true? (get-in output [:can-merge :can-merge/advisory-only])))
          (is (= :yes (get-in output [:can-merge :can-merge/advisory]))))))))

(deftest cli-never-touches-ledger-files
  (testing "observe-github and check-pr never open, create or append to ledgers"
    (let [calls (atom [])]
      (with-cli-fixtures (base-fixtures)
        (fn []
          (with-redefs [store/open! (fn [& args] (swap! calls conj [:open! args])
                                                   (throw (ex-info "must not open a ledger" {})))
                        store/append! (fn [& args] (swap! calls conj [:append! args])
                                                    (throw (ex-info "must not append" {})))]
            (is (= 0 (:exit (cli/run ["observe-github" "--repo" "synth-org/synth-repo" "--pr" "7"]))))
            (is (= 0 (:exit (cli/run ["check-pr" "--repo" "synth-org/synth-repo" "--pr" "7"]))))))
        (is (empty? @calls))))))

;; ------------------------------------------------------------------
;; T6: ledger integration through the 0002 append path

(deftest github-observation-records-through-append-path
  (let [observation (gh-adapter/observe! (observe-opts (base-fixtures)))
        path (temp-db)]
    (try
      (let [handle (store/open! path {:create true})]
        (try
          (let [envelope (ledger/record-observation nil (assoc (base-inputs 1) :observation observation))
                stored (store/append! handle envelope)
                envelopes (store/read-range handle 0 0)
                report (ledger/replay-report
                        {:source {:kind :ledger :path path}
                         :schema/version (:schema/version (store/ledger-identity handle))
                         :envelopes envelopes
                         :snapshot nil})]
            (testing "the envelope is an :observation payload with a content-derived candidate id"
              (is (= :observation (get-in envelope [:payload :record/kind])))
              (is (= observation (get-in envelope [:payload :observation])))
              (is (= (model/candidate-id observation) (:candidate/id envelope))))
            (testing "replay shows the provider observation behind the (absent) decisions"
              (is (= [{:seq 0 :event/id (:event/id stored) :observation observation}]
                     (:observations report)))
              (is (empty? (:decisions report))))
            (testing "hash-chained, replay-ordered, snapshot-covered like 0003 observations"
              (is (:chain/valid? (ledger/verify-chain envelopes)))
              (is (= [0] (mapv :seq envelopes)))))
          (finally (store/close! handle))))
      (finally (delete-db! path)))))

(deftest github-observation-trust-marks
  (let [anonymous (gh-adapter/observe! (observe-opts (base-fixtures)))
        authenticated (gh-adapter/observe!
                       (observe-opts (assoc (base-fixtures) user-url (resp 200 {"login" "synth-user"}))
                                     {:env {"AXIOM_GITHUB_TOKEN" "synth-token-1"}}))]
    (testing "trust marks are present and :trust/remote-ci is never produced"
      (is (= :trust/provider-observed (:trust anonymous)))
      (is (= :trust/provider-authenticated (:trust authenticated)))
      (is (not= :trust/remote-ci (:trust anonymous)))
      (is (not= :trust/remote-ci (:trust authenticated))))
    (testing "recording accepts both provider trust marks through the 0002 path"
      (is (map? (ledger/record-observation nil (assoc (base-inputs 1) :observation anonymous))))
      (is (map? (ledger/record-observation nil (assoc (base-inputs 2) :observation authenticated)))))
    (testing "forged or unknown observations are :invalid and can never be written"
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 3) :observation
                                               (assoc anonymous :trust :trust/remote-ci))))))
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 4) :observation
                                               (assoc anonymous :observation/kind :bogus))))))
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 5) :observation
                                               (dissoc anonymous :trust))))))
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 6) :observation
                                               ;; a local trust mark on a provider
                                               ;; observation is a mismatch
                                               (assoc anonymous :trust :trust/local-diagnostic)))))))))

(deftest github-observation-dedup-and-replay-cli
  (let [observation (gh-adapter/observe! (observe-opts (base-fixtures)))
        path (temp-db)]
    (try
      (let [handle (store/open! path {:create true})]
        (try
          (let [e1 (store/append! handle
                       (ledger/record-observation nil (assoc (base-inputs 1) :observation observation)))]
            (testing "duplicate event IDs and dedup keys are rejected deterministically"
              (is (= :duplicate
                     (error-kind #(store/append! handle
                                    (ledger/record-observation
                                     e1 (assoc (base-inputs 1) :observation observation))))))
              (is (= :duplicate-event-id
                     (error-reason #(store/append! handle
                                      (ledger/record-observation
                                       e1 (assoc (base-inputs 1) :dedup/key "submit-gh-1b"
                                                 :observation observation))))))
              (is (= :duplicate-dedup-key
                     (error-reason #(store/append! handle
                                      (ledger/record-observation
                                       e1 (assoc (base-inputs 2) :dedup/key "submit-gh-1"
                                                 :event/id "evt-gh-2"
                                                 :observation observation)))))))
            (testing "the CLI replay shows the recorded provider observation"
              (let [{:keys [exit output]} (cli/run ["replay" "--ledger" path])]
                (is (= 0 exit))
                (is (= [{:seq 0 :event/id (:event/id e1) :observation observation}]
                       (:observations output)))
                (is (= :github-observation
                       (:observation/kind (:observation (first (:observations output)))))))))
          (finally (store/close! handle))))
      (finally (delete-db! path)))))

(deftest fixture-observations-replay-byte-identically-offline
  (testing "two fixture runs produce byte-identical observations (deterministic)"
    (let [a (gh-adapter/observe! (observe-opts (base-fixtures)))
          b (gh-adapter/observe! (observe-opts (base-fixtures)))]
      (is (= (model/edn-str a) (model/edn-str b)))))
  (testing "a decision recorded from fixtures replays byte-identically offline"
    (let [observation (gh-adapter/observe! (observe-opts (base-fixtures)))
          live-report (gh/check-pr observation @#'axiom.cli/default-check-pr-gates)
          path (temp-db)]
      (try
        (let [handle (store/open! path {:create true})]
          (try
            (store/append! handle
              (ledger/record-observation nil (assoc (base-inputs 1) :observation observation)))
            (let [envelopes (store/read-range handle 0 0)
                  report (ledger/replay-report
                          {:source {:kind :ledger :path path}
                           :schema/version (:schema/version (store/ledger-identity handle))
                           :envelopes envelopes
                           :snapshot nil})
                  replayed (get-in report [:observations 0 :observation])
                  replayed-report (gh/check-pr replayed @#'axiom.cli/default-check-pr-gates)]
              (is (= (model/edn-str observation) (model/edn-str replayed))
                  "the replayed observation is byte-identical to the recorded one")
              (is (= (model/edn-str live-report) (model/edn-str replayed-report))
                  "check-pr over the replayed observation reproduces the live report byte-identically")
              (is (= :yes (get-in replayed-report [:can-merge :can-merge/advisory]))))
            (finally (store/close! handle))))
        (finally (delete-db! path))))))

(deftest github-observations-are-snapshot-covered-and-bundle-included
  (testing "GitHub observations ride the 0002 snapshot and export-bundle path"
    (let [fixtures (base-fixtures)
          obs1 (gh-adapter/observe! (observe-opts fixtures))
          obs2 (gh-adapter/observe! (observe-opts fixtures))
          path (temp-db)]
      (try
        (let [handle (store/open! path {:create true})]
          (try
            (let [e1 (store/append! handle
                       (ledger/record-observation
                        nil (assoc (base-inputs 1) :observation obs1)))
                  e2 (store/append! handle
                       (ledger/record-observation
                        e1 (assoc (base-inputs 2) :observation obs2)))
                  envelopes (store/read-range handle 0 1)
                  snapshot (ledger/build-snapshot envelopes)]
              (testing "a snapshot covers the GitHub observation prefix"
                (is (some? snapshot))
                (is (= 1 (:snapshot/seq snapshot)))
                (is (true? (ledger/verify-snapshot! snapshot envelopes))))
              (testing "an export bundle includes the GitHub observation events"
                (let [bundle (ledger/export-bundle-data
                              {:engine "axiom-test"
                               :envelopes envelopes
                               :snapshot snapshot
                               :schema/version (:schema/version
                                                (store/ledger-identity handle))})
                      kinds (map #(get-in % [:payload :observation
                                             :observation/kind])
                                 (:events bundle))]
                  (is (= [:github-observation :github-observation] (vec kinds)))
                  (is (= (model/edn-str obs1)
                          (model/edn-str (get-in bundle [:events 0 :payload
                                                         :observation])))))))
            (finally (store/close! handle))))
        (finally (delete-db! path))))))
