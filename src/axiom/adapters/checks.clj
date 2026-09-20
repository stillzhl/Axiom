(ns axiom.adapters.checks
  "The ONLY namespace permitted to mutate provider state (spec 0005 R5/R11).

   The checks writer sits behind the pure `axiom.gate` port: it
   publishes exactly one kind of provider mutation — a check run
   bound to the exact candidate identity (repository, PR number,
   base/head/tree SHAs) and the evaluator identity. There are no code
   paths for anything else: no merges, no branch updates, no labels,
   no protection edits. (Verify structurally: the only
   provider-mutating fns in this namespace are
   `:checks/create-run!` and `:checks/update-run!` inside the checks
   API fn-map, and the only caller of that map is `publish!`.)

   Construction is capability-gated: `construct!` requires a passing
   R8 capability record (`axiom.capability/compute-capability`
   output with `:mode :enforcement`, the three R8 answers true, and
   the evaluator identity matching). Without one the adapter refuses
   to construct — a pure, network-free refusal (`constructable?` is
   the testable predicate). The caller then reports advisory mode
   instead of claiming enforcement.

   Publication (`publish!`) is idempotent per (candidate identity,
   gate set, policy digest): the run's external ID is the digest of
   exactly that triple, so republishing the same evaluation updates
   one logical run instead of duplicating it. The provider-visible
   run always names the policy digest and the evaluator identity in
   its output summary; the full gate-decision digest travels in the
   run body for audit.

   A published run whose candidate identity mismatches the evaluation
   input is surfaced as an operational failure, never silently
   corrected; a run published by any identity other than the
   configured evaluator is not a valid enforcement record.

   The provider interaction is an injectable checks-API fn-map:

     {:checks/list-runs   (fn [{:keys [owner repo head-sha]}] [run-maps])
      :checks/create-run! (fn [{:keys [owner repo]} body] run-map)
      :checks/update-run! (fn [{:keys [owner repo run-id]} body] run-map)}

   Run bodies use `:checks/...` keys; run-maps read back use
   `:check/...` keys. `fake-checks-api` is the in-memory,
   inspectable fake that drives tests with zero network; the
   adapter's logic is identical against the fake and the real
   `github-checks-api` (JDK HttpClient, no new production
   dependencies)."
  (:require [axiom.model :as model]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.time Duration)))

;; ------------------------------------------------------------------
;; Errors

(declare github-checks-api)

(defn- operational!
  "An operational failure: the provider, the network, the local
   environment, or an identity binding failed in a named way.
   Carries :axiom/error :operational (the CLI maps it to exit 5).
   Never thrown for malformed caller input — that is :invalid."
  [message data]
  (throw (ex-info message (assoc data :axiom/error :operational))))

(defn- invalid!
  [message data]
  (throw (ex-info message (assoc data :axiom/error :invalid))))

