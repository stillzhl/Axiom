(ns axiom.github-test
  "Tests for the pure `axiom.github` port (spec 0004 T1, T4 pure part).

   Every repository, SHA, login, workflow and URL is invented for
   tests: `synth-org/synth-repo`, synthetic 40-hex SHAs, synthetic
   logins. No real repository identities, no live credentials, no
   network access."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.github :as gh]
            [clojure.string :as str]))

;; ------------------------------------------------------------------
;; Synthetic fixture builders (invented identities only)

(def ^:private sha-base (apply str (repeat 40 "b")))
(def ^:private sha-head (apply str (repeat 40 "c")))
(def ^:private sha-old (apply str (repeat 40 "d")))
(def ^:private sha-other (apply str (repeat 40 "e")))

(def ^:private api-base "https://api.github.com/repos/synth-org/synth-repo")
(def ^:private pr-url (str api-base "/pulls/7"))
(def ^:private files-url (str api-base "/pulls/7/files"))
(def ^:private runs-url (str api-base "/actions/runs"))

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(defn- mk-attempt [run-id n conclusion]
  {:github/attempt n
   :github/conclusion conclusion
   :evidence/url (str api-base "/actions/runs/" run-id "/attempts/" n)})

(defn- mk-job
  "attempt-conclusions: vector of conclusions, attempt 1..n. The job
   conclusion must be the selected attempt's conclusion."
  [job-id job-name conclusion selected-attempt attempt-conclusions axes]
  {:github/job-id job-id
   :github/job-name job-name
   :github/conclusion conclusion
   :github/selected-attempt selected-attempt
   :github/attempts (vec (map-indexed (fn [i c] (mk-attempt 101 (inc i) c))
                                      attempt-conclusions))
   :matrix/axes axes
   :evidence/url (str api-base "/actions/jobs/" job-id)})

(defn- mk-run
  "attempt-conclusions: vector of conclusions, attempt 1..n. The run
   conclusion must be the latest attempt's conclusion."
  [run-id head-sha run-conclusion attempt-conclusions jobs]
  {:github/run-id run-id
   :github/workflow-name "synth-ci"
   :github/workflow-path ".github/workflows/synth-ci.yml"
   :github/event "pull_request"
   :github/actor "synth-actor"
   :github/head-sha head-sha
   :github/conclusion run-conclusion
   :github/attempts (vec (map-indexed (fn [i c] (mk-attempt run-id (inc i) c))
                                      attempt-conclusions))
   :github/jobs {:pagination/complete true :pagination/pages 1 :jobs jobs}
   :evidence/url (str api-base "/actions/runs/" run-id)})

(defn- mk-review [review-id reviewer state commit-sha]
  {:github/review-id review-id
   :github/reviewer reviewer
   :github/state state
   :github/commit-sha commit-sha
   :github/submitted-at 1750000000
   :evidence/url (str api-base "/pulls/7/reviews/" review-id)})

(defn- mk-artifact [artifact-id artifact-name]
  {:github/artifact-name artifact-name
   :github/digest nil
   :github/expires-at nil
   :evidence/url (str api-base "/actions/artifacts/" artifact-id)})

(defn- mk-change [kind old-path new-path]
  {:change/kind kind
   :path/kind :file
   :change/old-path old-path
   :change/new-path new-path
   :change/old-mode "100644"
   :change/new-mode "100644"
   :path/target nil
   :path/commit nil
   :path/unsafe-reason nil})

