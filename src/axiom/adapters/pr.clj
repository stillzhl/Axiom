(ns axiom.adapters.pr
  "PR publication adapter. Creates pull requests through a configured
   provider; the fake adapter records the publication intent for
   synthetic tests. There is intentionally no merge code path —
   an allow decision is not a merge instruction (0006 scope)."
  (:require [axiom.model :as model]))

(defmulti create-pr
  "Creates a pull request for the given patch. Dispatches on
   `:pr/kind` (`:fake` for synthetic tests). Returns a map with
   `:pr/ok`, `:pr/reference` (provider reference), and
   `:pr/published-at` on success, or `:pr/ok false` with
   `:pr/reason` on failure."
  :pr/kind)

(defmethod create-pr :fake
  [{:pr/keys [task-id patch-digest base-head] :as pr}]
  (if (and (string? task-id) (seq task-id)
           (string? patch-digest) (seq patch-digest))
    {:pr/ok true
     :pr/reference (str "synth-pr-" (subs patch-digest 7 15))
     :pr/published-at (System/currentTimeMillis)
     :pr/base-head (or base-head "synth-base")}
    {:pr/ok false
     :pr/reason :invalid}))

(defmethod create-pr :default
  [pr]
  {:pr/ok false
   :pr/reason :unsupported
   :pr/kind (:pr/kind pr)})

(defn publication-authorized?
  "Pure predicate: true when the governance event authorizes
   publication of the given task's patch."
  [governance-event task-id]
  (and (= :governance/publication-authorized (:event/kind governance-event))
       (= task-id (:task/id governance-event))
       (string? (:governance/policy-id governance-event))))
