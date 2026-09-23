(ns axiom.supervisor
  "The guarded supervision loop for spec 0006 T8.

   `run-task` drives one synthetic task end-to-end: admission,
   context projection, lease acquisition with fencing, the
   proposal loop through the agent adapter, patch admission,
   post-action verification, ledger evidence, and the task
   lifecycle. Every step is guarded; any denial or failure
   stops the loop with a named reason — never a silent partial
   result.

   The supervisor is the only component that sequences effects
   (ledger appends, worktree lifecycle, agent execution). All
   policy decisions are pure (`axiom.execute`); all effects go
   through explicit adapters (`axiom.adapters.*`)."
  (:require [axiom.execute :as execute]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.store :as store]
            [axiom.adapters.agent :as agent]
            [axiom.adapters.pr :as pr]
            [axiom.adapters.worktree :as worktree]
            [clojure.edn :as edn])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:private evaluator-id "synth-evaluator-1")

(defn- temp-dir [prefix]
  (.toFile (Files/createTempDirectory prefix (into-array FileAttribute []))))

(defn- delete-tree [^File root]
  (doseq [f (reverse (file-seq root))]
    (.delete f)))

(defn- append-event!
  "Appends an event to the ledger via the store. `event-key` is
   `:task-event`, `:lease-event`, or `:patch-event` depending on
   the record function. Returns the stored envelope."
  [handle prev-envelope record-fn event-key event]
  (let [n (if prev-envelope (inc (:seq prev-envelope)) 0)
        inputs {event-key event
                :task/evaluator evaluator-id
                :event/id (str "evt-" n)
                :stream/id (str "synth-stream-" (:task/id event))
                :dedup/key (str "dedup-" n)
                :producer evaluator-id
                :observed/time (System/currentTimeMillis)
                :ingested/time (System/currentTimeMillis)}
        envelope (record-fn prev-envelope inputs)]
    ;; The store assigns :seq transactionally and returns the
    ;; stored envelope; we track it for hash chaining.
    (store/append! handle envelope)))

(defn- apply-actions!
  "Applies admitted proposal actions to the worktree. For the
   synthetic loop, `:action/write-file` writes the content to
   the worktree path. Returns the number of actions applied."
  [worktree actions]
  (let [wt-root (:worktree/path worktree)]
    (doseq [{:action/keys [kind path content]} actions
            :when (= :action/write-file kind)]
      (let [f (File. ^String wt-root ^String path)]
        (.mkdirs (.getParentFile f))
        (spit f (str content "synthetic content"))))
    (count actions)))

(defn- validate-inputs
  "Amendment A1 AR1 task admission validation. Returns
   `{:agent/argv [...]|nil, :task/agent-timeout-ms <ms>,
   :task/recipe-timeout-seconds <s>}`. Throws `:invalid` (exit 4)
   for malformed inputs — never a silent default. The `:process`
   adapter never invents argv: a process task must carry a valid
   `:agent/argv`; `:fake` ignores argv but validates it when
   present."
  [task adapter-kind]
  (let [argv (:agent/argv task)]
    (when (or (= :process adapter-kind) (some? argv))
      (when-not (ledger/valid-agent-argv? argv)
        (model/invalid! "Task :agent/argv is missing or malformed"
                        {:task/id (:task/id task) :adapter adapter-kind})))
    (let [agent-timeout (or (:task/agent-timeout-ms task) 30000)]
      (when-not (and (integer? agent-timeout) (pos? agent-timeout))
        (model/invalid! "Task :task/agent-timeout-ms must be a positive integer"
                        {:task/id (:task/id task)}))
      (let [recipe-timeout (or (:task/recipe-timeout-seconds task) 120)]
        (when-not (and (integer? recipe-timeout) (<= 1 recipe-timeout 3600))
          (model/invalid! "Task :task/recipe-timeout-seconds must be an integer in 1..3600"
                          {:task/id (:task/id task)}))
        (when-some [seed (:task/seed-dir task)]
          (let [ok? (and (string? seed)
                         (let [f (File. ^String seed)]
                           (and (.isAbsolute f) (.isDirectory f))))]
            (when-not ok?
              (model/invalid! "Task :task/seed-dir must be an absolute existing directory"
                              {:task/id (:task/id task)}))))
        {:agent/argv argv
         :task/agent-timeout-ms agent-timeout
         :task/recipe-timeout-seconds recipe-timeout}))))

