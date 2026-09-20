(ns axiom.github-adversarial-test
  "Adversarial and boundary tests for spec 0004 (T7).

   Audit of the T7 list against slices 1-3 (2026-09-20) found most
   cases already covered there: mid-list page failure naming the
   collection/page/bound (`mid-list-page-failure-is-incomplete`,
   `jobs-page-failure-names-run`), stale ETag cache hits after
   base/head movement (`stale-etag-cache-hit-is-operational`,
   `etag-304-reuses-cache-under-same-shas`), 403 terminal failures
   (`terminal-403-runs`), unknown check conclusions mapping to
   `:unknown` (`unknown-conclusions-map-to-unknown`), skipped required
   jobs / timed-out reruns / missing jobs making gates `:unknown`
   (`check-pr-unknowns`), dismissed and superseded-head approvals never
   counting (`check-pr-approvals`), matrix expansion with explicit
   selection rules (`matrix-selection`, `latest-selection`), forged
   producers rejected as `:invalid` (`forged-producer`,
   `forged-producer-claims-are-invalid`), and malformed provider
   payloads naming the offending field (`malformed-*`,
   `pr-number-mismatch-is-operational`).

   This namespace adds ONLY the genuine gaps the audit found:

   1. rate-limit exhaustion (429s until the retry budget runs out) on a
      collection — the existing exhaustion test used 503s.
   2. fork head identity at the adapter level — the pure tests covered
      it, but no fixture observed a fork PR end to end.
   3. expired artifacts — expiry was parsed and validated but nothing
      asserted the recorded behavior.
   4. static enforcement that no test opens a socket: every test runs
      the full adapter logic against injected synthetic fixtures.

   Every repository, SHA, login, workflow and token is invented:
   `synth-org/synth-repo`, synthetic 40-hex SHAs, `synth-*` logins.
   No real repository identities, no live credentials, no network."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.adapters.github :as gh-adapter]
            [axiom.github :as gh]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import (java.io File)))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-base (apply str (repeat 40 "b")))
(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-digest (str "sha256:" (apply str (repeat 64 "a"))))

(def ^:private api "https://api.github.com")
(def ^:private repo-api (str api "/repos/synth-org/synth-repo"))
(def ^:private pr-url (str repo-api "/pulls/7"))
(def ^:private files-url (str pr-url "/files?per_page=100"))
(def ^:private files-url-p2 (str pr-url "/files?per_page=100&page=2"))
(def ^:private runs-url (str repo-api "/actions/runs?per_page=100"))
(def ^:private run-url (str repo-api "/actions/runs/101"))
(def ^:private run-attempts-url (str run-url "/attempts?per_page=100"))
(def ^:private run-jobs-url (str run-url "/jobs?per_page=100"))
(def ^:private job-url (str repo-api "/actions/jobs/201"))
(def ^:private job-attempts-url (str job-url "/attempts?per_page=100"))
(def ^:private reviews-url (str pr-url "/reviews?per_page=100"))
(def ^:private artifacts-url (str repo-api "/actions/artifacts?per_page=100"))

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(defn- resp
  "A fixture response map."
  ([status body] (resp status body {}))
  ([status body headers]
   {:fetch/status status :fetch/headers headers :fetch/body body}))

(defn- link-next [url]
  (str "<" url ">; rel=\"next\""))

(defn- pr-body []
  {"number" 7 "title" "Synthetic change" "state" "open" "draft" false
   "merged" false "mergeable" true "merge_state_status" "clean"
   "base" {"sha" sha-base}
   "head" {"sha" sha-head
           "repo" {"owner" {"login" "synth-org"} "name" "synth-repo"}}
   "url" pr-url})

(defn- run-body []
  {"id" 101 "name" "synth-ci" "path" ".github/workflows/synth-ci.yml"
   "event" "pull_request" "actor" {"login" "synth-actor"}
   "head_sha" sha-head "conclusion" "success"
   "attempts_url" run-attempts-url "jobs_url" run-jobs-url
   "url" run-url})

(defn- job-body []
  {"id" 201 "name" "build" "run_id" 101
   "conclusion" "success" "run_attempt" 1
   "attempts_url" job-attempts-url "url" job-url})

