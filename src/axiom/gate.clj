(ns axiom.gate
  "Pure enforced-gate port for spec 0005 (T1).

   Inputs: an approved policy (already resolved and approval-pinned by
   `axiom.policy`), a validated candidate observation (already
   trust-marked by the adapters — 0003/0004 shapes extended with gate
   fields), the evaluator identity, and the deployment capability
   record.

   Outputs: a gate decision map with `:gate/decision` one of
   `:allow` / `:deny` / `:defer` / `:invalid`, `:gate/reasons` (one
   named entry per required gate), `:gate/candidate` (exact candidate
   identity), `:gate/evaluator`, `:gate/policy` (digest plus approval
   event id), and `:gate/trust` (evidence trust marks).

   Pure and side-effect-free: no I/O, no network, no database, no new
   production dependencies.

   Quarantine boundary: `evaluate` never uses a raw candidate string
   as a gate name, a map key, or a policy reference. Required gates
   are matched against provider-assigned check-run IDs plus workflow
   identity — never against display titles the candidate can set.
   The approved policy and the trusted observations are the only
   shared basis for decisions.

   Anti-bypass rules (R4) are pure predicates: policy edit in the
   candidate -> deny (`:policy-path-touched-by-candidate`); renamed
   check -> not satisfied (`:unknown`, the renamed run never matches);
   omitted required verification -> `:unknown`; stale success ->
   `:stale` (deny); forged `:trust/remote-ci` -> deny
   (`:forged-trust-mark`). Unknown or malformed required inputs
   produce `:invalid`: they can never admit an action.

   `:trust/remote-ci` is issued only by `evaluate`, and only when all
   three hold: the evaluator identity is the deployment's configured
   trusted evaluator, the observation is a current trusted
   observation (not synthetic, not stale), and the capability record
   shows enforcement mode. Earlier trust marks are never rewritten:
   evidence marks pass through unchanged; the mark is only ever
   *added* to the decision-level trust set under those conditions.

   An allow decision authorizes nothing beyond the gate itself: it is
   not an instruction to merge, execute, or mutate."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------
;; Small shape predicates (private; malformed input -> :invalid)

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- sha40? [x]
  (and (string? x) (boolean (re-matches #"[0-9a-f]{40}" x))))

(defn- sha256-digest? [x]
  (and (string? x) (boolean (re-matches #"sha256:[0-9a-f]{64}" x))))

(defn- positive-int? [x]
  (and (integer? x) (pos? x)))

;; ------------------------------------------------------------------
;; Decision construction

(defn- candidate-identity
  "Exact candidate identity echoed into every decision. Returns the
   identity map, or {:invalid reason} when the observation is malformed."
  [observation]
  (let [repo (:observation/repo observation)
        pr (:observation/pr observation)
        base (:observation/base observation)
        head (:observation/head observation)
        tree (:observation/tree observation)]
    (cond
      (not (map? observation)) {:invalid :malformed-observation}
      (not (non-blank-string? repo)) {:invalid :malformed-observation}
      (not (positive-int? pr)) {:invalid :malformed-observation}
      (not (sha40? base)) {:invalid :malformed-observation}
      (not (sha40? head)) {:invalid :malformed-observation}
      (not (sha40? tree)) {:invalid :malformed-observation}
      :else {:candidate/repo repo :candidate/pr pr
             :candidate/base base :candidate/head head
             :candidate/tree tree})))

(defn- policy-header
  "Policy digest plus approval reference echoed into every decision.
   Returns the header map, or {:invalid reason}: a gate run that
   cannot name its policy approval is :invalid, never allowed."
  [policy]
  (let [id (:policy/id policy)
        digest (:policy/digest policy)
        approval (:policy/approval-event-id policy)
        gates (:policy/gates policy)]
    (cond
      (not (map? policy)) {:invalid :malformed-policy}
      (not (non-blank-string? id)) {:invalid :malformed-policy}
      (not (sha256-digest? digest)) {:invalid :malformed-policy}
      (not (non-blank-string? approval)) {:invalid :missing-policy-approval}
      (not (and (vector? gates) (seq gates))) {:invalid :malformed-policy}
      :else {:policy/id id :policy/digest digest :policy/approval-event-id approval})))

(defn- validate-gate-shape
  "A required gate must name its provider identity. Display names are
   not part of the shape: they are never consulted."
  [gate]
  (and (map? gate)
       (keyword? (:gate/id gate))
       (positive-int? (:gate/check-run-id gate))
       (non-blank-string? (:gate/workflow-identity gate))
       (boolean? (:gate/required gate))))

(defn- validate-evaluator [evaluator-id]
  (when-not (non-blank-string? evaluator-id) :malformed-evaluator-id))

(defn- validate-capability
  "The capability record is a required input: a malformed record can
   never admit an action."
  [capability]
  (cond
    (not (map? capability)) :malformed-capability
    (not (contains? #{:enforcement :advisory} (:capability/mode capability)))
    :malformed-capability
    (not (non-blank-string? (:capability/trusted-evaluator capability)))
    :malformed-capability
    :else nil))

(defn- invalid-decision
  "An :invalid decision names its reason and echoes whatever identity
   was well-formed. It never allows."
  [candidate evaluator-id policy-header-or-nil reason]
  (cond-> {:gate/decision :invalid
           :gate/invalid-reason reason
           :gate/reasons []
           :gate/trust #{}}
    (not (:invalid candidate)) (assoc :gate/candidate candidate)
    (non-blank-string? evaluator-id) (assoc :gate/evaluator evaluator-id)
    (and policy-header-or-nil (not (:invalid policy-header-or-nil)))
    (assoc :gate/policy policy-header-or-nil)))

;; ------------------------------------------------------------------
;; Anti-bypass predicates (R4)

(defn policy-path-touched?
  "R4a. True when the candidate's changed files include any file under
   a reserved policy path. `changed-files` is candidate-controlled
   data used only for this denial check — never for policy loading."
  [observation reserved-paths]
  (let [files (or (:observation/changed-files observation) [])
        reserved (or reserved-paths [])]
    (boolean (and (sequential? files)
                  (seq reserved)
                  (some (fn [f]
                          (and (string? f)
                               (some #(str/starts-with? f %) reserved)))
                        files)))))

(defn match-check-run
  "R4c. Matches a required gate against provider-assigned check-run
   IDs plus workflow identity. The display title is deliberately not
   consulted: a renamed check can never satisfy a required gate."
  [gate check-runs]
  (let [id (:gate/check-run-id gate)
        workflow (:gate/workflow-identity gate)]
    (first (filter (fn [run]
                     (and (map? run)
                          (= id (:check/id run))
                          (= workflow (:check/workflow-identity run))))
                   (or check-runs [])))))

(defn check-run-current?
  "R4e. A check run is current only if its recorded base/head SHAs
   equal the observation's SHAs; otherwise it is :stale and does not
   satisfy the gate."
  [run observation]
  (and (sha40? (:check/base run))
       (sha40? (:check/head run))
       (= (:check/base run) (:observation/base observation))
       (= (:check/head run) (:observation/head observation))))

(defn forged-remote-ci-claim?
  "True when a record claims :trust/remote-ci without a valid
   evaluator-bound publication reference on a current observation.
   Such claims are forgeries: the gate denies, and later ledger
   validation (T3) rejects them as :invalid."
  [run trusted-evaluator observation-current?]
  (let [claims? (or (= :trust/remote-ci (:check/trust run))
                    (contains? (set (:check/trust-marks run)) :trust/remote-ci))
        publication (:check/publication run)
        bound? (and (map? publication)
                    (non-blank-string? trusted-evaluator)
                    (= trusted-evaluator (:publication/evaluator publication)))]
    (and claims? (not (and bound? observation-current?)))))

(defn issuable-remote-ci?
  "R7. :trust/remote-ci is issued by `evaluate` only when all three
   hold: the evaluator identity is the deployment's configured
   trusted evaluator, the observation is a current trusted
   observation (attested by the adapters; synthetic fixtures are
   structurally excluded), and the capability record shows
   enforcement mode."
  [observation evaluator-id capability]
  (and (non-blank-string? evaluator-id)
       (= evaluator-id (:capability/trusted-evaluator capability))
       (= :enforcement (:capability/mode capability))
       (boolean (:observation/current? observation))
       (not (:observation/synthetic-fixture? observation))
       (contains? #{:trust/provider-authenticated :trust/provider-observed}
                  (:observation/trust observation))))

;; ------------------------------------------------------------------
;; Gate evaluation

(def ^:private terminal-conclusions #{:failure :cancelled :timed-out})

(defn evaluate-gate
  "Evaluates one required gate against the observation. Returns a
   named-reason map; never :allow — the gate-level outcomes compose
   into the decision in `evaluate`."
  [gate observation trusted-evaluator]
  (let [base {:gate/id (:gate/id gate)
              :check/id (:gate/check-run-id gate)
              :check/workflow-identity (:gate/workflow-identity gate)}
        runs (:observation/check-runs observation)
        run (match-check-run gate runs)]
    (cond
      (nil? run)
      (assoc base :gate/outcome :unknown :gate/reason :no-matching-check-run)

      (forged-remote-ci-claim? run trusted-evaluator
                               (boolean (:observation/current? observation)))
      (assoc base :gate/outcome :forged :gate/reason :forged-trust-mark)

      (not (check-run-current? run observation))
      (assoc base :gate/outcome :stale :gate/reason :stale-check-run)

      (= :success (:check/conclusion run))
      (assoc base :gate/outcome :satisfied :gate/reason :check-satisfied)

      (contains? terminal-conclusions (:check/conclusion run))
      (assoc base :gate/outcome :failed :gate/reason :check-failed
             :check/conclusion (:check/conclusion run))

      :else
      (assoc base :gate/outcome :unknown :gate/reason :unknown-check-conclusion
             :check/conclusion (:check/conclusion run)))))

(defn- evidence-trust
  "Union of trust marks carried by the evidence actually used. Marks
   are never rewritten here: earlier marks pass through unchanged."
  [observation gate-results]
  (let [runs (into {} (map (fn [r] [(:check/id r) r])
                           (or (:observation/check-runs observation) [])))
        marks (mapcat (fn [gr]
                        (let [run (get runs (:check/id gr))]
                          (concat (when (:check/trust run) [(:check/trust run)])
                                  (:check/trust-marks run))))
                      gate-results)]
    (set (concat marks (when (:observation/trust observation)
                         [(:observation/trust observation)])))))

(defn evaluate
  "Pure gate evaluation. Takes (policy, observation, evaluator-id,
   capability) and returns the gate decision map.

   - :allow only when every required gate is :satisfied and no
     anti-bypass predicate fires.
   - :deny on any failure, stale run, forgery, or policy-path touch
     in the candidate (named reasons).
   - :defer when required evidence is :unknown (omitted or
     inconclusive).
   - :invalid on unknown or malformed required inputs, or when the
     run cannot name its policy approval. Never allows."
  [policy observation evaluator-id capability]
  (let [candidate (candidate-identity observation)
        pheader (policy-header policy)
        eval-err (validate-evaluator evaluator-id)
        cap-err (validate-capability capability)
        bad-gates (when-not (:invalid pheader)
                    (remove validate-gate-shape (:policy/gates policy)))]
    (cond
      (:invalid candidate)
      (invalid-decision {} evaluator-id nil (:invalid candidate))

      (:invalid pheader)
      (invalid-decision candidate evaluator-id nil (:invalid pheader))

      eval-err
      (invalid-decision candidate nil pheader eval-err)

      cap-err
      (invalid-decision candidate evaluator-id pheader cap-err)

      (seq bad-gates)
      (invalid-decision candidate evaluator-id pheader :malformed-policy-gates)

      :else
      (let [reserved (or (:policy/reserved-paths policy) [])
            integrity-touched? (policy-path-touched? observation reserved)
            trusted-evaluator (:capability/trusted-evaluator capability)
            gates (:policy/gates policy)
            required (filterv :gate/required gates)
            gate-results (mapv #(evaluate-gate % observation trusted-evaluator) required)
            reasons (cond-> (vec gate-results)
                      integrity-touched?
                      (into [{:gate/id :policy-integrity
                              :gate/outcome :violated
                              :gate/reason :policy-path-touched-by-candidate}]))
            trust (evidence-trust observation gate-results)
            trust (if (issuable-remote-ci? observation evaluator-id capability)
                    (conj trust :trust/remote-ci)
                    trust)
            outcomes (set (map :gate/outcome gate-results))
            decision (cond
                       integrity-touched? :deny
                       (contains? outcomes :forged) :deny
                       (contains? outcomes :failed) :deny
                       (contains? outcomes :stale) :deny
                       (contains? outcomes :unknown) :defer
                       :else :allow)]
        {:gate/decision decision
         :gate/reasons reasons
         :gate/candidate candidate
         :gate/evaluator evaluator-id
         :gate/policy pheader
         :gate/trust trust}))))

(defn decision-for-unresolved
  "Builds the gate decision for a policy resolution that did not
   produce an approved policy (`axiom.policy/resolve` output).
   A deferred resolution (unknown or unapproved digest) makes every
   gate :defer with the named reason, never allow. An invalid
   resolution (e.g. a policy source pointing at the candidate ref)
   makes the run :invalid."
  [resolution candidate evaluator-id]
  (let [reason (:policy/reason resolution)
        gates (get-in resolution [:policy/content :policy/gates])
        gates-ok? (and (vector? gates) (seq gates)
                       (every? validate-gate-shape gates))]
    (cond
      (not= :deferred (:policy/resolution resolution))
      (invalid-decision candidate evaluator-id nil (or reason :invalid-policy-resolution))

      (not gates-ok?)
      (invalid-decision candidate evaluator-id nil :malformed-policy-content)

      :else
      {:gate/decision :defer
       :gate/reasons (mapv (fn [g] {:gate/id (:gate/id g)
                                    :gate/outcome :defer
                                    :gate/reason reason})
                           gates)
       :gate/candidate candidate
       :gate/evaluator evaluator-id
       :gate/policy {:policy/id (:policy/id resolution)
                     :policy/digest (:policy/digest resolution)
                     :policy/approval-event-id nil}
       :gate/trust #{}})))
