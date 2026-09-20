(ns axiom.runner
  "Pure port for the local diagnostic verification runner (spec 0003,
   R4/R5/R8).

   Defines the command registry schema (command ID, executable path,
   fixed argument templates with structured typed argument slots,
   working directory, timeout seconds, output byte caps) as data with
   strict validation in the 0001 style (exact shapes, restricted
   types); run-request validation (known command ID, arguments
   conforming to the declared slots, no shell interpolation); and the
   Evidence record schema (kind, producer, trust mark, candidate
   identity, run identity, result, digests of captured output,
   applicability, limitations).

   No process execution, no I/O, no new production dependencies: the
   `axiom.adapters.runner` adapter spawns processes via ProcessBuilder
   with an argv vector (never a shell string), enforces
   timeout/cancellation/output caps, and assembles every run record
   with `build-run-record`, validated with `validate-run-record!`
   before it leaves the adapter.

   A run record is constructible from validated inputs alone:
   `build-run-record` is deterministic — identical inputs always yield
   identical records.

   Error contract: schema/shape violations throw `:invalid` (via
   `axiom.model/invalid!`)."

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
;; Constants

(def registry-schema-version 1)
(def run-record-schema-version 1)
(def run-kind :diagnostic-run)
(def producer-id "axiom-local-runner")
(def local-trust :trust/local-diagnostic)