(defn- base-data []
  {:observation/status :complete
   :producer {:producer/id "axiom-github-observer"
              :producer/authenticated? false
              :github/login nil}
   :trust :trust/provider-observed
   :subject {:github/owner "synth-org"
             :github/repo "synth-repo"
             :github/pr 7
             :git/base sha-base
             :git/head sha-head
             :github/head-repo {:github/owner "synth-org"
                                :github/repo "synth-repo"}}
   :value {:pr/record {:github/pr-title "Synthetic change"
                       :github/pr-state :open
                       :github/draft? false
                       :github/merge-state :clean
                       :github/mergeable? true
                       :evidence/url pr-url}
           :changes {:pagination/complete true
                     :pagination/pages 1
                     :changes [(mk-change :modified "src/a.clj" "src/a.clj")
                               (mk-change :added nil "src/b.clj")]}
           :runs {:pagination/complete true
                  :pagination/pages 1
                  :runs [(mk-run 101 sha-head :success [:success]
                                 [(mk-job 201 "build" :success 1 [:success] {})])]}
           :reviews {:pagination/complete true :pagination/pages 1 :reviews []}
           :artifacts {:pagination/complete true :pagination/pages 1 :artifacts []}}
   :provenance {:api/urls [pr-url files-url runs-url]
                :api/etags {pr-url "etag-pr-1"
                            files-url nil
                            runs-url "etag-runs-1"}
                :rate-limit {:rate-limit/limit 5000
                             :rate-limit/remaining 4990
                             :rate-limit/reset 1750003600}
                :http/client {:http/timeout-ms 10000
                              :http/user-agent "axiom-github-observer/0.1.0-offline"
                              :http/api-version "2022-11-28"}}
   :scope {:github/owner "synth-org"
           :github/repo "synth-repo"
           :github/pr 7
           :revisions {:base sha-base :head sha-head}}})

(defn- gate-specs []
  [{:gate/id :pr-identity}
   {:gate/id :required-checks
    :required/checks [{:required/job "build"
                       :selection/rule :all-required-matrix-jobs}]}
   {:gate/id :approvals :required/approvals 1}
   {:gate/id :merge-state}])

(defn- with-approval [data]
  (assoc-in data [:value :reviews :reviews]
            [(mk-review 301 "synth-reviewer" :approved sha-head)]))

;; ------------------------------------------------------------------
;; Schema validation

(deftest observation-validation
  (testing "a well-formed anonymous observation validates"
    (let [obs (gh/build-observation (base-data))]
      (is (= :complete (:observation/status obs)))
      (is (= :github-observation (:observation/kind obs)))
      (is (= 1 (:observation/schema-version obs)))))
  (testing "a well-formed authenticated observation validates"
    (let [obs (gh/build-observation
               (-> (base-data)
                   (assoc-in [:producer :producer/authenticated?] true)
                   (assoc-in [:producer :github/login] "synth-user")
                   (assoc :trust :trust/provider-authenticated)))]
      (is (= :complete (:observation/status obs)))
      (is (= "synth-user" (get-in obs [:producer :github/login])))))
  (testing "unknown top-level fields are rejected"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc (base-data) :bogus 1))))))
  (testing "unknown observation kind is rejected"
    (is (= :invalid (error-kind #(gh/validate-observation!
                                   (assoc (gh/build-observation (base-data))
                                          :observation/kind :git-observation))))))
  (testing "unknown status is rejected"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc (base-data) :observation/status :weird))))))
  (testing "validate-observation! is idempotent on valid observations"
    (is (= (gh/build-observation (base-data))
           (gh/validate-observation! (gh/build-observation (base-data)))))))

;; ------------------------------------------------------------------
;; Trust marks and forged producers

(deftest trust-marks
  (testing "anonymous observations carry provider-observed"
    (is (= :trust/provider-observed (:trust (gh/build-observation (base-data))))))
  (testing "authenticated observations carry provider-authenticated"
    (let [obs (gh/build-observation
               (-> (base-data)
                   (assoc-in [:producer :producer/authenticated?] true)
                   (assoc-in [:producer :github/login] "synth-user")
                   (assoc :trust :trust/provider-authenticated)))]
      (is (= :trust/provider-authenticated (:trust obs)))))
  (testing ":trust/remote-ci is rejected — it belongs to the M4 path"
    (is (= :invalid
           (error-kind #(gh/build-observation
                         (assoc (base-data) :trust :trust/remote-ci)))))
    (is (= :invalid
           (error-kind #(gh/build-observation
                         (-> (base-data)
                             (assoc-in [:producer :producer/authenticated?] true)
                             (assoc-in [:producer :github/login] "synth-user")
                             (assoc :trust :trust/remote-ci)))))))
  (testing "unknown trust marks are rejected"
    (is (= :invalid
           (error-kind #(gh/build-observation
                         (assoc (base-data) :trust :trust/pinkie-promise)))))))

