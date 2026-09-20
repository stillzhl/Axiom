(ns axiom.adapters-github-test
  "Tests for `axiom.adapters.github` (spec 0004 T2/T3).

   The fetch function is injected, so every test runs the full
   pagination/retry/identity logic against synthetic EDN fixtures
   with zero network. Every repository, SHA, login, workflow, token
   and URL is invented: `synth-org/synth-repo`, synthetic 40-hex
   SHAs, `synth-*` logins, `synth-token-*` credentials. No real
   repository identities, no live credentials, no network access."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.adapters.github :as gh-adapter]
            [axiom.github :as gh]
            [clojure.string :as str])
  (:import (java.io File)))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-base (apply str (repeat 40 "b")))
(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-stale (apply str (repeat 40 "a")))

(def ^:private api "https://api.github.com")
(def ^:private user-url (str api "/user"))
(def ^:private repo-api (str api "/repos/synth-org/synth-repo"))
(def ^:private pr-url (str repo-api "/pulls/7"))
(def ^:private files-url (str pr-url "/files?per_page=100"))
(def ^:private files-url-p2 (str pr-url "/files?per_page=100&page=2"))
(def ^:private runs-url (str repo-api "/actions/runs?per_page=100"))
(def ^:private runs-url-p2 (str repo-api "/actions/runs?per_page=100&page=2"))
(def ^:private run-url (str repo-api "/actions/runs/101"))
(def ^:private run-attempts-url (str run-url "/attempts?per_page=100"))
(def ^:private run-jobs-url (str run-url "/jobs?per_page=100"))
(def ^:private run-jobs-url-p2 (str run-url "/jobs?per_page=100&page=2"))
(def ^:private job-url (str repo-api "/actions/jobs/201"))
(def ^:private job-attempts-url (str job-url "/attempts?per_page=100"))
(def ^:private reviews-url (str pr-url "/reviews?per_page=100"))
(def ^:private artifacts-url (str repo-api "/actions/artifacts?per_page=100"))

