(ns axiom.git-test
  "Tests for the pure `axiom.git` port and the `axiom.adapters.git`
   adapter (spec 0003 T1/T2). All git repositories are synthetic,
   created in temp dirs for the test run only."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [axiom.adapters.git :as git-adapter]
            [axiom.git :as git]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str])
  (:import (java.nio.file Files LinkOption)
           (java.nio.file.attribute FileAttribute)))

;; ------------------------------------------------------------------
;; Fixture helpers (synthetic repos only)

(def ^:dynamic *tmp-dirs* nil)

(defn- tmp-dir [prefix]
  (let [dir (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0)))]
    (when *tmp-dirs* (swap! *tmp-dirs* conj dir))
    dir))

(defn- delete-recursively [dir]
  (let [root (.toPath (io/file dir))]
    (when (Files/exists root (into-array LinkOption []))
      (doseq [p (->> (.iterator (Files/walk root (make-array java.nio.file.FileVisitOption 0)))
                     iterator-seq
                     (sort-by #(.getNameCount ^java.nio.file.Path %) >))]
        (try (Files/deleteIfExists p) (catch Exception _ nil))))))

(use-fixtures :each
  (fn [t]
    (binding [*tmp-dirs* (atom [])]
      (try (t)
           (finally (doseq [d @*tmp-dirs*] (delete-recursively d)))))))

(defn- git!
  "Runs git in dir; throws when the fixture command itself fails."
  [dir & args]
  (let [{:keys [exit err]} (apply sh/sh "git" (concat args [:dir (.getAbsolutePath ^java.io.File dir)]))]
    (when-not (zero? exit)
      (throw (ex-info (str "fixture git failed: " (str/join " " args)) {:err err})))))

(defn- init-repo! []
  (let [dir (tmp-dir "axiom-git-")]
    (git! dir "init" "-q" "-b" "main")
    (git! dir "config" "user.email" "axiom-test@example.com")
    (git! dir "config" "user.name" "axiom-test")
    (git! dir "config" "commit.gpgsign" "false")
    dir))

(defn- write! [dir path content]
  (let [f (io/file dir ^String path)]
    (io/make-parents f)
    (spit f content)))

(defn- commit! [dir msg]
  (git! dir "add" "-A")
  (git! dir "commit" "-qm" msg))

(defn- rev! [dir rev]
  (str/trim (:out (sh/sh "git" "rev-parse" rev :dir (.getAbsolutePath ^java.io.File dir)))))

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(defn- observation [dir & {:as opts}]
  (git-adapter/observe! (merge {:repo (.getAbsolutePath ^java.io.File dir)} opts)))

;; ------------------------------------------------------------------
;; Pure port: path safety as data

(deftest path-safety-pure
  (testing "lexical escape detection"
    (is (false? (git/path-escapes-root? "a/b/c.txt")))
    (is (false? (git/path-escapes-root? "a/../b.txt")))
    (is (true? (git/path-escapes-root? "../escape.txt")))
    (is (true? (git/path-escapes-root? "a/../../escape.txt"))))
  (testing "symlink target classification never touches the filesystem"
    (is (= {:path/kind :symlink :path/target "a.txt"}
           (git/classify-symlink-target "" "a.txt")))
    (is (= {:path/kind :symlink :path/target "sub/../a.txt"}
           (git/classify-symlink-target "dir" "sub/../a.txt")))
    (is (= :escapes-worktree
           (:path/unsafe-reason (git/classify-symlink-target "dir" "../../escape"))))
    (is (= :escapes-worktree
           (:path/unsafe-reason (git/classify-symlink-target "" "../escape"))))
    (is (= :absolute-target
           (:path/unsafe-reason (git/classify-symlink-target "" "/etc/hostname"))))
    (is (= :unresolvable-target
           (:path/unsafe-reason (git/classify-symlink-target "" "")))))
  (testing "mode and status mappings"
    (is (= :symlink (git/path-kind-from-mode "120000")))
    (is (= :submodule (git/path-kind-from-mode "160000")))
    (is (= :file (git/path-kind-from-mode "100644")))
    (is (= :added (git/change-kind-from-status \A)))
    (is (= :modified (git/change-kind-from-status \M)))
    (is (= :deleted (git/change-kind-from-status \D)))
    (is (= :renamed (git/change-kind-from-status \R)))
    (is (= :type-changed (git/change-kind-from-status \T)))
    (is (nil? (git/change-kind-from-status \U)))))

;; ------------------------------------------------------------------
;; Pure port: observation validation

(def ^:private sha-a (apply str (repeat 40 "a")))
(def ^:private sha-b (apply str (repeat 40 "b")))
(def ^:private sha-c (apply str (repeat 40 "c")))

(defn- file-entry
  [kind old-path new-path]
  {:change/kind kind :path/kind :file
   :change/old-path old-path :change/new-path new-path
   :change/old-mode "100644" :change/new-mode "100644"
   :path/target nil :path/commit nil :path/unsafe-reason nil})

(defn- valid-data []
  {:observation/status :complete
   :repo/path "/tmp/synthetic-repo"
   :git/base sha-a :git/head sha-b :git/tree sha-c
   :git/branch "main" :git/upstream nil
   :git/clean? true :git/dirty-files []
   :changes [(file-entry :added nil "new.txt")]
   :git/version "git version 9.9.9"
   :commands ["git -C /tmp/synthetic-repo --version"]})

(deftest observation-validation
  (testing "a well-formed observation validates"
    (is (= :complete (:observation/status (git/validate-observation! (git/build-observation (valid-data)))))))
  (testing "unknown fields are rejected"
    (is (= :invalid (error-kind #(git/validate-observation!
                                   (assoc (git/build-observation (valid-data)) :bogus 1))))))
  (testing "incomplete observations carry the failing step and no partial changes"
    (let [obs (git/build-observation
               (assoc (valid-data)
                      :observation/status :incomplete
                      :git/head nil :git/tree nil
                      :changes []
                      :observation/failing-step {:command "git -C /tmp/x diff"
                                                 :exit 128
                                                 :reason "diff-step"}))]
      (is (= :incomplete (:observation/status obs)))
      (is (= "diff-step" (get-in obs [:observation/failing-step :reason])))
      (is (false? (get-in obs [:value :changes :complete?])))))
  (testing "a reported path escaping the worktree root is an operational failure"
    (is (= :operational
           (error-kind #(git/build-observation
                          (assoc (valid-data)
                                 :changes [(file-entry :added nil "../escape.txt")]))))))
  (testing "inconsistent change paths are rejected"
    (is (= :invalid
           (error-kind #(git/build-observation
                          (assoc (valid-data)
                                 :changes [(file-entry :added "old.txt" "new.txt")])))))
    (is (= :invalid
           (error-kind #(git/build-observation
                          (assoc (valid-data)
                                 :changes [(file-entry :renamed "a.txt" "a.txt")])))))))

