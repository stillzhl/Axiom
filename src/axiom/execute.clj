(ns axiom.execute
  "Pure supervised-execution port for spec 0006 (T1, T2, T3).

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
            [axiom.git :as git]
            [axiom.ledger :as ledger]
            [axiom.model :as model]))

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
  "The 0006 task classes (R4). Defined in `axiom.ledger`, which
   validates recorded task events; re-exported here for the proposal
   evaluator's class check."
  ledger/task-classes)

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

;; ------------------------------------------------------------------
;; Leases and fencing (T3)
;;
;; Pure lease logic over `:lease/*` event maps in ledger order. The
;; events themselves are recorded through the 0002 append path
;; (`axiom.ledger/record-lease`); the single-holder invariant is
;; enforced at append time by `axiom.store` (a `current_leases`
;; sidecar with a uniqueness constraint, maintained in the same
;; transaction as the lease event). These functions compute the
;; projection and the pure transition decisions; they perform no I/O.

(defn- apply-lease-event
  "Fold one `:lease/*` event into the {task-id lease-record}
   projection. Renewal rotates the token and extends the expiry only
   when the presented token matches the current lease — a renewal
   citing a stale token is never applied. Release, revocation and
   expiry clear the lease. Extra keys on the event (e.g.
   `:lease/acquired-seq` attached by a store-side fold) are
   preserved."
  [leases event]
  (let [task-id (:lease/task-id event)]
    (case (:event/kind event)
      :lease/acquired
      (assoc leases task-id
             (merge (select-keys event [:lease/task-id :lease/worker-id
                                        :lease/token :lease/expires-at
                                        :lease/issued-by])
                    (select-keys event [:lease/acquired-seq])))

      :lease/renewed
      (if (= (:lease/presented-token event) (get-in leases [task-id :lease/token]))
        (assoc leases task-id
               (merge (get leases task-id)
                      (select-keys event [:lease/token :lease/expires-at
                                          :lease/issued-by])))
        leases)

      (:lease/released :lease/revoked :lease/expired)
      (dissoc leases task-id)

      leases)))

(defn current-leases
  "Pure projection of the current lease per task over a sequence of
   `:lease/*` event maps in ledger order, at recorded time `now`.
   A lease whose expiry is at or before `now` is absent from the
   projection — expiry is computed from recorded time, never from a
   wall clock the supervisor trusts. Returns {task-id lease-record}."
  [lease-events now]
  (let [folded (reduce apply-lease-event {} lease-events)]
    (into {}
          (filter (fn [[_ lease]] (< now (:lease/expires-at lease))))
          folded)))

