(ns axiom.worktree-test
  "Tests for spec 0006 T5 (`axiom.adapters.worktree`): the isolated
   worktree lifecycle — deterministic per-task identity pinned to
   the base identity, structural path confinement, and guarded
   destruction.

   Every identity is invented (`synth-*`). Worktrees are created
   under a temporary directory and destroyed afterwards; no live
   credentials, no network."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.adapters.worktree :as worktree])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- temp-root []
  (let [dir (Files/createTempDirectory
             "axiom-worktree-test"
             (into-array FileAttribute []))]
    (.toFile dir)))

(defn- delete-root! [^File root]
  (worktree/destroy-worktree {:worktree/id (.getName root)
                              :worktree/path (str root)
                              :worktree/root (str (.getParentFile root))}))

(defn- inputs [task-id]
  {:worktree/task-id task-id
   :worktree/base-identity "synth-base-9f3c1a"
   :worktree/root (str (temp-root))})

(deftest worktree-identity-is-deterministic-and-per-task
  (testing "the id derives from (task-id, base-identity)"
    (let [id1 (worktree/worktree-id "synth-task-1" "synth-base-9f3c1a")
          id2 (worktree/worktree-id "synth-task-1" "synth-base-9f3c1a")]
      (is (= id1 id2))
      (is (re-matches #"wt-[0-9a-f]{16}" id1))))
  (testing "two tasks never share a worktree identity"
    (is (not= (worktree/worktree-id "synth-task-1" "synth-base-9f3c1a")
              (worktree/worktree-id "synth-task-2" "synth-base-9f3c1a"))))
  (testing "the base identity participates in the identity"
    (is (not= (worktree/worktree-id "synth-task-1" "synth-base-9f3c1a")
              (worktree/worktree-id "synth-task-1" "synth-base-000000")))))

(deftest create-worktree-lifecycle
  (let [{:worktree/keys [root] :as in} (inputs "synth-task-1")]
    (try
      (testing "creation records the pinned base and the deterministic id"
        (let [res (worktree/create-worktree in)]
          (is (true? (:worktree/ok res)))
          (is (= (worktree/worktree-id "synth-task-1" "synth-base-9f3c1a")
                 (:worktree/id res)))
          (is (= "synth-base-9f3c1a" (:worktree/base-identity res)))
          (is (.isDirectory (File. ^String (:worktree/path res))))))
      (testing "re-creation is idempotent"
        (let [a (worktree/create-worktree in)
              b (worktree/create-worktree in)]
          (is (= (:worktree/id a) (:worktree/id b)))
          (is (= (:worktree/path a) (:worktree/path b)))))
      (testing "malformed input never creates anything"
        (doseq [bad [(dissoc in :worktree/task-id)
                     (assoc in :worktree/base-identity "")
                     (dissoc in :worktree/root)
                     nil]]
          (let [res (worktree/create-worktree bad)]
            (is (false? (:worktree/ok res)))
            (is (= :malformed (:worktree/reason res))))))
      (testing "a seed directory is copied in"
        (let [seed (doto (File. ^String root "seed-src") (.mkdirs))
              _ (spit (File. seed "synth-seed.txt") "seeded")
              res (worktree/create-worktree
                   (assoc in :worktree/task-id "synth-task-seeded"
                          :worktree/seed (str seed)))]
          (is (true? (:worktree/ok res)))
          (is (= "seeded" (slurp (File. ^String (:worktree/path res)
                                        "synth-seed.txt"))))))
      (finally
        (delete-root! (File. ^String root))))))

(deftest resolve-path-confines-structurally
  (let [{:worktree/keys [root] :as in} (inputs "synth-task-1")
        wt (:worktree/path (worktree/create-worktree in))]
    (try
      (let [wt {:worktree/id "wt-x" :worktree/path wt :worktree/root root}]
        (testing "ordinary relative paths resolve inside the root"
          (let [res (worktree/resolve-path wt "docs/synth.md")]
            (is (true? (:worktree/ok res)))
            (is (= (str (:worktree/path wt) "/docs/synth.md")
                   (:worktree/path res)))))
        (testing "dot segments normalize but stay inside"
          (let [res (worktree/resolve-path wt "docs/./synth.md")]
            (is (true? (:worktree/ok res)))
            (is (= (str (:worktree/path wt) "/docs/synth.md")
                   (:worktree/path res)))))
        (testing "escapes are refused, never resolved"
          (doseq [evil ["../escape.md"
                        "docs/../../escape.md"
                        ".."
                        "/etc/passwd"
                        ""
                        "docs//../../../x"]]
            (let [res (worktree/resolve-path wt evil)]
              (is (false? (:worktree/ok res)) (str evil))
              (is (= :path-escape (:worktree/reason res)) (str evil))))))
      (finally
        (delete-root! (File. ^String root))))))

(deftest destroy-worktree-is-guarded-and-idempotent
  (let [{:worktree/keys [root] :as in} (inputs "synth-task-1")
        created (worktree/create-worktree in)
        path (:worktree/path created)]
    (try
      (testing "destruction removes the worktree"
        (let [res (worktree/destroy-worktree created)]
          (is (true? (:worktree/ok res)))
          (is (not (.exists (File. ^String path))))))
      (testing "destruction is idempotent"
        (is (true? (:worktree/ok (worktree/destroy-worktree created)))))
      (testing "a forged worktree map deletes nothing"
        (let [evil (assoc created :worktree/path root)
              before (.exists (File. ^String root))
              res (worktree/destroy-worktree evil)]
          (is (false? (:worktree/ok res)))
          (is (= :malformed (:worktree/reason res)))
          (is (= before (.exists (File. ^String root))))))
      (finally
        (delete-root! (File. ^String root))))))
