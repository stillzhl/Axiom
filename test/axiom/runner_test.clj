(ns axiom.runner-test
  "Tests for the pure `axiom.runner` port and the
   `axiom.adapters.runner` adapter (spec 0003 T4). All commands are
   generic synthetic probes (`true`, `false`, `sleep`, `echo`); no
   network, no heavy processes, nothing consumer-specific."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.adapters.runner :as runner-adapter]
            [axiom.model :as model]
            [axiom.runner :as runner]
            [clojure.string :as str])
  (:import (java.nio.charset StandardCharsets)))

;; ------------------------------------------------------------------
;; Helpers

(def ^:private sha-a (apply str (repeat 40 "a")))
(def ^:private sha-b (apply str (repeat 40 "b")))
(def ^:private sha-c (apply str (repeat 40 "c")))

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(defn- utf8 ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn- slot
  "Builds a fully-explicit slot map (every key present, nil for the
   inapplicable ones) — the strict shape the registry requires."
  [name type & {:keys [values min max max-length]}]
  {:slot/name name :slot/type type
   :slot/values values :slot/min min :slot/max max :slot/max-length max-length})

(defn- command-entry [id & {:as overrides}]
  (merge {:command/id id
          :command/executable "/bin/true"
          :command/args []
          :command/workdir "/tmp"
          :command/timeout-seconds 30
          :command/stdout-cap-bytes 65536
          :command/stderr-cap-bytes 65536
          :command/applicability "Synthetic test-only command."
          :runner/shell false}
         overrides))

(defn- registry-of [& entries]
  (runner/validate-registry!
   {:registry/version 1
    :commands (into {} (map (fn [entry] [(:command/id entry) entry]) entries))}))

(defn- candidate []
  {:candidate/base sha-a :candidate/head sha-b :candidate/tree sha-c})

(defn- request [id & {:as overrides}]
  (merge {:command/id id
          :args {}
          :candidate (candidate)
          :run/id "run-001"
          :applicability nil}
         overrides))

;; ------------------------------------------------------------------
;; Pure port: registry validation

(deftest registry-validation
  (testing "a well-formed registry validates"
    (let [reg (registry-of (command-entry "true-probe"))]
      (is (= 1 (get reg :registry/version)))
      (is (contains? (:commands reg) "true-probe"))))
  (testing "registry key must equal the entry's :command/id"
    (is (= :invalid (error-kind #(runner/validate-registry!
                                  {:registry/version 1
                                   :commands {"a-probe" (command-entry "b-probe")}})))))
  (testing "unknown command kind / bad shapes are rejected"
    (is (= :invalid (error-kind #(registry-of))))
    (is (= :invalid (error-kind #(runner/validate-registry! {}))))
    (is (= :invalid (error-kind #(runner/validate-registry!
                                  {:registry/version 2 :commands {}}))))
    (is (= :invalid (error-kind #(registry-of (command-entry "Bad_ID")))))
    (is (= :invalid (error-kind #(registry-of (command-entry "rel-probe"
                                                             :command/executable "bin/true")))))
    (is (= :invalid (error-kind #(registry-of (command-entry "nowd-probe"
                                                             :command/workdir "relative/dir")))))
    (is (= :invalid (error-kind #(registry-of (command-entry "t0-probe"
                                                             :command/timeout-seconds 0)))))
    (is (= :invalid (error-kind #(registry-of (command-entry "tbig-probe"
                                                             :command/timeout-seconds 3601)))))
    (is (= :invalid (error-kind #(registry-of (command-entry "cap0-probe"
                                                             :command/stdout-cap-bytes 0)))))
    (is (= :invalid (error-kind #(registry-of (dissoc (command-entry "noshell-probe")
                                                      :runner/shell)))))
    (is (= :invalid (error-kind #(registry-of (command-entry "strshell-probe"
                                                             :runner/shell "yes"))))))
  (testing ":runner/shell must be declared explicitly; there is no default"
    (is (= :invalid (error-kind #(registry-of (command-entry "implicit-probe"
                                                             :runner/shell nil))))))
  (testing "slot shapes are strict per type"
    (is (= :invalid
           (error-kind #(registry-of (command-entry "dup-slot"
                                                    :command/args [(slot "x" :integer :min 0 :max 1)
                                                                   (slot "x" :integer :min 0 :max 1)])))))
    (is (= :invalid
           (error-kind #(registry-of (command-entry "str-nolimit"
                                                    :command/args [(slot "s" :string)])))))
    (is (= :invalid
           (error-kind #(registry-of (command-entry "enum-novalues"
                                                    :command/args [(slot "e" :enum)])))))
    (is (= :invalid
           (error-kind #(registry-of (command-entry "int-badbounds"
                                                    :command/args [(slot "n" :integer :min 5 :max 4)])))))
    (is (= :invalid
           (error-kind #(registry-of (command-entry "mixed-slot"
                                                    :command/args [(slot "s" :string :min 0 :max-length 10)])))))
    (is (= :invalid
           (error-kind #(registry-of (command-entry "bad-template"
                                                    :command/args [42])))))))

