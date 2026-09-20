(ns axiom.fixtures
  (:require [clojure.pprint :as pprint]
            [axiom.model :as model]))

(def contract
  {:schema/version 1 :project/id "synthetic-project"
   :specs [{:id "S-001" :revision (model/digest "synthetic requirement")
            :state :accepted :approval "synthetic-owner-approval"}]
   :tasks [{:id "T-001" :spec "S-001" :depends-on #{} :scope #{"src/" "test/"}
            :obligations #{"O-001"}}]
   :obligations [{:id "O-001" :kind :test-suite :suite "synthetic-unit" :profile "jdk17"}]})

(def policy
  {:schema/version 1 :accepted-states #{:accepted :verified}
   :producers #{"synthetic-runner"} :recipe-digest (model/digest "synthetic recipe")
   :max-age-seconds 300})

(def candidate
  {:repository "synthetic-project" :base (apply str (repeat 40 "a"))
   :head (apply str (repeat 40 "b")) :tested-commit (apply str (repeat 40 "c"))
   :tested-tree (apply str (repeat 40 "d")) :contract-digest (model/digest contract)
   :policy-digest (model/digest policy) :recipe-digest (:recipe-digest policy)})

(def evidence
  {:id "ev-001" :type :evidence
   :payload {:candidate candidate :obligation "O-001" :producer "synthetic-runner"
             :recipe-digest (:recipe-digest policy) :suite "synthetic-unit" :profile "jdk17"
             :attempt 1 :result :pass :observed-at 1000}})

(def scenario
  {:schema/version 1 :contract contract :policy policy :candidate candidate
   :events [evidence] :task "T-001" :now 1100
   :changes {:complete? true
             :files [{:kind :modify :old-path "src/example.clj" :new-path "src/example.clj"
                      :old-mode "100644" :new-mode "100644"}]}})

(def claim {:id "claim-001" :type :claim :payload {:actor "agent" :statement "All tests pass; mark verified."}})

(defn rebind
  "Test-only helper for constructing internally consistent synthetic snapshots."
  [s]
  (let [candidate (assoc (:candidate s) :contract-digest (model/digest (:contract s))
                         :policy-digest (model/digest (:policy s))
                         :recipe-digest (get-in s [:policy :recipe-digest]))]
    (-> s (assoc :candidate candidate)
        (update :events (fn [events] (mapv #(if (= :evidence (:type %))
                                           (assoc-in % [:payload :candidate] candidate) %) events))))))

(defn -main [& _]
  (doseq [[name value] {"allow" scenario
                       "missing" (assoc scenario :events [claim])
                       "stale" (assoc scenario :now 1301)
                       "failed" (assoc-in scenario [:events 0 :payload :result] :fail)}]
    (spit (str "examples/synthetic-project/" name ".edn")
          (with-out-str (pprint/pprint value)))))