(def ^:private json-parse @#'gh-adapter/json-parse)

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

(defn- files-page-1 []
  [{"filename" "src/a.clj" "status" "modified"}])

(defn- files-page-2 []
  [{"filename" "src/b.clj" "status" "added"}
   {"filename" "src/new.clj" "status" "renamed" "previous_filename" "src/old.clj"}])

(defn- run-object
  ([] (run-object 101 "success" "success"))
  ([run-id run-conclusion attempt-conclusion]
   {"id" run-id "name" "synth-ci" "path" ".github/workflows/synth-ci.yml"
    "event" "pull_request" "actor" {"login" "synth-actor"}
    "head_sha" sha-head "conclusion" run-conclusion
    "attempts_url" run-attempts-url "jobs_url" run-jobs-url
    "url" run-url}))

(defn- attempt-object [url n conclusion]
  {"attempt_number" n "conclusion" conclusion "url" (str url "/attempts/" n)})

(defn- job-object
  ([] (job-object "success" "success"))
  ([job-conclusion attempt-conclusion]
   {"id" 201 "name" "build" "run_id" 101
    "conclusion" job-conclusion "run_attempt" 1
    "attempts_url" job-attempts-url "url" job-url}))

(defn- review-object []
  {"id" 301 "user" {"login" "synth-reviewer"} "state" "APPROVED"
   "commit_id" sha-head "submitted_at" "2026-09-20T12:00:00Z"
   "url" (str pr-url "/reviews/301")})

(defn- artifact-object []
  {"name" "synth-dist"
   "digest" (str "sha256:" (apply str (repeat 64 "a")))
   "expires_at" "2026-10-20T12:00:00Z"
   "url" (str repo-api "/actions/artifacts/401")})

(defn- base-fixtures
  "The complete happy-path fixture set: two file pages, one run with
   one job, one review, one artifact."
  []
  {pr-url (resp 200 (pr-body) {"etag" ["\"etag-pr\""]
                               "x-ratelimit-limit" ["60"]
                               "x-ratelimit-remaining" ["59"]
                               "x-ratelimit-reset" ["1750000060"]})
   files-url (resp 200 (files-page-1) {"link" [(link-next files-url-p2)]})
   files-url-p2 (resp 200 (files-page-2))
   runs-url (resp 200 {"total_count" 1 "workflow_runs" [(run-object)]})
   run-attempts-url (resp 200 [(attempt-object run-url 1 "success")])
   run-jobs-url (resp 200 {"total_count" 1 "jobs" [(job-object)]})
   job-attempts-url (resp 200 [(attempt-object job-url 1 "success")])
   reviews-url (resp 200 [(review-object)])
   artifacts-url (resp 200 {"total_count" 1 "artifacts" [(artifact-object)]})})

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

(defn- auth-fixtures
  "base-fixtures plus the /user identity endpoint."
  [login]
  (assoc (base-fixtures)
         user-url (resp 200 {"login" login})))

;; ------------------------------------------------------------------
;; T2: observation assembly, pagination, completeness

(deftest anonymous-complete-observation
  (testing "anonymous observation is complete, marked provider-observed, login nil"
    (let [obs (gh-adapter/observe! (base-opts (base-fixtures)))]
      (is (= :complete (:observation/status obs)))
      (is (= :trust/provider-observed (:trust obs)))
      (is (= {:producer/id "axiom-github-observer"
              :producer/authenticated? false
              :github/login nil}
             (:producer obs)))
      (is (= sha-base (get-in obs [:subject :git/base])))
      (is (= sha-head (get-in obs [:subject :git/head])))
      (is (= {:github/owner "synth-org" :github/repo "synth-repo"}
             (get-in obs [:subject :github/head-repo]))))))

(deftest pagination-collects-all-pages
  (testing "changed files are enumerated to the end across Link pages"
    (let [obs (gh-adapter/observe! (base-opts (base-fixtures)))
          changes (get-in obs [:value :changes])]
      (is (= true (:pagination/complete changes)))
      (is (= 2 (:pagination/pages changes)))
      (is (= [:modified :added :renamed]
             (mapv :change/kind (:changes changes))))
      (is (= ["src/a.clj" "src/b.clj" "src/new.clj"]
             (mapv :change/new-path (:changes changes))))
      (is (= "src/old.clj"
             (:change/old-path (last (:changes changes))))))))

(deftest provenance-records-client-config
  (testing "declared timeouts, user agent and API version are in provenance"
    (let [obs (gh-adapter/observe! (base-opts (base-fixtures)))
          provenance (:provenance obs)
          urls (:api/urls provenance)]
      (is (= {:http/timeout-ms 30000
              :http/user-agent "axiom-github-observer/0.1.0-offline"
              :http/api-version "2022-11-28"}
             (:http/client provenance)))
      (is (= (count urls) (count (set urls))) "requested URLs are distinct")
      (is (every? #(contains? (:api/etags provenance) %) urls)
          "every requested URL has an ETag entry (nil when absent)")
      (is (= "\"etag-pr\"" (get (:api/etags provenance) pr-url)))
      (is (= {:rate-limit/limit 60 :rate-limit/remaining 59 :rate-limit/reset 1750000060}
             (:rate-limit provenance))))))

(deftest mid-list-page-failure-is-incomplete
  (testing "a page failing after bounded retries names the collection, page and bound"
    (let [{:keys [sleep-fn sleeps]} (sleeps-recorder)
          fixtures (-> (base-fixtures)
                       (assoc files-url-p2 [(resp 503 nil) (resp 503 nil) (resp 503 nil)]))
          obs (gh-adapter/observe! (base-opts fixtures
                                              {:sleep-fn sleep-fn
                                               :config {:max-attempts 3}}))]
      (is (= :observation/incomplete (:observation/status obs)))
      (is (= {:collection :changes :page 2
              :reason "retry budget exhausted after 3 attempts (last status 503)"
              :github/run-id nil}
             (:observation/failing-step obs)))
      (is (= false (get-in obs [:value :changes :pagination/complete])))
      (is (= [] (get-in obs [:value :changes :changes]))
          "incomplete collections never carry partial items")
      (is (= 2 (count @sleeps)) "bounded retries: max-attempts 3 sleeps twice"))))

(deftest retry-429-honors-retry-after
  (testing "429 with Retry-After waits the honored duration, then succeeds"
    (let [{:keys [sleep-fn sleeps]} (sleeps-recorder)
          fixtures (-> (base-fixtures)
                       (assoc files-url [(resp 429 nil {"retry-after" ["2"]})
                                         (resp 200 (files-page-1)
                                                {"link" [(link-next files-url-p2)]})]))
          obs (gh-adapter/observe! (base-opts fixtures {:sleep-fn sleep-fn}))]
      (is (= :complete (:observation/status obs)))
      (is (= [2000] @sleeps) "Retry-After: 2 honored as 2000ms"))))

(deftest retry-honors-rate-limit-reset
  (testing "429 with X-RateLimit-Reset waits until the window resets"
    (let [{:keys [sleep-fn sleeps]} (sleeps-recorder)
          fixtures (-> (base-fixtures)
                       (assoc runs-url [(resp 429 nil {"x-ratelimit-reset" ["1750000005"]})
                                        (resp 200 {"total_count" 1
                                                   "workflow_runs" [(run-object)]})]))
          obs (gh-adapter/observe! (base-opts fixtures {:sleep-fn sleep-fn}))]
      (is (= :complete (:observation/status obs)))
      (is (= [5000] @sleeps) "reset 5s in the future honored as 5000ms"))))

(deftest terminal-404-unknown-pr
  (testing "404 on the PR is a terminal operational failure naming status and resource"
    (let [err (try (gh-adapter/observe! (base-opts (assoc (base-fixtures)
                                                         pr-url (resp 404 nil))))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? err))
      (is (= :operational (:axiom/error (ex-data err))))
      (is (= 404 (:status (ex-data err))))
      (is (str/includes? (.getMessage err) "pull request")))))

(deftest terminal-403-runs
  (testing "403 on a collection is terminal: never retried into a partial result"
    (let [{:keys [sleeps]} (sleeps-recorder)
          fixtures (assoc (base-fixtures) runs-url (resp 403 nil))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (= 403 (:status (ex-data err))))
      (is (str/includes? (.getMessage err) "workflow runs"))
      (is (= [] @sleeps) "terminal statuses are never retried"))))

(deftest single-resource-retry-exhaustion-is-operational
  (testing "retry exhaustion on the PR (no collection to mark) names the bound"
    (let [fixtures (assoc (base-fixtures)
                          pr-url [(resp 503 nil) (resp 503 nil) (resp 503 nil)])
          err (try (gh-adapter/observe! (base-opts fixtures {:config {:max-attempts 3}}))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (str/includes? (.getMessage err) "3 attempts")))))

;; ------------------------------------------------------------------
;; T2: malformed provider payloads

(deftest malformed-unknown-file-status
  (testing "an unknown file status is an operational failure naming the field"
    (let [fixtures (-> (base-fixtures)
                       (assoc files-url (resp 200 [{"filename" "src/a.clj" "status" "changed"}])))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (= "status" (:field (ex-data err)))))))

(deftest malformed-missing-pr-title
  (testing "a PR payload missing its title names the field"
    (let [fixtures (-> (base-fixtures)
                       (assoc pr-url (resp 200 (dissoc (pr-body) "title"))))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (= "title" (:field (ex-data err)))))))

(deftest malformed-run-head-sha
  (testing "a run with a malformed head SHA names the field"
    (let [bad-run (assoc (run-object) "head_sha" "not-a-sha")
          fixtures (-> (base-fixtures)
                       (assoc runs-url (resp 200 {"total_count" 1 "workflow_runs" [bad-run]})))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (= "head_sha" (:field (ex-data err)))))))

(deftest pr-number-mismatch-is-operational
  (testing "a payload PR number differing from the requested PR is rejected"
    (let [fixtures (-> (base-fixtures)
                       (assoc pr-url (resp 200 (assoc (pr-body) "number" 8))))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (str/includes? (.getMessage err) "PR identity mismatch")))))

;; ------------------------------------------------------------------
;; T2: ETag cache

(defn- etag-cache-with
  [key entry]
  (atom {:entries {key entry} :order [key]}))

(deftest stale-etag-cache-hit-is-operational
  (testing "a 304 for an entry recorded under moved base/head is an operational failure"
    (let [key ["synth-org" "synth-repo" "changed files" files-url]
          cache (etag-cache-with key {:etag "\"etag-files\""
                                       :body (files-page-1)
                                       :base sha-stale
                                       :head sha-head
                                       :next-url nil})
          fixtures (-> (base-fixtures)
                       (assoc files-url (resp 304 nil {"etag" ["\"etag-files\""]})))
          err (try (gh-adapter/observe! (base-opts fixtures {:etag-cache cache}))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (str/includes? (.getMessage err) "Stale ETag cache hit")))))

(deftest etag-304-reuses-cache-under-same-shas
  (testing "a 304 with a current entry reuses the cached page body"
    (let [key ["synth-org" "synth-repo" "changed files" files-url]
          cache (etag-cache-with key {:etag "\"etag-files\""
                                       :body [{"filename" "src/cached.clj" "status" "added"}]
                                       :base sha-base
                                       :head sha-head
                                       :next-url files-url-p2})
          requested (:fetch/requested (gh-adapter/fixture-fetch {}))
          ff (gh-adapter/fixture-fetch (-> (base-fixtures)
                                           (assoc files-url
                                                  (resp 304 nil {"etag" ["\"etag-files\""]}))))
          ;; rebuild with the same requested atom is unnecessary; use ff directly
          obs (gh-adapter/observe! (base-opts (-> (base-fixtures)
                                                  (assoc files-url
                                                         (resp 304 nil {"etag" ["\"etag-files\""]})))
                                             {:etag-cache cache
                                              :fetch-fn (:fetch/fn ff)}))]
      (is (= :complete (:observation/status obs)))
      (is (= ["src/cached.clj" "src/b.clj" "src/new.clj"]
             (mapv :change/new-path (get-in obs [:value :changes :changes])))
          "the cached page is reused and pagination continues from its recorded next page")
      (is (= 2 (get-in obs [:value :changes :pagination/pages])))
      (is (some #(= files-url %) @(:fetch/requested ff))
          "the conditional request was still issued"))))

;; ------------------------------------------------------------------
;; T2: nested jobs failure

(deftest jobs-page-failure-names-run
  (testing "a run's job list failing to paginate keeps the run with jobs incomplete"
    (let [fixtures (-> (base-fixtures)
                       (assoc run-jobs-url
                              (resp 200 {"total_count" 2 "jobs" [(job-object)]}
                                     {"link" [(link-next run-jobs-url-p2)]}))
                       (assoc run-jobs-url-p2 [(resp 503 nil) (resp 503 nil) (resp 503 nil)]))
          obs (gh-adapter/observe! (base-opts fixtures {:config {:max-attempts 3}}))]
      (is (= :observation/incomplete (:observation/status obs)))
      (let [step (:observation/failing-step obs)]
        (is (= :jobs (:collection step)))
        (is (= 2 (:page step)))
        (is (= 101 (:github/run-id step))))
      (let [runs (get-in obs [:value :runs :runs])]
        (is (= 1 (count runs)) "the failing run stays in the record")
        (is (= 101 (:github/run-id (first runs))))
        (is (= false (get-in (first runs) [:github/jobs :pagination/complete])))
        (is (= [] (get-in (first runs) [:github/jobs :jobs])))))))

;; ------------------------------------------------------------------
;; T3: identity — credentials, trust, forged producers

(deftest authenticated-observation-records-token-identity
  (testing "AXIOM_GITHUB_TOKEN resolves the token identity once per observation"
    (let [ff (gh-adapter/fixture-fetch (auth-fixtures "synth-user"))
          obs (gh-adapter/observe! (base-opts (auth-fixtures "synth-user")
                                             {:env {"AXIOM_GITHUB_TOKEN" "synth-token-1"}
                                              :fetch-fn (:fetch/fn ff)}))]
      (is (= :complete (:observation/status obs)))
      (is (= :trust/provider-authenticated (:trust obs)))
      (is (= {:producer/id "axiom-github-observer"
              :producer/authenticated? true
              :github/login "synth-user"}
             (:producer obs)))
      (is (= 1 (count (filter #(= user-url %) @(:fetch/requested ff))))
          "token identity resolved exactly once"))))

(deftest token-file-credential
  (testing "--token-file resolves an authenticated identity; raw tokens are never arguments"
    (let [f (File/createTempFile "axiom-token" ".txt")]
      (try
        (spit f "synth-token-2")
        (let [obs (gh-adapter/observe! (base-opts (auth-fixtures "synth-file-user")
                                                 {:token-file (.getPath f)}))]
          (is (= :trust/provider-authenticated (:trust obs)))
          (is (= "synth-file-user" (get-in obs [:producer :github/login]))))
        (finally (.delete f))))
    (testing "a raw :token argument is rejected as invalid input"
      (is (= :invalid (error-kind #(gh-adapter/observe! (base-opts (base-fixtures)
                                                                 {:token "raw-token"}))))))
    (testing "an unreadable token file is invalid input"
      (is (= :invalid (error-kind #(gh-adapter/observe! (base-opts (base-fixtures)
                                                                  {:token-file "/tmp/axiom-no-such-token-xyz"}))))))))

(deftest credential-rejected-is-operational
  (testing "a 401 on the identity endpoint names the rejected credential"
    (let [fixtures (assoc (auth-fixtures "synth-user") user-url (resp 401 nil))
          err (try (gh-adapter/observe! (base-opts fixtures
                                                  {:env {"AXIOM_GITHUB_TOKEN" "synth-bad-token"}}))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (= 401 (:status (ex-data err))))
      (is (not (str/includes? (.getMessage err) "synth-bad-token"))
          "the token value never appears in errors"))))

(deftest forged-producer-claims-are-invalid
  (testing "producer claims contradicting the resolved identity fail validation"
    (let [obs (gh-adapter/observe! (base-opts (auth-fixtures "synth-user")
                                             {:env {"AXIOM_GITHUB_TOKEN" "synth-token-1"}}))]
      (is (= obs (gh/validate-observation! obs)) "the adapter's own output validates")
      (testing "authenticated flag dropped while keeping the login"
        (is (= :invalid (error-kind
                         #(gh/validate-observation!
                           (assoc-in obs [:producer :producer/authenticated?] false))))))
      (testing "trust mark swapped to anonymous while authenticated"
        (is (= :invalid (error-kind
                         #(gh/validate-observation! (assoc obs :trust :trust/provider-observed))))))
      (testing ":trust/remote-ci is never producible here"
        (is (= :invalid (error-kind
                         #(gh/validate-observation! (assoc obs :trust :trust/remote-ci)))))))))

;; ------------------------------------------------------------------
;; T3: workflow/run identity consistency

(deftest duplicate-run-id-is-operational
  (testing "two pages yielding the same run ID is an identity inconsistency"
    (let [fixtures (-> (base-fixtures)
                       (assoc runs-url (resp 200 {"total_count" 2 "workflow_runs" [(run-object) (run-object)]})))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (str/includes? (.getMessage err) "duplicate run ID")))))

(deftest run-conclusion-must-match-latest-attempt
  (testing "a run conclusion contradicting its latest attempt is an inconsistency"
    (let [fixtures (-> (base-fixtures)
                       (assoc runs-url
                              (resp 200 {"total_count" 1
                                         "workflow_runs" [(run-object 101 "failure" "success")]})))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (str/includes? (.getMessage err) "latest attempt")))))

(deftest job-conclusion-must-match-selected-attempt
  (testing "a job conclusion contradicting its selected attempt is an inconsistency"
    (let [fixtures (-> (base-fixtures)
                       (assoc run-jobs-url
                              (resp 200 {"total_count" 1 "jobs" [(job-object "failure" "success")]})))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (str/includes? (.getMessage err) "selected attempt")))))

(deftest job-run-association-mismatch
  (testing "a job claiming a different parent run is an identity inconsistency"
    (let [fixtures (-> (base-fixtures)
                       (assoc run-jobs-url
                              (resp 200 {"total_count" 1
                                         "jobs" [(assoc (job-object) "run_id" 999)]})))
          err (try (gh-adapter/observe! (base-opts fixtures)) nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (= :operational (:axiom/error (ex-data err))))
      (is (str/includes? (.getMessage err) "run association")))))

(deftest job-conclusion-may-differ-from-run-aggregate
  (testing "a passing job in a failing run is a normal observation, not an inconsistency"
    (let [fixtures (-> (base-fixtures)
                       (assoc runs-url
                              (resp 200 {"total_count" 1
                                         "workflow_runs" [(run-object 101 "failure" "failure")]}))
                       (assoc run-attempts-url
                              (resp 200 [(attempt-object run-url 1 "failure")])))
          obs (gh-adapter/observe! (base-opts fixtures))
          run (first (get-in obs [:value :runs :runs]))
          job (first (get-in run [:github/jobs :jobs]))]
      (is (= :complete (:observation/status obs)))
      (is (= :failure (:github/conclusion run)))
      (is (= :success (:github/conclusion job))))))

(deftest unknown-conclusions-map-to-unknown
  (testing "unknown provider conclusions become :unknown, never success"
    (let [fixtures (-> (base-fixtures)
                       (assoc runs-url
                              (resp 200 {"total_count" 1
                                         "workflow_runs" [(run-object 101 "in_progress" "in_progress")]}))
                       (assoc run-attempts-url
                              (resp 200 [(attempt-object run-url 1 "in_progress")]))
                       (assoc run-jobs-url
                              (resp 200 {"total_count" 1 "jobs" [(job-object "in_progress" "in_progress")]}))
                       (assoc job-attempts-url
                              (resp 200 [(attempt-object job-url 1 "in_progress")])))
          obs (gh-adapter/observe! (base-opts fixtures))
          run (first (get-in obs [:value :runs :runs]))
          job (first (get-in run [:github/jobs :jobs]))]
      (is (= :complete (:observation/status obs)))
      (is (= :unknown (:github/conclusion run)))
      (is (= :unknown (:github/conclusion job))))))

;; ------------------------------------------------------------------
;; Fixture fetch contract and input validation

(deftest fixture-fetch-unknown-url
  (testing "a request with no fixture is an operational failure naming the URL"
    (let [fetch-fn (:fetch/fn (gh-adapter/fixture-fetch {}))]
      (is (= :operational (error-kind #(fetch-fn {:fetch/url "https://api.github.com/nope"
                                                 :fetch/headers {}})))))))

(deftest invalid-inputs-are-invalid
  (testing "malformed owner/repo/pr are :invalid, never :operational"
    (is (= :invalid (error-kind #(gh-adapter/observe! (base-opts (base-fixtures)
                                                                {:owner ""})))))
    (is (= :invalid (error-kind #(gh-adapter/observe! (base-opts (base-fixtures)
                                                                {:repo "has space"})))))
    (is (= :invalid (error-kind #(gh-adapter/observe! (base-opts (base-fixtures)
                                                                {:pr 0})))))))

(deftest adapter-is-get-only
  (testing "the adapter has no code path for mutating HTTP methods"
    (let [src (slurp "src/axiom/adapters/github.clj")]
      (is (= 1 (count (re-seq #"\(\.GET\)" src))) "exactly one GET constructor call")
      (doseq [method ["POST" "PUT" "PATCH" "DELETE"]]
        (is (nil? (re-find (re-pattern (str "\\." method "\\b")) src))
            (str "no ." method " request construction"))))))

;; ------------------------------------------------------------------
;; The JSON reader (used by the real network fetch)

(deftest json-reader
  (testing "objects, arrays, strings, escapes, unicode and numbers"
    (is (= {"a" [1 2.5 -3 true false nil {"b" "x\nyé\"\\"}]}
           (json-parse "{\"a\": [1, 2.5, -3, true, false, null, {\"b\": \"x\\ny\\u00e9\\\"\\\\\"}]}")))
    (is (= {} (json-parse "  { } ")))
    (is (= [] (json-parse "[]"))))
  (testing "malformed JSON is an operational failure"
    (is (= :operational (error-kind #(json-parse "{bad"))))
    (is (= :operational (error-kind #(json-parse "{\"a\": 1} trailing"))))))
