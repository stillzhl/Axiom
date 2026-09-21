(ns axiom.supervisor-test
  "Tests for spec 0006 T8 (`axiom.supervisor/run-task`): the full
   synthetic guarded loop through the fake adapter — admission,
   lease/fencing, proposal loop, patch admission, verification,
   ledger evidence, task lifecycle — plus the adversarial
   `:prompt-scope-escape` and the exit contract.

   Every identity is invented (`synth-*`). No network, no live
   credentials."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.supervisor :as supervisor]
            [clojure.edn :as edn]))

(defn- docs-task []
  {:task/id "synth-task-docs-1"
   :task/class :task-class/standard
   :task/capabilities {:capability/write-file #{"docs/"}}
   :task/pinned-recipe ["./scripts/check"]
   :task/scope {:scope/path-prefixes #{"docs/"}}
   :task/base-files {"docs/guide.md" "guide"}
   :task/fake-script
   [{:fake/kind :proposal
     :fake/proposal {:proposal/actions
                     [{:action/kind :action/write-file
                       :action/path "docs/guide.md"
                       :action/capability :capability/write-file
                       :action/content-digest
                       "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}}]})

(deftest run-task-completes-synthetic-docs-task
  (testing "the fake adapter drives a docs task through the guarded loop"
    (let [{:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task {:task/definition (pr-str (docs-task))
                                :task/adapter "fake"})]
      (is (= 0 exit))
      (is (= "synth-task-docs-1" (:task/id report)))
      (is (= :completed (:task/status report)))
      (is (re-matches #"sha256:[0-9a-f]{64}" (:patch/digest report)))
      (is (re-matches #"sha256:[0-9a-f]{64}" (:patch/evidence-digest report)))
      (is (= 1 (count (:proposal/steps report))))
      (is (= :proposal-admitted (:step (first (:proposal/steps report)))))
      (is (re-matches #"sha256:[0-9a-f]{64}"
                      (:proposal/digest (first (:proposal/steps report))))))))

(deftest run-task-blocks-on-prompt-scope-escape
  (testing "an adversarial scope-widening proposal is denied and blocks the task"
    (let [task (assoc (docs-task) :task/fake-script
                      [{:fake/kind :adversarial
                        :fake/move :scope-widen
                        :fake/proposal
                        {:proposal/actions
                         [{:action/kind :action/write-file
                           :action/path "src/axiom/ledger.clj"
                           :action/capability :capability/write-file
                           :action/content-digest
                           "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}]}}])
          {:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task {:task/definition (pr-str task)
                                :task/adapter "fake"})]
      (is (= 0 exit) "the run is valid; the task is blocked")
      (is (= :blocked (:task/status report)))
      (is (= :out-of-scope (:task/blocker report))
          "the scope-widening proposal is denied before any action executes"))))

(deftest run-task-exit-contract
  (testing "malformed task EDN is exit 4"
    (let [{:keys [supervisor/exit]} (supervisor/run-task
                                    {:task/definition "{unclosed"
                                     :task/adapter "fake"})]
      (is (= 4 exit))))
  (testing "a task without an id is exit 4"
    (let [{:keys [supervisor/exit]} (supervisor/run-task
                                    {:task/definition (pr-str {:foo 1})
                                     :task/adapter "fake"})]
      (is (= 4 exit))))
  (testing "an unknown adapter is exit 4"
    (let [{:keys [supervisor/exit]} (supervisor/run-task
                                    {:task/definition (pr-str (docs-task))
                                     :task/adapter "quantum"})]
      (is (= 4 exit)))))
