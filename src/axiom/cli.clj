(ns axiom.cli
  (:require [axiom.adapters.checks :as checks-adapter]
            [axiom.adapters.git :as git-adapter]
            [axiom.adapters.github :as github-adapter]
            [axiom.adapters.runner :as runner-adapter]
            [axiom.capability :as capability]
            [axiom.contract :as contract]
            [axiom.gate :as gate]
            [axiom.github :as github]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.nomos :as nomos]
            [axiom.policy :as policy]
            [axiom.store :as store]
            [axiom.supervisor :as supervisor]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CodingErrorAction StandardCharsets)
           (java.nio.file Files Paths)))

(defn- read-input [path]
  (with-open [stream (Files/newInputStream (Paths/get path (make-array String 0))
                                          (make-array java.nio.file.OpenOption 0))]
    (let [bytes (.readNBytes stream (inc contract/max-bytes))]
      (when (> (alength bytes) contract/max-bytes)
        (throw (ex-info "EDN byte limit exceeded" {:axiom/error :invalid})))
      (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                      (.onMalformedInput CodingErrorAction/REPORT)
                      (.onUnmappableCharacter CodingErrorAction/REPORT))]
        (try
          (contract/read-data (str (.decode decoder (ByteBuffer/wrap bytes))))
          (catch java.nio.charset.CharacterCodingException _
            (throw (ex-info "Invalid UTF-8 input" {:axiom/error :invalid}))))))))

(defn- usage []
  {:exit 4 :output {:error :usage
                    :message (str "axiom {validate|evaluate|status|next} --input SCENARIO.edn"
                                  " | axiom explain --input SCENARIO.edn [--decision DECISION_ID]"
                                  " | axiom replay --ledger LEDGER.db [--through SEQ]"
                                  " | axiom replay --bundle BUNDLE.edn"
                                  " | axiom export-bundle --ledger LEDGER.db --output BUNDLE.edn [--through SEQ]")}})

(def ^:private read-commands #{"validate" "evaluate" "status" "next"})

(defn- parse-through
  "Parses a --through sequence argument; nil when malformed."
  [s]
  (try (let [n (Long/parseLong ^String s)]
         (when (>= n 0) n))
       (catch NumberFormatException _ nil)))

(defn- resolve-through
  "Resolves the --through argument against the ledger head. Missing means
   the full ledger. Out-of-range values are invalid input."
  [through-s last-seq]
  (let [through (if (some? through-s)
                  (or (parse-through through-s)
                      (model/invalid! "Invalid --through sequence" {:value through-s}))
                  last-seq)]
    (when (or (< through -1) (> through last-seq))
      (model/invalid! "Through sequence out of range" {:through through :head last-seq}))
    through))

(defn- open-ledger!
  "Opens a ledger read-only: never creates, never migrates. A missing file
   is invalid input; a newer-than-supported schema is operational."
  [path]
  (store/open! path {:create false :migrate false}))

(defn- replay-ledger! [path through-s]
  (let [handle (open-ledger! path)]
    (try
      (let [identity (store/ledger-identity handle)
            last-seq (dec (:event/count identity))
            through (resolve-through through-s last-seq)
            envelopes (if (>= through 0) (store/read-range handle 0 through) [])
            report (ledger/replay-report
                    {:source {:kind :ledger :path path}
                     :schema/version (:schema/version identity)
                     :envelopes envelopes
                     :snapshot (store/latest-snapshot handle)})]
        {:exit 0 :output report})
      (finally (store/close! handle)))))

(defn- read-bundle-file!
  [path]
  (try (read-input path)
       (catch java.io.IOException _
         (model/invalid! "Cannot read bundle file" {:path path}))))

(defn- replay-bundle! [path]
  (let [bundle (ledger/read-bundle-data (read-bundle-file! path))
        report (ledger/replay-report
                {:source {:kind :bundle :path path}
                 :schema/version (get-in bundle [:ledger :schema/version])
                 :envelopes (:events bundle)
                 :snapshot (:snapshot bundle)})]
    {:exit 0 :output report}))

(defn- export-bundle! [path output through-s]
  (let [handle (open-ledger! path)]
    (try
      (let [identity (store/ledger-identity handle)
            last-seq (dec (:event/count identity))
            through (resolve-through through-s last-seq)
            envelopes (if (>= through 0) (store/read-range handle 0 through) [])
            snapshot (store/latest-snapshot handle)
            usable (when (and snapshot (<= (:snapshot/seq snapshot) through)) snapshot)
            engine {:axiom/version model/engine-version
                    :axiom/commit (git-adapter/current-commit-sha)
                    :clojure/version (clojure-version)
                    :reducer/version ledger/reducer-version}
            bundle (ledger/export-bundle-data
                    {:engine engine
                     :schema/version (:schema/version identity)
                     :envelopes envelopes
                     :snapshot usable})]
        (spit output (model/edn-str bundle))
        {:exit 0 :output {:exported? true :path output
                          :bundle/digest (:bundle/digest bundle)
                          :through/seq through
                          :event/count (count envelopes)}})
      (finally (store/close! handle)))))

(defn- usage-observations []
  "Usage for the 0003 observation commands. Kept separate from
   `usage` so the 0001/0002 usage text stays byte-identical."
  {:exit 4 :output {:error :usage
                    :message (str "axiom observe-git --repo PATH [--base REV]"
                                  " | axiom digest --path FILE [--media-type TYPE]"
                                  " | axiom run --command ID [--args k=v ...]")}})

(defn- observe-git!
  "Thin adapter over `axiom.adapters.git/observe!`: emits the EDN Git
   observation report. Read-only: never creates or migrates ledger
   files, never mutates the observed repository."
  [operands]
  (let [[f1 v1 f2 v2] operands]
    (if (and (= "--repo" f1) (string? v1)
             (or (and (nil? f2) (= 2 (count operands)))
                 (and (= "--base" f2) (string? v2) (= 4 (count operands)))))
      (let [observation (git-adapter/observe!
                         (cond-> {:repo v1} (some? v2) (assoc :base v2)))]
        ;; A git step failure yields a validated :incomplete observation
        ;; naming the failing step: still an honest EDN report, but the
        ;; observation itself failed — exit 5 (R7). A missing repository
        ;; is :invalid (exit 4); a rejected traversal is :operational
        ;; (exit 5), via the shared error mapping below.
        {:exit (if (= :complete (:observation/status observation)) 0 5)
         :output observation})
      (usage-observations))))

(defn- digest-file!
  "Thin digest of an artifact file: SHA-256 over the exact bytes
   (`axiom.model/sha256-bytes`), validated media type (default
   application/octet-stream) and byte size. Read-only: never creates
   or migrates ledger files."
  [operands]
  (let [[f1 v1 f2 v2] operands]
    (if (and (= "--path" f1) (string? v1)
             (or (and (nil? f2) (= 2 (count operands)))
                 (and (= "--media-type" f2) (string? v2) (= 4 (count operands)))))
      (let [media-type (or v2 store/default-media-type)
            file (io/file ^String v1)]
        (when-not (store/media-type? media-type)
          (model/invalid! "Invalid media type" {:media-type media-type}))
        (when-not (.exists file)
          (model/invalid! "File does not exist" {:path v1}))
        (when-not (.isFile file)
          (model/invalid! "Not a regular file" {:path v1}))
        (let [bytes (try (Files/readAllBytes (.toPath file))
                         (catch java.io.IOException e
                           (throw (ex-info "Cannot read file"
                                           {:axiom/error :operational :path v1} e))))]
          {:exit 0
           :output {:artifact/digest (model/sha256-bytes bytes)
                    :artifact/media-type media-type
                    :artifact/size-bytes (alength ^bytes bytes)
                    :artifact/location v1}}))
      (usage-observations))))

(defn- parse-arg-kv
  "Parses one k=v operand on the first '='; nil when malformed (empty
   key or no '=')."
  [s]
  (let [idx (str/index-of ^String s "=")]
    (when (and (some? idx) (pos? ^long idx))
      [(subs s 0 idx) (subs s (inc idx))])))

