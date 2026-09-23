(ns axiom.adapters.pr-test
  "Tests for the PR publication adapter."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.adapters.pr :as pr]))

(deftest create-pr-fake-publishes
  (testing "the fake PR adapter records a publication reference"
    (let [res (pr/create-pr {:pr/kind :fake
                             :pr/task-id "synth-task-1"
                             :pr/patch-digest "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})]
      (is (true? (:pr/ok res)))
      (is (string? (:pr/reference res)))
      (is (string? (:pr/base-head res))))))

(deftest create-pr-fake-rejects-invalid
  (testing "the fake PR adapter rejects invalid input"
    (let [res (pr/create-pr {:pr/kind :fake
                             :pr/task-id ""
                             :pr/patch-digest "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})]
      (is (false? (:pr/ok res)))
      (is (= :invalid (:pr/reason res))))))

(deftest publication-authorized-predicate
  (testing "publication-authorized? is true only for the matching task"
    (is (true? (pr/publication-authorized?
                {:event/kind :governance/publication-authorized
                 :task/id "synth-task-1"
                 :governance/policy-id "synth-policy-1"}
                "synth-task-1")))
    (is (false? (pr/publication-authorized?
                 {:event/kind :governance/publication-authorized
                  :task/id "synth-task-2"
                  :governance/policy-id "synth-policy-1"}
                 "synth-task-1")))
    (is (false? (pr/publication-authorized?
                 {:event/kind :governance/other
                  :task/id "synth-task-1"
                  :governance/policy-id "synth-policy-1"}
                 "synth-task-1")))))

(deftest publication-authorized-3-arity-binds-patch-digest
  (testing "the 3-arity binds a digest-carrying authorization to the admitted patch"
    (let [digest "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
          other "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
          bound {:event/kind :governance/publication-authorized
                 :task/id "synth-task-1"
                 :governance/policy-id "synth-policy-1"
                 :patch/digest digest}
          unbound {:event/kind :governance/publication-authorized
                   :task/id "synth-task-1"
                   :governance/policy-id "synth-policy-1"}]
      ;; Bound event: only the matching patch digest authorizes.
      (is (true? (pr/publication-authorized? bound "synth-task-1" digest)))
      (is (false? (pr/publication-authorized? bound "synth-task-1" other)))
      ;; Unbound event: any patch from the task authorizes.
      (is (true? (pr/publication-authorized? unbound "synth-task-1" digest)))
      (is (true? (pr/publication-authorized? unbound "synth-task-1" other)))
      ;; The 2-arity never authorizes a digest-bound event (no bypass).
      (is (false? (pr/publication-authorized? bound "synth-task-1")))
      (is (true? (pr/publication-authorized? unbound "synth-task-1"))))))
