(ns axiom.test-runner
  (:require [clojure.test :as test]
            [axiom.kernel-test]
            [axiom.git-test]
            [axiom.ledger-test]
            [axiom.store-test]
            [axiom.artifacts-test]
            [axiom.observations-test]
            [axiom.runner-test]
            [axiom.github-test]
            [axiom.adapters-github-test]
            [axiom.github-cli-ledger-test]
            [axiom.github-adversarial-test]
            [axiom.gate-test]
            [axiom.policy-test]
            [axiom.gate-ledger-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'axiom.kernel-test
                                             'axiom.git-test
                                             'axiom.ledger-test
                                             'axiom.store-test
                                             'axiom.artifacts-test
                                             'axiom.observations-test
                                             'axiom.runner-test
                                             'axiom.github-test
                                             'axiom.adapters-github-test
                                             'axiom.github-cli-ledger-test
                                             'axiom.github-adversarial-test
                                             'axiom.gate-test
                                             'axiom.policy-test
                                             'axiom.gate-ledger-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
