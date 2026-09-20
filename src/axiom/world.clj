(ns axiom.world)

(def empty-world {:revision 0 :evidence [] :claims []})

(defn replay
  "Pure reduction of a validated event sequence. Order is ledger order.
   The two-arity form continues folding from a snapshot world; the
   single-arity form is the 0001 reference reduction from the empty world."
  ([events] (replay empty-world events))
  ([base events]
   (reduce (fn [world {:keys [id type payload]}]
             (-> world
                 (update :revision inc)
                 (update (case type :evidence :evidence :claim :claims)
                         conj (assoc payload :event/id id))))
           base events)))
