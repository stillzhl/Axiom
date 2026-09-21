(ns axiom.execute
  "Pure supervised-execution port for spec 0006 (T1, T2).

   Inputs: a validated task record (id, class, admitted scope,
   capability grant, pinned recipe, obligations), a proposal, the
   current lease record, and the deployment capability record.
   Outputs: proposal admission decisions (`:admit` / `:deny` /
   `:invalid` with named reasons), projected contexts, and task
   lifecycle transitions.

   Pure and side-effect-free: no I/O, no network, no database, no
   process spawning. The worker is never a function argument — only
   its validated records are. Worker output is validated data, never
   evaluated as policy and never executed as code (the 0001 rule,
   extended).

   Quarantine boundary: `:proposal/note` is free text for the human
   reviewer and is never read by any admission predicate — it cannot
   widen scope, rename a gate, or authorize an action. Action fields
   (paths, argv entries, evidence descriptors) are scanned for
   instruction-shaped payloads first; a directive detected in data
   denies the whole proposal with `:prompt-scope-escape`. Required
   inputs that are unknown or malformed produce `:invalid`: they can
   never admit an action.

   Proposal admission (`evaluate-proposal`) checks, in order:
   (0) shape validation (`:invalid`); (1) prompt-escape scan
   (`:prompt-scope-escape`); (2) fencing token current
   (`:no-lease-held` / `:stale-fencing-token`); (3) lease held by
   this worker (`:lease-holder-mismatch`); (4) admitted scope
   (`:out-of-scope`); (5) capability grant (`:unknown-capability` /
   `:unauthorized-capability`); (6) path safety
   (`:path-safety-violation`); (7) class check
   (`:self-modification-requires-promotion`). The first failure
   denies the whole proposal with its named reason; partial
   admission is never offered.

   Context projection (`project-context`) is a pure function of
   (task, policy, ledger-state, budget). Mandatory blockers are
   never droppable: a budget too small to hold them fails with
   `:context-budget-too-small` rather than silently omitting a
   blocker. Projected obligation states are copied, never
   recomputed — projection cannot change an authoritative
   decision."
  (:require [clojure.string :as str]
            [axiom.git :as git]))