(defn- coerce-slot-value
  "Coerces one k=v string operand to the slot's declared type. Integer
   slots parse as longs (unparseable input is :invalid); string and
   enum slots take the raw string — the pure port validates membership
   and shape."
  [command-id slot-name slot-type s]
  (if (= :integer slot-type)
    (try (Long/parseLong ^String s)
         (catch NumberFormatException _
           (model/invalid! "Integer argument requires an integer value"
                           {:command/id command-id :slot/name slot-name :value s})))
    s))

(defn- run-diagnostic!
  "Thin adapter over `axiom.adapters.runner/run!`: executes the
   checked-in registry command against its configured working directory
   and emits the Evidence record (EDN). Honest about execution: it only
   ever runs within the registry's configured working directory."
  [operands]
  (let [[f1 v1 f2 & kvs] operands]
    (if (and (= "--command" f1) (string? v1) (not (str/blank? v1))
             (or (and (nil? f2) (= 2 (count operands)))
                 (and (= "--args" f2) (seq kvs) (every? string? kvs)
                      (= (+ 3 (count kvs)) (count operands)))))
      (let [pairs (mapv parse-arg-kv kvs)]
        (if (or (some nil? pairs)
                (not= (count pairs) (count (distinct (map first pairs)))))
          (usage-observations)
          ;; The CLI owns argument parsing: k=v strings are coerced to
          ;; the slots' declared types before the adapter validates.
          (let [registry (runner-adapter/load-registry!)
                command (get-in registry [:commands v1])]
            (when-not command
              (model/invalid! "Unknown command ID" {:command/id v1}))
            (let [slot-types (into {}
                                   (map (fn [slot] [(:slot/name slot) (:slot/type slot)]))
                                   (filter map? (:command/args command)))
                  args (into {}
                             (map (fn [[k v]]
                                    [k (coerce-slot-value v1 k (get slot-types k :string) v)]))
                             pairs)
                  record (runner-adapter/run!
                          {:registry registry
                           :command/id v1
                           :args args
                           ;; The CLI runs a diagnostic with no candidate
                           ;; under evaluation; candidate SHAs are nil-able.
                           :candidate {:candidate/base nil :candidate/head nil
                                       :candidate/tree nil}})]
              ;; A completed run (pass or fail) is a valid report: exit 0.
              ;; A timeout, cancellation or output-cap violation yields an
              ;; honest :incomplete Evidence record naming the bound — the
              ;; evidence is incomplete, so the run failed operationally:
              ;; exit 5 (R7).
              {:exit (if (:run/complete? record) 0 5)
               :output record}))))
      (usage-observations))))