(defn- attempt-body [url]
  {"attempt_number" 1 "conclusion" "success" "url" (str url "/attempts/1")})

(defn- review-body []
  {"id" 301 "user" {"login" "synth-reviewer"} "state" "APPROVED"
   "commit_id" sha-head "submitted_at" "2026-09-20T12:00:00Z"
   "url" (str pr-url "/reviews/301")})

(defn- artifact-body []
  {"name" "synth-dist"
   "digest" sha-digest
   "expires_at" "2026-10-20T12:00:00Z"
   "url" (str repo-api "/actions/artifacts/401")})

(defn- base-fixtures
  "The complete happy-path fixture set."
  []
  {pr-url (resp 200 (pr-body))
   files-url (resp 200 [{"filename" "src/a.clj" "status" "modified"}]
                       {"link" [(link-next files-url-p2)]})
   files-url-p2 (resp 200 [{"filename" "src/b.clj" "status" "added"}])
   runs-url (resp 200 {"total_count" 1 "workflow_runs" [(run-body)]})
   run-attempts-url (resp 200 [(attempt-body run-url)])
   run-jobs-url (resp 200 {"total_count" 1 "jobs" [(job-body)]})
   job-attempts-url (resp 200 [(attempt-body job-url)])
   reviews-url (resp 200 [(review-body)])
   artifacts-url (resp 200 {"total_count" 1 "artifacts" [(artifact-body)]})})

(defn- sleeps-recorder []
  (let [sleeps (atom [])]
    {:sleep-fn (fn [ms] (swap! sleeps conj ms))
     :sleeps sleeps}))

(defn- base-opts
  "observe! options with an injected fixture fetch and no real
   sleeping. Extra opts merged in."
  [fixtures & [extra]]
  (let [{:keys [sleep-fn]} (sleeps-recorder)]
    (merge {:owner "synth-org" :repo "synth-repo" :pr 7
            :env {}
            :fetch-fn (:fetch/fn (gh-adapter/fixture-fetch fixtures))
            :sleep-fn sleep-fn
            :clock-fn (fn [] 1750000000000)}
           extra)))

;; ------------------------------------------------------------------
;; Gap 1: rate-limit exhaustion (429s) on a collection