(deftest forged-producer
  (testing "authenticated without a resolved token identity is forged"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (-> (base-data)
                                       (assoc-in [:producer :producer/authenticated?] true)
                                       (assoc :trust :trust/provider-authenticated)))))))
  (testing "authenticated trust without the authenticated flag is forged"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (-> (base-data)
                                       (assoc-in [:producer :github/login] "synth-user")
                                       (assoc :trust :trust/provider-authenticated)))))))
  (testing "anonymous with a token identity is forged"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc-in (base-data) [:producer :github/login] "synth-user"))))))
  (testing "anonymous with authenticated trust is forged"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc (base-data) :trust :trust/provider-authenticated))))))
  (testing "unknown producer id is rejected"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc-in (base-data) [:producer :producer/id] "evil")))))))

;; ------------------------------------------------------------------
;; Pagination data model

(deftest pagination-model
  (testing "every collection carries completeness markers"
    (let [obs (gh/build-observation (base-data))]
      (doseq [k [:changes :runs :reviews :artifacts]]
        (is (true? (get-in obs [:value k :pagination/complete])))
        (is (pos? (get-in obs [:value k :pagination/pages]))))))
  (testing "an incomplete observation names the failing collection and page"
    (let [obs (gh/build-observation
               (-> (base-data)
                   (assoc :observation/status :observation/incomplete)
                   (assoc-in [:subject :git/head] nil)
                   (assoc-in [:scope :revisions :head] nil)
                   (assoc-in [:value :runs :pagination/complete] false)
                   (assoc-in [:value :runs :pagination/pages] 2)
                   (assoc-in [:value :runs :runs] [])
                   (assoc :observation/failing-step {:collection :runs
                                                     :page 3
                                                     :reason "rate-limit-exhausted"
                                                     :github/run-id nil})))]
      (is (= :observation/incomplete (:observation/status obs)))
      (is (= :runs (get-in obs [:observation/failing-step :collection])))
      (is (= 3 (get-in obs [:observation/failing-step :page])))
      (is (false? (get-in obs [:value :runs :pagination/complete])))))
  (testing "an incomplete collection must not carry partial items"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (-> (base-data)
                                       (assoc :observation/status :observation/incomplete)
                                       (assoc-in [:subject :git/head] nil)
                                       (assoc-in [:scope :revisions :head] nil)
                                       (assoc-in [:value :changes :pagination/complete] false)
                                       (assoc :observation/failing-step
                                              {:collection :changes :page 2
                                               :reason "page-fetch-failed"
                                               :github/run-id nil})))))))
  (testing "the named failing collection must be marked incomplete"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (-> (base-data)
                                       (assoc :observation/status :observation/incomplete)
                                       (assoc-in [:subject :git/head] nil)
                                       (assoc-in [:scope :revisions :head] nil)
                                       (assoc :observation/failing-step
                                              {:collection :artifacts :page 1
                                               :reason "page-fetch-failed"
                                               :github/run-id nil})))))))
  (testing "an incomplete observation without a failing step is rejected"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (-> (base-data)
                                       (assoc :observation/status :observation/incomplete)
                                       (assoc-in [:subject :git/head] nil)
                                       (assoc-in [:scope :revisions :head] nil)))))))
  (testing "a jobs failure names the run whose job list failed"
    (let [obs (gh/build-observation
               (-> (base-data)
                   (assoc :observation/status :observation/incomplete)
                   (assoc-in [:subject :git/head] nil)
                   (assoc-in [:scope :revisions :head] nil)
                   (assoc-in [:value :runs :runs 0 :github/jobs :pagination/complete] false)
                   (assoc-in [:value :runs :runs 0 :github/jobs :pagination/pages] 1)
                   (assoc-in [:value :runs :runs 0 :github/jobs :jobs] [])
                   (assoc :observation/failing-step {:collection :jobs
                                                     :page 2
                                                     :reason "jobs-page-fetch-failed"
                                                     :github/run-id 101})))]
      (is (= :jobs (get-in obs [:observation/failing-step :collection])))
      (is (= 101 (get-in obs [:observation/failing-step :github/run-id])))))
  (testing "a jobs failure naming an unknown run is rejected"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (-> (base-data)
                                       (assoc :observation/status :observation/incomplete)
                                       (assoc-in [:subject :git/head] nil)
                                       (assoc-in [:scope :revisions :head] nil)
                                       (assoc :observation/failing-step
                                              {:collection :jobs :page 2
                                               :reason "jobs-page-fetch-failed"
                                               :github/run-id 999}))))))))

