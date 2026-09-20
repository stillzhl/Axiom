(ns axiom.ledger
  "Pure durable-ledger port (spec 0002), extended with observation and
   evidence record kinds (spec 0003 R6) and GitHub provider
   observations (spec 0004 R6), and gate-evaluation decisions plus
   governance events (spec 0005 T3). No I/O, no database access:
   event envelope construction and validation, hash-chain verification,
   world reduction over stored envelopes, snapshot construction and
   verification, replay reports and decision export bundles. The SQLite
   adapter lives in axiom.store; this namespace never touches it."
  (:require [axiom.contract :as contract]
            [axiom.git :as git]
            [axiom.github :as github]
            [axiom.model :as model]
            [axiom.nomos :as nomos]
            [axiom.runner :as runner]
            [axiom.world :as world]
            [clojure.string :as str]))

(def envelope-schema-version 1)
(def reducer-version "ledger-reducer-v1")
(def bundle-version 1)
;; Highest ledger schema version this code understands. v1 is the base
;; schema; v2 adds a covering index; v3 adds the artifacts table (spec 0003);
;; v4 adds a (producer, seq) covering index (spec 0005 T3) — see
;; axiom.store migrations.
(def supported-schema-version 4)

(def report-limitations
  [:unauthenticated-inputs :test-evidence-is-not-proof :no-execution-authorization
   :local-ledger-not-tamper-proof :single-process-ledger])

(defn operational!
  "Operational failure: the ledger or bundle is unreadable, inconsistent or
   newer than supported. Never an input-validation problem."
  [message data]
  (throw (ex-info message (assoc data :axiom/error :operational))))

(defn- ensure! [condition message data]
  (when-not condition (model/invalid! message data)))