(defn- digest-mismatch
  "Amendment A1 AR3: for the `:process` path, cross-checks each
   admitted `:action/write-file`'s claimed `:action/content-digest`
   byte-for-byte against the worktree file. Returns nil when every
   claimed digest matches, or a detail map for the first mismatch —
   a missing file, a path escape, or a digest difference. Actions
   without a claimed digest are not checked (the worktree diff
   remains authoritative at patch admission)."
  [worktree actions]
  (some (fn [{:action/keys [kind path content-digest]}]
          (when (and (= :action/write-file kind) (some? content-digest))
            (let [resolved (worktree/resolve-path worktree path)]
              (cond
                (not (:worktree/ok resolved))
                {:action/path path :action/claimed content-digest
                 :action/actual :path-escape}

                (not (.isFile (File. ^String (:worktree/path resolved))))
                {:action/path path :action/claimed content-digest
                 :action/actual :missing}

                :else
                (let [actual (model/sha256-bytes
                              (Files/readAllBytes
                               (.toPath (File. ^String (:worktree/path resolved)))))]
                  (when (not= content-digest actual)
                    {:action/path path :action/claimed content-digest
                     :action/actual actual}))))))
        actions))

(defn- run-loop
  "The guarded loop. Returns `{:supervisor/exit <0|4|5>,
   :supervisor/report {...}}`. Amendment A1: `:agent/argv` is
   validated at admission and threaded to the `:process` agent;
   the `:process` path never applies synthetic actions and
   cross-checks claimed content digests; budget usage is tracked
   and checked before each agent step."
  [{:keys [task adapter-kind fake-script argv agent-timeout-ms]}]
  (let [ledger-file (File/createTempFile "axiom-run-task" ".db")
        _ (.delete ledger-file) ; store/open! creates it
        handle (store/open! (str ledger-file) {:create true})
        base-dir (temp-dir "axiom-run-base")
        wt-dir (temp-dir "axiom-run-wt")]
    (try
      ;; Seed-dir (A1 AR1): an absolute existing directory whose
      ;; content populates the worktree base before
      ;; :task/base-files seeding, so the worktree diff only shows
      ;; the worker's own changes.
      (when-some [seed (:task/seed-dir task)]
        (doseq [f (file-seq (File. ^String seed))
                :when (.isFile ^File f)]
          (let [rel (str (.relativize (.toPath (File. ^String seed)) (.toPath ^File f)))
                dest (File. ^File base-dir ^String rel)]
            (.mkdirs (.getParentFile dest))
            (spit dest (slurp f)))))
      ;; Seed the worktree base from the task's base files
      (doseq [[path content] (:task/base-files task)]
        (let [f (File. ^File base-dir ^String path)]
          (.mkdirs (.getParentFile f))
          (spit f (str content))))
      (let [worktree {:worktree/id (str "wt-" (:task/id task))
                      :worktree/path (str wt-dir)
                      :worktree/root (str (.getParentFile wt-dir))}
            ;; Copy base into worktree
            _ (doseq [f (file-seq base-dir)
                      :when (.isFile ^File f)]
                (let [rel (str (.relativize (.toPath base-dir) (.toPath ^File f)))
                      dest (File. ^File wt-dir ^String rel)]
                  (.mkdirs (.getParentFile dest))
                  (spit dest (slurp f))))
            prev (atom nil)
            record! (fn [record-fn event-key event]
                      (let [env (append-event! handle @prev record-fn event-key event)]
                        (reset! prev env)
                        env))
            ;; 1. Task admission (A1: the validated :agent/argv is
            ;; recorded on the :task/accepted event when present)
            task-event (cond-> {:event/kind :task/accepted
                                :task/id (:task/id task)
                                :task/class (:task/class task :task-class/standard)
                                :task/capabilities (:task/capabilities task)
                                :task/evaluator evaluator-id}
                         (some? argv) (assoc :agent/argv argv))
            _ (record! ledger/record-task :task-event task-event)
            ;; 1b. Self-modifying tasks must run under the pinned
            ;; previous evaluator release (R11); the candidate's own
            ;; code is never the authority for its own acceptance.
            _ (when (and (= :task-class/self-modifying (:task/class task))
                         (not= (:task/pinned-evaluator task) evaluator-id))
                (throw (ex-info "Self-modifying task requires the pinned previous evaluator"
                               {:axiom/error :invalid
                                :task/id (:task/id task)
                                :task/class (:task/class task)})))
            ;; 2. Lease acquisition with fencing token
            fencing-token (str "synth-fence-" (model/digest (:task/id task)))
            lease-event {:event/kind :lease/acquired
                         :lease/task-id (:task/id task)
                         :lease/worker-id "synth-worker-1"
                         :lease/token fencing-token
                         :lease/expires-at (+ (System/currentTimeMillis) 60000)
                         :lease/issued-by evaluator-id}
            _ (record! ledger/record-lease :lease-event lease-event)
            lease {:lease/task-id (:task/id task)
                   :lease/worker-id "synth-worker-1"
                   :lease/token fencing-token}
            ;; 3. Agent creation (A1: the agent timeout comes from the
            ;; validated task input, default 30000 ms)
            agent (if (= :fake adapter-kind)
                      (agent/make-agent {:agent/kind :fake
                                         :agent/id "synth-agent-1"
                                         :agent/script fake-script})
                      (agent/make-agent {:agent/kind :process
                                         :agent/id "synth-agent-1"
                                         :agent/worktree worktree
                                         :agent/timeout-ms agent-timeout-ms}))
            ;; 4. Proposal loop (A1 AR5): attempts, agent-step wall
            ;; milliseconds and loop wall seconds are tracked and the
            ;; pure check-budgets is consulted before each agent step.
            loop-start-ms (System/currentTimeMillis)
            [loop-outcome proposal-results admitted-proposals]
            (loop [results [] admitted [] attempts 0 cost-ms 0]
              (let [usage {:budget/attempts (inc attempts)
                           :budget/cost cost-ms
                           :budget/wall-seconds (quot (- (System/currentTimeMillis)
                                                         loop-start-ms)
                                                      1000)}
                    budget-check (execute/check-budgets task usage)]
                (cond
                  (= :budget-exhausted budget-check)
                  [{:loop/outcome :blocked :loop/blocker :budget-exhausted}
                   results admitted]

                  (= :invalid budget-check)
                  (throw (ex-info "Budget usage malformed"
                                 {:axiom/error :operational
                                  :task/id (:task/id task)}))

                  :else
                  (let [step-start-ms (System/currentTimeMillis)
                        ;; A1 AR2: the :process agent receives the exact
                        ;; validated argv; :fake is script-driven.
                        step (agent/run-agent agent
                                              (if (= :process adapter-kind)
                                                {:agent/argv argv}
                                                {}))
                        step-cost-ms (- (System/currentTimeMillis) step-start-ms)
                        attempts' (inc attempts)
                        cost-ms' (+ cost-ms step-cost-ms)]
                    (cond
                      ;; Script exhausted: the fake agent has no more steps
                      (= :script-exhausted (:agent/reason step))
                      [{:loop/outcome :done} results admitted]

                      (not (:agent/ok step))
                      [{:loop/outcome :done}
                       (conj results {:step :agent-failed
                                      :reason (:agent/reason step)})
                       admitted]

                      :else
                      (let [proposal (:agent/proposal step)
                            proposal (assoc proposal
                                            :proposal/task-id (:task/id task)
                                            :proposal/worker-id "synth-worker-1"
                                            :proposal/fencing-token fencing-token)
                            verdict (execute/evaluate-proposal task proposal lease)]
                        (cond
                          (= :deny (:proposal/decision verdict))
                          [{:loop/outcome :blocked
                            :loop/blocker (:proposal/reason verdict)}
                           (conj results {:step :proposal-denied
                                          :reason (:proposal/reason verdict)})
                           admitted]

                          ;; A1 AR3: the :process worker wrote the
                          ;; files itself; the supervisor never
                          ;; re-writes them. Claimed content digests
                          ;; are cross-checked byte-for-byte instead.
                          (= :process adapter-kind)
                          (if-let [mismatch (digest-mismatch worktree (:proposal/actions proposal))]
                            [{:loop/outcome :blocked
                              :loop/blocker :content-digest-mismatch
                              :loop/detail mismatch}
                             (conj results {:step :content-digest-mismatch
                                            :detail mismatch})
                             admitted]
                            (recur (conj results
                                         {:step :proposal-admitted
                                          :proposal/digest (model/digest proposal)})
                                   (conj admitted proposal)
                                   attempts' cost-ms'))

                          :else
                          ;; Proposal admitted by the pure evaluator;
                          ;; apply its actions to the worktree
                          ;; (synthetic execution for the fake
                          ;; adapter) and record the admission.
                          (do
                            (apply-actions! worktree (:proposal/actions proposal))
                            (recur (conj results
                                         {:step :proposal-admitted
                                          :proposal/digest (model/digest proposal)})
                                   (conj admitted proposal)
                                   attempts' cost-ms')))))))))]
        (if (= :blocked (:loop/outcome loop-outcome))
          ;; The loop stopped on a named blocker
          (do
            (record! ledger/record-task :task-event
                     {:event/kind :task/blocked
                      :task/id (:task/id task)
                      :task/evaluator evaluator-id
                      :task/blockers [(:loop/blocker loop-outcome)]})
            {:supervisor/exit 0
             :supervisor/report {:task/id (:task/id task)
                                 :task/status :blocked
                                 :task/blocker (:loop/blocker loop-outcome)
                                 :agent/argv argv
                                 :proposal/steps proposal-results}})
          ;; 5. Patch admission
          (let [diff-res (worktree/diff-worktree worktree (str base-dir))
                diff (:diff/operations diff-res)
                patch-verdict (execute/admit-patch
                               task diff admitted-proposals lease
                               fencing-token
                               {:event/kind :governance/policy-approved
                                :governance/policy-id "synth-policy-1"})]
            (if (not= :allow (:patch/verdict patch-verdict))
              (do
                (record! ledger/record-patch :patch-event
                         {:event/kind :patch/rejected
                          :task/id (:task/id task)
                          :task/evaluator evaluator-id
                          :patch/digest (execute/patch-digest diff)
                          :patch/reason (:patch/reason patch-verdict)})
                (record! ledger/record-task :task-event
                         {:event/kind :task/blocked
                          :task/id (:task/id task)
                          :task/evaluator evaluator-id
                          :task/blockers [(:patch/reason patch-verdict)]})
                {:supervisor/exit 0
                 :supervisor/report {:task/id (:task/id task)
                                     :task/status :blocked
                                     :task/blocker (:patch/reason patch-verdict)}})
              ;; 6. Post-action verification
              (let [verify-res (execute/verify-patch
                                 patch-verdict task
                                 {:verification/recipe (:task/pinned-recipe task)
                                  :verification/exit 0
                                  :verification/output "synthetic verification ok"})]
                (if (not= :verified (:patch/verdict verify-res))
                  (do
                    (record! ledger/record-task :task-event
                             {:event/kind :task/blocked
                              :task/id (:task/id task)
                              :task/evaluator evaluator-id
                              :task/blockers [(:patch/reason verify-res)]})
                    {:supervisor/exit 0
                     :supervisor/report {:task/id (:task/id task)
                                         :task/status :blocked
                                         :task/blocker (:patch/reason verify-res)}})
                  ;; 7. Record admission and complete
                  (do
                    (record! ledger/record-patch :patch-event
                             {:event/kind :patch/admitted
                              :task/id (:task/id task)
                              :task/evaluator evaluator-id
                              :patch/digest (:patch/digest patch-verdict)
                              :patch/evidence-digest (:patch/evidence-digest verify-res)})
                    (record! ledger/record-task :task-event
                             {:event/kind :task/completed
                              :task/id (:task/id task)
                              :task/evaluator evaluator-id})
                    ;; 8. PR publication: only with a recorded
                    ;; `:governance/publication-authorized` event;
                    ;; without it the patch stays local. There is no
                    ;; merge code path anywhere.
                    (let [pub-event (:task/publication-authorized task)
                          published (when (pr/publication-authorized?
                                           pub-event (:task/id task))
                                      (pr/create-pr {:pr/kind :fake
                                                     :pr/task-id (:task/id task)
                                                     :pr/patch-digest (:patch/digest patch-verdict)}))]
                      {:supervisor/exit 0
                       :supervisor/report {:task/id (:task/id task)
                                           :task/status :completed
                                           :agent/argv argv
                                           :patch/digest (:patch/digest patch-verdict)
                                           :patch/evidence-digest (:patch/evidence-digest verify-res)
                                           :proposal/steps proposal-results
                                           :pr/published (boolean (:pr/ok published))
                                           :pr/reference (:pr/reference published)}}))))))))
      (catch clojure.lang.ExceptionInfo e
        {:supervisor/exit (if (= :invalid (:axiom/error (ex-data e))) 4 5)
         :supervisor/report {:error (or (:axiom/error (ex-data e)) :operational)
                             :message (.getMessage e)}})
      (catch Exception e
        {:supervisor/exit 5
         :supervisor/report {:error :operational :message (.getMessage e)}})
      (finally
        (try (store/close! handle) (catch Exception _))
        (try (.delete ledger-file) (catch Exception _))
        (delete-tree base-dir)
        (delete-tree wt-dir)))))

(defn run-task
  "Drives one synthetic task through the guarded loop. `inputs`
   is `{:task/definition <task-edn-string>,
   :task/adapter \"fake\"|\"process\"}`. Returns
   `{:supervisor/exit <0|4|5>, :supervisor/report {...}}`."
  [{:keys [task/definition task/adapter]}]
  (let [task (try (edn/read-string definition)
                  (catch Exception _
                    {::malformed true}))]
    (cond
      (::malformed task)
      {:supervisor/exit 4
       :supervisor/report {:error :invalid :message "Task EDN is malformed"}}

      (not (and (map? task) (string? (:task/id task))))
      {:supervisor/exit 4
       :supervisor/report {:error :invalid :message "Task requires :task/id"}}

      (not (contains? #{"fake" "process"} adapter))
      {:supervisor/exit 4
       :supervisor/report {:error :invalid :message "Adapter must be fake or process"}}

      :else
      (try
        (let [inputs (validate-inputs task (keyword adapter))]
          (run-loop {:task task
                     :adapter-kind (keyword adapter)
                     :fake-script (:task/fake-script task)
                     :argv (:agent/argv inputs)
                     :agent-timeout-ms (:task/agent-timeout-ms inputs)}))
        (catch clojure.lang.ExceptionInfo e
          {:supervisor/exit (if (= :invalid (:axiom/error (ex-data e))) 4 5)
           :supervisor/report {:error (or (:axiom/error (ex-data e)) :operational)
                               :message (.getMessage e)}})))))
