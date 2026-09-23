(ns axiom.agent-test
  "Tests for spec 0006 T7 (`axiom.adapters.agent`,
   `axiom.execute/check-budgets`): the fake agent's deterministic
   scripted replay incl. adversarial moves, the process agent's
   bounded subprocess (argv-only, scrubbed env, confined workdir,
   timeout SIGKILL, cancellation, schema-validated output), and
   supervisor-side budget enforcement.

   Every identity is invented (`synth-*`). The process adapter
   runs only local synthetic commands (`echo`, `sleep`); no
   network, no live credentials."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.adapters.agent :as agent]
            [axiom.execute :as execute])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- proposal [path]
  {:proposal/actions [{:action/kind :action/write-file
                       :action/path path
                       :action/capability :capability/write-file}]})

(defn- fake-agent [script]
  (agent/make-agent {:agent/kind :fake
                     :agent/id "synth-agent-fake-1"
                     :agent/script script}))

;; ------------------------------------------------------------------
;; Fake agent

(deftest fake-agent-replays-script-deterministically
  (testing "scripted proposals come back in order, then the script is exhausted"
    (let [a (fake-agent [{:fake/kind :proposal :fake/proposal (proposal "docs/a.md")}
                         {:fake/kind :proposal :fake/proposal (proposal "docs/b.md")}])
          r1 (agent/run-agent a {})
          r2 (agent/run-agent a {})
          r3 (agent/run-agent a {})]
      (is (true? (:agent/ok a)))
      (is (= (proposal "docs/a.md") (:agent/proposal r1)))
      (is (= (proposal "docs/b.md") (:agent/proposal r2)))
      (is (false? (:agent/ok r3)))
      (is (= :script-exhausted (:agent/reason r3)))))
  (testing "adversarial moves are returned as data, never executed"
    (let [a (fake-agent [{:fake/kind :adversarial
                          :fake/move :unauthorized-shell
                          :fake/proposal (proposal "docs/x.md")}
                         {:fake/kind :adversarial
                          :fake/move :scope-widen
                          :fake/proposal (proposal "docs/y.md")}
                         {:fake/kind :adversarial
                          :fake/move :stale-token
                          :fake/proposal (proposal "docs/z.md")}])]
      (doseq [move [:unauthorized-shell :scope-widen :stale-token]]
        (let [r (agent/run-agent a {})]
          (is (true? (:agent/ok r)))
          (is (= move (:agent/adversarial r)))
          (is (map? (:agent/proposal r)))))))
  (testing "malformed agents and scripts are refused"
    (is (= :malformed (:agent/reason (agent/make-agent {:agent/kind :fake}))))
    (is (= :malformed (:agent/reason
                       (agent/make-agent {:agent/kind :fake
                                          :agent/id "x"
                                          :agent/script []}))))
    (is (= :malformed (:agent/reason (agent/run-agent nil {}))))
    (is (= :malformed (:agent/reason
                       (agent/run-agent {:agent/ok false} {}))))))

;; ------------------------------------------------------------------
;; Process agent

(defn- temp-worktree []
  (let [root (.toFile (Files/createTempDirectory "axiom-agent-test"
                                                 (into-array FileAttribute [])))
        wt {:worktree/id "wt-synth" :worktree/path (str root)
            :worktree/root (str (.getParentFile root))}]
    [root wt]))

