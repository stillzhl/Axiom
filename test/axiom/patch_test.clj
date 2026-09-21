(ns axiom.patch-test
  "Tests for spec 0006 T6 (`axiom.execute/admit-patch`,
   `axiom.ledger/record-patch`, `axiom.adapters.worktree/diff-worktree`):
   patch admission over the worktree diff — proposal coverage,
   lease currency, path safety, task-class check — and the
   `:patch/admitted` / `:patch/rejected` ledger events.

   Every identity and digest is invented (`synth-*`). No live
   credentials, no network."
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.execute :as execute]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.adapters.worktree :as worktree])
  (:import (java.io File)
           (java.nio.file Files Path Paths)
           (java.nio.file.attribute FileAttribute)))

(def ^:private digest-a (str "sha256:" (apply str (repeat 64 "a"))))
(def ^:private digest-b (str "sha256:" (apply str (repeat 64 "b"))))

(defn- task [& {:keys [class recipe]
                :or {class :task-class/standard
                     recipe ["./scripts/check"]}}]
  {:task/id "synth-task-1"
   :task/class class
   :task/pinned-recipe recipe})

(defn- op [path op-key digest & {:keys [symlink?] :or {symlink? false}}]
  {:diff/path path :diff/op op-key :diff/digest digest :diff/symlink? symlink?})

(defn- proposal [paths]
  {:proposal/actions (mapv (fn [p] {:action/kind :action/write-file
                                    :action/path p
                                    :action/capability :capability/write-file})
                           paths)})

(defn- lease [& {:keys [token] :or {token "synth-token-1"}}]
  {:lease/task-id "synth-task-1"
   :lease/worker-id "synth-worker-1"
   :lease/token token})

(defn- policy []
  {:event/kind :governance/policy-approved
   :governance/policy-id "synth-policy-1"})

(defn- admit [diff & {:keys [proposals tok pol task]
                      :or {proposals [(proposal ["docs/guide.md"])]
                           tok "synth-token-1"
                           pol (policy)
                           task (task)}}]
  (execute/admit-patch task diff proposals (lease :token "synth-token-1") tok pol))

;; ------------------------------------------------------------------
;; admit-patch

(deftest admit-patch-allows-a-covered-patch
  (testing "a fully covered, safe patch under a current lease is allowed"
    (let [diff [(op "docs/guide.md" :modify digest-a)]
          res (admit diff)]
      (is (= :allow (:patch/verdict res)))
      (is (= (execute/patch-digest diff) (:patch/digest res)))
      (is (= ["./scripts/check"] (:patch/verification-recipe res)))
      (is (= "synth-task-1" (:patch/task-id res))))))

(deftest admit-patch-denies-with-named-reasons
  (testing "a symlink escape is denied with :path-safety-violation"
    (let [res (admit [(op "docs/link.md" :add digest-a :symlink? true)])]
      (is (= :deny (:patch/verdict res)))
      (is (= :path-safety-violation (:patch/reason res)))))
  (testing "a traversal path is denied with :path-safety-violation"
    (let [res (admit [(op "docs/../../evil.md" :add digest-a)])]
      (is (= :deny (:patch/verdict res)))
      (is (= :path-safety-violation (:patch/reason res)))))
  (testing "a patch with no covering proposal is denied with :no-approved-proposal"
    (let [res (admit [(op "docs/uncovered.md" :add digest-a)]
                     :proposals [(proposal ["docs/other.md"])])]
      (is (= :deny (:patch/verdict res)))
      (is (= :no-approved-proposal (:patch/reason res)))))
  (testing "a kernel-namespace patch under a standard task is denied with :self-modification-requires-promotion"
    (let [diff [(op "src/axiom/nomos.clj" :modify digest-a)]
          res (admit diff
                     :proposals [(proposal ["src/axiom/nomos.clj"])]
                     :task (task :class :task-class/standard))]
      (is (= :deny (:patch/verdict res)))
      (is (= :self-modification-requires-promotion (:patch/reason res)))))
  (testing "the same kernel patch is allowed under a self-modifying task"
    (let [diff [(op "src/axiom/nomos.clj" :modify digest-a)]
          res (admit diff
                     :proposals [(proposal ["src/axiom/nomos.clj"])]
                     :task (task :class :task-class/self-modifying))]
      (is (= :allow (:patch/verdict res)))))
  (testing "a stale fencing token is denied with :stale-fencing-token"
    (let [res (admit [(op "docs/guide.md" :modify digest-a)] :tok "stale-token")]
      (is (= :deny (:patch/verdict res)))
      (is (= :stale-fencing-token (:patch/reason res)))))
  (testing "a missing lease token is denied with :no-lease-held"
    (let [res (execute/admit-patch (task) [(op "docs/guide.md" :modify digest-a)]
                                   [(proposal ["docs/guide.md"])]
                                   {} "synth-token-1" (policy))]
      (is (= :deny (:patch/verdict res)))
      (is (= :no-lease-held (:patch/reason res)))))
  (testing "a missing approved policy is denied with :no-approved-policy"
    (let [res (admit [(op "docs/guide.md" :modify digest-a)] :pol nil)]
      (is (= :deny (:patch/verdict res)))
      (is (= :no-approved-policy (:patch/reason res)))))
  (testing "malformed inputs are :invalid and can never allow"
    (doseq [bad [[nil [(proposal ["docs/guide.md"])]]
                 [[] [(proposal ["docs/guide.md"])]]
                 [[(assoc (op "docs/guide.md" :modify digest-a)
                          :diff/digest "not-a-digest")]
                  [(proposal ["docs/guide.md"])]]]]
      (let [[diff proposals] bad
            res (execute/admit-patch (task) diff proposals
                                     (lease) "synth-token-1" (policy))]
        (is (= :invalid (:patch/verdict res)) (str bad))
        (is (= :malformed (:patch/reason res)) (str bad))))))

