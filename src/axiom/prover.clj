(ns axiom.prover)

(defn evaluate-obligation [world policy candidate now obligation]
  (let [matching (filter #(and (= candidate (:candidate %))
                               (= (:id obligation) (:obligation %))
                               (contains? (:producers policy) (:producer %)))
                         (:evidence world))
        selected (->> matching
                      (group-by :producer)
                      (sort-by key)
                      (mapcat (fn [[_ records]]
                                (let [attempt (apply max (map :attempt records))]
                                  (filter #(= attempt (:attempt %)) records))))
                      (sort-by :event/id)
                      vec)
        results (set (map :result selected))
        unqualified? (some #(or (not= (:recipe-digest policy) (:recipe-digest %))
                                (not= (:suite obligation) (:suite %))
                                (not= (:profile obligation) (:profile %))) selected)
        stale? (some #(or (> (:observed-at %) now)
                          (> (- now (:observed-at %)) (:max-age-seconds policy))) selected)
        [status reason] (cond
                          (empty? selected) [:unknown :missing-qualified-evidence]
                          unqualified? [:unknown :unqualified-latest-attempt]
                          stale? [:unknown :stale-or-future-evidence]
                          (> (count results) 1) [:unknown :conflicting-evidence]
                          (= results #{:fail}) [:violated :test-failed]
                          (= results #{:pass}) [:satisfied :test-passed]
                          :else [:unknown :incomplete-test])]
    {:rule :evidence :rule/version 1 :subject (:id obligation)
     :status status :reason reason :support (mapv :event/id selected)}))