(deftest process-agent-runs-bounded-subprocess
  (let [[root wt] (temp-worktree)]
    (try
      (testing "a well-formed worker emits a schema-valid proposal"
        (let [a (agent/make-agent {:agent/kind :process
                                   :agent/id "synth-agent-proc-1"
                                   :agent/worktree wt
                                   :agent/timeout-ms 5000})]
          (is (true? (:agent/ok a)))
          ;; argv only: the supervisor passes the exact command
          (let [r (agent/run-agent
                   a {:agent/argv ["sh" "-c"
                                   "printf '%s' '{:proposal/actions [{:action/kind :action/write-file :action/path \"docs/a.md\"}]}'"]})]
            (is (true? (:agent/ok r)))
            (is (= "docs/a.md" (get-in r [:agent/proposal :proposal/actions 0 :action/path])))
            (is (integer? (:agent/exit r))))))
      (testing "a runaway worker is SIGKILLed at the timeout"
        (let [a (agent/make-agent {:agent/kind :process
                                   :agent/id "synth-agent-proc-2"
                                   :agent/worktree wt
                                   :agent/timeout-ms 500})
              start (System/currentTimeMillis)
              r (agent/run-agent a {:agent/argv ["sleep" "30"]})
              elapsed (- (System/currentTimeMillis) start)]
          (is (false? (:agent/ok r)))
          (is (= :timed-out (:agent/reason r)))
          (is (< elapsed 10000) "the timeout fired instead of waiting 30s")))
      (testing "non-proposal output is rejected as :invalid-output"
        (let [a (agent/make-agent {:agent/kind :process
                                   :agent/id "synth-agent-proc-3"
                                   :agent/worktree wt
                                   :agent/timeout-ms 5000})
              r (agent/run-agent a {:agent/argv ["echo" "not edn at all"]})]
          (is (false? (:agent/ok r)))
          (is (= :invalid-output (:agent/reason r)))))
      (testing "malformed process agents and requests are refused"
        (is (= :malformed (:agent/reason
                           (agent/make-agent {:agent/kind :process
                                              :agent/id "x"}))))
        (let [a (agent/make-agent {:agent/kind :process
                                   :agent/id "synth-agent-proc-4"
                                   :agent/worktree wt
                                   :agent/timeout-ms 5000})]
          (is (= :malformed (:agent/reason (agent/run-agent a {}))))
          (is (= :malformed (:agent/reason
                             (agent/run-agent a {:agent/argv []}))))))
      (finally
        (doseq [f (.listFiles root)] (.delete f))
        (.delete root)))))

(deftest process-agent-hardened-request-validation
  (testing "blank argv elements are :malformed and never spawned"
    (let [[root wt] (temp-worktree)]
      (try
        (let [a (agent/make-agent {:agent/kind :process
                                   :agent/id "synth-agent-proc-6"
                                   :agent/worktree wt
                                   :agent/timeout-ms 5000})]
          ;; A blank executable would previously reach ProcessBuilder
          ;; and fail as :spawn-failed; now it is :malformed up front.
          (is (= :malformed (:agent/reason
                             (agent/run-agent a {:agent/argv ["" "x"]}))))
          (is (= :malformed (:agent/reason
                             (agent/run-agent a {:agent/argv ["echo" ""]}))))
          (is (= :malformed (:agent/reason
                             (agent/run-agent a {:agent/argv ["echo" "  "]}))))
          (is (= :malformed (:agent/reason
                             (agent/run-agent a {:agent/argv ["echo" 7]})))))
        (finally
          (doseq [f (.listFiles root)] (.delete f))
          (.delete root))))
  (testing ":agent/env must be a map of strings when present"
    (let [[root wt] (temp-worktree)]
      (try
        (let [a (agent/make-agent {:agent/kind :process
                                   :agent/id "synth-agent-proc-7"
                                   :agent/worktree wt
                                   :agent/timeout-ms 5000})
              good-argv ["echo" "{:proposal/actions []}"]]
          ;; absent env is fine
          (is (true? (:agent/ok (agent/run-agent a {:agent/argv good-argv}))))
          ;; the process agent is one-shot (amendment A1 AR10): a
          ;; second valid call ends the loop instead of re-spawning
          (is (= :script-exhausted
                 (:agent/reason (agent/run-agent
                                 a {:agent/argv good-argv
                                    :agent/env {"SYNTH_VAR" "synth-value"}}))))
          ;; non-string keys/values and non-maps are :malformed;
          ;; validation runs before the one-shot guard, so these are
          ;; :malformed even though the agent has already run
          (doseq [env [{"SYNTH_VAR" 7}
                       {7 "synth-value"}
                       {"SYNTH_VAR" nil}
                       "not-a-map"
                       ["SYNTH_VAR" "synth-value"]]]
            (is (= :malformed (:agent/reason
                               (agent/run-agent a {:agent/argv good-argv
                                                   :agent/env env})))
                (str "expected :malformed for env " (pr-str env)))))
        (finally
          (doseq [f (.listFiles root)] (.delete f))
          (.delete root))))))
  (testing "cancel! SIGKILLs a running worker and wait! reports :cancelled"
    (let [[root wt] (temp-worktree)]
      (try
        (let [a (agent/make-agent {:agent/kind :process
                                   :agent/id "synth-agent-proc-5"
                                   :agent/worktree wt
                                   :agent/timeout-ms 30000})
              started (agent/start-agent a {:agent/argv ["sleep" "30"]})]
          (is (true? (:agent/ok started)))
          (Thread/sleep 500)
          (is (true? ((:agent/cancel! started))) "cancel! killed the live process")
          (let [res ((:agent/wait! started))]
            (is (false? (:agent/ok res)))
            (is (= :cancelled (:agent/reason res))))
          (is (false? ((:agent/cancel! started))) "cancel after completion is a no-op"))
        (finally
          (doseq [f (.listFiles root)] (.delete f))
          (.delete root))))))

