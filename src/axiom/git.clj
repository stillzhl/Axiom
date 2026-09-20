(ns axiom.git
  "Pure port for local Git observations (spec 0003, R1/R2/R8/R9).

   Defines the observation schema, the change-set data model and the
   path-safety rules as data. No process execution, no I/O, no new
   production dependencies: the `axiom.adapters.git` adapter collects
   raw `git` output as untrusted text, builds maps with the
   constructors here, and every map is validated with
   `validate-observation!` before it leaves the adapter.

   Error contract: schema/shape violations throw `:invalid`
   (via `axiom.model/invalid!`); a reported path that escapes the
   worktree root throws `:operational` (path traversal is rejected,
   never normalized silently — spec R2/R7)."

  (:require [axiom.contract :as contract]
            [axiom.model :as model]
            [clojure.string :as str]))

;; ------------------------------------------------------------------
;; Error helpers

(defn- ensure! [condition message data]
  (when-not condition (model/invalid! message data)))

(defn- operational!
  "Path traversal and other rejected-but-well-formed inputs are
   operational failures, not invalid input: the adapter reports them
   as operational (R7 exit 5) rather than normalizing them."
  [message data]
  (throw (ex-info message (assoc data :axiom/error :operational))))

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

(def producer-fields #{:producer/id :producer/authenticated?})
(def subject-fields #{:repo/path :git/base :git/head :git/tree})
(def value-fields #{:git/clean? :git/branch :git/upstream :git/dirty-files :changes})
(def changes-fields #{:complete? :changes})
(def provenance-fields #{:git/version :commands})
(def scope-fields #{:worktree/root :revisions})
(def revisions-fields #{:base :head})
(def failing-step-fields #{:command :exit :reason})

(def change-entry-fields
  #{:change/kind :path/kind :change/old-path :change/new-path
    :change/old-mode :change/new-mode :path/target :path/commit
    :path/unsafe-reason})

(def dirty-entry-fields
  #{:path :path/kind :path/target :path/unsafe-reason})

(def observation-schema-version 1)
(def observation-kind :git-observation)
(def producer-id "axiom-local-git")
(def local-trust :trust/local-diagnostic)

(def change-kinds #{:added :modified :deleted :renamed :type-changed})
(def path-kinds #{:file :symlink :submodule :unsafe})
(def dirty-path-kinds #{:file :symlink :submodule :unsafe :directory})

;; ------------------------------------------------------------------
;; Pure path-safety rules (data, no filesystem access)

(defn path-escapes-root?
  "Pure, lexical check: does the repo-relative path escape the worktree
   root via `..` segments? Never normalizes silently — callers reject
   an escaping path as an operational failure."
  [path]
  (loop [segments (str/split path #"/" -1) depth 0]
    (if (empty? segments)
      false
      (let [seg (first segments)]
        (cond
          (= seg "..") (if (zero? depth)
                         true
                         (recur (rest segments) (dec depth)))
          (= seg ".") (recur (rest segments) depth)
          :else (recur (rest segments) (inc depth)))))))

(defn- reject-traversal!
  "Any reported path whose `..` components escape the worktree root is
   rejected operationally (R2): never normalized, never trusted."
  [path context]
  (when (and (string? path) (path-escapes-root? path))
    (operational! "Reported path escapes the worktree root"
                  {:context context :path path})))

(defn- check-path! [path context]
  (reject-traversal! path context)
  (ensure! (contract/path? path) "Invalid path" {:context context :path path}))

(defn classify-symlink-target
  "Pure, lexical classification of a symlink target. link-dir is the
   repo-relative directory containing the link (\"\" for the repo
   root); target is the raw link text. The target is resolved
   lexically against link-dir — never followed — and a target that
   escapes the worktree root, is absolute, or cannot be resolved
   deterministically is classified :path/unsafe with the reason
   recorded. Returns a map with :path/kind plus :path/target or
   :path/unsafe-reason."
  [link-dir target]
  (let [unsafe (fn [reason] {:path/kind :unsafe
                             :path/target target
                             :path/unsafe-reason reason})]
    (cond
      (or (not (string? target)) (str/blank? target)
          (re-find #"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]" target))
      (unsafe :unresolvable-target)

      (str/starts-with? target "/")
      (unsafe :absolute-target)

      :else
      (let [base (if (str/blank? link-dir) [] (str/split link-dir #"/"))]
        (loop [segments (str/split target #"/" -1) stack (vec base)]
          (if (empty? segments)
            {:path/kind :symlink :path/target target}
            (let [seg (first segments)]
              (cond
                (or (= seg "") (= seg ".")) (recur (rest segments) stack)
                (= seg "..") (if (empty? stack)
                               (unsafe :escapes-worktree)
                               (recur (rest segments) (pop stack)))
                :else (recur (rest segments) (conj stack seg))))))))))

(defn path-kind-from-mode
  "Pure mapping from a git mode string to a path kind. 120000 is a
   symlink, 160000 (gitlink) is a submodule; anything else is a file.
   A nil mode (unknown) is treated as a file only by explicit
   caller choice — the adapter always supplies modes from --raw."
  [mode]
  (case mode
    "120000" :symlink
    "160000" :submodule
    :file))

(defn change-kind-from-status
  "Pure mapping from a --raw status letter to a change kind. Returns
   nil for anything outside the enumerated model; the adapter treats
   nil as unparseable output and reports :observation/incomplete."
  [status-letter]
  (case status-letter
    \A :added
    \M :modified
    \D :deleted
    \R :renamed
    \T :type-changed
    nil))

(defn- sha40? [s]
  (and (string? s) (boolean (re-matches #"[0-9a-f]{40}" s))))

(defn- mode? [m]
  (and (string? m) (boolean (re-matches #"[0-7]{6}" m))))

;; ------------------------------------------------------------------
;; Observation validation (strict, like axiom.ledger/validate-envelope!)

(defn- validate-producer! [producer]
  (shape! producer producer-fields :producer)
  (ensure! (= producer-id (:producer/id producer)) "Unknown producer" {})
  (ensure! (false? (:producer/authenticated? producer))
           "Local git producer is never authenticated" {}))

(defn- validate-subject! [subject complete?]
  (shape! subject subject-fields :subject)
  (ensure! (and (string? (:repo/path subject)) (not (str/blank? (:repo/path subject))))
           "Invalid repository path" {})
  (doseq [field [:git/base :git/head :git/tree]]
    (let [sha (get subject field)]
      (ensure! (or (nil? sha) (sha40? sha)) "Invalid Git identity" {:field field})
      (when complete?
        (ensure! (sha40? sha) "Complete observation requires Git identities" {:field field})))))

(defn- validate-dirty-entry! [entry]
  (shape! entry dirty-entry-fields :dirty-entry)
  (check-path! (:path entry) :dirty-entry)
  (ensure! (contains? dirty-path-kinds (:path/kind entry)) "Unknown dirty path kind" {})
  (let [kind (:path/kind entry)]
    (ensure! (if (= :symlink kind)
               (and (string? (:path/target entry)) (not (str/blank? (:path/target entry))))
               (or (nil? (:path/target entry))
                   (and (= :unsafe kind)
                        (string? (:path/target entry))
                        (not (str/blank? (:path/target entry))))))
             "Dirty entry target must accompany symlinks (or the judged-unsafe link) only" {})
    (ensure! (if (= :unsafe kind)
               (some? (:path/unsafe-reason entry))
               (nil? (:path/unsafe-reason entry)))
             "Dirty entry unsafe reason must accompany unsafe paths only" {})))

(defn- validate-change-entry! [entry]
  (shape! entry change-entry-fields :change-entry)
  (let [{:change/keys [kind old-path new-path old-mode new-mode]
         :path/keys [kind path-kind target commit unsafe-reason]} entry
        kind (:change/kind entry)
        path-kind (:path/kind entry)]
    (ensure! (contains? change-kinds kind) "Unknown change kind" {})
    (ensure! (contains? path-kinds path-kind) "Unknown path kind" {})
    (when (some? old-path) (check-path! old-path :change-entry))
    (when (some? new-path) (check-path! new-path :change-entry))
    (doseq [mode [old-mode new-mode]]
      (ensure! (or (nil? mode) (mode? mode)) "Invalid git mode" {:mode mode}))
    (ensure! (case kind
               :added (and (nil? old-path) (some? new-path))
               :deleted (and (some? old-path) (nil? new-path))
               (:modified :type-changed) (and (some? old-path) (= old-path new-path))
               :renamed (and (some? old-path) (some? new-path) (not= old-path new-path)))
             "Inconsistent change paths" {:kind kind})
    (ensure! (if (= :symlink path-kind)
               (and (string? target) (not (str/blank? target)))
               (or (nil? target)
                   (and (= :unsafe path-kind)
                        (string? target)
                        (not (str/blank? target)))))
             "Symlink target must accompany symlinks (or the judged-unsafe link) only" {})
    (ensure! (if (= :submodule path-kind)
               (sha40? commit)
               (nil? commit))
             "Submodule commit must accompany submodules only" {})
    (ensure! (if (= :unsafe path-kind)
               (some? unsafe-reason)
               (nil? unsafe-reason))
             "Unsafe reason must accompany unsafe paths only" {})))

(defn- validate-changes! [changes complete?]
  (shape! changes changes-fields :changes)
  (ensure! (boolean? (:complete? changes)) "Changes completeness must be boolean" {})
  (ensure! (= complete? (:complete? changes)) "Changes completeness mismatch" {})
  (ensure! (vector? (:changes changes)) "Changes must be a vector" {})
  (when-not complete?
    (ensure! (empty? (:changes changes)) "Incomplete enumeration must not carry partial changes" {}))
  (doseq [entry (:changes changes)]
    (validate-change-entry! entry)))

(defn- validate-value! [value complete?]
  (shape! value value-fields :value)
  (ensure! (boolean? (:git/clean? value)) "Clean flag must be boolean" {})
  (doseq [field [:git/branch :git/upstream]]
    (let [v (get value field)]
      (ensure! (or (nil? v) (and (string? v) (not (str/blank? v))))
               "Invalid branch identity" {:field field})))
  (ensure! (vector? (:git/dirty-files value)) "Dirty files must be a vector" {})
  (doseq [entry (:git/dirty-files value)]
    (validate-dirty-entry! entry))
  (validate-changes! (:changes value) complete?))

(defn- validate-provenance! [provenance]
  (shape! provenance provenance-fields :provenance)
  (ensure! (and (string? (:git/version provenance)) (not (str/blank? (:git/version provenance))))
           "Invalid git version" {})
  (ensure! (and (vector? (:commands provenance)) (seq (:commands provenance))
                (every? #(and (string? %) (not (str/blank? %))) (:commands provenance)))
           "Provenance must record the command lines run" {}))

(defn- validate-scope! [scope]
  (shape! scope scope-fields :scope)
  (ensure! (and (string? (:worktree/root scope)) (not (str/blank? (:worktree/root scope))))
           "Invalid worktree root" {})
  (let [revisions (:revisions scope)]
    (shape! revisions revisions-fields :revisions)
    (doseq [[field sha] revisions]
      (ensure! (or (nil? sha) (sha40? sha)) "Invalid revision identity" {:field field}))))

(defn- validate-failing-step! [step]
  (shape! step failing-step-fields :failing-step)
  (ensure! (and (string? (:command step)) (not (str/blank? (:command step))))
           "Failing step must name the command" {})
  (ensure! (integer? (:exit step)) "Failing step must record the exit code" {})
  (ensure! (and (string? (:reason step)) (not (str/blank? (:reason step))))
           "Failing step must name the reason" {}))

(defn validate-observation!
  "Strict validation of an adapter-built observation map. Unknown or
   malformed observations are rejected (:invalid) and therefore can
   never enter the ledger or admit actions; a reported path escaping
   the worktree root is rejected operationally (:operational), never
   normalized silently. Returns the observation unchanged."
  [observation]
  (contract/check-value! observation)
  (ensure! (map? observation) "Observation must be a map" {})
  (let [complete? (= :complete (:observation/status observation))]
    (shape! observation (if complete? observation-fields observation-fields-incomplete) :observation)
    (ensure! (= observation-schema-version (:observation/schema-version observation))
             "Unsupported observation schema version" {})
    (ensure! (= observation-kind (:observation/kind observation)) "Unknown observation kind" {})
    (ensure! (contains? #{:complete :incomplete} (:observation/status observation))
             "Unknown observation status" {})
    (validate-producer! (:producer observation))
    (ensure! (= local-trust (:trust observation)) "Local observations carry local-diagnostic trust" {})
    (validate-subject! (:subject observation) complete?)
    (validate-value! (:value observation) complete?)
    (validate-provenance! (:provenance observation))
    (validate-scope! (:scope observation))
    (when-not complete?
      (validate-failing-step! (:observation/failing-step observation))))
  observation)

;; ------------------------------------------------------------------
;; Construction (used by the adapter; always validated)

(defn build-observation
  "Assembles an observation from adapter-collected data and validates
   it. data keys:

     :observation/status   :complete or :incomplete (required)
     :repo/path            worktree root as reported by git (required)
     :git/base :git/head :git/tree   40-hex SHAs (nil-able only when
                                     :observation/status is :incomplete)
     :git/branch :git/upstream       strings, nil when absent
     :git/clean?           boolean
     :git/dirty-files      vector of dirty-entry maps
     :changes              vector of change-entry maps (:complete? is
                           derived from :observation/status; an
                           incomplete observation must pass [])
     :git/version          verbatim `git --version` output
     :commands             vector of verbatim command lines run
     :observation/failing-step  required when :incomplete:
                           {:command :exit :reason}

   Returns the validated observation map."
  [{:keys [observation/status] :as data}]
  (let [complete? (= :complete status)
        changes-complete? (if complete? true false)]
    (validate-observation!
     (cond-> {:observation/schema-version observation-schema-version
              :observation/kind observation-kind
              :observation/status status
              :producer {:producer/id producer-id
                         :producer/authenticated? false}
              :trust local-trust
              :subject {:repo/path (:repo/path data)
                        :git/base (:git/base data)
                        :git/head (:git/head data)
                        :git/tree (:git/tree data)}
              :value {:git/clean? (boolean (:git/clean? data))
                      :git/branch (:git/branch data)
                      :git/upstream (:git/upstream data)
                      :git/dirty-files (vec (:git/dirty-files data))
                      :changes {:complete? changes-complete?
                                :changes (vec (:changes data))}}
              :provenance {:git/version (:git/version data)
                           :commands (vec (:commands data))}
              :scope {:worktree/root (:repo/path data)
                      :revisions {:base (:git/base data)
                                  :head (:git/head data)}}}
       (not complete?) (assoc :observation/failing-step (:observation/failing-step data))))))