(def outcomes #{:completed :timed-out :cancelled :output-capped})
(def results #{:pass :fail :incomplete})
(def bound-names #{:timeout-seconds :stdout-cap-bytes :stderr-cap-bytes})
(def slot-types #{:string :enum :integer})

(def max-timeout-seconds 3600)
(def max-output-cap-bytes 16777216) ; 16 MiB per stream

(def isolation-limitations
  "No isolation beyond process timeout, cancellation and output capture bounds is provided. In particular: no CPU limits, no memory limits and no network sandboxing are enforced. The command runs as arbitrary local code with the invoking user's privileges in the registry's configured working directory; build scripts and tests are treated as arbitrary code. Full worker isolation belongs to a later milestone and is not claimed here.")

(def registry-fields #{:registry/version :commands})

(def command-fields
  #{:command/id :command/executable :command/args :command/workdir
    :command/timeout-seconds :command/stdout-cap-bytes :command/stderr-cap-bytes
    :command/applicability :runner/shell})

(def slot-fields
  #{:slot/name :slot/type :slot/values :slot/min :slot/max :slot/max-length})

(def request-fields
  #{:command/id :args :candidate :run/id :applicability})

(def candidate-fields #{:candidate/base :candidate/head :candidate/tree})
(def producer-fields #{:producer/id :producer/authenticated?})

(def run-record-fields
  #{:run/schema-version :run/kind :run/id
    :producer :trust :candidate
    :command/id :command/executable :command/argv :command/workdir
    :command/timeout-seconds :command/stdout-cap-bytes :command/stderr-cap-bytes
    :runner/shell :run/request-args
    :run/applicability :run/limitations
    :run/outcome :run/complete? :run/result :run/exit
    :run/stdout-digest :run/stderr-digest
    :run/stdout-bytes :run/stderr-bytes
    :run/bound-exceeded})

;; Shell metacharacters are never interpolated: the adapter always
;; spawns an argv vector via ProcessBuilder, never a shell string.
;; When :runner/shell is false, a caller-supplied slot value containing
;; one of these is rejected as :invalid — the caller may be relying on
;; shell semantics that will not happen. When :runner/shell is true the
;; recipe is explicitly shell-aware (a reviewed choice recorded
;; verbatim in the run record) and the check is skipped.
(def ^:private shell-metachars
  #"[\x00-\x1f\x7f;|&$`\"'\\*?<>~#()\[\]{}!^]")

;; ------------------------------------------------------------------
;; Scalar validators

(defn- non-blank-string? [s max-length]
  (and (string? s) (not (str/blank? s)) (<= (count s) max-length)))

(defn- command-id? [id]
  (and (string? id) (boolean (re-matches #"[a-z][a-z0-9-]{0,63}" id))))

(defn- absolute-path? [p]
  (and (string? p) (not (str/blank? p)) (str/starts-with? p "/")
       (not (re-find #"[\x00-\x1f\x7f]" p))
       (try (model/valid-text! p) true
            (catch clojure.lang.ExceptionInfo _ false))))

(defn- sha40-or-nil? [s]
  (or (nil? s) (and (string? s) (boolean (re-matches #"[0-9a-f]{40}" s)))))

(defn- digest-str? [s]
  (and (string? s) (boolean (re-matches #"sha256:[0-9a-f]{64}" s))))

;; ------------------------------------------------------------------
;; Registry validation

(defn- validate-slot! [slot]
  (shape! slot slot-fields :slot)
  (let [{:slot/keys [name type values min max max-length]} slot]
    (ensure! (non-blank-string? name 64) "Invalid slot name" {:slot slot})
    (ensure! (contains? slot-types type) "Unknown slot type" {:slot slot})
    (case type
      :string
      (do (ensure! (nil? values) "String slot must not declare values" {})
          (ensure! (nil? min) "String slot must not declare min" {})
          (ensure! (nil? max) "String slot must not declare max" {})
          (ensure! (and (integer? max-length) (pos? max-length)
                        (<= max-length max-output-cap-bytes))
                   "String slot requires an explicit positive max length" {}))
      :enum
      (do (ensure! (and (vector? values) (seq values)
                        (every? #(non-blank-string? % 256) values))
                   "Enum slot requires a non-empty vector of values" {})
          (ensure! (= (count values) (count (set values))) "Enum slot values must be distinct" {})
          (ensure! (nil? min) "Enum slot must not declare min" {})
          (ensure! (nil? max) "Enum slot must not declare max" {})
          (ensure! (nil? max-length) "Enum slot must not declare max length" {}))
      :integer
      (do (ensure! (nil? values) "Integer slot must not declare values" {})
          (ensure! (nil? max-length) "Integer slot must not declare max length" {})
          (ensure! (integer? min) "Integer slot requires an integer min" {})
          (ensure! (integer? max) "Integer slot requires an integer max" {})
          (ensure! (<= min max) "Integer slot min must not exceed max" {}))))
  slot)

(defn- validate-arg-template! [template]
  (cond
    (string? template)
    (do (ensure! (not (str/blank? template)) "Fixed argument must be non-blank" {})
        (model/valid-text! template)
        template)

    (map? template)
    (validate-slot! template)

    :else
    (model/invalid! "Argument template must be a fixed string or a slot map"
                    {:template-type (str (type template))})))

(defn- validate-command! [id command]
  (shape! command command-fields :command)
  (let [cmd-id (:command/id command)
        executable (:command/executable command)
        args (:command/args command)
        workdir (:command/workdir command)
        timeout-seconds (:command/timeout-seconds command)
        stdout-cap-bytes (:command/stdout-cap-bytes command)
        stderr-cap-bytes (:command/stderr-cap-bytes command)
        applicability (:command/applicability command)]
    (ensure! (command-id? cmd-id) "Invalid command ID" {:command/id cmd-id})
    (ensure! (= id cmd-id) "Registry key must equal the entry's :command/id" {})
    (ensure! (absolute-path? executable) "Executable must be an absolute path" {})
    (ensure! (vector? args) "Command args must be a vector" {})
    (doseq [template args] (validate-arg-template! template))
    (let [slot-names (mapv :slot/name (filter map? args))]
      (ensure! (= (count slot-names) (count (set slot-names)))
               "Slot names must be distinct within a command" {}))
    (ensure! (absolute-path? workdir) "Working directory must be an absolute path" {})
    (ensure! (and (integer? timeout-seconds)
                  (<= 1 timeout-seconds max-timeout-seconds))
             "Timeout must be 1..3600 seconds" {})
    (doseq [[field cap] [[:command/stdout-cap-bytes stdout-cap-bytes]
                         [:command/stderr-cap-bytes stderr-cap-bytes]]]
      (ensure! (and (integer? cap) (<= 1 cap max-output-cap-bytes))
               "Output cap must be 1..16777216 bytes" {:field field}))
    (ensure! (non-blank-string? applicability 1024) "Applicability note required" {})
    (ensure! (boolean? (:runner/shell command))
             "Every command must declare :runner/shell explicitly (true or false); there is no default" {}))
  command)

(defn validate-registry!
  "Strict validation of a command registry document. The registry is a
   bounded EDN document: {:registry/version 1 :commands {id -> entry}}.
   Returns the registry unchanged."
  [registry]
  (contract/check-value! registry)
  (shape! registry registry-fields :registry)
  (ensure! (= registry-schema-version (:registry/version registry))
           "Unsupported registry schema version" {})
  (let [commands (:commands registry)]
    (ensure! (map? commands) "Registry commands must be a map" {})
    (ensure! (seq commands) "Registry must declare at least one command" {})
    (ensure! (<= (count commands) 256) "Registry command count exceeds the bound" {})
    (doseq [[id command] commands]
      (ensure! (command-id? id) "Invalid registry key" {:key id})
      (validate-command! id command)))
  registry)

;; ------------------------------------------------------------------
;; Run-request validation

(defn- validate-slot-value! [command-id slot value shell?]
  (let [{:slot/keys [name type values min max max-length]} slot
        context {:command/id command-id :slot/name name}]
    (case type
      :string
      (do (ensure! (string? value) "String slot requires a string value" context)
          (model/valid-text! value)
          (ensure! (<= (count value) max-length)
                   "String slot value exceeds the declared max length" context)
          (when-not shell?
            (ensure! (not (re-find shell-metachars value))
                     (str "Shell metacharacters are never interpolated; remove them "
                          "or declare :runner/shell true as an explicit reviewed choice")
                     context)))
      :enum
      (ensure! (contains? (set values) value)
               "Enum slot value is not one of the declared values" context)
      :integer
      (do (ensure! (integer? value) "Integer slot requires an integer value" context)
          (ensure! (<= min value max)
                   "Integer slot value is outside the declared bounds" context))))
  value)

(defn- render-arg [template args]
  (if (string? template)
    template
    (str (get args (:slot/name template)))))

(defn- validate-candidate! [candidate]
  (shape! candidate candidate-fields :candidate)
  (doseq [field candidate-fields]
    (ensure! (sha40-or-nil? (get candidate field))
             "Candidate identity must be a 40-hex SHA or nil" {:field field}))
  candidate)

(defn validate-run-request!
  "Validates a run request against a validated registry. request keys:

     :command/id    approved command ID (required)
     :args          map of string slot-name -> value; must supply
                    exactly the declared slots, no more, no fewer
                    (required)
     :candidate     {:candidate/base :candidate/head :candidate/tree},
                    40-hex SHAs or nil (required)
     :run/id        run identity, non-blank string (required)
     :applicability non-blank string overriding the registry's
                    applicability note (optional; nil selects the
                    registry default)

   Arguments are rendered into an argv vector: fixed strings verbatim,
   slot values as their string form. No shell interpolation happens
   anywhere — the adapter spawns the returned :argv via ProcessBuilder
   directly. Returns the resolved run plan:

     {:command <registry entry> :argv [executable & args]
      :args <validated args> :candidate <validated> :run/id ...
      :applicability <effective note>}"
  [registry request]
  (let [reg (validate-registry! registry)
        id (:command/id request)]
    (shape! request request-fields :run-request)
    (ensure! (command-id? id) "Invalid command ID" {})
    (let [command (get (:commands reg) id)]
      (ensure! (some? command) "Unknown command ID" {:command/id id})
      (let [shell? (boolean (:runner/shell command))
            slots (filterv map? (:command/args command))
            slot-names (mapv :slot/name slots)
            args (:args request)]
        (ensure! (map? args) "Request args must be a map" {})
        (ensure! (every? string? (keys args)) "Request arg keys must be strings" {})
        (ensure! (= (set (keys args)) (set slot-names))
                 "Request args must supply exactly the declared slots"
                 {:expected (sort slot-names) :actual (sort (keys args))})
        (let [validated-args (into {} (map (fn [slot]
                                             [(:slot/name slot)
                                              (validate-slot-value!
                                               id slot (get args (:slot/name slot)) shell?)])
                                           slots))
              applicability (or (:applicability request) (:command/applicability command))]
          (ensure! (non-blank-string? applicability 1024) "Applicability note required" {})
          {:command command
           :argv (into [(:command/executable command)]
                       (mapv #(render-arg % validated-args) (:command/args command)))
           :args validated-args
           :candidate (validate-candidate! (:candidate request))
           :run/id (let [run-id (:run/id request)]
                     (ensure! (non-blank-string? run-id 128) "Run ID required" {})
                     run-id)
           :applicability applicability})))))

;; ------------------------------------------------------------------
;; Evidence record construction

(defn validate-run-record!
  "Strict validation of an adapter-built run Evidence record. Every
   outcome except a clean :completed within bounds is marked
   :incomplete (:run/complete? false, :run/result :incomplete) and can
   never satisfy an evidence obligation. Returns the record unchanged."
  [record]
  (contract/check-value! record)
  (shape! record run-record-fields :run-record)
  (ensure! (= run-record-schema-version (:run/schema-version record))
           "Unsupported run record schema version" {})
  (ensure! (= run-kind (:run/kind record)) "Unknown run kind" {})
  (ensure! (non-blank-string? (:run/id record) 128) "Run ID required" {})
  (let [producer (:producer record)]
    (shape! producer producer-fields :producer)
    (ensure! (= producer-id (:producer/id producer)) "Unknown producer" {})
    (ensure! (false? (:producer/authenticated? producer))
             "Local runner producer is never authenticated" {}))
  (ensure! (= local-trust (:trust record)) "Local runner evidence carries local-diagnostic trust" {})
  (validate-candidate! (:candidate record))
  (let [command-id (:command/id record)]
    (ensure! (command-id? command-id) "Invalid command ID" {}))
  (ensure! (absolute-path? (:command/executable record)) "Executable must be an absolute path" {})
  (let [argv (:command/argv record)]
    (ensure! (and (vector? argv) (seq argv) (every? string? argv))
             "Recorded argv must be a non-empty vector of strings" {})
    (ensure! (= (:command/executable record) (first argv))
             "Recorded argv must start with the executable" {}))
  (ensure! (absolute-path? (:command/workdir record)) "Working directory must be an absolute path" {})
  (ensure! (and (integer? (:command/timeout-seconds record))
                (<= 1 (:command/timeout-seconds record) max-timeout-seconds))
           "Timeout out of range" {})
  (doseq [field [:command/stdout-cap-bytes :command/stderr-cap-bytes]]
    (ensure! (and (integer? (get record field))
                  (<= 1 (get record field) max-output-cap-bytes))
             "Output cap out of range" {:field field}))
  (ensure! (boolean? (:runner/shell record)) ":runner/shell must be recorded verbatim as a boolean" {})
  (ensure! (map? (:run/request-args record)) "Request args must be a map" {})
  (ensure! (non-blank-string? (:run/applicability record) 1024) "Applicability note required" {})
  (ensure! (= isolation-limitations (:run/limitations record))
           "Limitations must state the exact isolation disclaimer" {})
  (let [outcome (:run/outcome record)]
    (ensure! (contains? outcomes outcome) "Unknown run outcome" {}))
  (let [outcome (:run/outcome record)
        complete? (:run/complete? record)]
    (ensure! (boolean? complete?) ":run/complete? must be a boolean" {})
    (ensure! (= complete? (= :completed outcome))
             ":run/complete? must be true exactly for :completed outcomes" {}))
  (let [outcome (:run/outcome record)
        result (:run/result record)
        exit (:run/exit record)
        complete? (:run/complete? record)]
    (ensure! (contains? results result) "Unknown run result" {})
    (ensure! (if complete?
               (contains? #{:pass :fail} result)
               (= :incomplete result))
             "Result must be :pass/:fail for completed runs, :incomplete otherwise" {})
    (ensure! (if (= :completed outcome)
               (integer? exit)
               (nil? exit))
             "Exit code is recorded exactly for :completed outcomes" {})
    (ensure! (if (and complete? (= :pass result)) (= 0 exit) true)
             ":pass requires exit code 0" {})
    (ensure! (if (and complete? (= :fail result)) (not= 0 exit) true)
             ":fail requires a non-zero exit code" {}))
  (let [bound (:run/bound-exceeded record)
        outcome (:run/outcome record)]
    (ensure! (or (nil? bound) (contains? bound-names bound)) "Unknown bound name" {})
    (ensure! (case outcome
               :timed-out (= :timeout-seconds bound)
               :output-capped (contains? #{:stdout-cap-bytes :stderr-cap-bytes} bound)
               (nil? bound))
             "Bound-exceeded must name the violated bound" {}))
  (doseq [[digest-field bytes-field cap-field bound-name]
          [[:run/stdout-digest :run/stdout-bytes :command/stdout-cap-bytes :stdout-cap-bytes]
           [:run/stderr-digest :run/stderr-bytes :command/stderr-cap-bytes :stderr-cap-bytes]]]
    (ensure! (digest-str? (get record digest-field)) "Output digest must be sha256:<64hex>" {:field digest-field})
    (let [nbytes (get record bytes-field)
          cap (get record cap-field)]
      (ensure! (and (integer? nbytes) (<= 0 nbytes cap))
               "Captured byte count must be within the cap" {:field bytes-field})
      (ensure! (if (= (:run/bound-exceeded record) bound-name)
                 (= nbytes cap)
                 true)
               "A capped stream records exactly the cap in bytes" {:field bytes-field})))
  record)

(defn build-run-record
  "Assembles the Evidence record from a validated run plan (as
   returned by `validate-run-request!`) and the adapter-observed
   execution. execution keys:

     :outcome        one of :completed :timed-out :cancelled :output-capped
     :exit           integer exit code (required when :completed, nil otherwise)
     :stdout-bytes   captured stdout as a byte array (required)
     :stderr-bytes   captured stderr as a byte array (required)
     :bound-exceeded nil, or the violated bound:
                    :timeout-seconds :stdout-cap-bytes :stderr-cap-bytes

   Captured output is digested with `axiom.model/sha256-bytes` — the
   digest format matches artifact digests, so a later slice can record
   the bytes as an artifact under the same digest. The record is
   validated with `validate-run-record!` before it is returned."
  [{:keys [command argv args candidate applicability] :as plan}
   {:keys [outcome exit stdout-bytes stderr-bytes bound-exceeded] :as execution}]
  (ensure! (map? plan) "Run plan must be a map" {})
  (ensure! (map? execution) "Execution must be a map" {})
  (ensure! (bytes? stdout-bytes) "Captured stdout must be a byte array" {})
  (ensure! (bytes? stderr-bytes) "Captured stderr must be a byte array" {})
  (let [complete? (= :completed outcome)
        result (cond (not complete?) :incomplete
                     (= 0 exit) :pass
                     :else :fail)]
    (validate-run-record!
     {:run/schema-version run-record-schema-version
      :run/kind run-kind
      :run/id (:run/id plan)
      :producer {:producer/id producer-id :producer/authenticated? false}
      :trust local-trust
      :candidate candidate
      :command/id (:command/id command)
      :command/executable (:command/executable command)
      :command/argv argv
      :command/workdir (:command/workdir command)
      :command/timeout-seconds (:command/timeout-seconds command)
      :command/stdout-cap-bytes (:command/stdout-cap-bytes command)
      :command/stderr-cap-bytes (:command/stderr-cap-bytes command)
      :runner/shell (boolean (:runner/shell command))
      :run/request-args args
      :run/applicability applicability
      :run/limitations isolation-limitations
      :run/outcome outcome
      :run/complete? complete?
      :run/result result
      :run/exit exit
      :run/stdout-digest (model/sha256-bytes stdout-bytes)
      :run/stderr-digest (model/sha256-bytes stderr-bytes)
      :run/stdout-bytes (alength ^bytes stdout-bytes)
      :run/stderr-bytes (alength ^bytes stderr-bytes)
      :run/bound-exceeded bound-exceeded})))
