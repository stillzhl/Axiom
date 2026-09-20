(ns axiom.github
  "Pure port for GitHub provider observations (spec 0004, R1/R5/R6/R8/R9).

   Defines the GitHub observation schema (`:observation/kind
   :github-observation`), the pagination data model (every collection
   carries `:pagination/complete` and `:pagination/pages`; an
   incomplete observation is `:observation/incomplete` and names the
   failing collection and page), the identity rules (repository owner
   and name, PR number, base and head SHAs, fork head-repo owner and
   name, workflow run/job/attempt/artifact identity) and the trust
   marks (`:trust/provider-observed` for anonymous observation,
   `:trust/provider-authenticated` for authenticated observation with
   a resolved token identity). `:trust/remote-ci` is rejected here:
   that mark belongs to the M4 trusted evaluation workflow and is
   never produced by this port.

   Also defines the pure `check-pr` evaluator: per-gate outcomes
   (`:pass` / `:fail` / `:unknown`) bound to exact base/head SHAs with
   exact evidence links, the advisory `can-merge` summary (not
   enforcement, not a merge, not a published check), explicit
   matrix/attempt selection rules recorded in the report, and the
   `:stale` rule — a report whose base/head SHAs no longer match a
   fresh observation is stale, never silently current.

   No network access, no I/O, no new production dependencies: the
   `axiom.adapters.github` adapter (spec T2) collects provider
   payloads as untrusted text, builds maps with the constructors here,
   and every map is validated with `validate-observation!` before it
   leaves the adapter.

   Error contract: schema/shape violations throw `:invalid` (via
   `axiom.model/invalid!`); unknown or malformed observations can
   never enter the ledger or admit actions. Unknown check-conclusion
   values map to `:unknown` — never to success."

  (:require [axiom.contract :as contract]
            [axiom.model :as model]
            [clojure.string :as str]))

;; ------------------------------------------------------------------
;; Error helpers

(defn- ensure! [condition message data]
  (when-not condition (model/invalid! message data)))

(defn- shape! [value fields context]
  (ensure! (map? value) "Expected a map" {:context context})
  (ensure! (= (set (keys value)) fields) "Missing or unknown fields"
           {:context context :expected (sort fields) :actual (sort-by str (keys value))}))

;; ------------------------------------------------------------------
;; Field sets (the exact observation shape)

(def observation-fields
  #{:observation/schema-version :observation/kind :observation/status
    :producer :trust :subject :value :provenance :scope})

(def observation-fields-incomplete
  (conj observation-fields :observation/failing-step))

