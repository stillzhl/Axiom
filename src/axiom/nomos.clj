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

(defn evaluate
  "Advisory evaluation. Validates input before producing any decision. No I/O."
  [scenario]
  (contract/validate-scenario! scenario)
  (let [{:keys [contract policy candidate events changes task now]} scenario
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
                               (let [rules
                                     (vec (concat
                                           [(spec-rule policy (get specs (:spec t)))]
                                           (for [dep (sort (:depends-on t))]
                                             (let [result (:result (get results dep))]
                                               (rule :dependency dep
                                                     (case result :allow :satisfied :deny :violated :defer :unknown)
                                                     (case result :allow :dependency-satisfied :deny :dependency-violated :defer :dependency-unknown)
                                                     [dep])))
                                           (for [id (sort (:obligations t))]
                                             (prover/evaluate-obligation state policy candidate now (get obligations id))))) ]
                                 (assoc results (:id t) {:result (outcome rules) :rules rules})))
                             done ready)]
                        (recur (remove #(contains? ready-ids (:id %)) remaining) new-done))))
        target (first (filter #(= task (:id %)) (:tasks contract)))
        rules (conj (:rules (get evaluated task)) (scope-rule target changes))
        decision {:schema/version 1 :engine/version model/engine-version
                  :mode :offline-advisory :candidate/id (model/candidate-id candidate)
                  :world/revision (:revision state) :input/digest (model/digest scenario)
                  :evaluated-at now :task task :result (outcome rules)
                  :rules rules :task-results evaluated
                  :limitations [:unauthenticated-inputs :test-evidence-is-not-proof
                                :no-execution-authorization]}]
    (assoc decision :decision/id (model/digest decision))))