;; ------------------------------------------------------------------
;; Identity rules

(deftest identity-rules
  (testing "repository owner and name are validated"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc-in (base-data) [:subject :github/owner] "-bad-")))))
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc-in (base-data) [:subject :github/repo] ""))))))
  (testing "PR number must be positive"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc-in (base-data) [:subject :github/pr] 0))))))
  (testing "base/head must be 40-hex SHAs on complete observations"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc-in (base-data) [:subject :git/head] "not-a-sha"))))))
  (testing "scope must agree with subject"
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc-in (base-data) [:scope :github/repo] "other-repo")))))
    (is (= :invalid (error-kind #(gh/build-observation
                                   (assoc-in (base-data) [:scope :revisions :head] sha-other)))))))

;; ------------------------------------------------------------------
;; Provider vocabulary mappings

(deftest provider-vocabulary
  (testing "check conclusions map, unknown values become :unknown never success"
    (is (= :success (gh/conclusion-from-provider "success")))
    (is (= :failure (gh/conclusion-from-provider "failure")))
    (is (= :skipped (gh/conclusion-from-provider "skipped")))
    (is (= :timed-out (gh/conclusion-from-provider "timed_out")))
    (is (= :action-required (gh/conclusion-from-provider "action_required")))
    (is (= :unknown (gh/conclusion-from-provider "some_future_conclusion")))
    (is (= :unknown (gh/conclusion-from-provider nil))))
  (testing "file statuses map, unknown values are nil (malformed payload)"
    (is (= :added (gh/change-kind-from-provider-status "added")))
    (is (= :modified (gh/change-kind-from-provider-status "modified")))
    (is (= :removed (gh/change-kind-from-provider-status "removed")))
    (is (= :renamed (gh/change-kind-from-provider-status "renamed")))
    (is (nil? (gh/change-kind-from-provider-status "copied")))
    (is (nil? (gh/change-kind-from-provider-status nil))))
  (testing "merge states map, unknown values become :unknown never :clean"
    (is (= :clean (gh/merge-state-from-provider "clean")))
    (is (= :dirty (gh/merge-state-from-provider "dirty")))
    (is (= :blocked (gh/merge-state-from-provider "blocked")))
    (is (= :unknown (gh/merge-state-from-provider "has_hooks")))
    (is (= :unknown (gh/merge-state-from-provider "weird"))))
  (testing "review states map, unknown values are nil"
    (is (= :dismissed (gh/review-state-from-provider "DISMISSED")))
    (is (= :approved (gh/review-state-from-provider "APPROVED")))
    (is (nil? (gh/review-state-from-provider "PENDING")))))

;; ------------------------------------------------------------------
;; Change entries (0003 vocabulary, provider kinds)