(deftest scrub-env-removes-credentials
  (testing "credential/token-shaped vars are scrubbed, ordinary vars pass"
    (let [clean (agent/scrub-env {"PATH" "/usr/bin"
                                  "HOME" "/home/synth"
                                  "SYNTH_API_TOKEN" "secret"
                                  "AWS_SECRET_ACCESS_KEY" "secret"
                                  "GITHUB_TOKEN" "secret"
                                  "DB_PASSWORD" "secret"})]
      (is (= {"PATH" "/usr/bin" "HOME" "/home/synth"} clean)))))

;; ------------------------------------------------------------------
;; Budget enforcement

(deftest check-budgets
  (testing "usage within all budgets passes"
    (let [task {:task/id "synth-task-1"
                :budget/max-cost 10
                :budget/max-attempts 5
                :budget/max-wall-seconds 60}]
      (is (nil? (execute/check-budgets
                 task {:budget/cost 3 :budget/attempts 2 :budget/wall-seconds 10})))))
  (testing "any exceeded budget yields :budget-exhausted"
    (let [task {:task/id "synth-task-1"
                :budget/max-cost 10
                :budget/max-attempts 5
                :budget/max-wall-seconds 60}]
      (is (= :budget-exhausted (execute/check-budgets
                                task {:budget/cost 11 :budget/attempts 2 :budget/wall-seconds 10})))
      (is (= :budget-exhausted (execute/check-budgets
                                task {:budget/cost 3 :budget/attempts 6 :budget/wall-seconds 10})))
      (is (= :budget-exhausted (execute/check-budgets
                                task {:budget/cost 3 :budget/attempts 2 :budget/wall-seconds 61})))))
  (testing "unconfigured budgets are unbounded"
    (is (nil? (execute/check-budgets
               {:task/id "synth-task-1"}
               {:budget/cost 1000000 :budget/attempts 1000000
                :budget/wall-seconds 1000000}))))
  (testing "malformed usage is :invalid, never a silent pass"
    (is (= :invalid (execute/check-budgets {:task/id "synth-task-1"} nil)))
    (is (= :invalid (execute/check-budgets
                     {:task/id "synth-task-1"}
                     {:budget/cost -1 :budget/attempts 0 :budget/wall-seconds 0})))
    (is (= :invalid (execute/check-budgets
                     {:task/id "synth-task-1"}
                     {:budget/cost 1 :budget/attempts 1})))))

;; ------------------------------------------------------------------
;; Guarded-loop integration: fake proposal through the evaluator

(deftest fake-proposal-through-evaluate-proposal
  (testing "an adversarial unauthorized-shell proposal is denied by the pure evaluator"
    (let [a (fake-agent [{:fake/kind :adversarial
                          :fake/move :unauthorized-shell
                          :fake/proposal {:proposal/actions
                                          [{:action/kind :action/run-command
                                            :action/argv ["./scripts/check"]
                                            :action/capability :capability/shell}]
                                          :proposal/fencing-token "synth-token-1"
                                          :proposal/worker-id "synth-worker-1"}}])
          {:agent/keys [proposal]} (agent/run-agent a {})
          task {:task/id "synth-task-1"
                :task/class :task-class/standard
                :task/capabilities {:capability/shell false}
                :task/pinned-recipe ["./scripts/check"]
                :task/scope {:scope/path-prefixes #{"docs/"}}}
          lease {:lease/task-id "synth-task-1"
                 :lease/worker-id "synth-worker-1"
                 :lease/token "synth-token-1"}
          ;; the worker's output must name the task to be evaluable
          proposal (assoc proposal :proposal/task-id "synth-task-1")
          res (execute/evaluate-proposal task proposal lease)]
      (is (= :deny (:proposal/decision res)))
      (is (= :unauthorized-capability (:proposal/reason res))))))
