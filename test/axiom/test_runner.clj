(ns axiom.test-runner
  (:require [clojure.test :as test]
            [axiom.kernel-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'axiom.kernel-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