;; ------------------------------------------------------------------
;; Pure port: run-request validation

(defn- enum-registry []
  (registry-of
   (command-entry "render-probe"
                  :command/executable "/bin/echo"
                  :command/args ["--mode"
                                 (slot "mode" :enum :values ["fast" "slow"])
                                 (slot "count" :integer :min 1 :max 9)
                                 (slot "label" :string :max-length 16)])))

(deftest run-request-validation
  (let [reg (enum-registry)]
    (testing "unknown command ID is rejected"
      (is (= :invalid (error-kind #(runner/validate-run-request!
                                    reg (request "no-such-command"))))))
    (testing "args must supply exactly the declared slots"
      (is (= :invalid (error-kind #(runner/validate-run-request!
                                    reg (request "render-probe" :args {})))))
      (is (= :invalid (error-kind #(runner/validate-run-request!
                                    reg (request "render-probe"
                                                 :args {"mode" "fast" "count" 3})))))
      (is (= :invalid (error-kind #(runner/validate-run-request!
                                    reg (request "render-probe"
                                                 :args {"mode" "fast" "count" 3
                                                        "label" "x" "extra" "y"})))))
      (is (= :invalid (error-kind #(runner/validate-run-request!
                                    reg (request "render-probe" :args nil))))))
    (testing "typed slots reject mistyped and out-of-range values"
      (let [good {"mode" "fast" "count" 3 "label" "x"}]
        (is (= :invalid (error-kind #(runner/validate-run-request!
                                      reg (request "render-probe"
                                                   :args (assoc good "mode" "medium"))))))
        (is (= :invalid (error-kind #(runner/validate-run-request!
                                      reg (request "render-probe"
                                                   :args (assoc good "count" 10))))))
        (is (= :invalid (error-kind #(runner/validate-run-request!
                                      reg (request "render-probe"
                                                   :args (assoc good "count" "3"))))))
        (is (= :invalid (error-kind #(runner/validate-run-request!
                                      reg (request "render-probe"
                                                   :args (assoc good "label" 7))))))
        (is (= :invalid (error-kind #(runner/validate-run-request!
                                      reg (request "render-probe"
                                                   :args (assoc good "label"
                                                                (apply str (repeat 17 "x"))))))))))
    (testing "shell interpolation attempts are rejected when :runner/shell is false"
      (let [good {"mode" "fast" "count" 3}]
        (doseq [evil ["$(whoami)" "a;b" "a|b" "`id`" "a&b" "x${HOME}y"]]
          (is (= :invalid (error-kind #(runner/validate-run-request!
                                        reg (request "render-probe"
                                                     :args (assoc good "label" evil)))))))))
    (testing "argv renders literals verbatim and slots in order, executable first"
      (let [plan (runner/validate-run-request!
                  reg (request "render-probe"
                               :args {"mode" "slow" "count" 9 "label" "ok"}))]
        (is (= ["/bin/echo" "--mode" "slow" "9" "ok"] (:argv plan)))
        (is (= {"mode" "slow" "count" 9 "label" "ok"} (:args plan)))))
    (testing "applicability defaults to the registry note, overridable per run"
      (let [plan (runner/validate-run-request!
                  reg (request "render-probe"
                               :args {"mode" "fast" "count" 1 "label" "x"}))]
        (is (= "Synthetic test-only command." (:applicability plan))))
      (let [plan (runner/validate-run-request!
                  reg (request "render-probe"
                               :args {"mode" "fast" "count" 1 "label" "x"}
                               :applicability "Per-run note."))]
        (is (= "Per-run note." (:applicability plan)))))
    (testing "candidate identities and run IDs are validated"
      (is (= :invalid (error-kind #(runner/validate-run-request!
                                    reg (request "render-probe"
                                                 :args {"mode" "fast" "count" 1 "label" "x"}
                                                 :candidate {:candidate/base "nope"
                                                             :candidate/head nil
                                                             :candidate/tree nil})))))
      (is (= :invalid (error-kind #(runner/validate-run-request!
                                    reg (request "render-probe"
                                                 :args {"mode" "fast" "count" 1 "label" "x"}
                                                 :run/id ""))))))))