;; ------------------------------------------------------------------
;; ledger/record-patch

(defn- inputs [n]
  {:event/id (str "evt-" n)
   :stream/id "synth-task-stream"
   :dedup/key (str "dedup-" n)
   :producer "synth-evaluator-1"
   :observed/time 1000
   :ingested/time 1001})

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(deftest patch-events-record
  (testing ":patch/admitted and :patch/rejected record valid envelopes"
    (let [admitted {:event/kind :patch/admitted
                    :task/id "synth-task-1"
                    :patch/digest digest-a
                    :patch/evidence-digest digest-b
                    :task/evaluator "synth-evaluator-1"}
          rejected {:event/kind :patch/rejected
                    :task/id "synth-task-1"
                    :patch/digest digest-a
                    :patch/reason :path-safety-violation
                    :task/evaluator "synth-evaluator-1"}]
      (doseq [[n event] (map-indexed vector [admitted rejected])]
        (let [env (ledger/record-patch nil (assoc (inputs n) :patch-event event))]
          (is (= :patch (get-in env [:payload :record/kind])))
          (is (= event (get-in env [:payload :patch/event])))
          (is (= (model/digest (:payload env)) (:payload/digest env)))
          ;; provenance: zero 0001 world events
          (is (empty? (ledger/extract-events env))))))))
  (testing "malformed patch events are :invalid"
    (let [base {:event/kind :patch/admitted
                :task/id "synth-task-1"
                :patch/digest digest-a
                :patch/evidence-digest digest-b
                :task/evaluator "synth-evaluator-1"}]
      (doseq [event [(assoc base :event/kind :patch/vanished)
                     (dissoc base :patch/evidence-digest)
                     (assoc base :patch/digest "not-a-digest")
                     (assoc base :patch/reason :path-safety-violation)
                     {:event/kind :patch/rejected
                      :task/id "synth-task-1"
                      :patch/digest digest-a
                      :patch/reason "not-a-keyword"
                      :task/evaluator "synth-evaluator-1"}]]
        (is (= :invalid
               (error-kind #(ledger/record-patch nil (assoc (inputs 0)
                                                           :patch-event event))))
            (str "expected :invalid for " event)))))

;; ------------------------------------------------------------------
;; worktree/diff-worktree

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "axiom-diff-test"
                                      (into-array FileAttribute []))))

(defn- write-file! [^File root rel content]
  (let [f (File. root ^String rel)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(deftest diff-worktree-detects-operations
  (let [base (temp-dir)
        wt (temp-dir)]
    (try
      (write-file! base "docs/keep.md" "keep")
      (write-file! base "docs/change.md" "old")
      (write-file! base "docs/gone.md" "gone")
      (write-file! wt "docs/keep.md" "keep")
      (write-file! wt "docs/change.md" "new")
      (write-file! wt "docs/added.md" "added")
      (let [res (worktree/diff-worktree {:worktree/path (str wt)} (str base))]
        (is (true? (:worktree/ok res)))
        (let [ops (into {} (map (juxt :diff/path identity)
                                (:diff/operations res)))]
          (is (= :modify (:diff/op (get ops "docs/change.md"))))
          (is (= :add (:diff/op (get ops "docs/added.md"))))
          (is (= :delete (:diff/op (get ops "docs/gone.md"))))
          (is (not (contains? ops "docs/keep.md")))
          (is (every? #(re-matches #"sha256:[0-9a-f]{64}" (:diff/digest %))
                      (vals ops)))
          (is (every? false? (map :diff/symlink? (vals ops))))))
      (testing "a symlink is flagged, never followed"
        (let [link (File. wt "docs/link.md")]
          (Files/createSymbolicLink (.toPath link)
                                    (.toPath (File. wt "docs/keep.md"))
                                    (into-array FileAttribute []))
          (let [res (worktree/diff-worktree {:worktree/path (str wt)} (str base))
                op (some #(when (= "docs/link.md" (:diff/path %)) %)
                         (:diff/operations res))]
            (is (true? (:diff/symlink? op)))
            (is (nil? (:diff/digest op))))))
      (testing "malformed input and a missing base are refused"
        (is (= :malformed (:worktree/reason
                           (worktree/diff-worktree {} (str base)))))
        (is (= :base-unavailable (:worktree/reason
                                  (worktree/diff-worktree
                                   {:worktree/path (str wt)}
                                   (str (File. base "nope")))))))
      (finally
        (doseq [d [base wt]]
          (worktree/destroy-worktree {:worktree/id (.getName d)
                                      :worktree/path (str d)
                                      :worktree/root (str (.getParentFile d))}))))))