(deftest change-entries
  (testing "added/modified/removed/renamed validate"
    (is (= :complete
           (:observation/status
            (gh/build-observation
             (assoc-in (base-data) [:value :changes :changes]
                       [(mk-change :added nil "new.clj")
                        (mk-change :modified "a.clj" "a.clj")
                        (mk-change :removed "gone.clj" nil)
                        (mk-change :renamed "old.clj" "new.clj")]))))))
  (testing "inconsistent change paths are rejected"
    (doseq [[kind old new] [[:added "a.clj" "b.clj"]
                            [:removed "a.clj" "b.clj"]
                            [:modified "a.clj" "b.clj"]
                            [:renamed "a.clj" "a.clj"]]]
      (is (= :invalid
             (error-kind #(gh/build-observation
                           (assoc-in (base-data) [:value :changes :changes]
                                     [(mk-change kind old new)]))))
          (str kind " with inconsistent paths"))))
  (testing "unknown change kinds are rejected"
    (let [data (assoc-in (base-data) [:value :changes :changes]
                         [(assoc (mk-change :added nil "x.clj")
                                 :change/kind :copied)])]
      (is (= :invalid (error-kind #(gh/build-observation data))))))
  (testing "non-file path kinds are rejected (provider lists carry no typing)"
    (let [data (assoc-in (base-data) [:value :changes :changes]
                         [(assoc (mk-change :added nil "x.clj")
                                 :path/kind :symlink
                                 :path/target "y.clj")])]
      (is (= :invalid (error-kind #(gh/build-observation data)))))))

;; ------------------------------------------------------------------
;; Run / job / attempt identity

(deftest attempt-identity
  (testing "run conclusion must be the latest attempt's conclusion"
    (let [data (assoc-in (base-data) [:value :runs :runs 0]
                         (mk-run 101 sha-head :failure
                                 [:failure :success]
                                 [(mk-job 201 "build" :success 1 [:success] {})]))]
      (is (= :invalid (error-kind #(gh/build-observation data))))))
  (testing "a rerun keeps its failed attempts and the latest wins"
    (let [data (assoc-in (base-data) [:value :runs :runs 0]
                         (mk-run 101 sha-head :success
                                 [:failure :success]
                                 [(mk-job 201 "build" :success 2 [:failure :success] {})]))
          obs (gh/build-observation data)]
      (is (= :complete (:observation/status obs)))
      (is (= 2 (get-in obs [:value :runs :runs 0 :github/jobs :jobs 0 :github/selected-attempt])))))
  (testing "job conclusion must be the selected attempt's conclusion"
    (let [data (assoc-in (base-data) [:value :runs :runs 0 :github/jobs :jobs]
                         [(mk-job 201 "build" :failure 1 [:success] {})])]
      (is (= :invalid (error-kind #(gh/build-observation data))))))
  (testing "selected attempt must be enumerated"
    (let [data (assoc-in (base-data) [:value :runs :runs 0 :github/jobs :jobs]
                         [(mk-job 201 "build" :success 3 [:success] {})])]
      (is (= :invalid (error-kind #(gh/build-observation data))))))
  (testing "duplicate attempt numbers are rejected"
    (let [data (assoc-in (base-data) [:value :runs :runs 0 :github/attempts]
                         [(mk-attempt 101 1 :success)
                          (mk-attempt 101 1 :success)])]
      (is (= :invalid (error-kind #(gh/build-observation data))))))
  (testing "unknown conclusion keywords are rejected"
    (let [data (assoc-in (base-data) [:value :runs :runs 0 :github/conclusion]
                         :mystery)]
      (is (= :invalid (error-kind #(gh/build-observation data)))))))

;; ------------------------------------------------------------------
;; check-pr gates

(defn- outcomes [report]
  (into {} (map (juxt :gate/id :gate/outcome) (:gates report))))

(defn- gate-by-id [report id]
  (first (filter #(= id (:gate/id %)) (:gates report))))

(deftest check-pr-all-green
  (let [report (gh/check-pr (gh/build-observation (with-approval (base-data)))
                            (gate-specs))]
    (testing "every gate passes and can-merge is advisory yes"
      (is (= {:pr-identity :pass :required-checks :pass
              :approvals :pass :merge-state :pass}
             (outcomes report)))
      (is (= :yes (get-in report [:can-merge :can-merge/advisory])))
      (is (true? (get-in report [:can-merge :can-merge/advisory-only]))))
    (testing "outcomes are bound to the exact base/head SHAs with evidence links"
      (is (= sha-base (get-in report [:subject :git/base])))
      (is (= sha-head (get-in report [:subject :git/head])))
      (doseq [gate (:gates report)]
        (is (seq (:gate/evidence gate)) (str (:gate/id gate) " carries evidence"))
        (doseq [item (:gate/evidence gate)]
          (is (str/starts-with? (:evidence/url item) api-base))
          (is (= sha-head (:evidence/sha item))))))
    (testing "the report is not stale against its own observation"
      (is (false? (gh/stale? report (gh/build-observation (with-approval (base-data))))))
      (is (= :current (gh/report-status report (gh/build-observation (with-approval (base-data)))))))))

(deftest check-pr-failures
  (testing "a failed required check fails its gate and can-merge is advisory no"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (-> (with-approval (base-data))
                        (assoc-in [:value :runs :runs 0 :github/jobs :jobs]
                                  [(mk-job 201 "build" :failure 1 [:failure] {})])))
                  (gate-specs))]
      (is (= :fail (:gate/outcome (gate-by-id report :required-checks))))
      (is (= :no (get-in report [:can-merge :can-merge/advisory])))))
  (testing "a draft PR fails the pr-identity gate"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (-> (with-approval (base-data))
                        (assoc-in [:value :pr/record :github/draft?] true)))
                  (gate-specs))]
      (is (= :fail (:gate/outcome (gate-by-id report :pr-identity))))
      (is (= :no (get-in report [:can-merge :can-merge/advisory])))))
  (testing "a dirty merge state fails the merge-state gate"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (-> (with-approval (base-data))
                        (assoc-in [:value :pr/record :github/merge-state] :dirty)))
                  (gate-specs))]
      (is (= :fail (:gate/outcome (gate-by-id report :merge-state))))))
  (testing "a blocked merge state is unknown, never pass"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (-> (with-approval (base-data))
                        (assoc-in [:value :pr/record :github/merge-state] :blocked)))
                  (gate-specs))]
      (is (= :unknown (:gate/outcome (gate-by-id report :merge-state))))
      (is (= :unknown (get-in report [:can-merge :can-merge/advisory]))))))

(deftest check-pr-unknowns
  (testing "a skipped required job makes the gate unknown, never pass"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (-> (with-approval (base-data))
                        (assoc-in [:value :runs :runs 0 :github/jobs :jobs]
                                  [(mk-job 201 "build" :skipped 1 [:skipped] {})])))
                  (gate-specs))]
      (is (= :unknown (:gate/outcome (gate-by-id report :required-checks))))
      (is (= :unknown (get-in report [:can-merge :can-merge/advisory])))))
  (testing "an unknown check conclusion makes the gate unknown, never success"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (-> (with-approval (base-data))
                        (assoc-in [:value :runs :runs 0 :github/jobs :jobs]
                                  [(mk-job 201 "build" :unknown 1 [:unknown] {})])))
                  (gate-specs))]
      (is (= :unknown (:gate/outcome (gate-by-id report :required-checks))))))
  (testing "a timed-out rerun makes the gate unknown, never allow"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (-> (with-approval (base-data))
                        (assoc-in [:value :runs :runs 0 :github/jobs :jobs]
                                  [(mk-job 201 "build" :timed-out 2 [:failure :timed-out] {})])))
                  (gate-specs))]
      (is (= :unknown (:gate/outcome (gate-by-id report :required-checks))))
      (is (= :unknown (get-in report [:can-merge :can-merge/advisory])))))
  (testing "a missing required job makes the gate unknown"
    (let [report (gh/check-pr (gh/build-observation (with-approval (base-data)))
                              [{:gate/id :required-checks
                                :required/checks [{:required/job "no-such-job"
                                                   :selection/rule :latest}]}])]
      (is (= :unknown (:gate/outcome (gate-by-id report :required-checks)))))))

(deftest check-pr-approvals
  (testing "a dismissed review never counts toward approvals"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (assoc-in (base-data) [:value :reviews :reviews]
                              [(mk-review 301 "synth-reviewer" :dismissed sha-head)]))
                  (gate-specs))]
      (is (= :unknown (:gate/outcome (gate-by-id report :approvals))))
      (is (= :unknown (get-in report [:can-merge :can-merge/advisory])))))
  (testing "an approval on a superseded head SHA never counts"
    (let [report (gh/check-pr
                  (gh/build-observation
                    (assoc-in (base-data) [:value :reviews :reviews]
                              [(mk-review 301 "synth-reviewer" :approved sha-old)]))
                  (gate-specs))]
      (is (= :unknown (:gate/outcome (gate-by-id report :approvals))))))
  (testing "an approval on the head SHA passes"
    (let [report (gh/check-pr (gh/build-observation (with-approval (base-data)))
                              (gate-specs))]
      (is (= :pass (:gate/outcome (gate-by-id report :approvals)))))))

(deftest check-pr-incomplete-observation
  (testing "an incomplete observation makes every gate unknown, never pass"
    (let [data (-> (base-data)
                   (assoc :observation/status :observation/incomplete)
                   (assoc-in [:subject :git/head] nil)
                   (assoc-in [:scope :revisions :head] nil)
                   (assoc-in [:value :runs :pagination/complete] false)
                   (assoc-in [:value :runs :pagination/pages] 1)
                   (assoc-in [:value :runs :runs] [])
                   (assoc :observation/failing-step {:collection :runs :page 2
                                                     :reason "page-fetch-failed"
                                                     :github/run-id nil}))
          report (gh/check-pr (gh/build-observation data) (gate-specs))]
      (is (every? #(= :unknown (:gate/outcome %)) (:gates report)))
      (is (= :unknown (get-in report [:can-merge :can-merge/advisory])))
      (is (false? (:check/complete-observation? report))))))

;; ------------------------------------------------------------------
;; Matrix / attempt selection rules

(deftest matrix-selection
  (let [matrix-jobs [(mk-job 201 "build" :success 1 [:success] {:os "ubuntu-latest" :jdk "17"})
                     (mk-job 202 "build" :success 1 [:success] {:os "macos-latest" :jdk "17"})
                     (mk-job 203 "build" :failure 1 [:failure] {:os "windows-latest" :jdk "17"})]
        data (assoc-in (base-data) [:value :runs :runs 0 :github/jobs :jobs] matrix-jobs)
        obs (gh/build-observation (with-approval data))]
    (testing "all-required-matrix-jobs: one failing job fails the gate"
      (let [report (gh/check-pr obs [{:gate/id :required-checks
                                     :required/checks [{:required/job "build"
                                                        :selection/rule :all-required-matrix-jobs}]}])
            check (first (:gate/checks (gate-by-id report :required-checks)))]
        (is (= :fail (:check/outcome check)))
        (is (= :fail (:gate/outcome (gate-by-id report :required-checks))))
        (is (= :all-required-matrix-jobs (get-in check [:gate/selection :selection/rule])))
        (is (= [201 202 203] (get-in check [:gate/selection :selection/selected])))))
    (testing "named-axes: only the named axis counts"
      (let [report (gh/check-pr obs [{:gate/id :required-checks
                                     :required/checks [{:required/job "build"
                                                        :selection/rule :named-axes
                                                        :selection/axes {:os "ubuntu-latest"}}]}])
            check (first (:gate/checks (gate-by-id report :required-checks)))]
        (is (= :pass (:check/outcome check)))
        (is (= {:os "ubuntu-latest"} (get-in check [:gate/selection :selection/axes])))
        (is (= [201] (get-in check [:gate/selection :selection/selected])))))
    (testing "named-axes with no matching job is unknown"
      (let [report (gh/check-pr obs [{:gate/id :required-checks
                                     :required/checks [{:required/job "build"
                                                        :selection/rule :named-axes
                                                        :selection/axes {:os "plan9"}}]}])]
        (is (= :unknown (:gate/outcome (gate-by-id report :required-checks))))))))

(deftest latest-selection
  (testing ":latest picks the highest selected attempt"
    (let [data (assoc-in (base-data) [:value :runs :runs 0 :github/jobs :jobs]
                         [(mk-job 201 "e2e" :success 2 [:failure :success] {})
                          (mk-job 202 "e2e" :failure 1 [:failure] {})])
          report (gh/check-pr (gh/build-observation (with-approval data))
                              [{:gate/id :required-checks
                                :required/checks [{:required/job "e2e"
                                                   :selection/rule :latest}]}])
          check (first (:gate/checks (gate-by-id report :required-checks)))]
      (is (= :pass (:check/outcome check)))
      (is (= [201] (get-in check [:gate/selection :selection/selected])))))
  (testing ":latest with a tie is ambiguous and unknown"
    (let [data (assoc-in (base-data) [:value :runs :runs 0 :github/jobs :jobs]
                         [(mk-job 201 "e2e" :success 2 [:failure :success] {:os "ubuntu"})
                          (mk-job 202 "e2e" :success 2 [:failure :success] {:os "macos"})])
          report (gh/check-pr (gh/build-observation (with-approval data))
                              [{:gate/id :required-checks
                                :required/checks [{:required/job "e2e"
                                                   :selection/rule :latest}]}])
          check (first (:gate/checks (gate-by-id report :required-checks)))]
      (is (= :unknown (:check/outcome check)))
      (is (str/includes? (get-in check [:gate/selection :selection/note]) "ambiguous"))))
  (testing "runs on other SHAs are ignored and named"
    (let [data (assoc-in (base-data) [:value :runs :runs]
                         [(mk-run 101 sha-head :success [:success]
                                   [(mk-job 201 "build" :success 1 [:success] {})])
                          (mk-run 102 sha-old :failure [:failure]
                                   [(mk-job 201 "build" :failure 1 [:failure] {})])])
          report (gh/check-pr (gh/build-observation (with-approval data))
                              [{:gate/id :required-checks
                                :required/checks [{:required/job "build"
                                                   :selection/rule :all-required-matrix-jobs}]}])
          check (first (:gate/checks (gate-by-id report :required-checks)))]
      (is (= :pass (:check/outcome check)))
      (is (= [102] (get-in check [:gate/selection :selection/ignored-runs]))))))

;; ------------------------------------------------------------------
;; Staleness and fork identity

(deftest stale-on-sha-move
  (let [obs (gh/build-observation (with-approval (base-data)))
        report (gh/check-pr obs (gate-specs))]
    (testing "matching SHAs are current"
      (is (= :current (gh/report-status report obs))))
    (testing "a moved head SHA makes the report stale"
      (let [moved (gh/build-observation
                   (-> (with-approval (base-data))
                       (assoc-in [:subject :git/head] sha-other)
                       (assoc-in [:scope :revisions :head] sha-other)))]
        (is (true? (gh/stale? report moved)))
        (is (= :stale (gh/report-status report moved)))))
    (testing "a moved base SHA makes the report stale"
      (let [moved (gh/build-observation
                   (-> (with-approval (base-data))
                       (assoc-in [:subject :git/base] sha-other)
                       (assoc-in [:scope :revisions :base] sha-other)))]
        (is (= :stale (gh/report-status report moved)))))
    (testing "a different PR number makes the report stale"
      (let [moved (gh/build-observation
                   (-> (with-approval (base-data))
                       (assoc-in [:subject :github/pr] 8)
                       (assoc-in [:scope :github/pr] 8)))]
        (is (= :stale (gh/report-status report moved)))))))

(deftest fork-identity
  (let [fork-data (-> (base-data)
                      (assoc-in [:subject :github/head-repo]
                                {:github/owner "synth-forker" :github/repo "synth-repo"}))
        obs (gh/build-observation fork-data)
        report (gh/check-pr (gh/build-observation (with-approval fork-data))
                            (gate-specs))]
    (testing "fork heads carry the head-repo owner/name"
      (is (= "synth-forker" (get-in obs [:subject :github/head-repo :github/owner])))
      (is (= "synth-org" (get-in obs [:subject :github/owner])))
      (is (= "synth-forker" (get-in report [:subject :github/head-repo :github/owner]))))
    (testing "fork and upstream refs are never conflated"
      (is (not= (get-in report [:subject :github/owner])
                (get-in report [:subject :github/head-repo :github/owner]))))
    (testing "non-fork heads carry the upstream owner/name as head-repo"
      (let [plain (gh/build-observation (base-data))]
        (is (= "synth-org" (get-in plain [:subject :github/head-repo :github/owner])))))))

;; ------------------------------------------------------------------
;; Gate spec validation

(deftest gate-spec-validation
  (testing "check-pr requires a non-empty gate list"
    (is (thrown? clojure.lang.ExceptionInfo
                 (gh/check-pr (gh/build-observation (base-data)) []))))
  (testing "unknown gate ids are rejected"
    (is (= :invalid (error-kind #(gh/check-pr (gh/build-observation (base-data))
                                                [{:gate/id :launch-missiles}]))))
  (testing "duplicate gate ids are rejected"
    (is (= :invalid (error-kind #(gh/check-pr (gh/build-observation (base-data))
                                                [{:gate/id :pr-identity} {:gate/id :pr-identity}])))))
  (testing "unknown selection rules are rejected"
    (is (= :invalid (error-kind #(gh/check-pr (gh/build-observation (base-data))
                                                [{:gate/id :required-checks
                                                  :required/checks [{:required/job "build"
                                                                     :selection/rule :vibes}]}])))))
  (testing "named-axes without axes is rejected"
    (is (= :invalid (error-kind #(gh/check-pr (gh/build-observation (base-data))
                                                [{:gate/id :required-checks
                                                  :required/checks [{:required/job "build"
                                                                     :selection/rule :named-axes
                                                                     :selection/axes {}}]}])))))
  (testing "check-pr validates its observation first"
    (is (= :invalid (error-kind #(gh/check-pr {:bogus true} (gate-specs))))))))
