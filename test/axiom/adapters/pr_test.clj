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