;; ------------------------------------------------------------------
;; Small shape predicates (private)

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- sha40? [x]
  (and (string? x) (boolean (re-matches #"[0-9a-f]{40}" x))))

(defn- positive-int? [x]
  (and (integer? x) (pos? x)))

(defn- repo-slug? [s]
  (and (non-blank-string? s)
       (boolean (re-matches #"[^/\s]+/[^/\s]+" s))))

;; ------------------------------------------------------------------
;; Capability-gated construction (T4)

(defn constructable?
  "Pure predicate: true iff the capability record permits the checks
   adapter to construct. All of: `:mode :enforcement`, the three R8
   answers true, a non-blank trusted evaluator, and the evaluator
   identity given to the constructor matching it. No network: the
   refusal is testable without touching the provider."
  [capability evaluator-id]
  (boolean
   (and (map? capability)
        (= :enforcement (:capability/mode capability))
        (true? (:capability/checks-write? capability))
        (true? (:capability/protection-readable? capability))
        (true? (:capability/protections-configurable? capability))
        (non-blank-string? (:capability/trusted-evaluator capability))
        (non-blank-string? evaluator-id)
        (= evaluator-id (:capability/trusted-evaluator capability)))))

(defn construct!
  "Builds the checks writer. Refuses — operational refusal, no
   network touched — without a passing R8 capability record: the
   caller then reports advisory mode instead of claiming
   enforcement. Options:

     :capability     the R8 capability record (required)
     :evaluator/id   the evaluator identity this adapter publishes as (required)
     :checks/owner   provider owner/org login (required)
     :checks/repo    repository name (required)
     :checks/api     checks-API fn-map (default: the real GitHub path)
     :checks/token-file  out-of-band token source for the real path"
  [{:keys [capability] :evaluator/keys [id] :checks/keys [owner repo api] :as opts}]
  (when-not (constructable? capability id)
    (operational! "Checks adapter refuses to construct: the capability record does not pass R8 (no enforcement mode). Report advisory mode instead of claiming enforcement."
                  {:checks/refusal :capability-not-enforcing
                   :capability/mode (:capability/mode capability)
                   :capability/advisory-reasons (:capability/advisory-reasons capability)}))
  (when-not (and (non-blank-string? owner) (non-blank-string? repo))
    (invalid! "Checks adapter requires :checks/owner and :checks/repo" {}))
  (let [api (or api (github-checks-api opts))]
    (doseq [k [:checks/list-runs :checks/create-run! :checks/update-run!]]
      (when-not (fn? (get api k))
        (invalid! (str "Checks API is missing required operation " k) {:checks/op k})))
    {:checks/adapter true
     :checks/owner owner
     :checks/repo repo
     :evaluator/id id
     :capability capability
     :checks/api api}))

;; ------------------------------------------------------------------
;; Identity binding and idempotency

(def check-run-name "axiom-gate")

(def ^:private publishable-decisions #{:allow :deny :defer})

(defn- decision->conclusion
  [decision]
  (case decision
    :allow :success
    :deny :failure
    :defer :neutral))

(defn external-id-for
  "The run's external ID: it encodes the gate decision digest, where
   the digest is taken over exactly the idempotency triple
   (candidate identity, gate set, policy digest). Republishing the
   same evaluation therefore addresses the same provider run and
   cannot duplicate logical effects."
  [candidate-identity gate-set policy-digest]
  (str "axiom-gate/"
       (subs (model/digest {:checks/candidate candidate-identity
                            :checks/gate-set (vec (sort-by pr-str gate-set))
                            :checks/policy-digest policy-digest})
             7)))

(defn- validate-candidate!
  [candidate]
  (when-not (and (map? candidate)
                 (repo-slug? (:candidate/repo candidate))
                 (positive-int? (:candidate/pr candidate))
                 (sha40? (:candidate/base candidate))
                 (sha40? (:candidate/head candidate))
                 (sha40? (:candidate/tree candidate)))
    (invalid! "publish! requires a well-formed candidate identity (repo slug, PR number, base/head/tree SHAs)"
              {:candidate candidate}))
  candidate)

(defn- validate-publishable!
  [decision]
  (when-not (map? decision)
    (invalid! "publish! requires a gate decision map" {}))
  (when-not (contains? publishable-decisions (:gate/decision decision))
    (invalid! "publish! cannot publish an :invalid (or unknown) decision: there is no valid evaluation to publish"
              {:gate/decision (:gate/decision decision)}))
  (when-not (non-blank-string? (:gate/evaluator decision))
    (invalid! "publish! requires the decision to name its evaluator identity" {}))
  (when-not (and (map? (:gate/policy decision))
                 (non-blank-string? (:policy/digest (:gate/policy decision))))
    (invalid! "publish! requires the decision to name its policy digest" {}))
  decision)

(defn- split-slug
  [slug]
  (let [[owner repo] (str/split slug #"/" 2)]
    (when-not (and (non-blank-string? owner) (non-blank-string? repo))
      (invalid! "Candidate repo is not an owner/name slug" {:candidate/repo slug}))
    [owner repo]))

(defn- run-summary
  "The provider-visible output summary: it always names the policy
   digest (with its approval event), the evaluator identity, the
   exact candidate identity, the gate decision and the named
   reasons."
  [decision candidate evaluator-id decision-digest]
  (let [policy (:gate/policy decision)
        reasons (:gate/reasons decision)]
    (str/join "\n"
              [(str "axiom enforced gate: " (name (:gate/decision decision)))
               (str "evaluator: " evaluator-id)
               (str "policy: " (:policy/id policy)
                    " digest " (:policy/digest policy)
                    " approval " (:policy/approval-event-id policy))
               (str "candidate: " (:candidate/repo candidate)
                    " PR #" (:candidate/pr candidate)
                    " base " (:candidate/base candidate)
                    " head " (:candidate/head candidate)
                    " tree " (:candidate/tree candidate))
               (str "decision digest: " decision-digest)
               "reasons:"
               (str/join "\n"
                         (map (fn [r]
                                (str "  - " (pr-str (:gate/id r))
                                     ": " (name (:gate/outcome r))
                                     " (" (name (:gate/reason r)) ")"))
                              reasons))])))

(defn publish!
  "The single write operation (T4): publishes the gate decision as a
   check run bound to the exact candidate identity and the
   configured evaluator identity.

   Identity binding (operational failures, never silent
   corrections):
   - the decision's `:gate/evaluator` must equal the adapter's
     configured evaluator — a run published by any other identity
     is not a valid enforcement record;
   - the `candidate-identity` argument must equal the decision's
     `:gate/candidate` — a mismatch is a defect surfaced as an
     operational failure.

   Idempotency: the external ID is the digest of (candidate
   identity, gate set, policy digest); the provider run with that
   external ID is updated, otherwise created. Republishing the same
   evaluation never duplicates logical effects.

   The candidate's repo slug must equal the adapter's configured
   target repository: a bound run is never published to a different
   repo than the one the deployment authorized.

   Returns `{:checks/published true :checks/mode :created/:updated
   ...}`. An `:invalid` decision is refused as invalid input."
  [adapter decision candidate-identity]
  (when-not (and (map? adapter) (:checks/adapter adapter))
    (invalid! "publish! requires a constructed checks adapter" {}))
  (let [decision (validate-publishable! decision)
        candidate (validate-candidate! candidate-identity)
        evaluator-id (:evaluator/id adapter)]
    (when-not (= evaluator-id (:gate/evaluator decision))
      (operational! "Evaluator identity mismatch: the decision was not produced by this adapter's configured evaluator; a run published by any other identity is not a valid enforcement record"
                    {:checks/refusal :evaluator-mismatch
                     :adapter/evaluator evaluator-id
                     :decision/evaluator (:gate/evaluator decision)}))
    (when-not (= (str (:checks/owner adapter) "/" (:checks/repo adapter))
                 (:candidate/repo candidate))
      (operational! "Candidate repo mismatch: the candidate is not in the adapter's configured target repository"
                    {:checks/mismatch :candidate-repo
                     :adapter/repo (str (:checks/owner adapter) "/" (:checks/repo adapter))
                     :candidate/repo (:candidate/repo candidate)}))
    (when-not (= candidate (:gate/candidate decision))
      (operational! "Candidate identity mismatch: the published run's candidate identity does not match the evaluation input"
                    {:checks/mismatch :candidate-identity
                     :evaluation/input candidate
                     :decision/candidate (:gate/candidate decision)}))
    (let [policy (:gate/policy decision)
          gate-set (vec (distinct (keep :gate/id (:gate/reasons decision))))
          external-id (external-id-for candidate gate-set (:policy/digest policy))
          decision-digest (model/digest decision)
          [owner repo] (split-slug (:candidate/repo candidate))
          head (:candidate/head candidate)
          api (:checks/api adapter)
          body {:checks/head-sha head
                :checks/name check-run-name
                :checks/external-id external-id
                :checks/conclusion (decision->conclusion (:gate/decision decision))
                :checks/title (str "axiom gate: " (name (:gate/decision decision)))
                :checks/summary (run-summary decision candidate evaluator-id decision-digest)
                :checks/decision-digest decision-digest}
          listed ((:checks/list-runs api) {:owner owner :repo repo :head-sha head})
          existing (first (filter #(= external-id (:check/external-id %)) listed))
          run (if existing
                ((:checks/update-run! api)
                 {:owner owner :repo repo :run-id (:check/run-id existing)} body)
                ((:checks/create-run! api) {:owner owner :repo repo} body))]
      (when-not (and (map? run)
                     (= external-id (:check/external-id run))
                     (= head (:check/head-sha run)))
        (operational! "Published run failed identity validation: the provider's run does not echo the bound candidate identity"
                      {:checks/defect :run-identity-mismatch
                       :checks/external-id external-id
                       :candidate/head head}))
      {:checks/published true
       :checks/mode (if existing :updated :created)
       :checks/external-id external-id
       :checks/run-id (:check/run-id run)
       :checks/conclusion (:checks/conclusion body)
       :checks/decision-digest decision-digest})))

;; ------------------------------------------------------------------
;; Fake in-memory Checks API (tests; zero network)

(defn fake-checks-api
  "In-memory fake of the Checks API. Records every attempted write
   in the inspectable `:checks/attempts` atom — each entry names the
   operation (`:checks/create-run` / `:checks/update-run`), its
   params, and the full request body. Published runs are stored
   keyed by external ID in `:checks/runs`, so republishing the same
   (candidate identity, gate set, policy digest) updates one logical
   run instead of duplicating it. No network, no threads."
  []
  (let [runs (atom {})
        attempts (atom [])
        next-id (atom 900000)
        record! (fn [op params body]
                  (swap! attempts conj {:attempt/op op
                                        :attempt/params params
                                        :attempt/body body}))
        run-of (fn [run-id owner repo body]
                 {:check/run-id run-id
                  :check/owner owner
                  :check/repo repo
                  :check/head-sha (:checks/head-sha body)
                  :check/name (:checks/name body)
                  :check/external-id (:checks/external-id body)
                  :check/conclusion (:checks/conclusion body)
                  :check/title (:checks/title body)
                  :check/summary (:checks/summary body)
                  :check/decision-digest (:checks/decision-digest body)})]
    {:checks/api
     {:checks/list-runs
      (fn [{:keys [owner repo head-sha]}]
        (filterv (fn [r] (and (= owner (:check/owner r))
                              (= repo (:check/repo r))
                              (= head-sha (:check/head-sha r))))
                 (vals @runs)))

      :checks/create-run!
      (fn [params body]
        (record! :checks/create-run params body)
        (doseq [k [:checks/head-sha :checks/name :checks/external-id
                   :checks/conclusion :checks/title :checks/summary]]
          (when (nil? (get body k))
            (operational! "Fake Checks API: create-run body is missing a required field"
                          {:field k})))
        (let [run (run-of (swap! next-id inc) (:owner params) (:repo params) body)]
          (swap! runs assoc (:check/external-id run) run)
          run))

      :checks/update-run!
      (fn [params body]
        (record! :checks/update-run params body)
        (let [existing (first (filter #(= (:run-id params) (:check/run-id %))
                                      (vals @runs)))]
          (when-not existing
            (operational! "Fake Checks API: update-run for an unknown run id"
                          {:run-id (:run-id params)}))
          (let [run (merge existing (run-of (:check/run-id existing)
                                            (:check/owner existing)
                                            (:check/repo existing)
                                            body))]
            (swap! runs assoc (:check/external-id run) run)
            run)))}
     :checks/attempts attempts
     :checks/runs runs}))

;; ------------------------------------------------------------------
;; Real GitHub Checks API write path (JDK HttpClient; no new deps)

(def ^:private real-config-defaults
  {:api-base "https://api.github.com"
   :api-version "2022-11-28"
   :user-agent "axiom-checks-writer/0.1.0-offline"
   :connect-timeout-ms 5000
   :request-timeout-ms 30000})

(defn- resolve-token!
  "Resolves the Checks credential out-of-band only: `:checks/token-file`
   (a path whose contents are read and trimmed) wins over the
   `AXIOM_CHECKS_TOKEN` environment variable. A raw token is never
   accepted as an option and never appears in errors or logs."
  [{:checks/keys [token-file]}]
  (let [token (if (some? token-file)
                (let [file (io/file ^String token-file)]
                  (when-not (.isFile file)
                    (operational! "Checks token file is not readable"
                                  {:checks/token-file token-file}))
                  (str/trim (slurp file :encoding "UTF-8")))
                (System/getenv "AXIOM_CHECKS_TOKEN"))]
    (when (str/blank? token)
      (operational! "Checks API credential missing: set AXIOM_CHECKS_TOKEN or pass :checks/token-file" {}))
    token))

(defn- json-write
  "Minimal JSON writer for provider request bodies."
  [x]
  (let [escape (fn [^String s]
                 (-> s
                     (str/replace "\\" "\\\\")
                     (str/replace "\"" "\\\"")
                     (str/replace "\n" "\\n")
                     (str/replace "\r" "\\r")
                     (str/replace "\t" "\\t")
                     (str/replace "\b" "\\b")
                     (str/replace "\f" "\\f")))]
    (cond
      (nil? x) "null"
      (true? x) "true"
      (false? x) "false"
      (string? x) (str "\"" (escape x) "\"")
      (keyword? x) (json-write (name x))
      (integer? x) (str x)
      (map? x) (str "{" (str/join ","
                                  (map (fn [[k v]]
                                         (str (json-write (if (keyword? k) (name k) (str k)))
                                              ":" (json-write v)))
                                       x))
                   "}")
      (sequential? x) (str "[" (str/join "," (map json-write x)) "]")
      :else (operational! "Cannot encode value as JSON for the Checks API"
                          {:value-type (str (type x))}))))

(defn- json-read
  "Minimal JSON reader for provider check-run responses (objects with
   string keys, arrays, strings, numbers, literals)."
  [^String s]
  (let [n (count s)
        pos (atom 0)
        fail #(operational! "Checks API response body is not valid JSON"
                            {:at @pos :detail (str %)})]
    (letfn [(peek-char [] (when (< @pos n) (.charAt s ^int @pos)))
            (next-char [] (let [c (peek-char)] (swap! pos inc) c))
            (skip-ws [] (loop [] (let [c (peek-char)]
                                   (when (and c (Character/isWhitespace ^char c))
                                     (next-char) (recur)))))
            (expect [c] (skip-ws)
              (if (= c (peek-char)) (next-char)
                  (fail (str "expected " c))))
            (parse-string []
              (expect \")
              (let [sb (StringBuilder.)]
                (loop []
                  (let [c (next-char)]
                    (cond
                      (nil? c) (fail "unterminated string")
                      (= c \") (str sb)
                      (= c \\)
                      (let [e (next-char)]
                        (case e
                          \" (.append sb \")
                          \\ (.append sb \\)
                          \/ (.append sb \/)
                          \b (.append sb \backspace)
                          \f (.append sb \formfeed)
                          \n (.append sb \newline)
                          \r (.append sb \return)
                          \t (.append sb \tab)
                          \u (let [hex (apply str (repeatedly 4 next-char))]
                               (.append sb (char (Integer/parseInt hex 16))))
                          (fail (str "bad escape: \\" e)))
                        (recur))
                      :else (do (.append sb c) (recur)))))))
            (parse-number []
              (skip-ws)
              (let [m (re-find #"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"
                               (subs s @pos))]
                (when-not m (fail "expected number"))
                (swap! pos + (count m))
                (if (re-find #"[\.eE]" m)
                  (Double/parseDouble m)
                  (Long/parseLong m))))
            (parse-literal [text value]
              (skip-ws)
              (if (str/starts-with? (subs s @pos) text)
                (do (swap! pos + (count text)) value)
                (fail (str "expected " text))))
            (parse-value []
              (skip-ws)
              (let [c (peek-char)]
                (cond
                  (nil? c) (fail "unexpected end of input")
                  (= c \") (parse-string)
                  (= c \{) (parse-object)
                  (= c \[) (parse-array)
                  (= c \t) (parse-literal "true" true)
                  (= c \f) (parse-literal "false" false)
                  (= c \n) (parse-literal "null" nil)
                  (or (= c \-) (Character/isDigit ^char c)) (parse-number)
                  :else (fail (str "unexpected character: " c)))))
            (parse-array []
              (expect \[)
              (loop [items []]
                (skip-ws)
                (if (= \] (peek-char))
                  (do (next-char) items)
                  (let [v (parse-value)]
                    (skip-ws)
                    (let [c (peek-char)]
                      (cond (= c \,) (do (next-char) (recur (conj items v)))
                            (= c \]) (do (next-char) (conj items v))
                            :else (fail "expected , or ] in array")))))))
            (parse-object []
              (expect \{)
              (loop [obj {}]
                (skip-ws)
                (if (= \} (peek-char))
                  (do (next-char) obj)
                  (let [k (parse-string)]
                    (skip-ws) (expect \:)
                    (let [v (parse-value)]
                      (skip-ws)
                      (let [c (peek-char)]
                        (cond (= c \,) (do (next-char) (recur (assoc obj k v)))
                              (= c \}) (do (next-char) (assoc obj k v))
                              :else (fail "expected , or } in object"))))))))]
      (let [v (parse-value)]
        (skip-ws)
        (when (< @pos n) (fail "trailing characters after JSON value"))
        v))))

(def ^:private next-link-re #"<([^>]+)>\s*;\s*rel=\"next\"")

(defn- http-call!
  "One provider request. 401/403/404 and any unexpected status are
   terminal operational failures naming the status and resource.
   429/transient 5xx are operational failures too: the write path
   does not retry (a retry could race a create), and callers re-run
   `publish!`, which is idempotent by external ID."
  [client config token method url body resource]
  (let [builder (doto (HttpRequest/newBuilder (URI/create url))
                  (.timeout (Duration/ofMillis (:request-timeout-ms config)))
                  (.header "User-Agent" (:user-agent config))
                  (.header "Accept" "application/vnd.github+json")
                  (.header "X-GitHub-Api-Version" (:api-version config))
                  (.header "Authorization" (str "Bearer " token)))
        publisher (if (some? body)
                    (HttpRequest$BodyPublishers/ofString ^String body)
                    (HttpRequest$BodyPublishers/noBody))
        request (.build (.method builder ^String method publisher))
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status (.statusCode response)
        headers (into {} (map (fn [[k v]] [(str/lower-case k) (vec v)])
                              (.map (.headers response))))
        parsed (when (seq (.body response)) (json-read (.body response)))]
    (cond
      (#{200 201} status) {:status status :headers headers :body parsed}
      :else (operational! (str "Checks API failure: HTTP " status " for " resource)
                          {:status status :resource resource :url url}))))

(defn- provider-run
  "Normalizes one provider check-run object into the run-map shape."
  [obj resource]
  (when-not (map? obj)
    (operational! "Malformed Checks API payload: expected a check-run object"
                  {:resource resource}))
  (let [run-id (get obj "id")
        external-id (get obj "external_id")
        head-sha (get obj "head_sha")]
    (when-not (and (integer? run-id) (pos? run-id))
      (operational! "Malformed Checks API payload: check-run without a numeric id"
                    {:resource resource}))
    {:check/run-id run-id
     :check/external-id external-id
     :check/head-sha head-sha}))

(defn- conclusion->provider
  [c]
  (case c
    :success "success"
    :failure "failure"
    :neutral "neutral"
    (operational! "Unknown check conclusion for the Checks API" {:conclusion c})))

(defn github-checks-api
  "The real Checks API write path over the provider REST API
   (JDK HttpClient; no new production dependencies). The credential
   resolves out-of-band only (`:checks/token-file` or
   `AXIOM_CHECKS_TOKEN`); the token value never appears in errors or
   logs. Reads are locate-only for the idempotent upsert: `publish!`
   lists the head SHA's runs to find the one with the matching
   external ID, then creates or updates exactly one check run. No
   other provider mutation exists on this path."
  [{:checks/keys [api-base user-agent api-version] :as opts}]
  (let [config (merge real-config-defaults
                      (cond-> {}
                        (some? api-base) (assoc :api-base api-base)
                        (some? user-agent) (assoc :user-agent user-agent)
                        (some? api-version) (assoc :api-version api-version)))
        token (resolve-token! opts)
        client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofMillis (:connect-timeout-ms config)))
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))
        call (fn [method url body resource]
               (http-call! client config token method url body resource))]
    {:checks/list-runs
     (fn [{:keys [owner repo head-sha]}]
       (let [resource (str "check runs for " owner "/" repo "@" head-sha)]
         (loop [url (str (:api-base config) "/repos/" owner "/" repo
                         "/commits/" head-sha "/check-runs?per_page=100")
                  acc []]
           (let [{:keys [status headers body]} (call "GET" url nil resource)
                 runs (get body "check_runs")]
             (when-not (sequential? runs)
               (operational! "Malformed Checks API payload: expected \"check_runs\" array"
                             {:resource resource}))
             (let [acc (into acc (map #(provider-run % resource) runs))
                   next-url (some (fn [[_ u]] u)
                                  (re-seq next-link-re
                                          (str/join "," (get headers "link" []))))]
               (if (some? next-url)
                 (recur next-url acc)
                 acc))))))

     :checks/create-run!
     (fn [{:keys [owner repo]} body]
       (let [resource (str "create check run on " owner "/" repo)
             payload {"name" (:checks/name body)
                      "head_sha" (:checks/head-sha body)
                      "external_id" (:checks/external-id body)
                      "status" "completed"
                      "conclusion" (conclusion->provider (:checks/conclusion body))
                      "output" {"title" (:checks/title body)
                                "summary" (:checks/summary body)}}
             {:keys [body]} (call "POST"
                                  (str (:api-base config) "/repos/" owner "/" repo "/check-runs")
                                  (json-write payload)
                                  resource)
             run (provider-run body resource)]
         (assoc run
                :check/head-sha (:checks/head-sha body)
                :check/external-id (:checks/external-id body))))

     :checks/update-run!
     (fn [{:keys [owner repo run-id]} body]
       (let [resource (str "update check run " run-id " on " owner "/" repo)
             payload {"status" "completed"
                      "conclusion" (conclusion->provider (:checks/conclusion body))
                      "output" {"title" (:checks/title body)
                                "summary" (:checks/summary body)}}
             {:keys [body]} (call "PATCH"
                                  (str (:api-base config) "/repos/" owner "/" repo
                                       "/check-runs/" run-id)
                                  (json-write payload)
                                  resource)
             run (provider-run body resource)]
         (assoc run
                :check/head-sha (:checks/head-sha body)
                :check/external-id (:checks/external-id body))))}))