(defn- lease-input-ok?
  [input required]
  (and (map? input)
       (every? #(contains? input %) required)
       (non-blank-string? (:lease/task-id input))
       (non-blank-string? (:lease/worker-id input))
       (non-blank-string? (:lease/token input))
       (integer? (:lease/expires-at input))
       (not (neg? (:lease/expires-at input)))
       (non-blank-string? (:lease/issued-by input))))

(defn acquire-lease
  "Pure acquire decision over the current-leases projection.
   Returns `{:lease/ok true, :lease/event <the :lease/acquired event
   map>}` when the task holds no live lease, or `{:lease/ok false,
   :lease/reason :task-already-leased}` when it does. Malformed input
   yields `:malformed` and can never produce an event."
  [leases input]
  (cond
    (or (not (map? leases))
        (not (lease-input-ok? input [:lease/task-id :lease/worker-id
                                     :lease/token :lease/expires-at
                                     :lease/issued-by])))
    {:lease/ok false :lease/reason :malformed}

    (contains? leases (:lease/task-id input))
    {:lease/ok false :lease/reason :task-already-leased}

    :else
    {:lease/ok true
     :lease/event {:event/kind :lease/acquired
                   :lease/task-id (:lease/task-id input)
                   :lease/worker-id (:lease/worker-id input)
                   :lease/token (:lease/token input)
                   :lease/expires-at (:lease/expires-at input)
                   :lease/issued-by (:lease/issued-by input)}}))

(defn renew-lease
  "Pure renewal decision. The presented token must match the task's
   current lease; renewal rotates the fencing token (the event
   carries both the presented and the new token) and extends the
   expiry. A wrong token is denied with `:stale-fencing-token` and
   the attempt is never applied — the denial is returned, not
   recorded."
  [leases input]
  (let [task-id (:lease/task-id input)
        current (get leases task-id)]
    (cond
      (or (not (map? leases)) (not (map? input))
          (not (non-blank-string? task-id))
          (not (non-blank-string? (:lease/presented-token input)))
          (not (non-blank-string? (:lease/token input)))
          (not (integer? (:lease/expires-at input)))
          (not (non-blank-string? (:lease/issued-by input))))
      {:lease/ok false :lease/reason :malformed}

      (nil? current)
      {:lease/ok false :lease/reason :no-lease-held}

      (not= (:lease/presented-token input) (:lease/token current))
      {:lease/ok false :lease/reason :stale-fencing-token}

      :else
      {:lease/ok true
       :lease/event {:event/kind :lease/renewed
                     :lease/task-id task-id
                     :lease/worker-id (:lease/worker-id current)
                     :lease/presented-token (:lease/presented-token input)
                     :lease/token (:lease/token input)
                     :lease/expires-at (:lease/expires-at input)
                     :lease/issued-by (:lease/issued-by input)}})))

(defn release-lease
  "Pure release decision. The worker presents its current fencing
   token; a mismatch is denied with `:stale-fencing-token`."
  [leases input]
  (let [task-id (:lease/task-id input)
        current (get leases task-id)]
    (cond
      (or (not (map? leases)) (not (map? input))
          (not (non-blank-string? task-id))
          (not (non-blank-string? (:lease/presented-token input)))
          (not (non-blank-string? (:lease/issued-by input))))
      {:lease/ok false :lease/reason :malformed}

      (nil? current)
      {:lease/ok false :lease/reason :no-lease-held}

      (not= (:lease/presented-token input) (:lease/token current))
      {:lease/ok false :lease/reason :stale-fencing-token}

      :else
      {:lease/ok true
       :lease/event {:event/kind :lease/released
                     :lease/task-id task-id
                     :lease/worker-id (:lease/worker-id current)
                     :lease/presented-token (:lease/presented-token input)
                     :lease/issued-by (:lease/issued-by input)}})))

(defn revoke-lease
  "Pure revocation decision. Revocation is evaluator/authorizer-
   initiated (the 0006 authorization model): it requires the issuing
   identity and a named reason, never a fencing token. Takes effect
   on the next record read — in-flight worker actions are fenced at
   the supervisor, which checks token currency before executing
   anything."
  [leases input]
  (let [task-id (:lease/task-id input)
        current (get leases task-id)]
    (cond
      (or (not (map? leases)) (not (map? input))
          (not (non-blank-string? task-id))
          (not (non-blank-string? (:lease/issued-by input)))
          (not (keyword? (:lease/reason input))))
      {:lease/ok false :lease/reason :malformed}

      (nil? current)
      {:lease/ok false :lease/reason :no-lease-held}

      :else
      {:lease/ok true
       :lease/event {:event/kind :lease/revoked
                     :lease/task-id task-id
                     :lease/issued-by (:lease/issued-by input)
                     :lease/reason (:lease/reason input)}})))

(defn check-fencing-token
  "Pure fencing check over the current-leases projection: nil when
   the presented token matches the task's current lease,
   `:no-lease-held` when the task holds no live lease, and
   `:stale-fencing-token` on mismatch. Every worker-submitted
   record (proposal, action result, evidence claim) is gated on
   this: a stale worker cannot publish, admit patches, or move
   outbox intents."
  [leases task-id token]
  (let [current (get leases task-id)]
    (cond
      (nil? current) :no-lease-held
      (not= token (:lease/token current)) :stale-fencing-token
      :else nil)))

;; ------------------------------------------------------------------
;; Action outbox (T4)
;;
;; Pure outbox logic over `:outbox/*` event maps in ledger order. The
;; events themselves are recorded through the 0002 append path
;; (`axiom.ledger/record-outbox`); the intents are executed only by
;; the supervisor (never by the worker) and reconciled afterward.
;; These functions compute the projection, the pure transition
;; decisions and the reconciliation plan; they perform no I/O.
;;
;; The idempotency key is (task-id, action, payload-digest): a
;; resubmission of the same key returns the existing intent, never a
;; second execution. Crash recovery: on restart the supervisor
;; replays the outbox (`outbox-intents` over the event prefix); an
;; intent with no terminal state is reconciled by querying the
;; provider (idempotent operations only), never by blind retry.

(def outbox-states
  "The outbox intent states (R7)."
  #{:intent-recorded :executing :executed :failed :uncertain})

(def ^:private outbox-event->state
  {:outbox/intent-recorded :intent-recorded
   :outbox/executed :executed
   :outbox/failed :failed
   :outbox/uncertain :uncertain})

(defn outbox-idempotency-key
  "Pure idempotency-key derivation: `(task-id, action,
   payload-digest)`. Deterministic and content-addressed — the same
   logical effect always maps to the same key, so duplicate
   submissions are deduplicated on it."
  [task-id action payload]
  (str "outbox/" task-id "/" (name action) "/" (model/digest payload)))

(defn- apply-outbox-event
  "Fold one `:outbox/*` event into the {idempotency-key intent}
   projection. The intent's state is the latest event's state;
   `:outbox/attempts` counts how many times execution was (re-)driven
   (the number of `:outbox/intent-recorded` events for the key)."
  [intents event]
  (let [key (:outbox/idempotency-key event)
        state (get outbox-event->state (:event/kind event))]
    (if (or (nil? key) (nil? state))
      intents
      (update intents key
              (fn [intent]
                (-> (merge intent (select-keys event [:outbox/task-id :outbox/action
                                                     :outbox/payload :outbox/fencing-token
                                                     :outbox/issued-by :outbox/provider-ref
                                                     :outbox/reason :outbox/detail]))
                    (assoc :outbox/idempotency-key key
                           :outbox/state state
                           :outbox/attempts (cond-> (or (:outbox/attempts intent) 0)
                                             (= :outbox/intent-recorded (:event/kind event))
                                             inc))))))))

(defn outbox-intents
  "Pure projection of the outbox over a sequence of `:outbox/*` event
   maps in ledger order. Returns {idempotency-key intent} where each
   intent carries `:outbox/state` (`:intent-recorded` | `:executing` |
   `:executed` | `:failed` | `:uncertain`), the recorded fields and
   `:outbox/attempts`."
  [outbox-events]
  (reduce apply-outbox-event {} outbox-events))

(defn- outbox-input-ok?
  [input]
  (and (map? input)
       (non-blank-string? (:outbox/task-id input))
       (keyword? (:outbox/action input))
       (map? (:outbox/payload input))
       (non-blank-string? (:outbox/fencing-token input))
       (non-blank-string? (:outbox/issued-by input))))

(defn record-intent
  "Pure intent-recording decision over the outbox projection and the
   current-leases projection. The idempotency key is derived from
   (task-id, action, payload): a resubmission of the same key
   returns `{:outbox/ok false, :outbox/reason :duplicate-intent,
   :outbox/existing <intent>}` — the existing intent, never a second
   execution. Recording requires a live lease with a matching fencing
   token (`:no-lease-held` / `:stale-fencing-token`) and the issuing
   evaluator identity (`:outbox/issued-by` must equal the lease's
   `:lease/issued-by` — only the supervisor records intents, never
   the worker; otherwise `:not-supervisor`). Malformed input yields
   `:malformed` and can never produce an event."
  [intents leases input]
  (let [task-id (:outbox/task-id input)
        key (when (outbox-input-ok? input)
              (outbox-idempotency-key task-id (:outbox/action input)
                                      (:outbox/payload input)))]
    (cond
      (or (not (map? intents)) (not (map? leases)) (nil? key))
      {:outbox/ok false :outbox/reason :malformed}

      (contains? intents key)
      {:outbox/ok false :outbox/reason :duplicate-intent
       :outbox/existing (get intents key)}

      :else
      (let [lease (get leases task-id)]
        (cond
          (nil? lease)
          {:outbox/ok false :outbox/reason :no-lease-held}

          (not= (:outbox/fencing-token input) (:lease/token lease))
          {:outbox/ok false :outbox/reason :stale-fencing-token}

          (not= (:outbox/issued-by input) (:lease/issued-by lease))
          {:outbox/ok false :outbox/reason :not-supervisor}

          :else
          {:outbox/ok true
           :outbox/event {:event/kind :outbox/intent-recorded
                          :outbox/idempotency-key key
                          :outbox/task-id task-id
                          :outbox/action (:outbox/action input)
                          :outbox/payload (:outbox/payload input)
                          :outbox/fencing-token (:outbox/fencing-token input)
                          :outbox/issued-by (:outbox/issued-by input)
                          :outbox/attempt 0}})))))

(def ^:private outbox-transitions
  "The supervisor-only outbox state machine. `:intent-recorded` is
   the state after the supervisor records the intent and before the
   provider write completes; `:executing` is the transient
   in-supervisor state while the provider write is in flight (it is
   not a ledger event — a crash during `:executing` replays as
   `:intent-recorded`, which reconciliation resolves by provider
   query). Terminal states are never left."
  {:intent-recorded #{:executing :executed :failed :uncertain}
   :executing #{:executed :failed :uncertain}
   :uncertain #{:intent-recorded :executed :failed}
   :executed #{}
   :failed #{}})

(defn transition-intent
  "Pure supervisor-only transition decision over the outbox
   projection and the current-leases projection. The intent must
   exist (`:unknown-intent`); the fencing token must match the
   task's current lease — a stale worker cannot move intents
   (`:stale-fencing-token`); the transition must come from the
   issuing evaluator (`:not-supervisor`); and the move must be in
   the state machine (`:illegal-transition`). A re-drive
   (`:uncertain → :intent-recorded`) rotates nothing — the
   idempotency key is unchanged, so the provider query that
   resolved the uncertainty is what guards against double
   execution. Malformed input yields `:malformed`."
  [intents leases input]
  (let [key (:outbox/idempotency-key input)
        to (:outbox/to-state input)
        intent (get intents key)
        task-id (:outbox/task-id intent)]
    (cond
      (or (not (map? intents)) (not (map? leases)) (not (map? input))
          (not (non-blank-string? key))
          (not (contains? outbox-states to))
          (not (non-blank-string? (:outbox/fencing-token input)))
          (not (non-blank-string? (:outbox/issued-by input))))
      {:outbox/ok false :outbox/reason :malformed}

      (nil? intent)
      {:outbox/ok false :outbox/reason :unknown-intent}

      :else
      (let [lease (get leases task-id)
            from (:outbox/state intent)]
        (cond
          (nil? lease)
          {:outbox/ok false :outbox/reason :no-lease-held}

          (not= (:outbox/fencing-token input) (:lease/token lease))
          {:outbox/ok false :outbox/reason :stale-fencing-token}

          (not= (:outbox/issued-by input) (:lease/issued-by lease))
          {:outbox/ok false :outbox/reason :not-supervisor}

          (not (contains? (get outbox-transitions from #{}) to))
          {:outbox/ok false :outbox/reason :illegal-transition
           :outbox/from from :outbox/to to}

          :else
          (if (= :executing to)
            ;; `:executing` is the supervisor's transient local state
            ;; while the provider write is in flight — it is never a
            ;; ledger event. A crash during `:executing` replays the
            ;; intent as `:intent-recorded`, which reconciliation
            ;; resolves by provider query.
            {:outbox/ok true :outbox/transient true
             :outbox/idempotency-key key
             :outbox/state :executing}
            (let [drives (:outbox/attempts intent 0)
                  ;; `:outbox/attempt` is the drive index the event
                  ;; belongs to: a re-drive is the next drive, a
                  ;; terminal/uncertainty event belongs to the current
                  ;; drive. It also keeps the 0002 dedup key distinct
                  ;; per drive.
                  attempt (if (= :intent-recorded to) drives (dec drives))]
              {:outbox/ok true
               :outbox/event (cond-> {:event/kind (case to
                                                    :executed :outbox/executed
                                                    :failed :outbox/failed
                                                    :uncertain :outbox/uncertain
                                                    :intent-recorded :outbox/intent-recorded)
                                      :outbox/idempotency-key key
                                      :outbox/fencing-token (:outbox/fencing-token input)
                                      :outbox/issued-by (:outbox/issued-by input)
                                      :outbox/attempt attempt}
                               ;; A re-drive carries the intent's
                               ;; identity forward (the ledger
                               ;; requires task/action/payload on every
                               ;; `:outbox/intent-recorded`).
                               (= :intent-recorded to)
                               (merge (select-keys intent [:outbox/task-id :outbox/action
                                                           :outbox/payload]))
                               (= :failed to)
                               (assoc :outbox/reason (:outbox/reason input))
                               (= :uncertain to)
                               (assoc :outbox/detail (:outbox/detail input))
                               (= :executed to)
                               (assoc :outbox/provider-ref (:outbox/provider-ref input)))})))))))

(defn reconcile-outbox
  "Pure crash-recovery plan over (outbox-state, provider-state).
   `provider-state` is {idempotency-key {:provider/effect-present?
   bool}} — the answers to provider queries the supervisor already
   performed (idempotent operations only). Returns {idempotency-key
   plan} for intents needing action; intents needing nothing are
   absent:

   - `:executed` → absent: never re-executed.
   - `:failed` → `{:reconcile/action :report-blocker}`: reported,
     never blind-retried.
   - `:intent-recorded` / `:uncertain` with a provider answer →
     `{:reconcile/action :mark-executed}` when the effect is
     present (the crash happened after external success:
     exactly-once, no re-execution), or `{:reconcile/action
     :re-drive}` when absent (a fresh drive under the same
     idempotency key — the query, not a blind retry, is what
     proved the effect missing).
   - `:intent-recorded` / `:uncertain` with no provider answer →
     `{:reconcile/action :query-provider}`: the supervisor must
     query first; the plan never assumes."
  [intents provider-state]
  (into {}
        (keep (fn [[key intent]]
                (let [answer (get provider-state key)
                      present? (:provider/effect-present? answer)]
                  (case (:outbox/state intent)
                    :executed nil
                    :failed [key {:reconcile/action :report-blocker
                                  :outbox/idempotency-key key
                                  :outbox/state :failed}]
                    (:intent-recorded :uncertain)
                    (cond
                      (nil? answer)
                      [key {:reconcile/action :query-provider
                            :outbox/idempotency-key key
                            :outbox/state (:outbox/state intent)}]
                      present?
                      [key {:reconcile/action :mark-executed
                            :outbox/idempotency-key key}]
                      :else
                      [key {:reconcile/action :re-drive
                            :outbox/idempotency-key key}])
                    nil))))
        intents))
