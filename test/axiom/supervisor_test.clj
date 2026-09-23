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
            [axiom.model :as model]
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

(deftest run-task-publishes-pr-only-when-authorized
  (testing "a completed task publishes a PR only with :governance/publication-authorized"
    (let [task (assoc (docs-task)
                      :task/publication-authorized
                      {:event/kind :governance/publication-authorized
                       :task/id "synth-task-docs-1"
                       :governance/policy-id "synth-policy-1"})
          {:keys [supervisor/exit supervisor/report]} (supervisor/run-task {:task/definition (pr-str task)
                                           :task/adapter "fake"})]
      (is (= 0 exit))
      (is (= :completed (:task/status report)))
      (is (true? (:pr/published report)))
      (is (string? (:pr/reference report))))))

(deftest run-task-keeps-patch-local-without-publication-authorization
  (testing "without the publication-authorized event the patch stays local"
    (let [task (docs-task) ; no :task/publication-authorized
          {:keys [supervisor/exit supervisor/report]} (supervisor/run-task {:task/definition (pr-str task)
                                           :task/adapter "fake"})]
      (is (= 0 exit))
      (is (= :completed (:task/status report)))
      (is (false? (:pr/published report)))
      (is (nil? (:pr/reference report))))))

(deftest run-task-self-modifying-requires-pinned-evaluator
  (testing "a self-modifying task under the pinned previous evaluator is accepted"
    (let [task (assoc (docs-task)
                      :task/id "synth-task-selfmod-1"
                      :task/class :task-class/self-modifying
                      :task/pinned-evaluator "synth-evaluator-1"
                      :task/evaluator "synth-evaluator-1")
          {:keys [supervisor/exit supervisor/report]} (supervisor/run-task {:task/definition (pr-str task)
                                           :task/adapter "fake"})]
      ;; The task runs; the evaluator check passes (the task may
      ;; complete or be blocked on other grounds, but not on the
      ;; evaluator mismatch).
      (is (= 0 exit))
      (is (not= :invalid (:error report))))))

(deftest run-task-self-modifying-rejects-candidate-evaluator
  (testing "a self-modifying task under the candidate evaluator is refused"
    (let [task (assoc (docs-task)
                      :task/id "synth-task-selfmod-2"
                      :task/class :task-class/self-modifying
                      :task/pinned-evaluator "synth-evaluator-v1"
                      :task/evaluator "synth-evaluator-v2-candidate")
          {:keys [supervisor/exit supervisor/report]} (supervisor/run-task {:task/definition (pr-str task)
                                           :task/adapter "fake"})]
      (is (= 4 exit))
      (is (= :invalid (:error report))))))

;; ----------------------------------------------------------------
;; Amendment A1 S4: supervisor argv plumbing, seed-dir, digest
;; cross-check, budgets, agent timeout.

(defn- write-worker-script!
  "Writes a real (non-synthetic) worker shell script to a temp
   file. The worker writes CONTENT to PATH inside its working
   directory (the supervisor runs it with the worktree as cwd)
   and prints a proposal claiming DIGEST for that file. Returns
   the absolute script path."
  [content path digest]
  (let [script (java.io.File/createTempFile "axiom-worker" ".sh")
        proposal (pr-str {:proposal/actions
                          [{:action/kind :action/write-file
                            :action/path path
                            :action/capability :capability/write-file
                            :action/content content
                            :action/content-digest digest}]})]
    ;; Single-quoted shell strings; the test content and the
    ;; pr-str proposal contain no single quotes.
    (spit script (str "#!/bin/sh\n"
                      "printf '%s' '" content "' > '" path "'\n"
                      "printf '%s' '" proposal "'\n"))
    (.deleteOnExit script)
    (str script)))

(defn- sha256-of [^String s]
  (model/sha256-bytes
   (.getBytes s java.nio.charset.StandardCharsets/UTF_8)))

