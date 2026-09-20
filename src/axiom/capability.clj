(ns axiom.capability
  "Pure R8 deployment-capability and trusted-observation-extension logic
   for spec 0005 (T5).

   The deployment capability record is computed from the three R8
   answers: (1) does the deployment credential have Checks write
   permission on the target repository; (2) is branch-protection state
   readable; (3) are the required protections configurable by the
   owner. Any no produces `:mode :advisory`: the evaluation is
   produced and recorded, but enforcement is never claimed.

   Advisory mode is a reporting posture, not an enforcement claim:
   `advisory-report` builds the pure report shape (evaluation
   included, zero provider writes, enforcement never claimed). The
   CLI-side rendering of that report is T6; the checks adapter's
   constructor refusal on a non-passing record is T4.

   This namespace is pure: no I/O, no network, no database. The
   protection observation extension shapes below describe what the
   evaluator's credential reads (T5); the actual provider reads are
   the CLI/deployment's job (T6), never this namespace's.

   Quarantine note: none of these functions take candidate-controlled
   bytes. Required checks are identified by provider-assigned
   check-run ID, never by display name; display titles appearing in
   provider payloads are ignored by construction."
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------------
;; Small shape predicates (private)

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- sha40? [x]
  (and (string? x) (boolean (re-matches #"[0-9a-f]{40}" x))))

(defn- positive-int? [x]
  (and (integer? x) (pos? x)))

(defn- non-negative-int? [x]
  (and (integer? x) (not (neg? x))))

(defn- invalid!
  [message data]
  (throw (ex-info message (assoc data :axiom/error :invalid))))

;; ------------------------------------------------------------------
;; R8 capability computation

(def r8-answers
  "The three R8 capability answers, in deployment-check order."
  [:capability/checks-write?
   :capability/protection-readable?
   :capability/protections-configurable?])

(def ^:private answer->advisory-reason
  {:capability/checks-write? :no-checks-write
   :capability/protection-readable? :protection-unreadable
   :capability/protections-configurable? :protections-unconfigurable})

(defn compute-capability
  "Pure: computes the deployment capability record from the three R8
   answers. Every answer true -> `:mode :enforcement`; any no (or a
   non-boolean answer) -> `:mode :advisory` with
   `:capability/advisory-reasons` naming each failed answer. The
   trusted evaluator identity is required and non-blank; anything
   else is invalid caller input."
  [{:capability/keys [trusted-evaluator] :as answers}]
  (when-not (map? answers)
    (invalid! "Capability answers must be a map" {}))
  (when-not (non-blank-string? trusted-evaluator)
    (invalid! "Capability record requires a non-blank trusted evaluator identity"
              {:capability/trusted-evaluator trusted-evaluator}))
  (let [failures (vec (keep (fn [k]
                              (when-not (true? (get answers k))
                                (get answer->advisory-reason k)))
                            r8-answers))]
    (cond-> {:capability/mode (if (empty? failures) :enforcement :advisory)
             :capability/trusted-evaluator trusted-evaluator
             :capability/checks-write? (true? (:capability/checks-write? answers))
             :capability/protection-readable? (true? (:capability/protection-readable? answers))
             :capability/protections-configurable? (true? (:capability/protections-configurable? answers))
             :capability/advisory-reasons failures}
      (empty? failures) (assoc :capability/advisory-reasons []))))

(defn enforcing?
  "True when the capability record permits the checks adapter to
   construct."
  [capability]
  (and (map? capability)
       (= :enforcement (:capability/mode capability))
       (empty? (:capability/advisory-reasons capability))))

(defn advisory?
  "True when the deployment must report advisory mode."
  [capability]
  (boolean (and (map? capability)
                (= :advisory (:capability/mode capability)))))

(defn advisory-report
  "Pure advisory-mode report: the evaluation is produced and recorded
   (`:report/evaluation`), zero provider writes were performed
   (`:report/provider-writes 0`), and enforcement is never claimed
   (`:report/enforcement-claimed false`). Takes an advisory
   capability record and the gate decision; anything else is invalid
   caller input."
  [capability decision]
  (when-not (advisory? capability)
    (invalid! "advisory-report requires an advisory capability record"
              {:capability/mode (:capability/mode capability)}))
  (when-not (and (map? decision) (keyword? (:gate/decision decision)))
    (invalid! "advisory-report requires a gate decision map" {}))
  {:report/mode :advisory
   :report/evaluation decision
   :report/provider-writes 0
   :report/enforcement-claimed false
   :report/advisory-reasons (:capability/advisory-reasons capability)})

;; ------------------------------------------------------------------
;; Trusted observation extension: branch-protection state (T5)

(defn protection-observation
  "Builds the trusted branch-protection observation extension (R6).
   Required checks are named by provider-assigned check-run ID
   (`:check/id`), never by display name: a map entry may carry a
   display title from the provider payload, but it is ignored here.
   Returns the validated shape, or throws :invalid on malformed
   input."
  [{:protection/keys [required-checks required-approval-count
                      dismiss-stale-reviews? enforce-admins?]}]
  (when-not (and (vector? required-checks)
                 (every? (fn [c] (and (map? c)
                                      (positive-int? (:check/id c))))
                         required-checks))
    (invalid! "Protection observation requires :protection/required-checks as a vector of {:check/id positive-int}"
              {:protection/required-checks required-checks}))
  (when-not (non-negative-int? required-approval-count)
    (invalid! "Protection observation requires a non-negative :protection/required-approval-count"
              {:protection/required-approval-count required-approval-count}))
  (when-not (boolean? dismiss-stale-reviews?)
    (invalid! "Protection observation requires boolean :protection/dismiss-stale-reviews?"
              {:protection/dismiss-stale-reviews? dismiss-stale-reviews?}))
  (when-not (boolean? enforce-admins?)
    (invalid! "Protection observation requires boolean :protection/enforce-admins?"
              {:protection/enforce-admins? enforce-admins?}))
  {:protection/required-checks (mapv (fn [c] {:check/id (:check/id c)}) required-checks)
   :protection/required-approval-count required-approval-count
   :protection/dismiss-stale-reviews? dismiss-stale-reviews?
   :protection/enforce-admins? enforce-admins?})

;; ------------------------------------------------------------------
;; Merge-group candidate identity (T5)

(defn- repo-slug? [s]
  (and (non-blank-string? s)
       (boolean (re-matches #"[^/\s]+/[^/\s]+" s))))

(defn merge-group-candidate
  "Candidate identity for a merge-group run (R6): the merge-group
   head SHA plus the grouped PR identities, where the provider offers
   merge queues. Staleness for such a candidate is computed against
   the merge-group head. Returns the validated shape, or throws
   :invalid on malformed input."
  [{:candidate/keys [merge-group-head grouped-prs]}]
  (when-not (sha40? merge-group-head)
    (invalid! "Merge-group candidate requires a 40-hex :candidate/merge-group-head"
              {:candidate/merge-group-head merge-group-head}))
  (when-not (and (vector? grouped-prs) (seq grouped-prs)
                 (every? (fn [pr] (and (map? pr)
                                       (repo-slug? (:candidate/repo pr))
                                       (positive-int? (:candidate/pr pr))))
                         grouped-prs))
    (invalid! "Merge-group candidate requires a non-empty :candidate/grouped-prs vector of {:candidate/repo slug :candidate/pr n}"
              {:candidate/grouped-prs grouped-prs}))
  {:candidate/kind :merge-group
   :candidate/merge-group-head merge-group-head
   :candidate/grouped-prs (mapv (fn [pr] {:candidate/repo (:candidate/repo pr)
                                          :candidate/pr (:candidate/pr pr)})
                                grouped-prs)})

;; ------------------------------------------------------------------
;; Admin bypasses (T5)

(defn admin-bypass-event
  "Builds the `:governance/admin-bypass` event shape (R6): observed
   where the provider API exposes admin bypasses, carrying the actor
   and the reason. Both are required — a bypass that cannot name its
   actor and reason is malformed, never recorded."
  [{:bypass/keys [actor reason target]}]
  (when-not (non-blank-string? actor)
    (invalid! "Admin bypass event requires a non-blank :bypass/actor"
              {:bypass/actor actor}))
  (when-not (non-blank-string? reason)
    (invalid! "Admin bypass event requires a non-blank :bypass/reason"
              {:bypass/reason reason}))
  (cond-> {:governance/kind :governance/admin-bypass
           :bypass/actor actor
           :bypass/reason reason}
    (some? target) (assoc :bypass/target target)))