;; ------------------------------------------------------------------
;; Shape predicates (private; malformed input -> :invalid)

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- sha256-digest? [x]
  (and (string? x) (boolean (re-matches #"sha256:[0-9a-f]{64}" x))))

(defn- token? [x]
  (non-blank-string? x))

;; ------------------------------------------------------------------
;; Closed capability set and kernel namespaces

(def capability-set
  "The closed capability set. Requests for anything outside it are
   rejected as `:unknown-capability`, never passed through."
  #{:capability/read-file
    :capability/write-file
    :capability/run-tests
    :capability/shell})

(def task-classes
  #{:task-class/standard :task-class/self-modifying})

(def kernel-namespaces
  "Namespace symbols forming the trust-critical kernel. Write-file
   actions touching these paths under a `:task-class/standard` task
   are denied with `:self-modification-requires-promotion` (R4)."
  '#{axiom.nomos axiom.ledger axiom.store axiom.gate axiom.policy
     axiom.contract axiom.model axiom.world axiom.prover axiom.capability})

(defn- path->namespace
  "Map a repo-relative source path to its namespace symbol, or nil
   when the path is not a Clojure source file under src/."
  [path]
  (when (and (string? path)
             (str/starts-with? path "src/")
             (str/ends-with? path ".clj"))
    (let [rel (-> path
                  (subs (count "src/"))
                  (subs 0 (- (count path) (count "src/") (count ".clj"))))]
      (symbol (str/replace (str/replace rel "/" ".") "-" ".")))))

(defn kernel-path?
  "True when the repo-relative path names a file inside the
   trust-critical kernel."
  [path]
  (boolean (and (string? path)
                (contains? kernel-namespaces (path->namespace path)))))

;; ------------------------------------------------------------------
;; Prompt-escape scan (input quarantine)

(def ^:private directive-patterns
  "Heuristic, case-insensitive patterns matching instruction-shaped
   payloads in agent-supplied data fields. This is input quarantine,
   not a prompt-injection proof: it is tested against synthetic
   adversaries only (R14)."
  [#"(?i)ignore\s+(all\s+)?previous\s+instructions"
   #"(?i)override\s+(the\s+)?policy"
   #"(?i)disregard\s+(the\s+)?policy"
   #"(?i)\bas\s+(your\s+)?supervisor\b"
   #"(?i)grant\s+(me\s+)?capabilit"
   #"(?i)self-?approv"
   #"(?i)\bbypass\b"])

(defn- directive-text? [s]
  (and (string? s)
       (boolean (some #(re-find % s) directive-patterns))))

(defn- action-strings
  "All free-text string fields of an action that the supervisor would
   otherwise consume as data: paths, argv entries, evidence
   descriptors. `:proposal/note` is deliberately excluded — it is
   never read by any predicate, so scanning it is unnecessary."
  [action]
  (let [path (:action/path action)
        argv (:action/argv action)
        evidence (:action/evidence action)]
    (concat (when (string? path) [path])
            (when (sequential? argv) (filter string? argv))
            (when (map? evidence)
              (filter string? (vals (select-keys evidence [:evidence/descriptor
                                                           :evidence/label])))))))

(defn prompt-escape?
  "True when any action carries instruction-shaped text in a data
   field — an attempt to widen scope through prompt text. The note
   is never consulted: it cannot authorize anything."
  [proposal]
  (boolean (and (map? proposal)
                (sequential? (:proposal/actions proposal))
                (some (fn [action]
                        (and (map? action)
                             (some directive-text? (action-strings action))))
                      (:proposal/actions proposal)))))

;; ------------------------------------------------------------------
;; Proposal shape validation

(def ^:private action-kinds
  #{:action/write-file :action/run-command :action/collect-evidence})

(defn- action-shape-ok?
  [action]
  (and (map? action)
       (contains? action-kinds (:action/kind action))
       (case (:action/kind action)
         :action/write-file (and (non-blank-string? (:action/path action))
                                  (sha256-digest? (:action/content-digest action)))
         :action/run-command (and (sequential? (:action/argv action))
                                  (seq (:action/argv action))
                                  (every? non-blank-string? (:action/argv action)))
         :action/collect-evidence (map? (:action/evidence action))
         false)))

(defn- proposal-shape-ok?
  [proposal]
  (and (map? proposal)
       (non-blank-string? (:proposal/task-id proposal))
       (non-blank-string? (:proposal/worker-id proposal))
       (token? (:proposal/fencing-token proposal))
       (sequential? (:proposal/actions proposal))
       (seq (:proposal/actions proposal))
       (every? action-shape-ok? (:proposal/actions proposal))))

(defn- task-shape-ok?
  [task]
  (and (map? task)
       (non-blank-string? (:task/id task))
       (contains? task-classes (:task/class task))
       (map? (:task/scope task))
       (map? (:task/capabilities task))))

;; ------------------------------------------------------------------
;; Individual admission checks (each returns nil or a named reason)

(defn- check-fencing
  "Lease missing -> :no-lease-held; token mismatch -> :stale-fencing-token."
  [proposal lease]
  (cond
    (not (and (map? lease) (token? (:lease/token lease)))) :no-lease-held
    (not= (:proposal/fencing-token proposal) (:lease/token lease)) :stale-fencing-token
    :else nil))

(defn- check-lease-holder
  [proposal lease]
  (when (not= (:proposal/worker-id proposal) (:lease/worker-id lease))
    :lease-holder-mismatch))

(defn- action-in-scope?
  "Write-file actions must sit under an admitted scope prefix;
   run-command actions must equal the task's pinned verification
   recipe — any other command is out of scope. Collect-evidence is
   read-only observation: it is in scope when it names no path, or
   when its path sits under an admitted prefix."
  [task action]
  (let [prefixes (or (:scope/path-prefixes (:task/scope task)) #{})
        under? (fn [p] (boolean (some #(str/starts-with? p %) prefixes)))]
    (case (:action/kind action)
      :action/write-file (and (string? (:action/path action))
                              (under? (:action/path action)))
      :action/collect-evidence (or (nil? (:action/path action))
                                   (and (string? (:action/path action))
                                        (under? (:action/path action))))
      :action/run-command (= (:action/argv action) (:task/pinned-recipe task))
      false)))

(defn- check-scope
  [task proposal]
  (when (not (every? #(action-in-scope? task %) (:proposal/actions proposal)))
    :out-of-scope))

(defn check-capability
  "Pure predicate over (grant, request): the grant is data on the
   task record, set by the supervisor at task admission, never by
   the worker. `:capability/shell` defaults to false and requires an
   explicit grant. Write-file grants are sets of path prefixes."
  [grant capability path]
  (let [value (get grant capability ::missing)]
    (cond
      (= value ::missing) false
      (= capability :capability/write-file)
      (and (set? value) (string? path)
           (boolean (some #(str/starts-with? path %) value)))
      :else (boolean value))))

(defn- check-capabilities
  [task proposal]
  (let [grant (:task/capabilities task)]
    (some (fn [action]
            (let [cap (:action/capability action)]
              (cond
                (not (contains? capability-set cap)) :unknown-capability
                (not (check-capability grant cap (:action/path action))) :unauthorized-capability
                :else nil)))
          (:proposal/actions proposal))))

(defn- path-safe?
  "Lexical path safety per the 0002/0003 rules: no traversal out of
   the root, no absolute paths, no blank paths. Symlink/submodule
   analysis happens on the worktree diff at patch admission (T6);
   the proposal carries strings, not filesystem entries."
  [path]
  (and (non-blank-string? path)
       (not (str/starts-with? path "/"))
       (not (git/path-escapes-root? path))))

(defn- check-path-safety
  [proposal]
  (let [paths (keep :action/path (:proposal/actions proposal))]
    (when (not (every? path-safe? paths))
      :path-safety-violation)))

(defn- check-class
  "Kernel-namespace writes under a standard task are denied with
   :self-modification-requires-promotion — the R11 promotion path
   governs them, never the proposal itself."
  [task proposal]
  (when (and (= :task-class/standard (:task/class task))
             (some #(and (= :action/write-file (:action/kind %))
                         (kernel-path? (:action/path %)))
                   (:proposal/actions proposal)))
    :self-modification-requires-promotion))

;; ------------------------------------------------------------------
;; Proposal evaluation (T1)

(defn evaluate-proposal
  "Admit or deny a worker proposal against the task, its scope, and
   the current lease. Returns `{:proposal/decision :admit}` or
   `{:proposal/decision :deny, :proposal/reason <named-reason>}` or
   `{:proposal/decision :invalid, :proposal/reason :malformed}`.
   Checks run in the spec order; the first failure denies the whole
   proposal — partial admission is never offered. Malformed or
   unknown required inputs can never admit an action."
  [task proposal lease]
  (cond
    (or (not (task-shape-ok? task))
        (not (map? proposal))
        (not (proposal-shape-ok? proposal)))
    {:proposal/decision :invalid
     :proposal/reason :malformed
     :proposal/task-id (:task/id task)}

    (prompt-escape? proposal)
    {:proposal/decision :deny
     :proposal/reason :prompt-scope-escape
     :proposal/task-id (:task/id task)}

    :else
    (let [reason (or (check-fencing proposal lease)
                     (check-lease-holder proposal lease)
                     (check-scope task proposal)
                     (check-capabilities task proposal)
                     (check-path-safety proposal)
                     (check-class task proposal))]
      (if reason
        {:proposal/decision :deny
         :proposal/reason reason
         :proposal/task-id (:task/id task)}
        {:proposal/decision :admit
         :proposal/task-id (:task/id task)}))))

;; ------------------------------------------------------------------
;; Task lifecycle state machine (T1)

(def task-states
  #{:task/accepted :task/running :task/completed :task/blocked :task/cancelled})

(def ^:private lifecycle-transitions
  {:task/accepted  {:task/started :task/running
                    :task/cancelled :task/cancelled}
   :task/running   {:task/completed :task/completed
                    :task/blocked :task/blocked
                    :task/cancelled :task/cancelled}})

(defn transition-task
  "Pure task lifecycle transition. `event` is a map with
   `:task/event` one of `:task/started`, `:task/completed`,
   `:task/blocked`, `:task/cancelled`. Returns the updated task or
   `{:task/ok false, :task/reason ...}` for illegal transitions and
   malformed input. Terminal states never transition."
  [task event]
  (let [state (:task/state task)
        ev (:task/event event)]
    (cond
      (or (not (map? task)) (not (contains? task-states state))
          (not (map? event)) (not (keyword? ev)))
      {:task/ok false :task/reason :malformed}

      :else
      (if-let [next (get-in lifecycle-transitions [state ev])]
        (cond-> (assoc task :task/state next :task/ok true)
          (= ev :task/blocked) (assoc :task/blockers (or (:task/blockers event) [])))
        {:task/ok false :task/reason :illegal-transition :task/state state}))))

;; ------------------------------------------------------------------
;; Context projection (T2)

(defn- obligation-blocking? [ob]
  (boolean (:obligation/blocking? ob)))

(defn project-context
  "Pure context projection: (task, policy, ledger-state, budget) ->
   context. Selects the task's obligations, the approved policy
   excerpt, the admitted scope, and informational records, truncated
   to the budget by dropping lowest-priority informational records
   first. Blocking obligations are never droppable: a budget too
   small to hold every mandatory blocker fails with
   `:context-budget-too-small`. Projected obligation states are
   copied, never recomputed, so projection cannot change an
   authoritative decision. `ledger-state` is accepted for signature
   stability (T3 wires lease lookups); it is not read here."
  [task policy _ledger-state budget]
  (let [max-records (:context/max-records budget)
        obligations (or (:task/obligations task) [])
        mandatory (filter obligation-blocking? obligations)
        informational (remove obligation-blocking? obligations)
        policy-excerpt (select-keys policy [:policy/id :policy/digest])
        scope-record {:scope/admitted (:task/scope task)}]
    (cond
      (or (not (map? task)) (not (map? budget))
          (not (integer? max-records)) (not (pos? max-records)))
      {:context/ok false :context/reason :malformed}

      (< max-records (count mandatory))
      {:context/ok false :context/reason :context-budget-too-small
       :context/required (count mandatory) :context/budget max-records}

      :else
      (let [ordered (concat (map (fn [ob] {:context/kind :obligation
                                          :context/obligation ob}) mandatory)
                            [{:context/kind :scope
                              :context/record scope-record}
                             {:context/kind :policy
                              :context/record policy-excerpt}]
                            (map (fn [ob] {:context/kind :obligation
                                          :context/obligation ob}) informational))
            kept (vec (take max-records ordered))]
        {:context/ok true
         :context/records kept
         :context/omitted-count (- (count ordered) (count kept))
         :context/budget max-records}))))