(deftest shell-flag-reviewed-choice
  (testing ":runner/shell true skips the metacharacter check and is recorded verbatim"
    (let [reg (registry-of
               (command-entry "sh-probe"
                              :command/executable "/bin/sh"
                              :command/args [(slot "snippet" :string :max-length 64)]
                              :runner/shell true))
          plan (runner/validate-run-request!
                reg (request "sh-probe" :args {"snippet" "echo $(whoami)"}))]
      (is (= true (:runner/shell (:command plan))))
      (is (= ["/bin/sh" "echo $(whoami)"] (:argv plan)))
      (let [record (runner/build-run-record
                    plan {:outcome :completed :exit 0
                          :stdout-bytes (byte-array 0) :stderr-bytes (byte-array 0)
                          :bound-exceeded nil})]
        (is (= true (:runner/shell record))))))
  (testing ":runner/shell false is recorded verbatim too"
    (let [reg (registry-of (command-entry "plain-probe"))
          plan (runner/validate-run-request! reg (request "plain-probe"))
          record (runner/build-run-record
                  plan {:outcome :completed :exit 0
                        :stdout-bytes (byte-array 0) :stderr-bytes (byte-array 0)
                        :bound-exceeded nil})]
      (is (= false (:runner/shell record))))))

;; ------------------------------------------------------------------
;; Pure port: Evidence record construction

(defn- exec [outcome exit out-str err-str bound]
  {:outcome outcome :exit exit
   :stdout-bytes (utf8 out-str) :stderr-bytes (utf8 err-str)
   :bound-exceeded bound})

