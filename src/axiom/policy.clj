(ns axiom.policy
  "Approved-policy loading and approval validation for spec 0005 (T2).
   The enforced gate evaluates a candidate against an *approved*
   policy: the policy is loaded from a deployment-configured source
   pinned by content digest and checked against a recorded
   `:governance/policy-approved` event — never read from the
   candidate branch, fork, or any path the candidate can write.

   Policy identity is (policy-id, content-digest), where the digest
   is SHA-256 over the 0001 canonical EDN encoding
   (`axiom.model/digest`). A policy-source descriptor that points at
   the candidate ref is rejected structurally: candidate branches
   are not policy sources.

   All functions are pure: no I/O, no network, no database. The
   caller supplies the policy bytes and the governance events (read
   from the 0002 ledger by later slices). Unknown or malformed
   required inputs produce invalid resolutions, never an approved
   policy."
  (:refer-clojure :exclude [resolve])
  (:require [axiom.model :as model]
            [clojure.string :as str]))

;; ------------------------------------------------------------------
;; Digest pinning

(defn content-digest
  "SHA-256 content digest of the policy, over the 0001 canonical EDN
   encoding. The digest is what approval events pin."
  [policy-content]
  (model/digest policy-content))

;; ------------------------------------------------------------------
;; Policy-source descriptors: structural rejection of candidate refs

