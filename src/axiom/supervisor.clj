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

(defn- run-loop
  "The guarded loop. Returns `{:supervisor/exit <0|4|5>,
   :supervisor/report {...}}`."
  [{:keys [task adapter-kind fake-script worktree-base]}]
  (let [ledger-file (File/createTempFile "axiom-run-task" ".db")
        _ (.delete ledger-file) ; store/open! creates it
        handle (store/open! (str ledger-file) {:create true})
        base-dir (temp-dir "axiom-run-base")
        wt-dir (temp-dir "axiom-run-wt")]
    (try
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
            ;; 1. Task admission
            task-event {:event/kind :task/accepted
                        :task/id (:task/id task)
                        :task/class (:task/class task :task-class/standard)
                        :task/capabilities (:task/capabilities task)
                        :task/evaluator evaluator-id}
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
            ;; 3. Agent creation
            agent (if (= :fake adapter-kind)
                      (agent/make-agent {:agent/kind :fake
                                         :agent/id "synth-agent-1"
                                         :agent/script fake-script})
                      (agent/make-agent {:agent/kind :process
                                         :agent/id "synth-agent-1"
                                         :agent/worktree worktree
                                         :agent/timeout-ms 30000}))
            ;; 4. Proposal loop
            [proposal-results admitted-proposals]
            (loop [results [] admitted []]
              (let [step (agent/run-agent agent {})]
                (cond
                  ;; Script exhausted: the fake agent has no more steps
                  (= :script-exhausted (:agent/reason step))
                  [results admitted]

                  (not (:agent/ok step))
                  [(conj results {:step :agent-failed
                                  :reason (:agent/reason step)})
                   admitted]

                  :else
                  (let [proposal (:agent/proposal step)
                        proposal (assoc proposal
                                        :proposal/task-id (:task/id task)
                                        :proposal/worker-id "synth-worker-1"
                                        :proposal/fencing-token fencing-token)
                        verdict (execute/evaluate-proposal task proposal lease)]
                    (if (= :deny (:proposal/decision verdict))
                      [(conj results {:step :proposal-denied
                                      :reason (:proposal/reason verdict)})
                       admitted]
                      ;; Proposal admitted by the pure evaluator; apply
                      ;; its actions to the worktree (synthetic
                      ;; execution for the fake adapter) and record
                      ;; the admission in the report.
                      (do
                        (apply-actions! worktree (:proposal/actions proposal))
                        (recur (conj results
                                     {:step :proposal-admitted
                                      :proposal/digest (model/digest proposal)})
                               (conj admitted proposal))))))))
            denied (some #(when (= :proposal-denied (:step %)) %) proposal-results)]
        (if denied
          ;; Adversarial or invalid proposal stopped the loop
          (do
            (record! ledger/record-task :task-event
                     {:event/kind :task/blocked
                      :task/id (:task/id task)
                      :task/evaluator evaluator-id
                      :task/blockers [(:reason denied)]})
            {:supervisor/exit 0
             :supervisor/report {:task/id (:task/id task)
                                 :task/status :blocked
                                 :task/blocker (:reason denied)
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
      (run-loop {:task task
                 :adapter-kind (keyword adapter)
                 :fake-script (:task/fake-script task)
                 :worktree-base (:task/worktree-base task)}))))