(defn- id? [value]
  (and (string? value) (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]{0,127}" value))))

(defn- digest? [value]
  (and (string? value) (boolean (re-matches #"sha256:[0-9a-f]{64}" value))))

(defn- time? [value]
  (and (integer? value) (<= 0 value Long/MAX_VALUE)))

(defn- shape! [value fields context]
  (ensure! (map? value) "Expected a map" {:context context})
  (ensure! (= (set (keys value)) fields) "Missing or unknown fields"
           {:context context :expected (sort fields) :actual (sort-by str (keys value))}))

(def ^:private envelope-fields
  #{:event/id :schema/version :stream/id :dedup/key :producer
    :observed/time :ingested/time :candidate/id :payload/digest :payload :prev/hash})

(def ^:private stored-envelope-fields (conj envelope-fields :seq))

(defn- validate-observation!
  "Dispatches observation validation to the pure port matching
   :observation/kind, with the kind-appropriate trust check. Local Git
   observations carry :trust/local-diagnostic; GitHub provider
   observations carry :trust/provider-observed or
   :trust/provider-authenticated — never :trust/remote-ci (0004 R9).
   Unknown kinds are rejected (:invalid) and can never be written."
  [observation]
  (let [kind (:observation/kind observation)]
    (cond
      (= git/observation-kind kind)
      (do (ensure! (= git/local-trust (:trust observation))
                   "Git observation must carry local-diagnostic trust" {})
          (git/validate-observation! observation))

      (= github/observation-kind kind)
      (do (ensure! (contains? github/trust-marks (:trust observation))
                   "GitHub observation must carry a provider trust mark" {})
          (github/validate-observation! observation))

      :else
      (model/invalid! "Unknown observation kind" {:observation/kind kind}))))

(defn- validate-observation-record!
  "Strict validation of an :observation payload: the exact record shape,
   plus the pure observation port's validation dispatched on
   :observation/kind. Unknown or malformed observations are rejected
   (:invalid) and can never be written. The trust check is explicit here
   as well as in the ports: local observations can never masquerade as
   trusted remote CI, and provider observations can never carry
   :trust/remote-ci (0004 R9)."
  [payload]
  (shape! payload #{:record/kind :observation} :observation-record)
  (let [observation (:observation payload)]
    (ensure! (map? observation) "Observation record must carry an observation" {})
    (validate-observation! observation))
  payload)

(defn- validate-evidence-record!
  "Strict validation of an :evidence-record payload: the exact record
   shape, plus the pure `axiom.runner` port's run-record validation.
   Unknown or malformed evidence is rejected (:invalid) and can never
   be written. Trust forgery (anything but :trust/local-diagnostic) is
   rejected here and in the port (R5)."
  [payload]
  (shape! payload #{:record/kind :evidence} :evidence-record)
  (let [evidence (:evidence payload)]
    (ensure! (map? evidence) "Evidence record must carry a run record" {})
    (ensure! (= :trust/local-diagnostic (:trust evidence))
             "Evidence must carry local-diagnostic trust" {})
    (runner/validate-run-record! evidence))
  payload)

;; ---------------------------------------------------------------------------
;; Gate decisions and governance events (spec 0005 T3)
;;
;; New payload kinds through the 0002 append path, additive to the
;; existing schema: `:decision/gate-evaluation` (a gate decision with
;; candidate/evaluator/policy identities, named reasons and trust
;; marks, recorded verbatim — replay never re-derives it from a
;; different policy) and `:governance` (an authorization event of one
;; of the `:governance/*` kinds below). The 0002 invariants apply
;; unchanged: transactional sequence, hash chain, event-id/dedup-key
;; dedup, forward-only migrations, rebuildable snapshots.

(def gate-decision-record-kind :decision/gate-evaluation)
(def governance-record-kind :governance)

(def governance-event-kinds
  "The `:governance/*` event kinds this slice records. Policy
   approvals and revocations follow the `axiom.policy` event shape;
   verifier-config approvals, protection changes and admin bypasses
   are the R2/R6 authorization events."
  #{:governance/policy-approved :governance/policy-revoked
    :governance/verifier-config-approved :governance/protection-changed
    :governance/admin-bypass})

(def ^:private trust-marks
  #{:trust/local-diagnostic :trust/provider-observed
    :trust/provider-authenticated :trust/remote-ci})

(def ^:private remote-ci :trust/remote-ci)

(defn- sha40? [x]
  (and (string? x) (boolean (re-matches #"[0-9a-f]{40}" x))))

(defn- non-negative-int? [x]
  (and (integer? x) (<= 0 x Long/MAX_VALUE)))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn- authorizer-of
  "The authorizer identity on a governance event: `:governance/authorizer`
   or `:governance/approver` (the `axiom.policy` approval-event shape),
   whichever is a non-blank string — or nil. Missing or mismatched role
   identities are :invalid, never normalized."
  [event]
  (some #(when (non-blank-string? (get event %)) (get event %))
        [:governance/authorizer :governance/approver]))

(defn- governance-shape!
  "Validates one governance event: every key must be in the allowed
   set, every required key present, and the authorizer rule applied.
   Returns the event."
  [event kind required allowed]
  (ensure! (map? event) "Governance event must be a map" {})
  (ensure! (= kind (:event/kind event)) "Governance event kind mismatch"
           {:expected kind :actual (:event/kind event)})
  (doseq [k (keys event)]
    (ensure! (contains? allowed k) "Unknown governance event field"
             {:kind kind :field k}))
  (doseq [k required]
    (ensure! (contains? event k) "Missing required governance event field"
             {:kind kind :field k}))
  (when (contains? event :event/id)
    (ensure! (id? (:event/id event)) "Invalid governance event ID" {}))
  (when (contains? event :governance/digest)
    (ensure! (digest? (:governance/digest event)) "Invalid governance digest" {}))
  (when (some? (:governance/supersedes event))
    (ensure! (digest? (:governance/supersedes event))
             "Invalid superseded digest" {}))
  (doseq [t [:governance/approved-at :governance/revoked-at
             :governance/changed-at :governance/bypassed-at]]
    (when (contains? event t)
      (ensure! (non-negative-int? (get event t)) "Invalid governance timestamp"
               {:field t})))
  event)

(defn- validate-governance-event!
  "Strict per-kind validation of a `:governance/*` event map.
   Authorization-bearing kinds (policy approval/revocation, verifier
   config approval, protection change) require an authorizer identity
   and a content digest; the admin-bypass kind requires the actor and
   the reason (the design's R6 bypass record). Missing or mismatched
   role identities are :invalid, never normalized."
  [event]
  (ensure! (map? event) "Governance record must carry an event map" {})
  (let [kind (:event/kind event)]
    (ensure! (contains? governance-event-kinds kind)
             "Unknown governance event kind" {:event/kind kind})
    (case kind
      :governance/policy-approved
      (do (governance-shape! event kind
                             #{:event/kind :governance/policy-id :governance/digest}
                             #{:event/kind :event/id :governance/policy-id :governance/digest
                               :governance/supersedes :governance/approver
                               :governance/authorizer :governance/approved-at})
          (ensure! (non-blank-string? (:governance/policy-id event))
                   "Governance policy id must be a non-blank string" {})
          (ensure! (authorizer-of event)
                   "Governance event requires an authorizer identity" {:kind kind}))

      :governance/policy-revoked
      (do (governance-shape! event kind
                             #{:event/kind :governance/digest}
                             #{:event/kind :event/id :governance/digest
                               :governance/approver :governance/authorizer
                               :governance/revoked-at :governance/reason})
          (ensure! (authorizer-of event)
                   "Governance event requires an authorizer identity" {:kind kind}))

      :governance/verifier-config-approved
      (do (governance-shape! event kind
                             #{:event/kind :governance/digest}
                             #{:event/kind :event/id :governance/config-id :governance/digest
                               :governance/supersedes :governance/approver
                               :governance/authorizer :governance/approved-at})
          (when (contains? event :governance/config-id)
            (ensure! (non-blank-string? (:governance/config-id event))
                     "Verifier config id must be a non-blank string" {}))
          (ensure! (authorizer-of event)
                   "Governance event requires an authorizer identity" {:kind kind}))

      :governance/protection-changed
      (do (governance-shape! event kind
                             #{:event/kind :governance/digest}
                             #{:event/kind :event/id :governance/repo :governance/digest
                               :governance/approver :governance/authorizer
                               :governance/changed-at :governance/reason})
          (when (contains? event :governance/repo)
            (ensure! (non-blank-string? (:governance/repo event))
                     "Protection-change repo must be a non-blank string" {}))
          (ensure! (authorizer-of event)
                   "Governance event requires an authorizer identity" {:kind kind}))

      :governance/admin-bypass
      (do (governance-shape! event kind
                             #{:event/kind :governance/actor :governance/reason}
                             #{:event/kind :event/id :governance/actor :governance/reason
                               :governance/authorizer :governance/bypassed-at})
          (ensure! (non-blank-string? (:governance/actor event))
                   "Admin bypass requires an actor identity" {})
          (ensure! (non-blank-string? (:governance/reason event))
                   "Admin bypass requires a reason" {}))))
  event)

(defn- validate-governance-record!
  "Strict validation of a `:governance` payload: the exact record
   shape plus per-kind event validation. Unknown or malformed
   governance events are :invalid and can never be written."
  [payload]
  (shape! payload #{:record/kind :governance/event} :governance-record)
  (validate-governance-event! (:governance/event payload))
  payload)

(def ^:private gate-decisions #{:allow :deny :defer :invalid})
(def ^:private gate-outcomes
  #{:satisfied :unknown :stale :failed :forged :violated :defer})

(defn- validate-publication!
  "The evaluator-bound publication reference (T4 writes it; T3
   validates it). When present it must name the publishing evaluator;
   the `:trust/remote-ci` rule in `validate-gate-decision-record!`
   requires that name to equal the decision's `:gate/evaluator`."
  [publication context]
  (when (some? publication)
    (ensure! (map? publication) "Publication reference must be a map" {:context context})
    (doseq [k (keys publication)]
      (ensure! (contains? #{:publication/evaluator :publication/run-id
                            :publication/published-at} k)
               "Unknown publication field" {:context context :field k}))
    (ensure! (non-blank-string? (:publication/evaluator publication))
             "Publication must name its evaluator" {:context context})
    (when (contains? publication :publication/published-at)
      (ensure! (non-negative-int? (:publication/published-at publication))
               "Invalid publication time" {:context context})))
  publication)

(defn- validate-gate-decision-record!
  "Strict validation of a `:decision/gate-evaluation` payload: the
   exact record shape, the recorded gate decision verbatim (evaluator
   identity and policy-approval reference required — a run that
   cannot name its policy approval is :invalid, R2), and the
   `:trust/remote-ci` issuance rule: a record carrying the mark must
   carry a valid evaluator-bound publication reference (the
   publication's evaluator equals the decision's evaluator).
   Missing/mismatched role identities and forged trust marks are
   :invalid, never normalized."
  [payload]
  (shape! payload #{:record/kind :decision :decision/publication}
          :gate-decision-record)
  (let [decision (:decision payload)]
    (ensure! (map? decision) "Gate decision record must carry a decision map" {})
    (doseq [k (keys decision)]
      (ensure! (contains? #{:gate/decision :gate/reasons :gate/candidate
                            :gate/evaluator :gate/policy :gate/trust
                            :gate/invalid-reason} k)
               "Unknown gate decision field" {:field k}))
    (ensure! (contains? gate-decisions (:gate/decision decision))
             "Unknown gate decision" {:gate/decision (:gate/decision decision)})
    (ensure! (non-blank-string? (:gate/evaluator decision))
             "Gate decision requires an evaluator identity" {})
    (let [policy (:gate/policy decision)]
      (ensure! (map? policy) "Gate decision requires a policy header" {})
      (ensure! (= (set (keys policy))
                  #{:policy/id :policy/digest :policy/approval-event-id})
               "Malformed policy header in gate decision" {})
      (ensure! (non-blank-string? (:policy/id policy))
               "Gate decision requires a policy id" {})
      (ensure! (digest? (:policy/digest policy))
               "Gate decision requires a policy digest" {})
      (ensure! (non-blank-string? (:policy/approval-event-id policy))
               "Gate decision requires a policy-approval reference" {}))
    (let [candidate (:gate/candidate decision)]
      (ensure! (map? candidate) "Gate decision requires a candidate identity" {})
      (ensure! (= (set (keys candidate))
                  #{:candidate/repo :candidate/pr :candidate/base
                    :candidate/head :candidate/tree})
               "Malformed candidate identity in gate decision" {})
      (ensure! (non-blank-string? (:candidate/repo candidate))
               "Malformed candidate identity in gate decision" {})
      (ensure! (and (integer? (:candidate/pr candidate))
                    (pos? (:candidate/pr candidate)))
               "Malformed candidate identity in gate decision" {})
      (doseq [sha [:candidate/base :candidate/head :candidate/tree]]
        (ensure! (sha40? (get candidate sha))
                 "Malformed candidate identity in gate decision" {:field sha})))
    (let [reasons (:gate/reasons decision)]
      (ensure! (vector? reasons) "Gate decision reasons must be a vector" {})
      (doseq [reason reasons]
        (ensure! (map? reason) "Gate reason must be a map" {})
        (ensure! (keyword? (:gate/id reason)) "Gate reason needs an id" {})
        (ensure! (contains? gate-outcomes (:gate/outcome reason))
                 "Unknown gate outcome" {:gate/outcome (:gate/outcome reason)})
        (ensure! (keyword? (:gate/reason reason)) "Gate reason needs a named reason" {})))
    (let [trust (:gate/trust decision)]
      (ensure! (set? trust) "Gate trust marks must be a set" {})
      (doseq [mark trust]
        (ensure! (contains? trust-marks mark) "Unknown trust mark" {:mark mark}))
      (let [publication (validate-publication! (:decision/publication payload)
                                               :gate-decision-record)]
        (when (contains? trust remote-ci)
          (ensure! (map? publication)
                   ":trust/remote-ci requires an evaluator-bound publication reference" {})
          (ensure! (= (:gate/evaluator decision) (:publication/evaluator publication))
                   ":trust/remote-ci publication evaluator must match the decision evaluator"
                   {:decision/evaluator (:gate/evaluator decision)
                    :publication/evaluator (:publication/evaluator publication)})))))
  payload)

(defn validate-envelope!
  "Strict validation of a caller-supplied envelope (pre-store, no :seq).
   Unknown or malformed required inputs are rejected and therefore never
   written. Throws :invalid."
  [envelope]
  (contract/check-value! envelope)
  (shape! envelope envelope-fields :ledger-envelope)
  (ensure! (id? (:event/id envelope)) "Invalid event ID" {})
  (ensure! (= envelope-schema-version (:schema/version envelope)) "Unsupported envelope schema version" {})
  (ensure! (id? (:stream/id envelope)) "Invalid stream ID" {})
  (ensure! (id? (:dedup/key envelope)) "Invalid deduplication key" {})
  (ensure! (id? (:producer envelope)) "Invalid producer" {})
  (ensure! (time? (:observed/time envelope)) "Invalid observed time" {})
  (ensure! (time? (:ingested/time envelope)) "Invalid ingestion time" {})
  (ensure! (digest? (:candidate/id envelope)) "Invalid candidate ID" {})
  (ensure! (digest? (:payload/digest envelope)) "Invalid payload digest" {})
  (ensure! (= (:payload/digest envelope) (model/digest (:payload envelope)))
           "Payload digest mismatch" {})
  (let [prev (:prev/hash envelope)]
    (ensure! (or (= "" prev) (digest? prev)) "Invalid previous hash" {}))
  (let [payload (:payload envelope)]
    (ensure! (map? payload) "Envelope payload must be a map" {})
    (case (:record/kind payload)
      :scenario (do (shape! payload #{:record/kind :scenario :decision} :scenario-record)
                    (ensure! (map? (:scenario payload)) "Scenario record must carry a scenario" {}))
      :event (do (shape! payload #{:record/kind :event} :event-record)
                 (ensure! (map? (:event payload)) "Event record must carry an event" {}))
      :observation (validate-observation-record! payload)
      :evidence-record (validate-evidence-record! payload)
      :decision/gate-evaluation (validate-gate-decision-record! payload)
      :governance (validate-governance-record! payload)
      (model/invalid! "Unknown record kind" {:record/kind (:record/kind payload)})))
  envelope)

(defn- validate-event-shape! [event]
  (contract/check-value! event)
  (shape! event #{:id :type :payload} :ledger-event)
  (ensure! (id? (:id event)) "Invalid event ID" {})
  (ensure! (#{:evidence :claim} (:type event)) "Unknown event type" {:type (:type event)})
  (ensure! (map? (:payload event)) "Event payload must be a map" {})
  event)

(defn chain-digest
  "Canonical digest identifying a stored envelope in the hash chain: the
   digest of the envelope with its own :prev/hash removed. Includes :seq,
   so the digest binds the envelope's position."
  [stored-envelope]
  (model/digest (dissoc stored-envelope :prev/hash)))

(defn- base-envelope [prev-envelope inputs]
  {:event/id (:event/id inputs)
   :schema/version envelope-schema-version
   :stream/id (:stream/id inputs)
   :dedup/key (:dedup/key inputs)
   :producer (:producer inputs)
   :observed/time (:observed/time inputs)
   :ingested/time (:ingested/time inputs)
   :prev/hash (if prev-envelope (chain-digest prev-envelope) "")})

(defn record-scenario
  "Pure construction of the envelope to store for a scenario submission.
   The scenario is validated with the 0001 strict schemas and evaluated with
   the pure kernel; the recorded decision is the evaluation at the
   scenario's recorded time. prev-envelope is the current stored head, or
   nil for the genesis event. Returns the envelope without :seq; the store
   assigns the sequence transactionally."
  [prev-envelope {:keys [scenario] :as inputs}]
  (contract/validate-scenario! scenario)
  (let [decision (nomos/evaluate scenario)
        envelope (assoc (base-envelope prev-envelope inputs)
                        :candidate/id (model/candidate-id (:candidate scenario))
                        :payload {:record/kind :scenario
                                  :scenario scenario
                                  :decision decision})]
    (validate-envelope! (assoc envelope :payload/digest (model/digest (:payload envelope))))))

(defn record-event
  "Pure construction of the envelope to store for a single 0001 event
   (evidence or claim). The event shape is validated; semantic validation
   against a contract happens when the event is evaluated as part of a
   scenario. Returns the envelope without :seq."
  [prev-envelope {:keys [event] :as inputs}]
  (validate-event-shape! event)
  (let [candidate-id (:candidate/id inputs)]
    (ensure! (digest? candidate-id) "Invalid candidate ID" {})
    (let [envelope (assoc (base-envelope prev-envelope inputs)
                          :candidate/id candidate-id
                          :payload {:record/kind :event :event event})]
      (validate-envelope! (assoc envelope :payload/digest (model/digest (:payload envelope)))))))

(defn record-observation
  "Pure construction of the envelope to store for an observation. The
   observation is validated with the pure port matching its
   :observation/kind — `axiom.git` for local Git observations,
   `axiom.github` for GitHub provider observations; unknown or
   malformed observations are :invalid and can never be written, and
   the trust level must match the kind (local observations carry
   :trust/local-diagnostic; provider observations carry
   :trust/provider-observed or :trust/provider-authenticated, never
   :trust/remote-ci). The envelope's :candidate/id is the content
   digest of the observation (`axiom.model/candidate-id`): Axiom never
   invents a candidate identity. Returns the envelope without :seq;
   the store assigns the sequence transactionally."
  [prev-envelope {:keys [observation] :as inputs}]
  (ensure! (map? observation) "record-observation requires an observation map" {})
  (validate-observation! observation)
  (let [envelope (assoc (base-envelope prev-envelope inputs)
                        :candidate/id (model/candidate-id observation)
                        :payload {:record/kind :observation :observation observation})]
    (validate-envelope! (assoc envelope :payload/digest (model/digest (:payload envelope))))))

(defn record-evidence
  "Pure construction of the envelope to store for a runner Evidence
   record. The record is validated with the pure `axiom.runner` port —
   unknown or malformed evidence is :invalid and can never be written,
   and any trust level other than :trust/local-diagnostic is rejected
   (R5). The envelope's :candidate/id is the content digest of the
   evidence (`axiom.model/candidate-id`). Returns the envelope without
   :seq; the store assigns the sequence transactionally."
  [prev-envelope {:keys [evidence] :as inputs}]
  (ensure! (map? evidence) "record-evidence requires an evidence record map" {})
  (runner/validate-run-record! evidence)
  (let [envelope (assoc (base-envelope prev-envelope inputs)
                        :candidate/id (model/candidate-id evidence)
                        :payload {:record/kind :evidence-record :evidence evidence})]
    (validate-envelope! (assoc envelope :payload/digest (model/digest (:payload envelope))))))

(defn record-governance
  "Pure construction of the envelope to store for a governance
   authorization event (`:governance/policy-approved`,
   `:governance/policy-revoked`, `:governance/verifier-config-approved`,
   `:governance/protection-changed` or `:governance/admin-bypass`).
   The event is validated strictly per kind: authorization-bearing
   kinds require an authorizer identity and a content digest; the
   admin-bypass kind requires the actor and the reason. Missing or
   mismatched role identities are :invalid and can never be written.
   The envelope's :candidate/id is the content digest of the event
   (`axiom.model/candidate-id`): Axiom never invents an identity.
   Returns the envelope without :seq; the store assigns the sequence
   transactionally."
  [prev-envelope {:keys [governance-event] :as inputs}]
  (ensure! (map? governance-event) "record-governance requires a governance event map" {})
  (validate-governance-event! governance-event)
  (let [envelope (assoc (base-envelope prev-envelope inputs)
                        :candidate/id (model/candidate-id governance-event)
                        :payload {:record/kind :governance
                                  :governance/event governance-event})]
    (validate-envelope! (assoc envelope :payload/digest (model/digest (:payload envelope))))))

(defn record-gate-decision
  "Pure construction of the envelope to store for a gate evaluation
   decision (the verbatim `axiom.gate/evaluate` output map). The
   decision is validated strictly: an evaluator identity and a
   policy-approval reference are required, and a decision carrying
   `:trust/remote-ci` must carry a valid evaluator-bound publication
   reference (inputs `:publication`, nil when unpublished) whose
   evaluator equals the decision's evaluator. Forged trust marks are
   :invalid and can never be written. Replay reproduces the recorded
   decision verbatim, with its policy digest — never re-derived from
   a different policy. Returns the envelope without :seq; the store
   assigns the sequence transactionally."
  [prev-envelope {:keys [decision publication] :as inputs}]
  (ensure! (map? decision) "record-gate-decision requires a decision map" {})
  (let [envelope (assoc (base-envelope prev-envelope inputs)
                        :candidate/id (model/candidate-id decision)
                        :payload {:record/kind :decision/gate-evaluation
                                  :decision decision
                                  :decision/publication publication})]
    (validate-envelope! (assoc envelope :payload/digest (model/digest (:payload envelope))))))

(defn stored-envelope!
  "Validation of an envelope read back from storage. Stored corruption is
   an operational failure, never an input problem."
  [envelope]
  (try
    (contract/check-value! envelope)
    (shape! envelope stored-envelope-fields :stored-ledger-envelope)
    (ensure! (integer? (:seq envelope)) "Stored envelope needs an integer :seq" {})
    (validate-envelope! (dissoc envelope :seq))
    envelope
    (catch clojure.lang.ExceptionInfo e
      (if (= :invalid (:axiom/error (ex-data e)))
        (operational! "Stored envelope failed validation"
                      {:reason :stored-envelope-invalid :cause (.getMessage e)})
        (throw e)))))

(defn extract-events
  "The 0001 events an envelope contributes to the world fold, in order.
   Observations and evidence records are provenance, not world events:
   they contribute zero 0001 events, so 0001/0002 decision bytes are
   unchanged by their presence."
  [envelope]
  (case (get-in envelope [:payload :record/kind])
    :scenario (vec (get-in envelope [:payload :scenario :events]))
    :event [(get-in envelope [:payload :event])]
    (:observation :evidence-record :decision/gate-evaluation :governance) []
    (operational! "Unknown record kind in stored envelope"
                  {:record/kind (get-in envelope [:payload :record/kind])})))

(defn ledger-world
  "Pure fold of stored envelopes in sequence order: the 0001 in-memory
   reduction is the reference implementation that ledger replay must equal."
  [envelopes]
  (world/replay (mapcat extract-events envelopes)))

(defn verify-chain
  "Verifies the hash chain and payload digests of stored envelopes, which
   must form the contiguous prefix 0..N in sequence order. Returns
   {:chain/valid? true :head/hash ... :event/count ...}; a broken chain is
   an operational failure, never a silent skip."
  [envelopes]
  (let [ordered (vec (sort-by :seq envelopes))]
    (doseq [[expected actual] (map vector (range) ordered)]
      (when (not= expected (:seq actual))
        (operational! "Ledger sequence gap" {:expected expected :actual (:seq actual)})))
    (loop [prev nil remaining ordered]
      (if (empty? remaining)
        {:chain/valid? true
         :head/hash (if prev (chain-digest prev) "")
         :event/count (count ordered)}
        (let [env (stored-envelope! (first remaining))]
          (when (not= (:payload/digest env) (model/digest (:payload env)))
            (operational! "Stored payload digest mismatch" {:seq (:seq env)}))
          (let [expected-prev (if prev (chain-digest prev) "")]
            (when (not= expected-prev (:prev/hash env))
              (operational! "Hash chain broken" {:seq (:seq env)})))
          (recur env (rest remaining)))))))

(defn build-snapshot
  "Snapshot of the world reduced from envelopes 0..N. Snapshots are a
   performance optimization only; replay equivalence is the hard invariant."
  [envelopes]
  (when (seq envelopes)
    (let [ordered (vec (sort-by :seq envelopes))
          world (ledger-world ordered)
          last-env (last ordered)]
      {:snapshot/seq (:seq last-env)
       :reducer/version reducer-version
       :world/digest (model/digest world)
       :head/hash (chain-digest last-env)
       :world world})))

(defn verify-snapshot!
  "Checks a snapshot against the stored prefix it claims to cover.
   Returns true; any mismatch is an operational failure."
  [snapshot envelopes]
  (let [ordered (vec (sort-by :seq envelopes))]
    (when (empty? ordered)
      (operational! "Snapshot covers an empty prefix" {}))
    (let [chain (verify-chain ordered)
          last-seq (:seq (last ordered))]
      (when (not= reducer-version (:reducer/version snapshot))
        (operational! "Snapshot reducer version mismatch"
                      {:expected reducer-version :actual (:reducer/version snapshot)}))
      (when (not= last-seq (:snapshot/seq snapshot))
        (operational! "Snapshot sequence mismatch"
                      {:expected last-seq :actual (:snapshot/seq snapshot)}))
      (when (not= (:head/hash chain) (:head/hash snapshot))
        (operational! "Snapshot head hash mismatch" {:seq last-seq}))
      ;; The materialized world must match its own digest; restore-world
      ;; trusts it directly, so a tampered world with an intact digest
      ;; must not pass.
      (when (not= (model/digest (:world snapshot)) (:world/digest snapshot))
        (operational! "Snapshot world digest mismatch" {:seq last-seq}))
      ;; The digest must also match a replay of the covered prefix, so a
      ;; snapshot cannot claim a prefix it was not built from.
      (when (not= (model/digest (ledger-world ordered)) (:world/digest snapshot))
        (operational! "Snapshot world does not match covered prefix" {:seq last-seq}))
      true)))

(defn restore-world
  "Rebuilds the world from a verified snapshot plus the stored envelopes
   after it. Must equal a full replay of 0..end (replay equivalence)."
  [snapshot envelopes-after]
  (world/replay (:world snapshot) (mapcat extract-events (sort-by :seq envelopes-after))))

(defn- scenario-entries [envelopes]
  (keep (fn [env]
          (when (= :scenario (get-in env [:payload :record/kind]))
            {:seq (:seq env)
             :event/id (:event/id env)
             :recorded (get-in env [:payload :decision])
             :recomputed (nomos/evaluate (get-in env [:payload :scenario]))}))
        envelopes))

(defn replay-report
  "Pure replay report over a verified envelope prefix. Decisions are
   recomputed at their recorded evaluation times and compared with the
   recorded decisions; :reproduced? must be true for byte-identical
   reproduction (R5)."
  [{:keys [source envelopes snapshot] schema-version :schema/version}]
  (let [ordered (vec (sort-by :seq envelopes))
        chain (verify-chain ordered)
        head-hash (:head/hash chain)
        event-count (:event/count chain)
        last-seq (if (seq ordered) (:seq (last ordered)) -1)
        usable (when (and snapshot (<= (:snapshot/seq snapshot) last-seq)) snapshot)
        ;; An incompatible snapshot (version or head-hash mismatch) is
        ;; ignored and the world is rebuilt from validated events; snapshots
        ;; are an optimization, events are the source of truth.
        [world used] (if usable
                       (try
                         (let [covered (filterv #(<= (:seq %) (:snapshot/seq usable)) ordered)
                               after (filterv #(> (:seq %) (:snapshot/seq usable)) ordered)]
                           (verify-snapshot! usable covered)
                           [(restore-world usable after) usable])
                         (catch clojure.lang.ExceptionInfo _
                           [(ledger-world ordered) nil]))
                       [(ledger-world ordered) nil])
        entries (scenario-entries ordered)
        ;; Observations and evidence are provenance for the decisions:
        ;; each entry carries its sequence, event ID and the recorded
        ;; map, so a replay shows what was observed and evidenced.
        observations (mapv (fn [env] {:seq (:seq env)
                                      :event/id (:event/id env)
                                      :observation (get-in env [:payload :observation])})
                           (filterv #(= :observation (get-in % [:payload :record/kind])) ordered))
        evidence (mapv (fn [env] {:seq (:seq env)
                                  :event/id (:event/id env)
                                  :evidence (get-in env [:payload :evidence])})
                       (filterv #(= :evidence-record (get-in % [:payload :record/kind])) ordered))
        ;; Gate decisions and governance events are provenance, not
        ;; world events. Replay reproduces them verbatim — the stored
        ;; record, byte-identical, with its policy digest — never
        ;; re-derived from a different policy. Verbatim reproduction
        ;; is identity of the stored record; its integrity is already
        ;; established by verify-chain (payload digest + hash chain)
        ;; above, so :reproduced? is true exactly when the chain
        ;; verifies.
        gate-decisions (mapv (fn [env]
                               {:seq (:seq env)
                                :event/id (:event/id env)
                                :policy/digest (get-in env [:payload :decision
                                                           :gate/policy :policy/digest])
                                :reproduced? true
                                :decision (get-in env [:payload :decision])})
                             (filterv #(= gate-decision-record-kind
                                          (get-in % [:payload :record/kind]))
                                      ordered))
        governance (mapv (fn [env]
                           {:seq (:seq env)
                            :event/id (:event/id env)
                            :event/kind (get-in env [:payload :governance/event :event/kind])
                            :reproduced? true
                            :event (get-in env [:payload :governance/event])})
                         (filterv #(= governance-record-kind
                                      (get-in % [:payload :record/kind]))
                                  ordered))
        decisions (mapv (fn [{:keys [seq recorded recomputed] :as entry}]
                          {:seq seq :event/id (:event/id entry)
                           :decision/id (:decision/id recorded)
                           :result (:result recorded)
                           :reproduced? (= (:decision/id recorded) (:decision/id recomputed))
                           :recorded recorded})
                        entries)]
    {:report :replay :schema/version 1 :mode :offline-advisory
     :source source
     :ledger {:schema/version schema-version
              :head/hash head-hash
              :through/seq (if (seq ordered) (:seq (last ordered)) -1)
              :event/count event-count}
     :world/digest (model/digest world)
     :world/revision (:revision world)
     :decisions decisions
     :observations observations
     :evidence evidence
     :gate-decisions gate-decisions
     :governance governance
     :snapshot/used (when used (select-keys used [:snapshot/seq :reducer/version :world/digest]))
     :snapshot/ignored? (boolean (and usable (nil? used)))
     :limitations report-limitations}))

(defn export-bundle-data
  "Versioned, content-addressed decision export bundle over a verified
   envelope prefix. Pure; the caller writes the bytes."
  [{:keys [engine envelopes snapshot] schema-version :schema/version}]
  (let [ordered (vec (sort-by :seq envelopes))
        head-hash (:head/hash (verify-chain ordered))
        scenarios (mapv (fn [env]
                          {:seq (:seq env) :event/id (:event/id env)
                           :scenario (get-in env [:payload :scenario])
                           :decision (get-in env [:payload :decision])})
                        (filter #(= :scenario (get-in % [:payload :record/kind])) ordered))
        bundle {:bundle/version bundle-version
                :engine engine
                :ledger {:schema/version schema-version
                         :head/hash head-hash
                         :through/seq (if (seq ordered) (:seq (last ordered)) -1)
                         :stream/ids (vec (sort (distinct (map :stream/id ordered))))}
                :scenarios scenarios
                :events ordered
                :snapshot snapshot
                :decisions (mapv (fn [env] {:seq (:seq env) :event/id (:event/id env)
                                            :decision/id (get-in env [:payload :decision :decision/id])
                                            :result (get-in env [:payload :decision :result])})
                                 (filter #(= :scenario (get-in % [:payload :record/kind])) ordered))}]
    (assoc bundle :bundle/digest (model/digest (dissoc bundle :bundle/digest)))))

(def ^:private bundle-fields
  #{:bundle/version :engine :ledger :scenarios :events :snapshot :decisions :bundle/digest})

(defn read-bundle-data
  "Validates a parsed bundle: supported version and matching content digest.
   A digest mismatch or an unknown version is an operational failure."
  [bundle]
  (contract/check-value! bundle)
  (shape! bundle bundle-fields :bundle)
  (when (not= bundle-version (:bundle/version bundle))
    (operational! "Unsupported bundle version" {:version (:bundle/version bundle)}))
  (when (not= (:bundle/digest bundle) (model/digest (dissoc bundle :bundle/digest)))
    (operational! "Bundle digest mismatch" {}))
  (doseq [env (:events bundle)] (stored-envelope! env))
  bundle)