(deftest run-record-construction
  (let [reg (registry-of (command-entry "true-probe"))
        plan (runner/validate-run-request! reg (request "true-probe"))]
    (testing "a clean completed run"
      (let [record (runner/build-run-record plan (exec :completed 0 "" "" nil))]
        (is (= :diagnostic-run (:run/kind record)))
        (is (= "run-001" (:run/id record)))
        (is (= {:producer/id "axiom-local-runner" :producer/authenticated? false}
               (:producer record)))
        (is (= :trust/local-diagnostic (:trust record)))
        (is (= {:candidate/base sha-a :candidate/head sha-b :candidate/tree sha-c}
               (:candidate record)))
        (is (= :completed (:run/outcome record)))
        (is (= true (:run/complete? record)))
        (is (= :pass (:run/result record)))
        (is (= 0 (:run/exit record)))
        (is (nil? (:run/bound-exceeded record)))
        (is (= runner/isolation-limitations (:run/limitations record)))
        (is (str/includes? (:run/limitations record) "no network sandboxing"))
        (is (= (model/sha256-bytes (byte-array 0)) (:run/stdout-digest record)))
        (is (= 0 (:run/stdout-bytes record)))
        (is (= ["/bin/true"] (:command/argv record)))))
    (testing "non-zero exit is still :completed, reported honestly as :fail"
      (let [record (runner/build-run-record plan (exec :completed 1 "" "boom" nil))]
        (is (= :completed (:run/outcome record)))
        (is (= true (:run/complete? record)))
        (is (= :fail (:run/result record)))
        (is (= 1 (:run/exit record)))
        (is (= (model/sha256-bytes (utf8 "boom")) (:run/stderr-digest record)))
        (is (= 4 (:run/stderr-bytes record)))))
    (testing "every non-completed outcome is :incomplete"
      ;; A capped stream honestly records exactly the cap in bytes.
      (doseq [[outcome bound stdout] [[:timed-out :timeout-seconds (byte-array 0)]
                                      [:cancelled nil (byte-array 0)]
                                      [:output-capped :stdout-cap-bytes (byte-array 65536)]]]
        (let [record (runner/build-run-record
                      plan {:outcome outcome :exit nil
                            :stdout-bytes stdout :stderr-bytes (byte-array 0)
                            :bound-exceeded bound})]
          (is (= outcome (:run/outcome record)))
          (is (= false (:run/complete? record)))
          (is (= :incomplete (:run/result record)))
          (is (nil? (:run/exit record)))
          (is (= bound (:run/bound-exceeded record))))))
    (testing "record validation rejects inconsistent combinations"
      (is (= :invalid (error-kind #(runner/build-run-record plan (exec :completed nil "" "" nil)))))
      (is (= :invalid (error-kind #(runner/build-run-record plan (exec :timed-out 124 "" "" nil)))))
      (is (= :invalid (error-kind #(runner/build-run-record plan (exec :timed-out nil "" "" :stdout-cap-bytes)))))
      (is (= :invalid (error-kind #(runner/build-run-record plan (exec :completed 0 "" "" :timeout-seconds))))))
    (testing "build-run-record is deterministic from validated inputs alone"
      (let [execution (exec :completed 0 "out" "err" nil)]
        (is (= (runner/build-run-record plan execution)
               (runner/build-run-record plan execution)))))
    (testing "tampered records fail validation"
      (let [record (runner/build-run-record plan (exec :completed 0 "" "" nil))]
        (is (= :invalid (error-kind #(runner/validate-run-record!
                                      (assoc record :trust :trust/remote-ci)))))
        (is (= :invalid (error-kind #(runner/validate-run-record!
                                      (assoc record :run/complete? false)))))
        (is (= :invalid (error-kind #(runner/validate-run-record!
                                      (assoc record :run/limitations "full isolation")))))
        (is (= :invalid (error-kind #(runner/validate-run-record!
                                      (dissoc record :run/outcome)))))))))

;; ------------------------------------------------------------------
;; Adapter: registry loading and run!

(deftest adapter-registry
  (testing "the checked-in registry loads and validates"
    (let [reg (runner-adapter/load-registry!)]
      (is (= #{"true-probe" "false-probe" "sleep-probe" "echo-probe"}
             (set (keys (:commands reg)))))))
  (testing "unknown command IDs and bad args are :invalid, without spawning"
    (is (= :invalid (error-kind #(runner-adapter/run!
                                  {:registry nil :command/id "nope"
                                   :args {} :candidate (candidate) :run/id "r1"}))))
    (is (= :invalid (error-kind #(runner-adapter/run!
                                  {:registry nil :command/id "true-probe"
                                   :args {"bogus" 1} :candidate (candidate) :run/id "r1"}))))
    (is (= :invalid (error-kind #(runner-adapter/run!
                                  {:registry nil :command/id "echo-probe"
                                   :args {"message" "$(whoami)"}
                                   :candidate (candidate) :run/id "r1"}))))))

(deftest adapter-successful-run
  (testing "true-probe produces a :completed Evidence record"
    (let [record (runner-adapter/run! {:registry nil :command/id "true-probe"
                                       :args {} :candidate (candidate)
                                       :run/id "run-true-1"})]
      (is (= "run-true-1" (:run/id record)))
      (is (= :completed (:run/outcome record)))
      (is (= true (:run/complete? record)))
      (is (= :pass (:run/result record)))
      (is (= 0 (:run/exit record)))
      (is (= :trust/local-diagnostic (:trust record)))
      (is (= "axiom-local-runner" (get-in record [:producer :producer/id])))
      (is (= false (get-in record [:producer :producer/authenticated?])))
      (is (= runner/isolation-limitations (:run/limitations record)))
      (is (= ["/bin/true"] (:command/argv record)))
      (is (= false (:runner/shell record)))
      (is (= (model/sha256-bytes (byte-array 0)) (:run/stdout-digest record)))))
  (testing "a nil registry selects the checked-in registry; :run/id defaults to a UUID"
    (let [record (runner-adapter/run! {:command/id "true-probe"
                                       :args {} :candidate (candidate)})]
      (is (= :completed (:run/outcome record)))
      (is (re-matches #"[0-9a-f-]{36}" (:run/id record)))))
  (testing "echo-probe captures stdout and digests it"
    (let [record (runner-adapter/run! {:registry nil :command/id "echo-probe"
                                       :args {"message" "hello-diagnostic"}
                                       :candidate (candidate) :run/id "run-echo-1"})]
      (is (= :completed (:run/outcome record)))
      (is (= :pass (:run/result record)))
      (is (= (model/sha256-bytes (utf8 "hello-diagnostic\n"))
             (:run/stdout-digest record)))
      (is (= 17 (:run/stdout-bytes record)))
      (is (= ["/bin/echo" "hello-diagnostic"] (:command/argv record))))))

(deftest adapter-nonzero-exit
  (testing "false-probe: non-zero exit is :completed with :result :fail"
    (let [record (runner-adapter/run! {:registry nil :command/id "false-probe"
                                       :args {} :candidate (candidate)
                                       :run/id "run-false-1"})]
      (is (= :completed (:run/outcome record)))
      (is (= true (:run/complete? record)))
      (is (= :fail (:run/result record)))
      (is (= 1 (:run/exit record))))))

(deftest adapter-timeout
  (testing "sleep past the timeout kills the process: :timed-out + :incomplete"
    (let [started-at (System/currentTimeMillis)
          record (runner-adapter/run! {:registry nil :command/id "sleep-probe"
                                       :args {"seconds" 30}
                                       :candidate (candidate) :run/id "run-sleep-1"})
          elapsed (- (System/currentTimeMillis) started-at)]
      (is (= :timed-out (:run/outcome record)))
      (is (= false (:run/complete? record)))
      (is (= :incomplete (:run/result record)))
      (is (nil? (:run/exit record)))
      (is (= :timeout-seconds (:run/bound-exceeded record)))
      ;; The 5s registry timeout fired; the 30s sleep did not run to completion.
      (is (< elapsed 20000)))))

(deftest adapter-output-cap
  (testing "output past the cap: :output-capped + :incomplete, bound named"
    (let [reg (runner/validate-registry!
               {:registry/version 1
                :commands {"cap-probe" (command-entry "cap-probe"
                                                      :command/executable "/bin/echo"
                                                      :command/args [(slot "message" :string
                                                                           :max-length 256)]
                                                      :command/stdout-cap-bytes 16
                                                      :command/stderr-cap-bytes 16)}})
          message (apply str (repeat 100 "a"))
          record (runner-adapter/run! {:registry reg :command/id "cap-probe"
                                       :args {"message" message}
                                       :candidate (candidate) :run/id "run-cap-1"})
          captured (subs (str message "\n") 0 16)]
      (is (= :output-capped (:run/outcome record)))
      (is (= false (:run/complete? record)))
      (is (= :incomplete (:run/result record)))
      (is (nil? (:run/exit record)))
      (is (= :stdout-cap-bytes (:run/bound-exceeded record)))
      (is (= 16 (:run/stdout-bytes record)))
      (is (= (model/sha256-bytes (utf8 captured)) (:run/stdout-digest record))))))

(deftest adapter-cancellation
  (testing "cancel! stops a running command: :cancelled + :incomplete"
    (let [started (runner-adapter/start! {:registry nil :command/id "sleep-probe"
                                          :args {"seconds" 30}
                                          :candidate (candidate) :run/id "run-cancel-1"})]
      (Thread/sleep 300)
      (is (= true (runner-adapter/cancel! started)))
      (let [record (try @(:future started)
                        (catch java.util.concurrent.ExecutionException e
                          (throw (.getCause e))))]
        (is (= "run-cancel-1" (:run/id record)))
        (is (= :cancelled (:run/outcome record)))
        (is (= false (:run/complete? record)))
        (is (= :incomplete (:run/result record)))
        (is (nil? (:run/bound-exceeded record))))
      (is (= false (runner-adapter/cancel! started)))))
  (testing "an explicit registry map is used verbatim, not the checked-in one"
    (let [reg (runner/validate-registry!
               {:registry/version 1
                :commands {"only-here" (command-entry "only-here")}})]
      (is (= :completed (:run/outcome (runner-adapter/run!
                                        {:registry reg :command/id "only-here"
                                         :args {} :candidate (candidate)
                                         :run/id "run-override-1"}))))
      (is (= :invalid (error-kind #(runner-adapter/run!
                                    {:registry reg :command/id "true-probe"
                                     :args {} :candidate (candidate)
                                     :run/id "run-override-2"})))))))
