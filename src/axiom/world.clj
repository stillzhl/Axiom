(ns axiom.world)

(def empty-world {:revision 0 :evidence [] :claims []})

(defn replay
  "Pure reduction of a validated event sequence. Order is ledger order."
  [events]
  (reduce (fn [world {:keys [id type payload]}]
            (-> world
                (update :revision inc)
                (update (case type :evidence :evidence :claim :claims)
                        conj (assoc payload :event/id id))))
          empty-world events))
