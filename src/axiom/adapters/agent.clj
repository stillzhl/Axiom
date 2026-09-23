(ns axiom.adapters.agent
  "Agent adapter interface for spec 0006 T7 (R9).

   The supervisor talks to workers only through this interface;
   adapters are distinguished by declared identity (`:agent/kind`),
   never by behavior. Two implementations:

   - `:agent/fake`: deterministic and scripted from fixtures for
     the offline loop. Replays a scripted action sequence —
     proposals, action results, evidence claims — including
     scripted adversarial moves (a scope-widening proposal, an
     unauthorized shell request, a stale-token resubmission) so
     the guarded loop is exercised against misbehavior
     deterministically.

   - `:agent/process`: the one real adapter. Spawns the worker as
     a bounded OS subprocess in the isolated worktree: argv comes
     from the admitted proposal only (the adapter never invents
     argv), the environment is scrubbed of evaluator credentials
     and tokens, the working directory is confined to the
     worktree, a wall-clock timeout SIGKILLs on expiry, and
     cancellation SIGKILLs on supervisor cancel. Stdout is
     parsed as EDN and schema-validated as a proposal before the
     supervisor reads it.

   Budgets are enforced supervisor-side (`axiom.execute/check-budgets`),
   not by the adapter.

   API:

     (make-agent {:agent/kind :fake, :agent/id id, :agent/script [...]})
       Builds the deterministic fake agent.

     (make-agent {:agent/kind :process, :agent/id id,
                  :agent/worktree worktree-map,
                  :agent/timeout-ms ms})
       Builds the process agent. The worktree map confines the
       workdir; the timeout bounds the run.

     (run-agent agent {:agent/argv [...] [:agent/env {...}]})
       Runs one worker step, blocking until completion. For `:fake`,
       returns the next scripted `{:agent/ok true, :agent/proposal
       <proposal>}`. For `:process`, spawns the subprocess (argv
       from the admitted proposal only) and returns
       `{:agent/ok true, :agent/proposal <validated>, :agent/exit
       <int>}` or `{:agent/ok false, :agent/reason <named>}`.

     (start-agent agent {:agent/argv [...] [:agent/env {...}]})
       Starts one worker step asynchronously. Returns
       `{:agent/ok true, :agent/cancel!, :agent/wait!}` —
       `cancel!` SIGKILLs the running worker (no-op false after
       completion), `wait!` blocks until the step completes and
       returns the same result shape as `run-agent`. The fake
       agent completes immediately."
  (:require [axiom.adapters.worktree :as worktree]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import (java.util.concurrent TimeUnit)))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(def ^:private credential-env-pattern
  "Environment variable names scrubbed before a worker subprocess
   is spawned: anything credential- or token-shaped."
  #"(?i)^(.*(token|secret|password|credential|api[_-]?key|private[_-]?key|auth).*|AWS_.*|GH_.*|GITHUB_.*)$")

(defn scrub-env
  "Removes credential/token-shaped variables from the environment
   map. Pure."
  [env]
  (into {} (remove (fn [[k _]] (re-matches credential-env-pattern (str k))) env)))

;; ------------------------------------------------------------------
;; Fake agent

(defn- fake-step-ok?
  [step]
  (and (map? step)
       (contains? #{:proposal :adversarial} (:fake/kind step))
       (case (:fake/kind step)
         :proposal (map? (:fake/proposal step))
         :adversarial (keyword? (:fake/move step)))))

(defn make-agent
  "Builds an agent handle. `:agent/kind` is `:fake` or `:process`.
   The fake agent requires `:agent/script` (a vector of
   `:proposal` / `:adversarial` steps); the process agent
   requires `:agent/worktree` (the worktree map, confining the
   workdir) and `:agent/timeout-ms` (positive). Returns the agent
   map, or `{:agent/ok false, :agent/reason :malformed}`."
  [{:agent/keys [kind id script worktree timeout-ms] :as _inputs}]
  (cond
    (not (and (keyword? kind) (non-blank-string? id)))
    {:agent/ok false :agent/reason :malformed}

    (= kind :fake)
    (if (and (sequential? script) (seq script) (every? fake-step-ok? script))
      {:agent/kind :fake :agent/id id :agent/ok true
       :agent/state (atom {:script (vec script) :pos 0})}
      {:agent/ok false :agent/reason :malformed})

    (= kind :process)
    (if (and (map? worktree) (non-blank-string? (:worktree/path worktree))
             (integer? timeout-ms) (pos? timeout-ms))
      {:agent/kind :process :agent/id id :agent/ok true
       :agent/worktree worktree :agent/timeout-ms timeout-ms
       ;; A process worker is one-shot: it runs once, writes its
       ;; files, and emits its proposal. A second run-agent call
       ;; reports :script-exhausted so the supervisor loop ends
       ;; instead of re-spawning the worker forever.
       :process/ran (atom false)}
      {:agent/ok false :agent/reason :malformed})

    :else
    {:agent/ok false :agent/reason :malformed}))

(defn- run-fake
  [agent _request]
  (let [{:keys [script pos]} @(:agent/state agent)]
    (if (>= pos (count script))
      {:agent/ok false :agent/reason :script-exhausted :agent/id (:agent/id agent)}
      (let [step (nth script pos)]
        (swap! (:agent/state agent) update :pos inc)
        (case (:fake/kind step)
          :proposal {:agent/ok true :agent/id (:agent/id agent)
                     :agent/proposal (:fake/proposal step)}
          :adversarial {:agent/ok true :agent/id (:agent/id agent)
                        :agent/adversarial (:fake/move step)
                        :agent/proposal (:fake/proposal step)})))))

(defn- validate-proposal-shape
  "Schema check on worker output: it must be a map with an action
   vector. Deep admission happens in `axiom.execute/evaluate-proposal`;
   this only ensures the adapter never hands the supervisor
   non-data."
  [x]
  (and (map? x)
       (sequential? (:proposal/actions x))
       (every? map? (:proposal/actions x))))

(defn- valid-env?
  "The `:agent/env` shape rule (spec 0006 amendment A1, AR8): when
   present, the environment must be a map with string keys and
   string values. Absent (nil) is fine — the adapter never invents
   environment."
  [env]
  (or (nil? env)
      (and (map? env)
           (every? (fn [[k v]] (and (string? k) (string? v))) env))))

(defn- valid-process-request?
  "Request validation for a :process run (spec 0006 amendment A1,
   AR8): `:agent/argv` must be a non-empty sequence of non-blank
   strings, and `:agent/env`, when present, a map of strings to
   strings. `run-agent` checks this before the one-shot guard so a
   malformed request always yields `:malformed`, even on an agent
   that has already run."
  [{:agent/keys [argv env]}]
  (and (sequential? argv) (seq argv) (every? non-blank-string? argv)
       (valid-env? env)))

(defn- start-process
  [agent request]
  (cond
    (not (valid-process-request? request))
    {:agent/ok false :agent/reason :malformed :agent/id (:agent/id agent)}

    :else
    (let [workdir (:worktree/path (:agent/worktree agent))
          resolved (worktree/resolve-path (:agent/worktree agent) ".")
          argv (:agent/argv request)
          env (:agent/env request)]
      (if (not (:worktree/ok resolved))
        {:agent/ok false :agent/reason :workdir-escape :agent/id (:agent/id agent)}
        (let [pb (ProcessBuilder. ^java.util.List argv)]
          (.directory pb (java.io.File. ^String workdir))
          (doseq [[k v] (scrub-env (or env {}))]
            (.put (.environment pb) (str k) (str v)))
          (try
            (let [proc (.start pb)
                  cancelled? (atom false)
                  cancel! (fn []
                            (if (.isAlive proc)
                              (do (.destroyForcibly proc)
                                  (reset! cancelled? true)
                                  true)
                              false))
                  wait! (fn []
                          (let [finished? (.waitFor proc (long (:agent/timeout-ms agent))
                                                    TimeUnit/MILLISECONDS)]
                            ;; Destroy before reading: slurping a live
                            ;; process's stream would block past the
                            ;; timeout. After destroy the stream
                            ;; closes and the read terminates.
                            (when-not finished?
                              (.destroyForcibly proc)
                              (.waitFor proc))
                            (let [out (try (slurp (.getInputStream proc))
                                           (catch java.io.IOException _ ""))
                                  exit (if finished? (.exitValue proc) -1)]
                              (cond
                                @cancelled?
                                {:agent/ok false :agent/reason :cancelled
                                 :agent/id (:agent/id agent)}

                                (not finished?)
                                {:agent/ok false :agent/reason :timed-out
                                 :agent/id (:agent/id agent)}

                                :else
                                (let [proposal (try (edn/read-string out)
                                                    (catch Exception _ ::unparseable))]
                                  (if (validate-proposal-shape proposal)
                                    {:agent/ok true :agent/id (:agent/id agent)
                                     :agent/proposal proposal :agent/exit exit}
                                    {:agent/ok false :agent/reason :invalid-output
                                     :agent/id (:agent/id agent)}))))))]
              {:agent/ok true :agent/id (:agent/id agent)
               :agent/cancel! cancel! :agent/wait! wait!})
            (catch Exception e
              {:agent/ok false :agent/reason :spawn-failed
               :agent/id (:agent/id agent)
               :agent/detail (str (.getMessage e))})))))))

(defn start-agent
  "Starts one worker step asynchronously. Returns
   `{:agent/ok true, :agent/cancel!, :agent/wait!}` on success —
   `wait!` blocks until completion and returns the `run-agent`
   result shape. Malformed agents or requests yield
   `{:agent/ok false, :agent/reason <named>}`."
  [agent request]
  (cond
    (not (and (map? agent) (:agent/ok agent) (map? request)))
    {:agent/ok false :agent/reason :malformed}

    (= :fake (:agent/kind agent))
    (let [res (run-fake agent request)]
      (if (:agent/ok res)
        {:agent/ok true :agent/id (:agent/id agent)
         :agent/cancel! (fn [] false)
         :agent/wait! (fn [] res)}
        res))

    (= :process (:agent/kind agent))
    (start-process agent request)

    :else
    {:agent/ok false :agent/reason :malformed}))

(defn run-agent
  "Runs one worker step through the adapter, blocking until
   completion. Returns `{:agent/ok true, :agent/proposal
   <proposal>}` for the fake agent (adversarial steps
   additionally carry `:agent/adversarial`), or the process
   result. Request validation runs on every call: a malformed
   request yields `{:agent/ok false, :agent/reason :malformed}`
   even after the agent has run. A `:process` agent is one-shot:
   after its first execution a further *valid* call returns
   `{:agent/ok false, :agent/reason :script-exhausted}` so the
   supervisor loop ends instead of re-spawning the worker.
   Malformed agents yield `{:agent/ok false, :agent/reason
   :malformed}`."
  [agent request]
  (if (and (= :process (:agent/kind agent))
           (valid-process-request? request)
           @(:process/ran agent))
    {:agent/ok false :agent/reason :script-exhausted :agent/id (:agent/id agent)}
    (let [started (start-agent agent request)]
      (when (and (= :process (:agent/kind agent)) (:agent/ok started))
        (reset! (:process/ran agent) true))
      (if (and (:agent/ok started) (:agent/wait! started))
        ((:agent/wait! started))
        started))))