(def ^:private candidate-source-types #{:candidate-branch :candidate-ref})

(defn structural-reject
  "Inspects a deployment-configured policy-source descriptor against
   the candidate ref. Returns nil when the descriptor is structurally
   acceptable, otherwise a named rejection reason:

   - :malformed-policy-source — the descriptor is not a map.
   - :policy-source-is-candidate-ref — the descriptor names the
     candidate ref (candidate type, candidate-branch flag, or a
     :source/ref equal to the candidate's branch or head SHA).
     Candidate branches are not policy sources, structurally.
   - :malformed-candidate-ref — the candidate ref is present but not
     a map."
  [descriptor candidate-ref]
  (cond
    (not (map? descriptor)) :malformed-policy-source

    (and (some? candidate-ref) (not (map? candidate-ref)))
    :malformed-candidate-ref

    (contains? candidate-source-types (:source/type descriptor))
    :policy-source-is-candidate-ref

    (true? (:source/candidate-branch descriptor))
    :policy-source-is-candidate-ref

    (let [ref (:source/ref descriptor)]
      (and (string? ref)
           (or (= ref (:candidate/branch candidate-ref))
               (= ref (:candidate/head candidate-ref)))))
    :policy-source-is-candidate-ref

    :else nil))

;; ------------------------------------------------------------------
;; Governance approval events

(def approval-event-kind :governance/policy-approved)
(def revocation-event-kind :governance/policy-revoked)

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- digest-shape? [x]
  (and (string? x) (boolean (re-matches #"sha256:[0-9a-f]{64}" x))))

(defn policy-approve-event
  "Constructs the `:governance/policy-approved` event shape used to
   record an approval (later slices persist it through the 0002
   append path; the CLI `policy-approve` command emits it). The
   event carries the authorizer identity, the pinned content digest,
   and the superseded digest. This constructor records an approval;
   it does not itself authorize anything."
  [policy-content approver-id {:keys [event-id policy-id supersedes approved-at]}]
  (when-not (map? policy-content)
    (model/invalid! "policy-approve requires a policy content map"
                    {:value-type (str (type policy-content))}))
  (when-not (non-blank-string? approver-id)
    (model/invalid! "policy-approve requires an approver identity" {}))
  (when-not (non-blank-string? policy-id)
    (model/invalid! "policy-approve requires a policy id" {}))
  (when (and (some? supersedes) (not (digest-shape? supersedes)))
    (model/invalid! "policy-approve supersedes must be a sha256 digest" {}))
  (when-not (non-blank-string? event-id)
    (model/invalid! "policy-approve requires an event id" {}))
  {:event/id event-id
   :event/kind approval-event-kind
   :governance/policy-id policy-id
   :governance/digest (content-digest policy-content)
   :governance/supersedes supersedes
   :governance/approver approver-id
   :governance/approved-at (or approved-at 0)})

(defn- approval-match?
  [event policy-id digest]
  (and (map? event)
       (= approval-event-kind (:event/kind event))
       (= policy-id (:governance/policy-id event))
       (= digest (:governance/digest event))))

(defn approval-for
  "Finds the recorded `:governance/policy-approved` event for
   (policy-id, digest), or nil. When several exist, the latest by
   :governance/approved-at wins."
  [governance-events policy-id digest]
  (->> (or governance-events [])
       (filter #(approval-match? % policy-id digest))
       (sort-by :governance/approved-at)
       last))

(defn revoked?
  "True when a `:governance/policy-revoked` event names the digest:
   the gate accepts a policy only when no revocation event exists
   for its digest."
  [governance-events digest]
  (boolean (some (fn [event]
                   (and (map? event)
                        (= revocation-event-kind (:event/kind event))
                        (= digest (:governance/digest event))))
                 (or governance-events []))))

(defn approval-chain
  "Walks the `:governance/supersedes` chain from the approval of
   (policy-id, digest) back through superseded digests, newest
   first. Returns the event vector, or nil when no approval exists
   for the digest."
  [governance-events policy-id digest]
  (let [by-digest (into {}
                        (comp (filter #(= approval-event-kind (:event/kind %)))
                              (filter #(= policy-id (:governance/policy-id %)))
                              (map (juxt :governance/digest identity)))
                        (or governance-events []))]
    (when (contains? by-digest digest)
      (loop [d digest chain []]
        (if-let [event (get by-digest d)]
          (recur (:governance/supersedes event) (conj chain event))
          chain)))))

;; ------------------------------------------------------------------
;; Resolution

(defn- policy-id-of
  "The policy id comes from the deployment descriptor or the content;
   both, when present, must agree."
  [descriptor policy-content]
  (let [from-descriptor (:source/policy-id descriptor)
        from-content (:policy/id policy-content)]
    (cond
      (and (some? from-descriptor) (some? from-content)
           (not= from-descriptor from-content))
      {:invalid :policy-id-mismatch}

      (non-blank-string? (or from-descriptor from-content))
      {:ok (or from-descriptor from-content)}

      :else {:invalid :missing-policy-id})))

(defn resolve
  "Resolves a deployment-configured policy source to an approved
   policy the gate may enforce. Returns one of:

   - {:policy/resolution :ok, :policy/id, :policy/digest,
      :policy/approval-event-id, :policy/content} — the digest
      matches a recorded, unrevoked approval event.
   - {:policy/resolution :deferred, :policy/reason
      :no-approved-policy | :policy-approval-revoked, ...} — the
      digest has no approval event (or was revoked). Every dependent
      gate defers under this resolution, never allows.
   - {:policy/resolution :invalid, :policy/reason
      :policy-source-is-candidate-ref | :malformed-policy-source |
      :malformed-policy-content | :policy-id-mismatch |
      :missing-policy-id | :malformed-candidate-ref} — structurally
      refused."
  [descriptor policy-content governance-events candidate-ref]
  (if-let [rejected (structural-reject descriptor candidate-ref)]
    {:policy/resolution :invalid :policy/reason rejected}
    (if-not (map? policy-content)
      {:policy/resolution :invalid :policy/reason :malformed-policy-content}
      (let [id-result (policy-id-of descriptor policy-content)]
        (if (:invalid id-result)
          {:policy/resolution :invalid :policy/reason (:invalid id-result)}
          (let [policy-id (:ok id-result)
                digest (content-digest policy-content)
                approval (approval-for governance-events policy-id digest)]
            (cond
              (nil? approval)
              {:policy/resolution :deferred :policy/reason :no-approved-policy
               :policy/id policy-id :policy/digest digest
               :policy/content policy-content}

              (revoked? governance-events digest)
              {:policy/resolution :deferred :policy/reason :policy-approval-revoked
               :policy/id policy-id :policy/digest digest
               :policy/content policy-content}

              :else
              {:policy/resolution :ok
               :policy/id policy-id
               :policy/digest digest
               :policy/approval-event-id (:event/id approval)
               :policy/content policy-content})))))))