;; ------------------------------------------------------------------
;; Spec 0006 commands: run-task (T8)

(defn- run-task!
  "Thin adapter over `axiom.supervisor/run-task`: drives one
   synthetic task through the guarded loop and emits the EDN
   report. Exit 0: valid report (the task may have completed or
   been blocked with a named blocker — the run itself is valid).
   Exit 4: invalid input (malformed task EDN, unknown adapter).
   Exit 5: operational failure."
  [operands]
  (let [[f1 v1 f2 v2] operands]
    (if (and (= "--task" f1) (string? v1)
             (= "--adapter" f2) (string? v2)
             (= 4 (count operands)))
      (let [{:keys [supervisor/exit supervisor/report]}
            (supervisor/run-task {:task/definition (slurp v1)
                                  :task/adapter v2})]
        {:exit exit :output report})
      {:exit 4 :output {:error :usage
                        :message "axiom run-task --task TASK-EDN --adapter fake|process"}})))

;; ------------------------------------------------------------------
;; Spec 0004 commands: observe-github / check-pr (T5)

(defn- usage-0004
  "Usage for the 0004 GitHub observation commands. Kept separate from
   `usage` so the 0001/0002 usage text stays byte-identical."
  []
  {:exit 4 :output {:error :usage
                    :message (str "axiom observe-github --repo OWNER/NAME --pr N [--sha SHA] [--token-file PATH]"
                                  " | axiom check-pr --repo OWNER/NAME --pr N [--token-file PATH]")}})

(defn- parse-flag-pairs
  "Parses operands as --flag value pairs into a map; nil when malformed
   (odd operand count, unknown flag, non-string value, duplicate flag)."
  [operands allowed]
  (when (even? (count operands))
    (let [pairs (map vec (partition 2 operands))]
      (when (and (seq pairs)
                 (every? (fn [[f v]] (and (contains? allowed f) (string? v))) pairs)
                 (= (count pairs) (count (distinct (map first pairs)))))
        (into {} pairs)))))