(defn- proc-task [id argv]
  {:task/id id
   :task/class :task-class/standard
   :task/capabilities {:capability/write-file #{"note.txt"}}
   :task/scope {:scope/path-prefixes #{"note.txt"}}
   :task/pinned-recipe ["./scripts/check"]
   :task/base-files {"note.txt" "base-content"}
   :task/agent-timeout-ms 10000
   :agent/argv argv})

(deftest run-task-process-real-script-happy-path
  (testing "a real process worker writes files itself; the claimed digest cross-checks"
    (let [content "real-process-content"
          digest (sha256-of content)
          script (write-worker-script! content "note.txt" digest)
          argv ["/bin/sh" script]
          {:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task {:task/definition (pr-str (proc-task "synth-task-proc-1" argv))
                                :task/adapter "process"})]
      (is (= 0 exit))
      (is (= :completed (:task/status report)))
      (is (= argv (:agent/argv report)) "the validated argv is in the report")
      (is (re-matches #"sha256:[0-9a-f]{64}" (:patch/digest report)))
      (is (= 1 (count (:proposal/steps report))))
      (is (= :proposal-admitted (:step (first (:proposal/steps report))))))))

(deftest run-task-process-requires-valid-argv
  (testing "a process task without :agent/argv is :invalid (exit 4) and never spawns"
    (let [{:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task {:task/definition (pr-str (proc-task "synth-task-proc-2" nil))
                                :task/adapter "process"})]
      (is (= 4 exit))
      (is (= :invalid (:error report)))))
  (testing "a process task with a blank argv element is :invalid"
    (let [{:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task
           {:task/definition (pr-str (proc-task "synth-task-proc-3" ["/bin/sh" ""]))
            :task/adapter "process"})]
      (is (= 4 exit))
      (is (= :invalid (:error report)))))
  (testing "a process task with a relative executable is :invalid"
    (let [{:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task
           {:task/definition (pr-str (proc-task "synth-task-proc-4" ["bin/worker" "x"]))
            :task/adapter "process"})]
      (is (= 4 exit))
      (is (= :invalid (:error report))))))

(deftest run-task-process-blocks-on-digest-mismatch
  (testing "a worker that claims a wrong content digest blocks with :content-digest-mismatch"
    (let [content "real-process-content"
          wrong-digest (str "sha256:" (apply str (repeat 64 "0")))
          script (write-worker-script! content "note.txt" wrong-digest)
          argv ["/bin/sh" script]
          {:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task {:task/definition (pr-str (proc-task "synth-task-proc-5" argv))
                                :task/adapter "process"})]
      (is (= 0 exit) "the run is valid; the task is blocked")
      (is (= :blocked (:task/status report)))
      (is (= :content-digest-mismatch (:task/blocker report)))
      (is (= :content-digest-mismatch
             (:step (first (:proposal/steps report))))
          "no proposal was admitted; the mismatch step is recorded"))))

(deftest run-task-blocks-on-budget-exhaustion
  (testing "a task with :budget/max-attempts 1 and a two-step script stops after the first step"
    (let [proposal-action (fn [digest]
                            {:action/kind :action/write-file
                             :action/path "docs/guide.md"
                             :action/capability :capability/write-file
                             :action/content-digest digest})
          task (assoc (docs-task)
                      :task/id "synth-task-budget-1"
                      :budget/max-attempts 1
                      :task/fake-script
                      [{:fake/kind :proposal
                        :fake/proposal {:proposal/actions
                                        [(proposal-action
                                          "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]}}
                       {:fake/kind :proposal
                        :fake/proposal {:proposal/actions
                                        [(proposal-action
                                          "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")]}}])
          {:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task {:task/definition (pr-str task)
                                :task/adapter "fake"})]
      (is (= 0 exit) "the run is valid; the task is blocked")
      (is (= :blocked (:task/status report)))
      (is (= :budget-exhausted (:task/blocker report)))
      (is (= 1 (count (filter #(= :proposal-admitted (:step %))
                              (:proposal/steps report))))
          "exactly one proposal was admitted before the budget stopped the loop"))))

(deftest run-task-seed-dir-populates-worktree-base
  (testing ":task/seed-dir content lands in the diff base, so only the worker's changes appear in the patch"
    (let [seed (java.io.File/createTempFile "axiom-seed" "")
          _ (.delete seed)
          _ (.mkdir seed)
          _ (spit (java.io.File. seed "seeded.txt") "seeded-content")
          task {:task/id "synth-task-seed-1"
                :task/class :task-class/standard
                :task/capabilities {:capability/write-file #{"added.txt"}}
                :task/scope {:scope/path-prefixes #{"added.txt"}}
                :task/pinned-recipe ["./scripts/check"]
                :task/seed-dir (str seed)
                :task/fake-script
                [{:fake/kind :proposal
                  :fake/proposal {:proposal/actions
                                  [{:action/kind :action/write-file
                                    :action/path "added.txt"
                                    :action/capability :capability/write-file
                                    :action/content-digest
                                    "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}]}}]}
          {:keys [supervisor/exit supervisor/report]}
          (supervisor/run-task {:task/definition (pr-str task)
                                :task/adapter "fake"})]
      (is (= 0 exit))
      (is (= :completed (:task/status report))
          "the seeded file is in the diff base; the patch covers only the worker's added.txt"))))