;; ------------------------------------------------------------------
;; Adapter: clean repo, identities, default base

(deftest clean-repo-observation
  (let [dir (init-repo!)]
    (write! dir "a.txt" "hello\n")
    (commit! dir "first")
    (let [obs (observation dir :base "HEAD")
          head (rev! dir "HEAD")
          tree (rev! dir "HEAD^{tree}")]
      (is (= :complete (:observation/status obs)))
      (is (= head (get-in obs [:subject :git/head])))
      (is (= head (get-in obs [:subject :git/base])))
      (is (= tree (get-in obs [:subject :git/tree])))
      (is (= "main" (get-in obs [:value :git/branch])))
      (is (nil? (get-in obs [:value :git/upstream])))
      (is (true? (get-in obs [:value :git/clean?])))
      (is (= [] (get-in obs [:value :git/dirty-files])))
      (is (true? (get-in obs [:value :changes :complete?])))
      (is (= [] (get-in obs [:value :changes :changes])))
      (is (= "axiom-local-git" (get-in obs [:producer :producer/id])))
      (is (false? (get-in obs [:producer :producer/authenticated?])))
      (is (= :trust/local-diagnostic (:trust obs)))
      (is (re-matches #"git version \S+" (get-in obs [:provenance :git/version])))
      (is (seq (get-in obs [:provenance :commands])))
      (is (every? #(str/starts-with? % "git -C ") (get-in obs [:provenance :commands])))
      (is (= (.getAbsolutePath dir) (get-in obs [:scope :worktree/root])))
      (is (= {:base head :head head} (get-in obs [:scope :revisions]))))))

(deftest default-base-from-upstream
  (let [bare (tmp-dir "axiom-git-bare-")
        dir (init-repo!)]
    (git! bare "init" "-q" "--bare")
    (write! dir "a.txt" "v1\n")
    (commit! dir "first")
    (git! dir "remote" "add" "origin" (.getAbsolutePath bare))
    (git! dir "push" "-q" "-u" "origin" "main")
    (write! dir "b.txt" "v2\n")
    (commit! dir "second")
    (let [obs (observation dir) ; no :base -> merge-base of HEAD and origin/main
          origin-head (rev! dir "origin/main")]
      (is (= :complete (:observation/status obs)))
      (is (= "origin/main" (get-in obs [:value :git/upstream])))
      (is (= origin-head (get-in obs [:subject :git/base])))
      (is (= 1 (count (get-in obs [:value :changes :changes]))))
      (is (= :added (:change/kind (first (get-in obs [:value :changes :changes])))))
      (is (= "b.txt" (:change/new-path (first (get-in obs [:value :changes :changes]))))))))

(deftest no-base-no-upstream-is-invalid
  (let [dir (init-repo!)]
    (write! dir "a.txt" "x\n")
    (commit! dir "first")
    (is (= :invalid (error-kind #(observation dir))))))

(deftest missing-repo-is-invalid
  (is (= :invalid (error-kind #(git-adapter/observe! {:repo "/tmp/axiom-no-such-repo-xyz" :base "HEAD"})))))

;; ------------------------------------------------------------------
;; Adapter: dirty worktree (R9 — no synthesized dirty-tree SHA)

(deftest dirty-worktree
  (let [dir (init-repo!)]
    (write! dir "a.txt" "v1\n")
    (commit! dir "first")
    (write! dir "a.txt" "v2-dirty\n")
    (write! dir "new.txt" "untracked\n")
    (let [obs (observation dir :base "HEAD")
          head (rev! dir "HEAD")
          tree (rev! dir "HEAD^{tree}")
          dirty-paths (set (map :path (get-in obs [:value :git/dirty-files])))]
      (is (= :complete (:observation/status obs)))
      (is (false? (get-in obs [:value :git/clean?])))
      (is (= #{"a.txt" "new.txt"} dirty-paths))
      (is (= head (get-in obs [:subject :git/head])))
      ;; :git/tree is the HEAD *commit's* tree (stable); there is no
      ;; tree SHA for the dirty worktree state itself.
      (is (= tree (get-in obs [:subject :git/tree])))
      (is (not (contains? (:subject obs) :git/dirty-tree)))
      (is (not (contains? (:value obs) :git/dirty-tree))))))

;; ------------------------------------------------------------------
;; Adapter: change enumeration (added/modified/deleted/renamed/type-change)

(deftest change-enumeration
  (let [dir (init-repo!)]
    (doseq [f ["a.txt" "b.txt" "c.txt" "d.txt"]]
      (write! dir f (str "content of " f "\n")))
    (commit! dir "base")
    (write! dir "a.txt" "modified\n")
    (io/delete-file (io/file dir "b.txt"))
    (git! dir "mv" "c.txt" "c2.txt")
    (write! dir "f.txt" "added\n")
    (io/delete-file (io/file dir "d.txt"))
    (Files/createSymbolicLink (.toPath (io/file dir "d.txt")) (.toPath (io/file "target")) (make-array FileAttribute 0))
    (commit! dir "changes")
    (let [obs (observation dir :base "HEAD~1")
          by-new (into {} (map (juxt :change/new-path identity)
                               (get-in obs [:value :changes :changes])))]
      (is (= :complete (:observation/status obs)))
      (is (= :modified (:change/kind (by-new "a.txt"))))
      (is (= :deleted (:change/kind (first (filter #(= "b.txt" (:change/old-path %))
                                                          (get-in obs [:value :changes :changes]))))))
      (let [renamed (by-new "c2.txt")]
        (is (= :renamed (:change/kind renamed)))
        (is (= "c.txt" (:change/old-path renamed))))
      (is (= :added (:change/kind (by-new "f.txt"))))
      (let [tc (by-new "d.txt")]
        (is (= :type-changed (:change/kind tc)))
        (is (= :symlink (:path/kind tc)))
        (is (= "target" (:path/target tc)))))))

;; ------------------------------------------------------------------
;; Adapter: symlinks and submodules

(deftest symlink-escape-is-unsafe
  (let [dir (init-repo!)]
    (write! dir "a.txt" "x\n")
    (commit! dir "base")
    (Files/createSymbolicLink (.toPath (io/file dir "evil")) (.toPath (io/file "../../escape")) (make-array FileAttribute 0))
    (Files/createSymbolicLink (.toPath (io/file dir "abs")) (.toPath (io/file "/etc/hostname")) (make-array FileAttribute 0))
    (Files/createSymbolicLink (.toPath (io/file dir "good")) (.toPath (io/file "a.txt")) (make-array FileAttribute 0))
    (commit! dir "links")
    (let [obs (observation dir :base "HEAD~1")
          by-new (into {} (map (juxt :change/new-path identity)
                               (get-in obs [:value :changes :changes])))]
      (is (= :complete (:observation/status obs)))
      (let [evil (by-new "evil")]
        (is (= :unsafe (:path/kind evil)))
        (is (= :escapes-worktree (:path/unsafe-reason evil))))
      (let [abs (by-new "abs")]
        (is (= :unsafe (:path/kind abs)))
        (is (= :absolute-target (:path/unsafe-reason abs))))
      (let [good (by-new "good")]
        (is (= :symlink (:path/kind good)))
        (is (= "a.txt" (:path/target good)))))))

(deftest dirty-symlink-escape-is-unsafe
  (let [dir (init-repo!)]
    (write! dir "a.txt" "x\n")
    (commit! dir "base")
    (Files/createSymbolicLink (.toPath (io/file dir "evil")) (.toPath (io/file "../escape")) (make-array FileAttribute 0))
    (let [obs (observation dir :base "HEAD")
          evil (first (filter #(= "evil" (:path %)) (get-in obs [:value :git/dirty-files])))]
      (is (= :complete (:observation/status obs)))
      (is (false? (get-in obs [:value :git/clean?])))
      (is (= :unsafe (:path/kind evil)))
      (is (= :escapes-worktree (:path/unsafe-reason evil))))))

(deftest submodule-is-typed-not-recursed
  (let [sub (init-repo!)]
    (write! sub "inner.txt" "inner\n")
    (commit! sub "sub-base")
    (let [dir (init-repo!)]
      (write! dir "a.txt" "x\n")
      (commit! dir "base")
      (git! dir "-c" "protocol.file.allow=always" "submodule" "add" "-q"
            (.getAbsolutePath sub) "sub")
      (commit! dir "add submodule")
      (let [obs (observation dir :base "HEAD~1")
            by-new (into {} (map (juxt :change/new-path identity)
                                 (get-in obs [:value :changes :changes])))
            entry (by-new "sub")]
        (is (= :complete (:observation/status obs)))
        (is (= :added (:change/kind entry)))
        (is (= :submodule (:path/kind entry)))
        (is (= "sub" (:change/new-path entry)))
        (is (= (rev! sub "HEAD") (:path/commit entry)))
        ;; Not recursed: the submodule's inner file never appears.
        (is (not-any? #(= "sub/inner.txt" (:change/new-path %))
                      (get-in obs [:value :changes :changes])))))))

;; ------------------------------------------------------------------
;; Adapter: incomplete enumeration and determinism

(deftest failing-git-step-is-incomplete
  (testing "unresolvable base revision"
    (let [dir (init-repo!)]
      (write! dir "a.txt" "x\n")
      (commit! dir "first")
      (let [obs (observation dir :base "bogus-rev-xyz")]
        (is (= :incomplete (:observation/status obs)))
        (is (str/includes? (get-in obs [:observation/failing-step :command]) "rev-parse"))
        (is (false? (get-in obs [:value :changes :complete?])))
        (is (= [] (get-in obs [:value :changes :changes]))))))
  (testing "not a git repository"
    (let [dir (tmp-dir "axiom-git-plain-")
          obs (observation dir :base "HEAD")]
      (is (= :incomplete (:observation/status obs)))
      (is (= "toplevel-step" (get-in obs [:observation/failing-step :reason]))))))

(deftest repeat-runs-are-deterministic
  (let [dir (init-repo!)]
    (write! dir "a.txt" "x\n")
    (commit! dir "base")
    (write! dir "b.txt" "y\n")
    (commit! dir "second")
    (let [first-obs (observation dir :base "HEAD~1")
          second-obs (observation dir :base "HEAD~1")]
      (is (= first-obs second-obs)))))

;; ------------------------------------------------------------------
;; Adapter: unsafe symlinks are typed and never followed for content
;; (R2 — excluded from digestion)

(deftest unsafe-symlink-excluded-from-digestion
  (testing "an escaping symlink's target content never enters the observation"
    (let [dir (init-repo!)
          secret "TOP-SECRET-OUTSIDE-WORKTREE-0003"
          ;; The secret lives in the repo's parent directory: the link
          ;; text "../<name>" escapes the worktree root lexically and
          ;; names a real file with distinctive bytes, so a follower
          ;; would leak them into the observation.
          secret-name (str "axiom-git-secret-" (System/nanoTime) ".txt")
          secret-file (io/file (.getParent dir) secret-name)]
      (spit secret-file secret)
      (swap! *tmp-dirs* conj secret-file)
      (write! dir "a.txt" "x\n")
      (commit! dir "base")
      (Files/createSymbolicLink (.toPath (io/file dir "evil"))
                                (.toPath (io/file (str "../" secret-name)))
                                (make-array FileAttribute 0))
      (commit! dir "escaping link")
      (let [obs (observation dir :base "HEAD~1")
            entry (first (filter #(= "evil" (:change/new-path %))
                                 (get-in obs [:value :changes :changes])))]
        (is (= :complete (:observation/status obs)))
        ;; Typed, never followed: the entry carries the raw link text
        ;; and the reason — nothing derived from the target's content.
        (is (= :unsafe (:path/kind entry)))
        (is (= (str "../" secret-name) (:path/target entry)))
        (is (= :escapes-worktree (:path/unsafe-reason entry)))
        ;; No content identity anywhere in the entry: exactly the
        ;; change-entry field set, no digest/blob keys.
        (is (= git/change-entry-fields (set (keys entry))))
        ;; The outside file's bytes appear nowhere in the observation.
        (is (not (str/includes? (pr-str obs) secret)))))))

;; ------------------------------------------------------------------
;; Moved git-commit helper: behavior unchanged

(deftest current-commit-sha
  (let [expected (str/trim (:out (sh/sh "git" "rev-parse" "HEAD"
                                        :dir (.getAbsolutePath (io/file ".")))))]
    (is (re-matches #"[0-9a-f]{40}" (git-adapter/current-commit-sha)))
    (is (= expected (git-adapter/current-commit-sha)))))