(defn- parse-repo-slug
  "Splits an OWNER/NAME slug into [owner name]; nil when the slug does
   not have exactly two slash-separated segments. Segment shape is
   validated by the adapter (invalid owner/repo names are :invalid)."
  [slug]
  (let [parts (str/split ^String slug #"/" -1)]
    (when (= 2 (count parts)) parts)))

(defn- parse-pr-number
  "Parses a --pr argument as a long; unparseable input is :invalid.
   Positivity is validated by the adapter."
  [s]
  (try (Long/parseLong ^String s)
       (catch NumberFormatException _
         (model/invalid! "Invalid --pr number" {:pr s}))))

(defn- sha40?
  "A 40-hex commit SHA."
  [s]
  (and (string? s) (boolean (re-matches #"[0-9a-f]{40}" s))))

(defn- github-fixture-fetch-fn
  "Fixture-mode fetch for offline runs: when AXIOM_GITHUB_FIXTURES names
   an EDN fixture file (provider response snapshots keyed by request
   URL, per the port's fixture format), returns an injected fetch fn;
   nil otherwise, in which case the adapter uses the real network. This
   is the CLI's offline-reproduction hook: `scripts/check` drives the
   0004 gates through it with synthetic fixtures, no network."
  []
  (when-let [path (System/getenv "AXIOM_GITHUB_FIXTURES")]
    (:fetch/fn (github-adapter/fixture-fetch (read-input path)))))

(defn- github-env
  "The environment map the GitHub adapter reads AXIOM_GITHUB_TOKEN from."
  []
  (into {} (System/getenv)))

(defn- github-observe-opts
  "Builds the adapter option map from parsed CLI flags: owner, repo,
   pr, the optional :token-file, the real environment, and the
   fixture-mode fetch when AXIOM_GITHUB_FIXTURES is set. A raw token is
   never accepted here — credentials resolve out-of-band only."
  [owner repo pr token-file]
  (let [fetch-fn (github-fixture-fetch-fn)]
    (cond-> {:owner owner :repo repo :pr pr :env (github-env)}
      (some? token-file) (assoc :token-file token-file)
      (some? fetch-fn) (assoc :fetch-fn fetch-fn))))

(defn- github-args!
  "Shared argument handling for the 0004 commands: parses the flag
   pairs (allowed set varies per command) and validates the repo slug,
   PR number and SHA shapes. Returns [owner repo pr sha token-file].
   Malformed flags return the usage map (exit 4); invalid values throw
   :invalid (exit 4)."
  [operands allowed]
  (let [opts (parse-flag-pairs operands allowed)]
    (if (and opts (get opts "--repo") (get opts "--pr"))
      (let [[owner repo] (parse-repo-slug (get opts "--repo"))]
        (when (nil? owner)
          (model/invalid! "Malformed --repo slug; expected OWNER/NAME" {:repo (get opts "--repo")}))
        (let [pr (parse-pr-number (get opts "--pr"))
              sha (get opts "--sha")]
          (when (and (some? sha) (not (sha40? sha)))
            (model/invalid! "Invalid --sha; expected a 40-hex commit SHA" {:sha sha}))
          [owner repo pr sha (get opts "--token-file")]))
      (usage-0004))))

(def ^:private default-check-pr-gates
  "The CLI's default advisory gate set for `check-pr`: PR identity, one
   approval recorded on the head SHA, and merge state. Required-checks
   needs repository-specific job names and selection rules, so it is
   not in the default set — consumers with explicit job lists call the
   pure `axiom.github/check-pr` directly."
  [{:gate/id :pr-identity}
   {:gate/id :approvals :required/approvals 1}
   {:gate/id :merge-state}])

(defn- observe-github!
  "Thin adapter over `axiom.adapters.github/observe!`: emits the EDN
   GitHub observation report. Read-only: never mutates provider state,
   never creates, migrates or writes ledger files (recording an
   observation goes through the 0002 append API, not the CLI).

   --repo OWNER/NAME and --pr N are required (--pr because the adapter
   observes pull requests; repository-at-SHA observation without a PR
   is not implemented). --sha SHA optionally pins the expected head
   SHA: a PR whose head moved is invalid input (exit 4).
   --token-file PATH selects the credential file (missing/unreadable:
   exit 4); otherwise AXIOM_GITHUB_TOKEN or anonymous observation.

   Exit 0 on a complete validated report; exit 5 when the observation
   is :observation/incomplete (failed page after bounded retries,
   rate-limit exhaustion, stale cache) or a terminal API failure names
   its reason."
  [operands]
  (let [parsed (github-args! operands #{"--repo" "--pr" "--sha" "--token-file"})]
    (if (map? parsed)
      parsed
      (let [[owner repo pr sha token-file] parsed
            observation (github-adapter/observe!
                         (github-observe-opts owner repo pr token-file))]
        (if (= :complete (:observation/status observation))
          (do
            (when (and (some? sha)
                       (not= sha (get-in observation [:subject :git/head])))
              (model/invalid! "Observed head SHA does not match --sha"
                              {:expected sha
                               :observed (get-in observation [:subject :git/head])}))
            {:exit 0 :output observation})
          ;; An incomplete observation is an honest operational failure
          ;; naming the failing collection and page (R7: exit 5).
          {:exit 5 :output observation})))))

(defn- check-pr!
  "Thin adapter over `axiom.adapters.github/observe!` and the pure
   `axiom.github/check-pr`: emits the advisory check-pr report (EDN)
   with per-gate outcomes bound to the exact base/head SHAs, exact
   evidence links and the advisory-only can-merge summary. Advisory
   only: not enforcement, not a merge, not a published check.
   Read-only: never mutates provider state, never creates, migrates or
   writes ledger files. Exit 0 on a valid advisory report; 4 on invalid
   input; 5 when the underlying observation is incomplete."
  [operands]
  (let [parsed (github-args! operands #{"--repo" "--pr" "--token-file"})]
    (if (map? parsed)
      parsed
      (let [[owner repo pr _sha token-file] parsed
            observation (github-adapter/observe!
                         (github-observe-opts owner repo pr token-file))]
        (if (= :complete (:observation/status observation))
          {:exit 0 :output (github/check-pr observation default-check-pr-gates)}
          {:exit 5 :output observation})))))

;; ------------------------------------------------------------------
;; Spec 0005 commands: gate / publish-check / policy-approve (T6)

(defn- usage-0005
  "Usage for the 0005 enforced-gate commands. Kept separate from
   `usage` so the 0001/0002 usage text stays byte-identical."
  []
  {:exit 4 :output {:error :usage
                    :message (str "axiom gate --repo OWNER/NAME --pr N [--policy DIGEST]"
                                  " | axiom publish-check --repo OWNER/NAME --pr N [--ledger PATH]"
                                  " | axiom policy-approve --policy PATH --approver ID [--ledger PATH]")}})

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- positive-int? [x]
  (and (integer? x) (pos? x)))

(defn- sha256-digest?
  "A sha256:<64 hex> content digest."
  [x]
  (and (string? x) (boolean (re-matches #"sha256:[0-9a-f]{64}" x))))

(defn- gate-fixture-fn
  "Fixture-mode hook for the 0005 commands, following the
   AXIOM_GITHUB_FIXTURES pattern: when AXIOM_GATE_FIXTURES names an
   EDN fixture file (candidate observations keyed by repo slug and PR
   number, the approved-policy content, the policy approval, the
   evaluator identity and the R8 capability record), returns the
   parsed fixture map; nil otherwise. `scripts/check` drives the
   0005 gates through it with synthetic fixtures and the fake Checks
   API: zero network. Live provider wiring for observations and
   policy sources is deployment-specific and is not implemented in
   this slice: without the hook the 0005 commands report an
   operational failure naming what is missing."
  []
  (when-let [path (System/getenv "AXIOM_GATE_FIXTURES")]
    (read-input path)))

(defn- gate-fixtures!
  "Loads the fixture map for the 0005 commands. A missing hook or a
   malformed fixture shape is operational (5): the deployment has no
   observation source configured."
  []
  (let [fixtures (gate-fixture-fn)]
    (when (nil? fixtures)
      (throw (ex-info "No gate observation source configured: set AXIOM_GATE_FIXTURES to a fixture file (live provider wiring is deployment-specific)"
                      {:axiom/error :operational})))
    (when-not (and (map? fixtures)
                   (non-blank-string? (:fixture/evaluator-id fixtures))
                   (map? (:fixture/policy-content fixtures))
                   (map? (:fixture/capability fixtures))
                   (map? (:fixture/observations fixtures)))
      (throw (ex-info "AXIOM_GATE_FIXTURES names a malformed fixture map"
                      {:axiom/error :operational})))
    fixtures))

(defn- fixture-approval-events
  "Builds the recorded `:governance/policy-approved` event for the
   fixture policy content from the fixture's approval descriptor
   (`:fixture/policy-approval`), using the same constructor the
   `policy-approve` command uses. The digest is pinned over the
   canonical EDN encoding, never hand-written."
  [fixtures]
  (let [content (:fixture/policy-content fixtures)
        approval (:fixture/policy-approval fixtures)]
    (if (nil? approval)
      []
      [(policy/policy-approve-event
        content
        (:approver approval)
        {:event-id (:event-id approval)
         :policy-id (:policy/id content)
         :approved-at (:approved-at approval)})])))

(defn- gate-ledger-env
  "The AXIOM_GATE_LEDGER fallback for governance-ledger commands."
  []
  (System/getenv "AXIOM_GATE_LEDGER"))

(defn- gate-cli-args!
  "Shared argument handling for `gate` and `publish-check`: parses
   the flag pairs (allowed set varies per command) and validates the
   repo slug, PR number and policy-digest shapes. Returns
   [slug pr digest ledger-path]; malformed flags return the usage map
   (exit 4); invalid values throw :invalid (exit 4)."
  [operands allowed]
  (let [opts (parse-flag-pairs operands allowed)]
    (if (and opts (get opts "--repo") (get opts "--pr"))
      (let [[owner repo] (parse-repo-slug (get opts "--repo"))]
        (when (nil? owner)
          (model/invalid! "Malformed --repo slug; expected OWNER/NAME" {:repo (get opts "--repo")}))
        (let [pr (parse-pr-number (get opts "--pr"))
              digest (get opts "--policy")]
          (when (and (some? digest) (not (sha256-digest? digest)))
            (model/invalid! "Invalid --policy digest; expected sha256:<64 hex>" {:policy digest}))
          [(str owner "/" repo) pr digest (or (get opts "--ledger") (gate-ledger-env))]))
      (usage-0005))))

(defn- gate-observation!
  "Looks up the candidate observation for (repo slug, pr) in the
   fixtures. An unknown PR is invalid input (exit 4)."
  [fixtures slug pr]
  (let [observation (get-in fixtures [:fixture/observations slug pr])]
    (when (nil? observation)
      (model/invalid! "Unknown PR for the configured gate fixtures" {:repo slug :pr pr}))
    observation))

(defn- candidate-identity
  "The exact candidate identity echoed into deferred decisions, built
   from the observation's validated identity fields; nil when the
   fixture observation is malformed."
  [observation]
  (let [repo (:observation/repo observation)
        pr (:observation/pr observation)
        base (:observation/base observation)
        head (:observation/head observation)
        tree (:observation/tree observation)]
    (when (and (non-blank-string? repo) (positive-int? pr)
               (sha40? base) (sha40? head) (sha40? tree))
      {:candidate/repo repo :candidate/pr pr
       :candidate/base base :candidate/head head
       :candidate/tree tree})))

(defn- resolve-gate-policy
  "Resolves the fixture policy content against the fixture
   governance events (`axiom.policy/resolve`). Returns the policy
   map for `axiom.gate/evaluate`, `{:deferred resolution}` when the
   digest has no approval event, or `{:invalid resolution}` when the
   source is structurally refused. A requested digest that does not
   match the deployment's configured policy content is invalid input
   (4): the caller named a policy the deployment does not have, and
   the gate never evaluates content it was not configured with."
  [fixtures requested-digest]
  (let [content (:fixture/policy-content fixtures)
        source (or (:fixture/policy-source fixtures)
                   {:source/type :pinned-path
                    :source/policy-id (:policy/id content)})
        computed (policy/content-digest content)]
    (when (and (some? requested-digest) (not= requested-digest computed))
      (model/invalid! "The requested --policy digest does not match the deployment's configured policy content"
                      {:requested requested-digest :configured computed}))
    (let [resolution (policy/resolve source content
                                     (fixture-approval-events fixtures)
                                     nil)]
      (case (:policy/resolution resolution)
        :ok {:policy/id (:policy/id resolution)
             :policy/digest (:policy/digest resolution)
             :policy/approval-event-id (:policy/approval-event-id resolution)
             :policy/gates (get-in resolution [:policy/content :policy/gates])
             :policy/reserved-paths (get-in resolution [:policy/content :policy/reserved-paths])}
        :deferred {:deferred resolution}
        {:invalid resolution}))))

(defn- evaluate-candidate!
  "Pure evaluation of one candidate observation against the resolved
   approved policy. Returns the gate decision map, which may be
   :allow/:deny/:defer/:invalid — never allow on malformed input."
  [fixtures slug pr requested-digest]
  (let [observation (gate-observation! fixtures slug pr)
        evaluator-id (:fixture/evaluator-id fixtures)
        capability (:fixture/capability fixtures)
        policy-or (resolve-gate-policy fixtures requested-digest)]
    (cond
      (:deferred policy-or)
      (let [candidate (candidate-identity observation)]
        (when (nil? candidate)
          (throw (ex-info "Configured gate fixture observation is malformed"
                          {:axiom/error :operational :repo slug :pr pr})))
        ;; Unapproved digests make every dependent gate :defer with
        ;; the named reason (R1) — a valid report, never allow.
        (gate/decision-for-unresolved (:deferred policy-or) candidate evaluator-id))

      (:invalid policy-or)
      (throw (ex-info "Configured gate policy source is structurally refused"
                      {:axiom/error :operational
                       :reason (:policy/reason (:invalid policy-or))}))

      :else
      (gate/evaluate policy-or observation evaluator-id capability))))

(defn- gate!
  "Thin evaluation over `axiom.gate/evaluate`: pure — it never
   constructs the checks adapter and never mutates provider state
   (the report carries no `:checks/` keys by construction). Exit 0
   on a valid report (allow/deny/defer with named reasons); 4 on
   invalid input; 5 when the deployment has no observation source or
   the evaluation is :invalid."
  [operands]
  (let [parsed (gate-cli-args! operands #{"--repo" "--pr" "--policy"})]
    (if (map? parsed)
      parsed
      (let [[slug pr digest _ledger] parsed
            decision (evaluate-candidate! (gate-fixtures!) slug pr digest)]
        (if (= :invalid (:gate/decision decision))
          {:exit 5 :output decision}
          {:exit 0 :output decision})))))

(defn- ledger-head-envelope
  "The ledger's current head envelope (for the 0002 append path), or
   nil for an empty ledger."
  [handle]
  (when-let [h (store/head handle)]
    (first (store/read-range handle (:seq h) (:seq h)))))

(defn- ledger-governance-events
  "All recorded `:governance/*` events in the ledger, in sequence
   order."
  [handle]
  (let [n (:event/count (store/ledger-identity handle))]
    (if (zero? n)
      []
      (into []
            (comp (filter #(= :governance (get-in % [:payload :record/kind])))
                  (map #(get-in % [:payload :governance/event])))
            (store/read-range handle 0 (dec n))))))

(defn- record-gate-decision!
  "Appends the gate decision with its evaluator-bound publication
   reference through the 0002 append path (R9). Returns the recording
   receipt. In advisory mode there is no provider publication, so the
   publication reference is nil and the receipt says so: the
   evaluation is still recorded (R8/R9). A missing ledger path means
   the deployment records elsewhere: the report says so honestly
   instead of inventing a location."
  [ledger-path decision published evaluator-id]
  (when (some? ledger-path)
    (let [handle (store/open! ledger-path {:create true})]
      (try
        (let [now (System/currentTimeMillis)
              external-id (:checks/external-id published)
              event-id (if (some? external-id)
                         (str "gate-publish-" (subs ^String external-id 11 27) "-" now)
                         (str "gate-advisory-" now))
              publication (when (some? published)
                            {:publication/evaluator evaluator-id
                             :publication/run-id (:checks/run-id published)
                             :publication/published-at now})
              envelope (ledger/record-gate-decision
                        (ledger-head-envelope handle)
                        {:event/id event-id
                         :stream/id "gate"
                         :dedup/key event-id
                         :producer evaluator-id
                         :observed/time now
                         :ingested/time now
                         :decision decision
                         :publication publication})
              stored (store/append! handle envelope)]
          {:recorded? true :event/id event-id :event/seq (:seq stored)
           :publication? (some? publication)})
        (finally (store/close! handle))))))

(defn- publish-check!
  "The only command that may mutate provider state (R10/R11): it
   runs the R8 capability check first, then publishes the evaluation
   through `axiom.adapters.checks`.

   In this slice the checks API is the in-memory fake in fixture
   mode (zero network; live provider wiring is deployment-specific
   and deferred): the report names `:publish/api :fake-checks-api`
   honestly. In advisory mode the adapter is never constructed —
   the report says advisory mode explicitly, performs zero provider
   writes (`:report/provider-writes 0`), claims no enforcement, and
   still records the evaluation in the ledger with no publication
   reference (R8/R9).

   Exit 0 on a valid report (publication or advisory); 4 on invalid
   input; 5 on operational failure (API error, incomplete
   observation, missing policy approval, capability check failure)."
  [operands]
  (let [parsed (gate-cli-args! operands #{"--repo" "--pr" "--ledger"})]
    (if (map? parsed)
      parsed
      (let [[slug pr _digest ledger-path] parsed
            fixtures (gate-fixtures!)
            evaluator-id (:fixture/evaluator-id fixtures)
            capability (:fixture/capability fixtures)]
        (when-not (and (map? capability)
                       (contains? #{:enforcement :advisory} (:capability/mode capability))
                       (non-blank-string? (:capability/trusted-evaluator capability)))
          (throw (ex-info "Configured capability record is malformed"
                          {:axiom/error :operational})))
        (let [decision (evaluate-candidate! fixtures slug pr nil)]
          (cond
            ;; A decision that cannot name its policy approval cannot
            ;; be published (R2): the publication would not be pinned.
            (nil? (get-in decision [:gate/policy :policy/approval-event-id]))
            (throw (ex-info "Missing policy approval: publish-check cannot publish a decision that names no policy approval"
                            {:axiom/error :operational :repo slug :pr pr}))

            (= :invalid (:gate/decision decision))
            (throw (ex-info "Cannot publish an :invalid gate decision"
                            {:axiom/error :operational :repo slug :pr pr}))

            (capability/advisory? capability)
            ;; Advisory mode: the evaluation is produced and recorded
            ;; locally (R8/R9); the checks adapter is never
            ;; constructed, so zero provider writes happen by
            ;; construction. The recording carries no publication
            ;; reference — there was no publication.
            (let [receipt (record-gate-decision! ledger-path decision nil evaluator-id)]
              {:exit 0
               :output (cond-> (assoc (capability/advisory-report capability decision)
                                      :report/command "publish-check")
                         (some? receipt) (assoc :report/ledger receipt)
                         (nil? receipt) (assoc :report/ledger
                                               {:recorded? false
                                                :reason "no ledger configured: pass --ledger PATH"}))})

            :else
            (let [[owner repo] (str/split ^String (:candidate/repo (:gate/candidate decision)) #"/" 2)
                  fake (checks-adapter/fake-checks-api)
                  adapter (checks-adapter/construct!
                           {:capability capability
                            :evaluator/id evaluator-id
                            :checks/owner owner
                            :checks/repo repo
                            :checks/api (:checks/api fake)})
                  published (checks-adapter/publish!
                             adapter decision (:gate/candidate decision))
                  receipt (record-gate-decision! ledger-path decision published evaluator-id)]
              {:exit 0
               :output (cond-> {:publish/command "publish-check"
                                :publish/api :fake-checks-api
                                :publish/evaluation decision
                                :publish/result published
                                :publish/attempts @(:checks/attempts fake)}
                         (some? receipt) (assoc :publish/ledger receipt)
                         (nil? receipt) (assoc :publish/ledger
                                               {:recorded? false
                                                :reason "no ledger configured: pass --ledger PATH or set AXIOM_GATE_LEDGER"}))})))))))

(defn- read-policy-file!
  "Reads the policy file for `policy-approve`. An unreadable file or
   malformed EDN is invalid input (4)."
  [path]
  (try
    (read-input path)
    (catch java.io.IOException _
      (model/invalid! "Cannot read policy file" {:path path}))))

(defn- latest-approval-for-policy
  "The latest recorded `:governance/policy-approved` event for the
   policy id, or nil — its digest becomes the new approval's
   `:governance/supersedes`."
  [events policy-id]
  (->> events
       (filter #(and (= :governance/policy-approved (:event/kind %))
                     (= policy-id (:governance/policy-id %))))
       (sort-by :governance/approved-at)
       last))

(defn- policy-approve!
  "Records a `:governance/policy-approved` event in the ledger (R2):
   a governance action, distinct from candidate evaluation. Carries
   the approver (authorizer) identity, the SHA-256 content digest of
   the policy file, and the superseded digest when the ledger
   already holds an approval for the same policy id. Exit 0 on a
   recorded approval; 4 on invalid input (unreadable policy file,
   malformed policy, missing approver or ledger); 5 on operational
   failure (append failure, duplicate approval of the same digest)."
  [operands]
  (let [opts (parse-flag-pairs operands #{"--policy" "--approver" "--ledger"})]
    (if (and opts (get opts "--policy") (get opts "--approver")
             (non-blank-string? (get opts "--approver")))
      (let [policy-path (get opts "--policy")
            approver (get opts "--approver")
            ledger-path (or (get opts "--ledger") (gate-ledger-env))]
        (when (nil? ledger-path)
          (throw (ex-info "No governance ledger configured: pass --ledger PATH or set AXIOM_GATE_LEDGER"
                          {:axiom/error :operational})))
        (let [content (read-policy-file! policy-path)]
          (when-not (map? content)
            (model/invalid! "Policy file must contain a policy content map" {:path policy-path}))
          (let [policy-id (:policy/id content)]
            (when-not (non-blank-string? policy-id)
              (model/invalid! "Policy file must carry a non-blank :policy/id" {:path policy-path}))
            (let [handle (store/open! ledger-path {:create true})]
              (try
                (let [now (System/currentTimeMillis)
                      digest (policy/content-digest content)
                      supersedes (:governance/digest
                                  (latest-approval-for-policy
                                   (ledger-governance-events handle) policy-id))
                      event-id (str "policy-approve-" (subs ^String digest 7 23))
                      event (policy/policy-approve-event
                             content approver
                             {:event-id event-id :policy-id policy-id
                              :supersedes supersedes :approved-at now})
                      envelope (ledger/record-governance
                                (ledger-head-envelope handle)
                                {:governance-event event
                                 :event/id event-id
                                 :stream/id "governance"
                                 :dedup/key event-id
                                 :producer approver
                                 :observed/time now
                                 :ingested/time now})]
                  (try
                    (let [stored (store/append! handle envelope)]
                      {:exit 0
                       :output {:approved? true
                                :governance/event event
                                :event/id event-id
                                :event/seq (:seq stored)
                                :ledger ledger-path}})
                    (catch clojure.lang.ExceptionInfo e
                      (if (= :duplicate (:axiom/error (ex-data e)))
                        (throw (ex-info (str "Policy digest already approved: " digest)
                                        {:axiom/error :operational
                                         :governance/digest digest}))
                        (throw e)))))
                (finally (store/close! handle)))))))
      (usage-0005))))

(defn run [args]
  (try
    (let [[command & operands] args]
      (cond
        (contains? read-commands command)
        (if (and (= 2 (count operands)) (= "--input" (first operands)))
          (let [scenario (read-input (second operands))]
            (case command
              "validate" (do (contract/validate-scenario! scenario)
                             {:exit 0 :output {:valid? true :mode :offline-advisory}})
              "evaluate" (let [decision (nomos/evaluate scenario)]
                           {:exit ({:allow 0 :deny 2 :defer 3} (:result decision)) :output decision})
              "status" {:exit 0 :output (nomos/status-report scenario)}
              "next" {:exit 0 :output (nomos/next-report scenario)}))
          (usage))

        (= "explain" command)
        (let [[flag path flag2 id] operands]
          (if (and (= "--input" flag) (string? path)
                   (or (nil? flag2) (and (= "--decision" flag2) (string? id))))
            (let [scenario (read-input path)
                  decision (nomos/evaluate scenario)]
              {:exit 0 :output (nomos/explain-decision decision id)})
            (usage)))

        (= "replay" command)
        (let [[flag path flag2 through] operands]
          (if (and (= "--ledger" flag) (string? path)
                   (or (and (nil? flag2) (= 2 (count operands)))
                       (and (= "--through" flag2) (string? through) (= 4 (count operands)))))
            (replay-ledger! path through)
            (let [[bflag bpath] operands]
              (if (and (= "--bundle" bflag) (string? bpath) (= 2 (count operands)))
                (replay-bundle! bpath)
                (usage)))))

        (= "export-bundle" command)
        (let [[f1 v1 f2 v2 f3 v3] operands]
          (if (and (= "--ledger" f1) (string? v1)
                   (= "--output" f2) (string? v2)
                   (or (and (nil? f3) (= 4 (count operands)))
                       (and (= "--through" f3) (string? v3) (= 6 (count operands)))))
            (export-bundle! v1 v2 v3)
            (usage)))

        (= "observe-git" command)
        (observe-git! operands)

        (= "observe-github" command)
        (observe-github! operands)

        (= "check-pr" command)
        (check-pr! operands)

        (= "gate" command)
        (gate! operands)

        (= "publish-check" command)
        (publish-check! operands)

        (= "policy-approve" command)
        (policy-approve! operands)

        (= "digest" command)
        (digest-file! operands)

        (= "run" command)
        (run-diagnostic! operands)

        (= "run-task" command)
        (run-task! operands)

        :else (usage)))
    (catch clojure.lang.ExceptionInfo e
      {:exit (if (= :invalid (:axiom/error (ex-data e))) 4 5)
       :output {:error (or (:axiom/error (ex-data e)) :operational) :message (.getMessage e)
                :details (dissoc (ex-data e) :axiom/error)}})
    (catch java.io.IOException _
      {:exit 5 :output {:error :operational :message "Cannot read input file"}})))

(defn -main [& args]
  (let [{:keys [exit output]} (run args)]
    ;; The CLI emits EDN in Axiom's strict sense (readable by
    ;; `contract/read-data`): namespace-map printing (`#:ns{...}`) is
    ;; disabled. This is byte-identical for the 0001/0002 reports,
    ;; which never contain namespace-map forms.
    (binding [*print-namespace-maps* false]
      (prn output))
    (shutdown-agents)
    (System/exit exit)))