(deftest rate-limit-exhaustion-names-bound
  (testing "429s until the retry budget is exhausted name the bound and the 429 status"
    (let [{:keys [sleep-fn sleeps]} (sleeps-recorder)
          fixtures (-> (base-fixtures)
                       (assoc files-url-p2
                              (vec (repeat 3 (resp 429 nil {"retry-after" ["1"]})))))
          obs (gh-adapter/observe! (base-opts fixtures
                                              {:sleep-fn sleep-fn
                                               :config {:max-attempts 3}}))]
      (is (= :observation/incomplete (:observation/status obs)))
      (let [step (:observation/failing-step obs)]
        (is (= :changes (:collection step)))
        (is (= 2 (:page step)))
        (is (str/includes? (:reason step) "retry budget exhausted after 3 attempts")
            "the retry bound is named")
        (is (str/includes? (:reason step) "last status 429")
            "the rate-limit status is preserved, not generalized"))
      (is (= [] (get-in obs [:value :changes :changes]))
          "an exhausted collection never carries partial items")
      (is (= [1000 2000] @sleeps)
          "Retry-After honored on the first wait; the exponential backoff
           dominates on the second; then the budget gives up"))))

;; ------------------------------------------------------------------
;; Gap 2: fork head identity at the adapter level

(deftest fork-pr-observation-carries-head-repo
  (testing "a fork PR observed end to end carries the forker as head-repo, never conflated"
    (let [fork-pr (assoc-in (pr-body) ["head" "repo"]
                            {"owner" {"login" "synth-forker"} "name" "synth-repo"})
          fixtures (assoc (base-fixtures) pr-url (resp 200 fork-pr))
          obs (gh-adapter/observe! (base-opts fixtures))]
      (is (= :complete (:observation/status obs)))
      (is (= {:github/owner "synth-org" :github/repo "synth-repo"}
             (select-keys (:subject obs) [:github/owner :github/repo]))
          "the subject is the upstream repository")
      (is (= {:github/owner "synth-forker" :github/repo "synth-repo"}
             (get-in obs [:subject :github/head-repo]))
          "the head repo is the fork, exactly as the provider payload states")
      (is (= obs (gh/validate-observation! obs)) "the fork observation validates"))
    (testing "check-pr keeps the fork identity distinct in its report"
      (let [fork-pr (assoc-in (pr-body) ["head" "repo"]
                              {"owner" {"login" "synth-forker"} "name" "synth-repo"})
            fixtures (assoc (base-fixtures) pr-url (resp 200 fork-pr))
            obs (gh-adapter/observe! (base-opts fixtures))
            report (gh/check-pr obs [{:gate/id :pr-identity}])]
        (is (= "synth-forker"
               (get-in report [:subject :github/head-repo :github/owner])))
        (is (not= (get-in report [:subject :github/owner])
                  (get-in report [:subject :github/head-repo :github/owner]))
            "fork and upstream refs are never conflated")))))

;; ------------------------------------------------------------------
;; Gap 3: expired artifacts

(deftest expired-artifacts-recorded-intact
  (testing "an expired artifact is recorded with its expiry intact — never dropped or normalized"
    (let [expired (assoc (artifact-body) "expires_at" "2020-01-01T00:00:00Z")
          fixtures (assoc (base-fixtures)
                          artifacts-url
                          (resp 200 {"total_count" 1 "artifacts" [expired]}))
          obs (gh-adapter/observe! (base-opts fixtures))
          artifact (first (get-in obs [:value :artifacts :artifacts]))]
      (is (= :complete (:observation/status obs)))
      (is (= :github-observation (:observation/kind (gh/validate-observation! obs))))
      (is (= 1577836800 (:github/expires-at artifact))
          "the past expiry is recorded as epoch seconds, not silently dropped")
      (is (= sha-digest (:github/digest artifact)))
      (is (= (str repo-api "/actions/artifacts/401") (:evidence/url artifact)))))
  (testing "a malformed expires_at is an operational failure naming the field"
    (let [bad (assoc (artifact-body) "expires_at" "not-a-time")
          fixtures (assoc (base-fixtures)
                          artifacts-url
                          (resp 200 {"total_count" 1 "artifacts" [bad]}))]
      (is (= :operational (error-kind #(gh-adapter/observe! (base-opts fixtures)))))
      (try (gh-adapter/observe! (base-opts fixtures)) nil
           (catch clojure.lang.ExceptionInfo e
             (is (= "expires_at" (:field (ex-data e))))))))
  (testing "a malformed digest is an operational failure naming the field"
    (let [bad (assoc (artifact-body) "digest" "not-a-digest")
          fixtures (assoc (base-fixtures)
                          artifacts-url
                          (resp 200 {"total_count" 1 "artifacts" [bad]}))]
      (is (= :operational (error-kind #(gh-adapter/observe! (base-opts fixtures)))))
      (try (gh-adapter/observe! (base-opts fixtures)) nil
           (catch clojure.lang.ExceptionInfo e
             (is (= "digest" (:field (ex-data e)))))))))

;; ------------------------------------------------------------------
;; Gap 4: no test may open a socket (static enforcement)

(deftest no-test-opens-a-socket
  (testing "no test source references socket/network APIs; fixtures are injected, never fetched live"
    (let [;; Fragments are concatenated so this very file cannot
          ;; self-match the scan below.
          patterns (mapv re-pattern
                         [(str "java" "\\.net" "\\.http")
                          (str "Http" "Client")
                          (str "Sock" "et")
                          (str "Inet" "Address")
                          (str "Http" "URLConnection")
                          (str "Data" "gram" "Sock" "et")
                          (str "Serv" "er" "Sock" "et")])
          files (->> (file-seq (io/file "test"))
                     (filter #(.isFile ^File %))
                     (filter #(str/ends-with? (.getName ^File %) ".clj")))
          hits (for [f files
                     p patterns
                     :let [m (re-find p (slurp f))]
                     :when m]
                 [(.getPath ^File f) (str p)])]
      (is (seq files) "test sources were found to scan")
      (is (empty? hits)
          (str "network API references in test sources: " (pr-str (take 5 hits)))))))
