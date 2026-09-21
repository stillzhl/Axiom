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
            [axiom.gate-ledger-test]
            [axiom.checks-test]
            [axiom.capability-test]
            [axiom.gate-cli-test]
            [axiom.gate-adversarial-test]
            [axiom.execute-test]
            [axiom.ledger-0006-test]
            [axiom.execute-lease-test]
            [axiom.store-lease-test]
            [axiom.execute-outbox-test]
            [axiom.store-outbox-test]
            [axiom.worktree-test]
            [axiom.patch-test]
            [axiom.agent-test]
            [axiom.supervisor-test]))

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
                                             'axiom.gate-ledger-test
                                             'axiom.checks-test
                                             'axiom.capability-test
                                             'axiom.gate-cli-test
                                             'axiom.gate-adversarial-test
                                             'axiom.execute-test
                                             'axiom.ledger-0006-test
                                             'axiom.execute-lease-test
                                             'axiom.store-lease-test
                                             'axiom.execute-outbox-test
                                             'axiom.store-outbox-test
                                             'axiom.worktree-test
                                             'axiom.patch-test
                                             'axiom.agent-test
                                             'axiom.supervisor-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
