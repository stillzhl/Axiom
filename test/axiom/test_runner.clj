(ns axiom.test-runner
  (:require [clojure.test :as test]
            [axiom.kernel-test]
            [axiom.git-test]
            [axiom.ledger-test]
            [axiom.store-test]
            [axiom.artifacts-test]
            [axiom.runner-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'axiom.kernel-test
                                             'axiom.git-test
                                             'axiom.ledger-test
                                             'axiom.store-test
                                             'axiom.artifacts-test
                                             'axiom.runner-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