(def producer-fields #{:producer/id :producer/authenticated? :github/login})
(def subject-fields #{:github/owner :github/repo :github/pr :git/base :git/head :github/head-repo})
(def head-repo-fields #{:github/owner :github/repo})
(def value-fields #{:pr/record :changes :runs :reviews :artifacts})
(def pr-record-fields #{:github/pr-title :github/pr-state :github/draft?
                        :github/merge-state :github/mergeable? :evidence/url})
(def run-fields #{:github/run-id :github/workflow-name :github/workflow-path
                  :github/event :github/actor :github/head-sha
                  :github/conclusion :github/attempts :github/jobs :evidence/url})
(def attempt-fields #{:github/attempt :github/conclusion :evidence/url})
(def job-fields #{:github/job-id :github/job-name :github/conclusion
                   :github/selected-attempt :github/attempts :matrix/axes :evidence/url})
(def review-fields #{:github/review-id :github/reviewer :github/state
                     :github/commit-sha :github/submitted-at :evidence/url})
(def artifact-fields #{:github/artifact-name :github/digest :github/expires-at :evidence/url})
(def provenance-fields #{:api/urls :api/etags :rate-limit :http/client})
(def rate-limit-fields #{:rate-limit/limit :rate-limit/remaining :rate-limit/reset})
(def http-client-fields #{:http/timeout-ms :http/user-agent :http/api-version})
(def scope-fields #{:github/owner :github/repo :github/pr :revisions})
(def scope-revisions-fields #{:base :head})
(def failing-step-fields #{:collection :page :reason :github/run-id})

;; The change-entry field vocabulary is the 0003 local observation's
;; vocabulary (axiom.git/change-entry-fields); the change kinds follow
;; the provider's file-status words (requirements R1): added, modified,
;; removed, renamed. The provider file list carries no symlink,
;; submodule or mode typing, so :path/kind is always :file here and
;; the unused 0003 fields must stay nil.
(def change-entry-fields
  #{:change/kind :path/kind :change/old-path :change/new-path
    :change/old-mode :change/new-mode :path/target :path/commit
    :path/unsafe-reason})

(def observation-schema-version 1)
(def observation-kind :github-observation)
(def producer-id "axiom-github-observer")

(def anonymous-trust :trust/provider-observed)
(def authenticated-trust :trust/provider-authenticated)
(def trust-marks #{anonymous-trust authenticated-trust})

(def change-kinds #{:added :modified :removed :renamed})
(def pr-states #{:open :closed :merged})
(def merge-states #{:clean :dirty :blocked :behind :unknown})
(def review-states #{:approved :changes-requested :dismissed :commented})

;; Check-conclusion vocabulary. Unknown provider values map to
;; :unknown — never to success (spec R4/design).
(def check-conclusions
  #{:success :failure :neutral :cancelled :skipped
    :timed-out :action-required :stale :unknown})

(def failing-collections #{:changes :runs :jobs :artifacts})

;; The exact set of input fields build-observation accepts. Anything
;; else is :invalid — the adapter cannot smuggle unmodeled data past
;; validation.
(def observation-input-fields
  #{:observation/status :producer :trust :subject :value :provenance
    :scope :observation/failing-step})

;; ------------------------------------------------------------------
;; Pure provider-vocabulary mappings (data, no network)

(defn conclusion-from-provider
  "Maps a provider check-conclusion string to the port vocabulary.
   Unknown values (including nil, the provider's in-progress marker)
   map to :unknown — never to success."
  [s]
  (case s
    "success" :success
    "failure" :failure
    "neutral" :neutral
    "cancelled" :cancelled
    "skipped" :skipped
    "timed_out" :timed-out
    "action_required" :action-required
    "stale" :stale
    :unknown))

(defn change-kind-from-provider-status
  "Maps a provider file-status word to the port change kind. Returns
   nil for anything outside the enumerated model; the adapter treats
   nil as a malformed provider payload and reports an operational
   failure (never silently normalized)."
  [s]
  (case s
    "added" :added
    "modified" :modified
    "removed" :removed
    "renamed" :renamed
    nil))

(defn pr-state-from-provider
  "Maps a provider PR state word to the port vocabulary; nil means
   the adapter must treat the payload as malformed."
  [s]
  (case s
    "open" :open
    "closed" :closed
    "merged" :merged
    nil))

(defn merge-state-from-provider
  "Maps a provider merge_state_status word to the port vocabulary.
   Unknown values map to :unknown — never to :clean."
  [s]
  (case s
    "clean" :clean
    "dirty" :dirty
    "blocked" :blocked
    "behind" :behind
    :unknown))

(defn review-state-from-provider
  "Maps a provider review-state word to the port vocabulary; nil
   means the adapter must treat the payload as malformed."
  [s]
  (case s
    "APPROVED" :approved
    "CHANGES_REQUESTED" :changes-requested
    "DISMISSED" :dismissed
    "COMMENTED" :commented
    nil))

;; ------------------------------------------------------------------
;; Scalar validators

(defn- sha40? [s]
  (and (string? s) (boolean (re-matches #"[0-9a-f]{40}" s))))

(defn- non-blank-string? [s]
  (and (string? s) (not (str/blank? s))))

(defn- positive-int? [n]
  (and (integer? n) (pos? n)))

(defn- non-neg-int? [n]
  (and (integer? n) (>= n 0)))

(defn- digest? [d]
  (and (string? d) (boolean (re-matches #"sha256:[0-9a-f]{64}" d))))

(defn- github-login?
  "GitHub account names: alphanumerics and hyphens, 1-39 chars, no
   leading or trailing hyphen."
  [s]
  (and (string? s)
       (boolean (re-matches #"[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?" s))))

(defn- github-repo?
  "GitHub repository names: alphanumerics plus `.`, `-`, `_`, 1-100
   chars, no leading separator."
  [s]
  (and (string? s)
       (boolean (re-matches #"[A-Za-z0-9](?:[A-Za-z0-9._-]{0,99})?" s))))

;; ------------------------------------------------------------------
;; Observation validation (strict, like axiom.git/validate-observation!)

(defn- validate-producer-and-trust!
  "The producer id is fixed; the trust mark must agree with the
   authenticated flag and the presence of a resolved token identity.
   Any contradiction is a forged producer (:invalid). Neither
   anonymous nor authenticated observations may claim
   :trust/remote-ci — that mark belongs to the M4 trusted evaluation
   workflow and is rejected here."
  [producer trust]
  (shape! producer producer-fields :producer)
  (ensure! (= producer-id (:producer/id producer)) "Unknown producer" {})
  (let [authenticated? (:producer/authenticated? producer)
        login (:github/login producer)]
    (ensure! (boolean? authenticated?) "Producer authenticated flag must be boolean" {})
    (ensure! (or (nil? login) (non-blank-string? login))
             "Token identity must be a non-blank login or nil" {})
    (if authenticated?
      (do (ensure! (some? login)
                   "Forged producer: authenticated without a resolved token identity" {})
          (ensure! (= authenticated-trust trust)
                   "Forged producer: authenticated trust must be provider-authenticated" {}))
      (do (ensure! (nil? login)
                   "Forged producer: anonymous with a token identity" {})
          (ensure! (= anonymous-trust trust)
                   "Forged producer: anonymous trust must be provider-observed" {}))))
  (ensure! (contains? trust-marks trust)
           "Unknown trust mark: only provider-observed and provider-authenticated are produced here" {}))

(defn- validate-head-repo! [head-repo]
  (shape! head-repo head-repo-fields :head-repo)
  (ensure! (github-login? (:github/owner head-repo)) "Invalid head repository owner" {})
  (ensure! (github-repo? (:github/repo head-repo)) "Invalid head repository name" {}))

(defn- validate-subject! [subject complete?]
  (shape! subject subject-fields :subject)
  (ensure! (github-login? (:github/owner subject)) "Invalid repository owner" {})
  (ensure! (github-repo? (:github/repo subject)) "Invalid repository name" {})
  (ensure! (positive-int? (:github/pr subject)) "Invalid PR number" {})
  ;; Fork identity is always explicit: the head repo owner/name is
  ;; carried even when the head is not a fork (then it equals the
  ;; upstream owner/name), so fork and upstream refs are never
  ;; conflated.
  (validate-head-repo! (:github/head-repo subject))
  (doseq [field [:git/base :git/head]]
    (let [sha (get subject field)]
      (ensure! (or (nil? sha) (sha40? sha)) "Invalid Git identity" {:field field})
      (when complete?
        (ensure! (sha40? sha) "Complete observation requires base and head identities" {:field field})))))

(defn- validate-change-entry! [entry]
  (shape! entry change-entry-fields :change-entry)
  (let [kind (:change/kind entry)]
    (ensure! (contains? change-kinds kind) "Unknown change kind" {})
    ;; The provider file list carries no path typing: entries are
    ;; files, and the 0003 symlink/submodule/unsafe fields stay nil.
    (ensure! (= :file (:path/kind entry)) "GitHub change entries are files" {})
    (ensure! (nil? (:path/target entry)) "GitHub change entries carry no symlink target" {})
    (ensure! (nil? (:path/commit entry)) "GitHub change entries carry no submodule commit" {})
    (ensure! (nil? (:path/unsafe-reason entry)) "GitHub change entries carry no unsafe reason" {})
    (let [old-path (:change/old-path entry)
          new-path (:change/new-path entry)]
      (when (some? old-path)
        (ensure! (contract/path? old-path) "Invalid old path" {:path old-path}))
      (when (some? new-path)
        (ensure! (contract/path? new-path) "Invalid new path" {:path new-path}))
      (doseq [mode [(:change/old-mode entry) (:change/new-mode entry)]]
        (ensure! (or (nil? mode) (and (string? mode) (re-matches #"[0-7]{6}" mode)))
                 "Invalid git mode" {:mode mode}))
      (ensure! (case kind
                 :added (and (nil? old-path) (some? new-path))
                 :removed (and (some? old-path) (nil? new-path))
                 :modified (and (some? old-path) (= old-path new-path))
                 :renamed (and (some? old-path) (some? new-path) (not= old-path new-path)))
               "Inconsistent change paths" {:kind kind}))))

(defn- validate-attempts! [attempts context]
  (ensure! (and (vector? attempts) (seq attempts)) "Attempts must be a non-empty vector"
           {:context context})
  (doseq [attempt attempts]
    (shape! attempt attempt-fields context)
    (ensure! (positive-int? (:github/attempt attempt)) "Invalid attempt number" {:context context})
    (ensure! (contains? check-conclusions (:github/conclusion attempt))
             "Unknown check conclusion" {:context context})
    (ensure! (non-blank-string? (:evidence/url attempt))
             "Attempt must carry its evidence URL" {:context context}))
  (let [numbers (mapv :github/attempt attempts)]
    (ensure! (= numbers (sort numbers)) "Attempts must be enumerated in ascending order"
             {:context context})
    (ensure! (= (count numbers) (count (set numbers))) "Duplicate attempt numbers"
             {:context context})))

(defn- validate-matrix-axes! [axes]
  (ensure! (map? axes) "Matrix axes must be a map" {})
  (doseq [[k v] axes]
    (ensure! (keyword? k) "Matrix axis names must be keywords" {:axis k})
    (ensure! (non-blank-string? v) "Matrix axis values must be non-blank strings" {:axis k})))

(declare validate-collection!)

(defn- validate-job! [job]
  (shape! job job-fields :job)
  (ensure! (positive-int? (:github/job-id job)) "Invalid job id" {})
  (ensure! (non-blank-string? (:github/job-name job)) "Invalid job name" {})
  (ensure! (contains? check-conclusions (:github/conclusion job))
           "Unknown check conclusion" {:job (:github/job-id job)})
  (validate-attempts! (:github/attempts job) :job)
  (let [attempts (:github/attempts job)
        numbers (set (map :github/attempt attempts))
        selected (:github/selected-attempt job)]
    ;; Latest-attempt semantics: which attempt counts is explicit and
    ;; recorded; the failed attempts remain in the record.
    (ensure! (contains? numbers selected)
             "Selected attempt must be one of the enumerated attempts" {})
    (let [selected-conclusion (:github/conclusion
                               (first (filter #(= selected (:github/attempt %)) attempts)))]
      (ensure! (= selected-conclusion (:github/conclusion job))
               "Job conclusion must be the selected attempt's conclusion" {})))
  (validate-matrix-axes! (:matrix/axes job))
  (ensure! (non-blank-string? (:evidence/url job)) "Job must carry its evidence URL" {}))

(defn- validate-run! [run complete?]
  (shape! run run-fields :run)
  (ensure! (positive-int? (:github/run-id run)) "Invalid run id" {})
  (ensure! (non-blank-string? (:github/workflow-name run)) "Invalid workflow name" {})
  (ensure! (non-blank-string? (:github/workflow-path run)) "Invalid workflow path" {})
  (ensure! (non-blank-string? (:github/event run)) "Invalid triggering event" {})
  (ensure! (non-blank-string? (:github/actor run)) "Invalid run actor" {})
  (ensure! (sha40? (:github/head-sha run)) "Invalid run head SHA" {})
  (ensure! (contains? check-conclusions (:github/conclusion run))
           "Unknown check conclusion" {:run (:github/run-id run)})
  (validate-attempts! (:github/attempts run) :run)
  ;; The run conclusion is the latest attempt's conclusion: a rerun
  ;; never rewrites history, and which attempt counts is explicit.
  (let [latest (apply max-key :github/attempt (:github/attempts run))]
    (ensure! (= (:github/conclusion latest) (:github/conclusion run))
             "Run conclusion must be the latest attempt's conclusion" {}))
  ;; Nested jobs collection: complete exactly when the observation is
  ;; complete (a mid-list jobs failure marks the observation
  ;; incomplete via the failing step naming :jobs and the run).
  (let [jobs (:github/jobs run)]
    (shape! jobs #{:pagination/complete :pagination/pages :jobs} :run-jobs)
    (ensure! (boolean? (:pagination/complete jobs)) "Jobs completeness must be boolean" {})
    (when complete?
      (ensure! (:pagination/complete jobs) "Complete observation requires complete job lists" {}))
    (let [pages (:pagination/pages jobs)]
      (ensure! (non-neg-int? pages) "Job page count must be a non-negative integer" {})
      (when (:pagination/complete jobs)
        (ensure! (pos? pages) "Complete job list observed at least one page" {})))
    (let [items (:jobs jobs)]
      (ensure! (vector? items) "Jobs must be a vector" {})
      (when-not (:pagination/complete jobs)
        (ensure! (empty? items) "Incomplete job list must not carry partial jobs" {}))
      (doseq [job items] (validate-job! job))))
  (ensure! (non-blank-string? (:evidence/url run)) "Run must carry its evidence URL" {}))

(defn- validate-review! [review]
  (shape! review review-fields :review)
  (ensure! (positive-int? (:github/review-id review)) "Invalid review id" {})
  (ensure! (non-blank-string? (:github/reviewer review)) "Invalid reviewer" {})
  (ensure! (contains? review-states (:github/state review)) "Unknown review state" {})
  (ensure! (sha40? (:github/commit-sha review)) "Invalid review commit SHA" {})
  (ensure! (non-neg-int? (:github/submitted-at review)) "Invalid review timestamp" {})
  (ensure! (non-blank-string? (:evidence/url review)) "Review must carry its evidence URL" {}))

(defn- validate-artifact! [artifact]
  (shape! artifact artifact-fields :artifact)
  (ensure! (non-blank-string? (:github/artifact-name artifact)) "Invalid artifact name" {})
  (ensure! (or (nil? (:github/digest artifact)) (digest? (:github/digest artifact)))
           "Invalid artifact digest" {})
  (ensure! (or (nil? (:github/expires-at artifact)) (non-neg-int? (:github/expires-at artifact)))
           "Invalid artifact expiry" {})
  (ensure! (non-blank-string? (:evidence/url artifact)) "Artifact must carry its evidence URL" {}))

(defn- collection-validator
  [item-key complete?]
  (case item-key
    :changes validate-change-entry!
    :runs (fn [run] (validate-run! run complete?))
    :reviews validate-review!
    :artifacts validate-artifact!))

(defn- validate-collection!
  "Every paginated collection carries `:pagination/complete` and
   `:pagination/pages`. A complete observation requires every
   collection complete; an incomplete observation must mark the
   failing collection incomplete, and incomplete collections never
   carry partial items."
  [collection item-key complete? failing-step context]
  (shape! collection #{:pagination/complete :pagination/pages item-key} context)
  (let [coll-complete? (:pagination/complete collection)]
    (ensure! (boolean? coll-complete?) "Pagination completeness must be boolean" {:context context})
    (when complete?
      (ensure! coll-complete? "Complete observation requires complete collections" {:context context}))
    (when (and (not complete?) (= item-key (:collection failing-step)))
      (ensure! (not coll-complete?) "Failing collection must be marked incomplete" {:context context}))
    (let [pages (:pagination/pages collection)]
      (ensure! (non-neg-int? pages) "Page count must be a non-negative integer" {:context context})
      (when coll-complete?
        (ensure! (pos? pages) "Complete collection observed at least one page" {:context context})))
    (let [items (get collection item-key)]
      (ensure! (vector? items) "Collection items must be a vector" {:context context})
      (when-not coll-complete?
        (ensure! (empty? items) "Incomplete collection must not carry partial items" {:context context}))
      (doseq [item items]
        ((collection-validator item-key complete?) item)))))

(defn- validate-pr-record! [pr-record]
  (shape! pr-record pr-record-fields :pr-record)
  (ensure! (non-blank-string? (:github/pr-title pr-record)) "Invalid PR title" {})
  (ensure! (contains? pr-states (:github/pr-state pr-record)) "Unknown PR state" {})
  (ensure! (boolean? (:github/draft? pr-record)) "Draft flag must be boolean" {})
  (ensure! (contains? merge-states (:github/merge-state pr-record)) "Unknown merge state" {})
  (ensure! (boolean? (:github/mergeable? pr-record)) "Mergeable flag must be boolean" {})
  (ensure! (non-blank-string? (:evidence/url pr-record)) "PR record must carry its evidence URL" {}))

(defn- validate-value! [value complete? failing-step]
  (shape! value value-fields :value)
  (validate-pr-record! (:pr/record value))
  (validate-collection! (:changes value) :changes complete? failing-step :changes)
  (validate-collection! (:runs value) :runs complete? failing-step :runs)
  (validate-collection! (:reviews value) :reviews complete? failing-step :reviews)
  (validate-collection! (:artifacts value) :artifacts complete? failing-step :artifacts)
  ;; A mid-list jobs failure names :jobs and the run: that run's job
  ;; list must be marked incomplete.
  (when (and (not complete?) (= :jobs (:collection failing-step)))
    (let [run-id (:github/run-id failing-step)
          runs (get-in value [:runs :runs])
          run (first (filter #(= run-id (:github/run-id %)) runs))]
      (ensure! (some? run) "Failing step names an unknown run" {:run run-id})
      (ensure! (not (get-in run [:github/jobs :pagination/complete]))
               "Failing run's job list must be marked incomplete" {:run run-id}))))

(defn- validate-provenance! [provenance]
  (shape! provenance provenance-fields :provenance)
  (let [urls (:api/urls provenance)]
    (ensure! (and (vector? urls) (seq urls) (every? non-blank-string? urls))
             "Provenance must record the exact API URLs requested" {})
    (ensure! (= (count urls) (count (set urls))) "Provenance URLs must be distinct" {})
    (let [etags (:api/etags provenance)]
      (ensure! (map? etags) "Provenance must map each URL to its response ETag" {})
      (ensure! (= (set urls) (set (keys etags)))
               "ETag map must cover exactly the requested URLs" {})
      (doseq [[url etag] etags]
        (ensure! (or (nil? etag) (non-blank-string? etag))
                 "ETag must be a non-blank string or nil" {:url url}))))
  (let [rate-limit (:rate-limit provenance)]
    (shape! rate-limit rate-limit-fields :rate-limit)
    (doseq [field [:rate-limit/limit :rate-limit/remaining :rate-limit/reset]]
      (ensure! (or (nil? (get rate-limit field)) (non-neg-int? (get rate-limit field)))
               "Rate-limit state must be a non-negative integer or nil" {:field field})))
  (let [client (:http/client provenance)]
    (shape! client http-client-fields :http-client)
    (ensure! (positive-int? (:http/timeout-ms client)) "Client timeout must be a positive integer" {})
    (ensure! (non-blank-string? (:http/user-agent client)) "Client must record its user agent" {})
    (ensure! (non-blank-string? (:http/api-version client)) "Client must record the API version header" {})))

(defn- validate-scope! [scope subject complete?]
  (shape! scope scope-fields :scope)
  (ensure! (= (:github/owner subject) (:github/owner scope)) "Scope owner must match subject" {})
  (ensure! (= (:github/repo subject) (:github/repo scope)) "Scope repo must match subject" {})
  (ensure! (= (:github/pr subject) (:github/pr scope)) "Scope PR must match subject" {})
  (let [revisions (:revisions scope)]
    (shape! revisions scope-revisions-fields :revisions)
    (ensure! (= (:git/base subject) (:base revisions)) "Scope base must match subject" {})
    (ensure! (= (:git/head subject) (:head revisions)) "Scope head must match subject" {})
    (doseq [[field sha] revisions]
      (ensure! (or (nil? sha) (sha40? sha)) "Invalid revision identity" {:field field})
      (when complete?
        (ensure! (sha40? sha) "Complete observation requires revision identities" {:field field})))))

(defn- validate-failing-step! [step]
  (shape! step failing-step-fields :failing-step)
  (ensure! (contains? failing-collections (:collection step))
           "Failing step must name a paginated collection" {})
  (ensure! (positive-int? (:page step)) "Failing step must name the failing page" {})
  (ensure! (non-blank-string? (:reason step)) "Failing step must name the reason" {})
  (if (= :jobs (:collection step))
    (ensure! (positive-int? (:github/run-id step))
             "A jobs failure must name the run whose job list failed" {})
    (ensure! (nil? (:github/run-id step))
             "Only a jobs failure names a run" {})))

(defn validate-observation!
  "Strict validation of an adapter-built GitHub observation map.
   Unknown or malformed observations are rejected (:invalid) and
   therefore can never enter the ledger or admit actions. Returns the
   observation unchanged."
  [observation]
  (contract/check-value! observation)
  (ensure! (map? observation) "Observation must be a map" {})
  (let [status (:observation/status observation)
        complete? (= :complete status)]
    (ensure! (contains? #{:complete :observation/incomplete} status)
             "Unknown observation status" {})
    (shape! observation (if complete? observation-fields observation-fields-incomplete) :observation)
    (ensure! (= observation-schema-version (:observation/schema-version observation))
             "Unsupported observation schema version" {})
    (ensure! (= observation-kind (:observation/kind observation)) "Unknown observation kind" {})
    (validate-producer-and-trust! (:producer observation) (:trust observation))
    (validate-subject! (:subject observation) complete?)
    (validate-value! (:value observation) complete? (:observation/failing-step observation))
    (validate-provenance! (:provenance observation))
    (validate-scope! (:scope observation) (:subject observation) complete?)
    (when-not complete?
      (validate-failing-step! (:observation/failing-step observation))))
  observation)

;; ------------------------------------------------------------------
;; Construction (used by the adapter; always validated)

(defn build-observation
  "Assembles an observation from adapter-collected data and validates
   it. data keys:

     :observation/status  :complete or :observation/incomplete (required)
     :producer            {:producer/id \"axiom-github-observer\"
                           :producer/authenticated? bool
                           :github/login <token identity or nil>} (required)
     :trust               :trust/provider-observed or
                           :trust/provider-authenticated (required, must
                           agree with :producer — see
                           validate-producer-and-trust!)
     :subject             {:github/owner :github/repo :github/pr
                           :git/base :git/head
                           :github/head-repo {:github/owner :github/repo}}
                           (base/head nil-able only when
                           :observation/incomplete)
     :value               {:pr/record {...}
                           :changes/:runs/:reviews/:artifacts each
                           {:pagination/complete bool
                            :pagination/pages n
                            :<items> [...]}}
     :provenance          {:api/urls [...] :api/etags {url etag-or-nil}
                           :rate-limit {:rate-limit/limit
                                        :rate-limit/remaining
                                        :rate-limit/reset}
                           :http/client {:http/timeout-ms
                                         :http/user-agent
                                         :http/api-version}}
     :scope               {:github/owner :github/repo :github/pr
                           :revisions {:base :head}} (must agree with
                           :subject)
     :observation/failing-step  required when :observation/incomplete:
                           {:collection :changes/:runs/:jobs/:artifacts
                            :page n :reason \"...\"
                            :github/run-id <id, only for :jobs>}

   Unknown input fields are rejected (:invalid): the adapter must not
   smuggle unmodeled data past validation.

   Returns the validated observation map."
  [data]
  (ensure! (map? data) "Observation data must be a map" {})
  (let [unknown (remove observation-input-fields (keys data))]
    (ensure! (empty? unknown) "Unknown observation input fields"
             {:unknown (vec unknown)}))
  (let [status (:observation/status data)]
    (validate-observation!
     (cond-> {:observation/schema-version observation-schema-version
              :observation/kind observation-kind
              :observation/status status
              :producer (:producer data)
              :trust (:trust data)
              :subject (:subject data)
              :value (:value data)
              :provenance (:provenance data)
              :scope (:scope data)}
       (= :observation/incomplete status)
       (assoc :observation/failing-step (:observation/failing-step data))))))

;; ------------------------------------------------------------------
;; check-pr: pure advisory evaluation over validated observations

(def gate-ids #{:pr-identity :required-checks :approvals :merge-state})
(def selection-rules #{:all-required-matrix-jobs :named-axes :latest})

(def required-check-fields #{:required/job :selection/rule :selection/axes})
(def gate-spec-fields
  {:pr-identity #{:gate/id}
   :required-checks #{:gate/id :required/checks}
   :approvals #{:gate/id :required/approvals}
   :merge-state #{:gate/id}})

(def check-schema-version 1)
(def check-kind :check-pr)

(def evidence-fields #{:evidence/url :evidence/sha})

(defn- evidence [url sha]
  (ensure! (non-blank-string? url) "Evidence needs a URL" {})
  (ensure! (sha40? sha) "Evidence needs a 40-hex SHA" {})
  {:evidence/url url :evidence/sha sha})

(defn job-outcome
  "Maps a selected check conclusion to a gate outcome. :success
   passes; a determinate :failure fails; every other non-success —
   skipped required jobs, failed reruns, unknown conclusions,
   cancelled or timed-out checks — is :unknown, never :pass (spec
   R4: obligations become :unknown, never allow)."
  [conclusion]
  (cond
    (= :success conclusion) :pass
    (= :failure conclusion) :fail
    :else :unknown))

(defn- validate-required-check! [entry]
  (let [with-axes (= :named-axes (:selection/rule entry))]
    (shape! entry (if with-axes required-check-fields (disj required-check-fields :selection/axes))
            :required-check)
    (ensure! (non-blank-string? (:required/job entry)) "Required check needs a job name" {})
    (ensure! (contains? selection-rules (:selection/rule entry)) "Unknown selection rule" {})
    (when with-axes
      (let [axes (:selection/axes entry)]
        (ensure! (and (map? axes) (seq axes)) "Named-axes selection needs non-empty axes" {})
        (validate-matrix-axes! axes)))))

(defn- validate-gate-spec! [gate-spec]
  (ensure! (map? gate-spec) "Gate spec must be a map" {})
  (let [id (:gate/id gate-spec)]
    (ensure! (contains? gate-ids id) "Unknown gate id" {:gate id})
    (shape! gate-spec (get gate-spec-fields id) :gate-spec)
    (case id
      :required-checks (do (ensure! (and (vector? (:required/checks gate-spec))
                                        (seq (:required/checks gate-spec)))
                                   "required-checks gate needs a non-empty check list" {})
                           (doseq [entry (:required/checks gate-spec)]
                             (validate-required-check! entry)))
      :approvals (ensure! (positive-int? (:required/approvals gate-spec))
                          "approvals gate needs a positive required count" {})
      (:pr-identity :merge-state) nil)))

(defn- validate-gate-specs! [gate-specs]
  (ensure! (and (vector? gate-specs) (seq gate-specs)) "check-pr needs a non-empty gate list" {})
  (doseq [gate-spec gate-specs] (validate-gate-spec! gate-spec))
  (ensure! (= (count gate-specs) (count (set (map :gate/id gate-specs))))
           "Duplicate gate ids" {}))

;; ------------------------------------------------------------------
;; Gate evaluation (pure; observations are validated before use)

(defn- observation-head [observation]
  (get-in observation [:subject :git/head]))

(defn- observation-base [observation]
  (get-in observation [:subject :git/base]))

(defn- gate-pr-identity [observation]
  (let [pr (get-in observation [:value :pr/record])
        head (observation-head observation)
        open? (= :open (:github/pr-state pr))
        ready? (not (:github/draft? pr))]
    {:gate/id :pr-identity
     :gate/outcome (if (and open? ready?) :pass :fail)
     :gate/evidence [(evidence (:evidence/url pr) head)]
     :gate/detail (str "PR #" (get-in observation [:subject :github/pr])
                       " state " (name (:github/pr-state pr))
                       (when-not ready? ", draft"))}))

(defn- gate-merge-state [observation]
  (let [pr (get-in observation [:value :pr/record])
        head (observation-head observation)
        merge-state (:github/merge-state pr)
        mergeable? (:github/mergeable? pr)]
    {:gate/id :merge-state
     :gate/outcome (cond
                     (and (= :clean merge-state) mergeable?) :pass
                     (= :dirty merge-state) :fail
                     :else :unknown)
     :gate/evidence [(evidence (:evidence/url pr) head)]
     :gate/detail (str "merge-state " (name merge-state)
                       ", mergeable? " mergeable?)}))

(defn- valid-approvals
  "Approvals that satisfy an approval obligation: state :approved,
   recorded against the observation's head SHA. Dismissed reviews and
   reviews on a different (superseded) head SHA never count."
  [observation]
  (let [head (observation-head observation)]
    (filterv (fn [review]
               (and (= :approved (:github/state review))
                    (= head (:github/commit-sha review))))
             (get-in observation [:value :reviews :reviews]))))

(defn- gate-approvals [observation required]
  (let [approved (valid-approvals observation)
        head (observation-head observation)
        dismissed (count (filter #(= :dismissed (:github/state %))
                                 (get-in observation [:value :reviews :reviews])))
        stale (count (filter (fn [review]
                               (and (= :approved (:github/state review))
                                    (not= head (:github/commit-sha review))))
                             (get-in observation [:value :reviews :reviews])))]
    {:gate/id :approvals
     :gate/outcome (if (>= (count approved) required) :pass :unknown)
     :gate/evidence (mapv #(evidence (:evidence/url %) (:github/commit-sha %)) approved)
     :gate/detail (str (count approved) " of " required " required approvals on head "
                       head "; " dismissed " dismissed, " stale " on superseded SHAs (neither counts)")}))

(defn- head-runs
  "Only runs observed against the observation's head SHA participate
   in check selection; runs on other SHAs are ignored and named, so
   evidence is always bound to the exact SHAs under evaluation."
  [observation]
  (let [head (observation-head observation)
        runs (get-in observation [:value :runs :runs])]
    {:selected (filterv #(= head (:github/head-sha %)) runs)
     :ignored (mapv :github/run-id (filter #(not= head (:github/head-sha %)) runs))}))

(defn- select-jobs
  "Applies an explicit matrix/attempt selection rule to the candidate
   jobs (same name, observed on the head SHA) and returns the selected
   jobs plus the recorded selection. Rules:

     :all-required-matrix-jobs — every candidate counts (the matrix
       expansion is fully required).
     :named-axes — only candidates whose :matrix/axes cover the named
       axes count.
     :latest — the single candidate with the highest selected attempt
       counts; a tie is ambiguous and makes the check :unknown."
  [candidates rule axes]
  (let [base {:selection/rule rule
              :selection/considered (count candidates)}
        named? (= :named-axes rule)]
    (cond
      (= :all-required-matrix-jobs rule)
      {:selected candidates
       :selection (assoc base
                         :selection/selected (mapv :github/job-id candidates)
                         :selection/note "all matrix jobs with this name are required")}

      named?
      (let [selected (filterv (fn [job]
                                (every? (fn [[axis value]]
                                          (= value (get (:matrix/axes job) axis)))
                                        axes))
                              candidates)]
        {:selected selected
         :selection (assoc base
                           :selection/axes axes
                           :selection/selected (mapv :github/job-id selected)
                           :selection/note "only jobs covering the named matrix axes count")})

      :else ; :latest
      (let [by-attempt (group-by :github/selected-attempt candidates)
            latest (apply max (map :github/selected-attempt candidates))
            tied (get by-attempt latest)]
        (if (= 1 (count tied))
          {:selected tied
           :selection (assoc base
                             :selection/selected (mapv :github/job-id tied)
                             :selection/note (str "latest attempt wins: attempt " latest))}
          {:selected []
           :ambiguous? true
           :selection (assoc base
                             :selection/selected []
                             :selection/note (str "ambiguous latest: "
                                                  (count tied)
                                                  " jobs share attempt " latest))})))))

(defn- gate-required-checks [observation required-checks]
  (let [head (observation-head observation)
        {:keys [selected ignored]} (head-runs observation)
        jobs (mapcat #(get-in % [:github/jobs :jobs]) selected)
        check-results
        (mapv (fn [{:required/keys [job] :selection/keys [rule axes]}]
                (let [candidates (filterv #(= job (:github/job-name %)) jobs)
                      {:keys [selected selection ambiguous?]}
                      (if (seq candidates)
                        (select-jobs candidates rule axes)
                        {:selected [] :ambiguous? false
                         :selection {:selection/rule rule
                                     :selection/considered 0
                                     :selection/selected []
                                     :selection/note "required job not observed on the head SHA"}})
                      selection (assoc selection
                                       :selection/required-job job
                                       :selection/ignored-runs ignored)
                      outcomes (mapv job-outcome (map :github/conclusion selected))
                      outcome (cond
                                ambiguous? :unknown
                                (empty? selected) :unknown
                                (every? #(= :pass %) outcomes) :pass
                                (some #(= :fail %) outcomes) :fail
                                :else :unknown)]
                  {:required/job job
                   :check/outcome outcome
                   :gate/selection selection
                   :check/evidence (mapv #(evidence (:evidence/url %) head) selected)
                   :check/detail (if ambiguous?
                                   "ambiguous latest-attempt selection"
                                   (str (count (filter #(= :pass %) outcomes)) "/"
                                        (count outcomes) " selected jobs pass"))}))
              required-checks)
        outcome (cond
                  (every? #(= :pass (:check/outcome %)) check-results) :pass
                  (some #(= :fail (:check/outcome %)) check-results) :fail
                  :else :unknown)]
    {:gate/id :required-checks
     :gate/outcome outcome
     :gate/checks check-results
     :gate/evidence (vec (mapcat :check/evidence check-results))
     :gate/detail (str (count (filter #(= :pass (:check/outcome %)) check-results)) "/"
                       (count check-results) " required checks pass")}))

(defn- evaluate-gate [observation gate-spec complete?]
  (if-not complete?
    (let [step (:observation/failing-step observation)]
      {:gate/id (:gate/id gate-spec)
       :gate/outcome :unknown
       :gate/evidence []
       :gate/detail (str "observation incomplete: collection " (name (:collection step))
                        " page " (:page step) " failed (" (:reason step) ")")})
    (case (:gate/id gate-spec)
      :pr-identity (gate-pr-identity observation)
      :required-checks (gate-required-checks observation (:required/checks gate-spec))
      :approvals (gate-approvals observation (:required/approvals gate-spec))
      :merge-state (gate-merge-state observation))))

(defn- report-subject [observation]
  (-> (:subject observation)
      (select-keys [:github/owner :github/repo :github/pr :git/base :git/head :github/head-repo])))

(defn- advisory-can-merge
  "Advisory only: :yes when every gate passes, :no when any gate
   fails, :unknown otherwise. Never enforcement, never a merge, never
   a published check."
  [gates]
  (cond
    (some #(= :fail (:gate/outcome %)) gates) :no
    (every? #(= :pass (:gate/outcome %)) gates) :yes
    :else :unknown))

(defn- validate-report! [report]
  (ensure! (map? report) "check-pr must return a map" {})
  (shape! report #{:check/schema-version :check/kind :check/complete-observation?
                   :subject :gates :can-merge :check/stale} :check-report)
  (ensure! (= check-schema-version (:check/schema-version report)) "Unsupported check schema version" {})
  (ensure! (= check-kind (:check/kind report)) "Unknown check kind" {})
  (ensure! (boolean? (:check/complete-observation? report)) "Complete-observation flag must be boolean" {})
  (let [subject (:subject report)]
    (shape! subject subject-fields :check-subject)
    (validate-head-repo! (:github/head-repo subject)))
  (ensure! (and (vector? (:gates report)) (seq (:gates report))) "Report must carry gates" {})
  (doseq [gate (:gates report)]
    (ensure! (map? gate) "Gate outcome must be a map" {})
    (ensure! (contains? gate-ids (:gate/id gate)) "Unknown gate id in report" {})
    (ensure! (contains? #{:pass :fail :unknown} (:gate/outcome gate))
             "Unknown gate outcome" {})
    (ensure! (vector? (:gate/evidence gate)) "Gate evidence must be a vector" {})
    (doseq [item (:gate/evidence gate)] (shape! item evidence-fields :evidence))
    (ensure! (string? (:gate/detail gate)) "Gate detail must be a string" {}))
  (let [can-merge (:can-merge report)]
    (shape! can-merge #{:can-merge/advisory :can-merge/advisory-only :can-merge/note} :can-merge)
    (ensure! (contains? #{:yes :no :unknown} (:can-merge/advisory can-merge))
             "Unknown can-merge advisory" {})
    (ensure! (true? (:can-merge/advisory-only can-merge)) "can-merge is advisory only" {}))
  (ensure! (false? (:check/stale report)) "Fresh reports are never stale" {})
  report)

(defn check-pr
  "Pure advisory evaluation over a validated GitHub observation.
   Emits per-gate outcomes (:pass / :fail / :unknown) bound to the
   exact base/head SHAs, each with exact evidence links (the API URLs
   and SHAs the outcome was read from), plus the advisory can-merge
   summary.

   gate-specs is a vector of gate specs, e.g.:

     [{:gate/id :pr-identity}
      {:gate/id :required-checks
       :required/checks [{:required/job \"build\"
                           :selection/rule :all-required-matrix-jobs}
                          {:required/job \"lint\"
                           :selection/rule :named-axes
                           :selection/axes {:os \"ubuntu-latest\"}}
                          {:required/job \"e2e\"
                           :selection/rule :latest}]}
      {:gate/id :approvals :required/approvals 1}
      {:gate/id :merge-state}]

   Selection rules are explicit and recorded in the report
   (:gate/selection): :all-required-matrix-jobs, :named-axes, or
   :latest. A missing or :observation/incomplete observation makes
   every dependent gate :unknown, never :pass — incomplete
   observations cannot admit actions."
  [observation gate-specs]
  (validate-observation! observation)
  (validate-gate-specs! gate-specs)
  (let [complete? (= :complete (:observation/status observation))
        gates (mapv #(evaluate-gate observation % complete?) gate-specs)
        report {:check/schema-version check-schema-version
                :check/kind check-kind
                :check/complete-observation? complete?
                :subject (report-subject observation)
                :gates gates
                :can-merge {:can-merge/advisory (advisory-can-merge gates)
                            :can-merge/advisory-only true
                            :can-merge/note (str "Advisory only: not enforcement, "
                                                 "not a merge, not a published check. "
                                                 "Observations authorize nothing.")}
                :check/stale false}]
    (validate-report! report)))

(defn stale?
  "True when the observation's repository, PR number, or base/head
   SHAs no longer match the report's subject: the report is :stale,
   never silently current."
  [report observation]
  (validate-observation! observation)
  (let [subject (:subject report)
        observed (:subject observation)]
    (not (and (= (:github/owner subject) (:github/owner observed))
              (= (:github/repo subject) (:github/repo observed))
              (= (:github/pr subject) (:github/pr observed))
              (= (:git/base subject) (:git/base observed))
              (= (:git/head subject) (:git/head observed))))))

(defn report-status
  "`:current` when the report still matches the observation,
   `:stale` when base/head (or repo/PR identity) moved."
  [report observation]
  (if (stale? report observation) :stale :current))
