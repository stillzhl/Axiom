(ns axiom.nomos
  (:require [clojure.string :as str]
            [axiom.contract :as contract]
            [axiom.model :as model]
            [axiom.prover :as prover]
            [axiom.world :as world]))

(defn- rule [id subject status reason support]
  {:rule id :rule/version 1 :subject subject :status status :reason reason :support support})

(defn- outcome [rules]
  (let [statuses (set (map :status rules))]
    (cond (contains? statuses :violated) :deny
          (contains? statuses :unknown) :defer
          :else :allow)))

(defn- spec-rule [policy spec]
  (cond
    (not (contains? (:accepted-states policy) (:state spec)))
    (rule :accepted-spec (:id spec) :violated :spec-not-accepted [])
    (nil? (:approval spec))
    (rule :accepted-spec (:id spec) :unknown :missing-approval-reference [])
    :else (rule :accepted-spec (:id spec) :satisfied :accepted-revision
                [(:revision spec) (:approval spec)])))

(defn- scope-rule [task changes]
  (let [paths (mapcat #(keep % [:old-path :new-path]) (:files changes))
        outside (sort (remove (fn [path] (some #(str/starts-with? path %) (:scope task))) paths))
        modes (mapcat #(keep % [:old-mode :new-mode]) (:files changes))]
    (cond
      (seq outside) (rule :scope (:id task) :violated :outside-allowed-scope (vec outside))
      (not (:complete? changes)) (rule :scope (:id task) :unknown :incomplete-change-observation [])
      (some #(not (#{"100644" "100755"} %)) modes)
      (rule :scope (:id task) :unknown :unsupported-file-mode [])
      :else (rule :scope (:id task) :satisfied :paths-in-scope (vec (sort paths))))))

(defn- task-rules [policy specs obligations state candidate now task evaluated]
  (vec (concat
        [(spec-rule policy (get specs (:spec task)))]
        (for [dep (sort (:depends-on task))]
          (let [result (:result (get evaluated dep))]
            (rule :dependency dep
                  (case result :allow :satisfied :deny :violated :defer :unknown)
                  (case result :allow :dependency-satisfied :deny :dependency-violated :defer :dependency-unknown)
                  [dep])))
        (for [id (sort (:obligations task))]
          (prover/evaluate-obligation state policy candidate now (get obligations id))))))

(defn evaluate-all
  "Validates the scenario, replays its events and evaluates every contract
   task in dependency order. Pure; no I/O. Returns {:state :task-results},
   where each task entry is {:result :rules} without the change-scope rule."
  [scenario]
  (contract/validate-scenario! scenario)
  (let [{:keys [contract policy candidate events now]} scenario
        state (world/replay events)
        specs (into {} (map (juxt :id identity) (:specs contract)))
        obligations (into {} (map (juxt :id identity) (:obligations contract)))
        ;; Topological evaluation is iterative, including for long dependency chains.
        evaluated (loop [remaining (:tasks contract) done (sorted-map)]
                    (if (empty? remaining)
                      done
                      (let [ready (sort-by :id (filter #(every? (set (keys done)) (:depends-on %)) remaining))
                            ready-ids (set (map :id ready))
                            new-done
                            (reduce
                             (fn [results t]
                               (let [rules (task-rules policy specs obligations state candidate now t results)]
                                 (assoc results (:id t) {:result (outcome rules) :rules rules})))
                             done ready)]
                        (recur (remove #(contains? ready-ids (:id %)) remaining) new-done))))]
    {:state state :task-results evaluated}))

(defn evaluate
  "Advisory evaluation. Validates input before producing any decision. No I/O."
  [scenario]
  (let [{:keys [state task-results]} (evaluate-all scenario)
        {:keys [contract candidate changes task now]} scenario
        target (first (filter #(= task (:id %)) (:tasks contract)))
        rules (conj (:rules (get task-results task)) (scope-rule target changes))
        decision {:schema/version 1 :engine/version model/engine-version
                  :mode :offline-advisory :candidate/id (model/candidate-id candidate)
                  :world/revision (:revision state) :input/digest (model/digest scenario)
                  :evaluated-at now :task task :result (outcome rules)
                  :rules rules :task-results task-results
                  :limitations [:unauthenticated-inputs :test-evidence-is-not-proof
                                :no-execution-authorization]}]
    (assoc decision :decision/id (model/digest decision))))

(def ^:private remediation
  "Human-readable remediation per [rule reason]. Absent means no action needed."
  {[:accepted-spec :spec-not-accepted]
   "Reference an accepted spec revision; acceptance is granted through the project's governance process, not by the candidate."
   [:accepted-spec :missing-approval-reference]
   "Attach the approval reference that accepted this spec revision."
   [:dependency :dependency-violated]
   "Resolve the blocking dependency first; its own decision explains why it is denied."
   [:dependency :dependency-unknown]
   "Supply the missing prerequisites of the dependency; its own decision names them."
   [:evidence :missing-qualified-evidence]
   "Provide test evidence from an approved producer for the exact candidate, obligation, recipe, suite and profile."
   [:evidence :unqualified-latest-attempt]
   "The latest attempt used a different recipe, suite or profile; rerun the approved verification recipe."
   [:evidence :stale-or-future-evidence]
   "Collect fresh evidence; the selected record is outside the policy freshness interval or timestamped in the future."
   [:evidence :conflicting-evidence]
   "Conflicting results exist within the selected attempt; investigate and produce a single unambiguous result."
   [:evidence :test-failed]
   "Fix the failing obligation and rerun the approved verification recipe."
   [:evidence :incomplete-test]
   "The selected evidence has an inconclusive result; rerun to a terminal pass or fail."
   [:scope :outside-allowed-scope]
   "Restrict changed paths to the task's declared scope prefixes, or widen the scope through an accepted spec change."
   [:scope :incomplete-change-observation]
   "Declare change observation completeness; partial observations cannot admit the action."
   [:scope :unsupported-file-mode]
   "Only regular-file add/modify/delete/rename are supported; symlinks, submodules and other modes defer."})

(def ^:private missing
  "Missing inputs per [rule reason]. Absent means nothing is missing."
  {[:accepted-spec :missing-approval-reference] [:approval-reference]
   [:evidence :missing-qualified-evidence] [:evidence-record]
   [:evidence :stale-or-future-evidence] [:fresh-evidence-record]
   [:evidence :conflicting-evidence] [:unambiguous-evidence]
   [:scope :incomplete-change-observation] [:complete-change-observation]})

(defn explain-decision
  "Structured explanation of a decision map. Pure; no I/O. When expected-id is
   supplied and differs from the decision's identity, reports the mismatch
   instead of explaining a stale record."
  ([decision] (explain-decision decision nil))
  ([decision expected-id]
   (let [actual (:decision/id decision)]
     (if (and (some? expected-id) (not= expected-id actual))
       {:explained? false :reason :decision-id-mismatch
        :expected-decision expected-id :actual-decision actual
        :remediation "Re-run evaluate on the current inputs; the recorded decision does not describe them."}
       {:explained? true
        :decision/id actual :mode (:mode decision) :result (:result decision)
        :candidate/id (:candidate/id decision) :task (:task decision)
        :world/revision (:world/revision decision)
        :input/digest (:input/digest decision)
        :evaluated-at (:evaluated-at decision)
        :limitations (:limitations decision)
        :rules (mapv (fn [r] (assoc r
                                    :missing (get missing [(:rule r) (:reason r)] [])
                                    :remediation (get remediation [(:rule r) (:reason r)])))
                     (:rules decision))}))))

(defn ready-tasks
  "Eligibility per contract task in id order. A task is eligible when every
   declared dependency evaluated to :allow. Pure; no I/O."
  [contract task-results]
  (mapv (fn [t]
          (let [deps (sort (:depends-on t))
                unmet (filterv #(not= :allow (:result (get task-results %))) deps)
                own (get task-results (:id t))]
            {:task (:id t)
             :eligible? (empty? unmet)
             :result (:result own)
             :unmet-dependencies unmet
             :missing-prerequisites (mapv :reason (filter #(= :unknown (:status %)) (:rules own)))}))
        (sort-by :id (:tasks contract))))

(def ^:private report-limitations
  [:unauthenticated-inputs :test-evidence-is-not-proof :no-execution-authorization])

(defn status-report
  "Read-only per-task status. Pure; no I/O."
  [scenario]
  (let [{:keys [state task-results]} (evaluate-all scenario)
        {:keys [contract candidate changes now]} scenario
        tasks (mapv (fn [t]
                      (let [rules (conj (:rules (get task-results (:id t))) (scope-rule t changes))
                            blockers (filterv #(not= :satisfied (:status %)) rules)]
                        {:task (:id t)
                         :result (outcome rules)
                         :rules-summary (into (sorted-map) (frequencies (map :status rules)))
                         :blockers (mapv #(select-keys % [:rule :rule/version :subject :status :reason :support])
                                         blockers)}))
                    (sort-by :id (:tasks contract)))]
    {:report :status :schema/version 1 :mode :offline-advisory
     :candidate/id (model/candidate-id candidate)
     :world/revision (:revision state) :evaluated-at now
     :tasks tasks :limitations report-limitations}))

(defn next-report
  "Read-only eligibility report. Pure; no I/O."
  [scenario]
  (let [{:keys [task-results]} (evaluate-all scenario)
        {:keys [contract candidate now]} scenario
        ready (ready-tasks contract task-results)]
    {:report :next :schema/version 1 :mode :offline-advisory
     :candidate/id (model/candidate-id candidate) :evaluated-at now
     :eligible (filterv :eligible? ready)
     :waiting (filterv (complement :eligible?) ready)
     :limitations report-limitations}))
