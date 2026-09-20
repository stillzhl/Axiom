(ns axiom.observations-test
  "Tests for spec 0003 T5 (CLI: observe-git / digest / run) and T6
   (observation/evidence ledger integration). All repositories, files,
   commands and ledgers are synthetic, created in temp dirs for the
   test run only."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [axiom.adapters.runner :as runner-adapter]
            [axiom.cli :as cli]
            [axiom.fixtures :as f]
            [axiom.git :as git]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.runner :as runner]
            [axiom.store :as store]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str])
  (:import (java.io File)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;; ------------------------------------------------------------------
;; Fixture helpers (synthetic only)

(def ^:dynamic *tmp-dirs* nil)

(defn- tmp-dir [prefix]
  (let [dir (.toFile (Files/createTempDirectory prefix (make-array FileAttribute 0)))]
    (when *tmp-dirs* (swap! *tmp-dirs* conj dir))
    dir))

(defn- delete-recursively [dir]
  (let [root (.toPath (io/file dir))]
    (when (Files/exists root (into-array java.nio.file.LinkOption []))
      (doseq [p (->> (.iterator (Files/walk root (make-array java.nio.file.FileVisitOption 0)))
                     iterator-seq
                     (sort-by #(.getNameCount ^java.nio.file.Path %) >))]
        (try (Files/deleteIfExists p) (catch Exception _ nil))))))

(use-fixtures :each
  (fn [t]
    (binding [*tmp-dirs* (atom [])]
      (try (t)
           (finally (doseq [d @*tmp-dirs*] (delete-recursively d)))))))

(defn- temp-db []
  (let [file (File/createTempFile "axiom-ledger-obs-test" ".db")]
    (.delete file)
    (.getPath file)))

(defn- delete-db! [path]
  (doseq [suffix ["" "-wal" "-shm" "-journal"]]
    (.delete (File. (str path suffix)))))

(defn- git!
  "Runs git in dir; throws when the fixture command itself fails."
  [dir & args]
  (let [{:keys [exit err]} (apply sh/sh "git" (concat args [:dir (.getAbsolutePath ^File dir)]))]
    (when-not (zero? exit)
      (throw (ex-info (str "fixture git failed: " (str/join " " args)) {:err err})))))

(defn- init-repo! []
  (let [dir (tmp-dir "axiom-obs-")]
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

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(defn- utf8 ^bytes [^String s]
  (.getBytes s StandardCharsets/UTF_8))

;; ------------------------------------------------------------------
;; Synthetic observation / evidence builders

(def ^:private sha-a (apply str (repeat 40 "a")))
(def ^:private sha-b (apply str (repeat 40 "b")))
(def ^:private sha-c (apply str (repeat 40 "c")))

(defn- synthetic-observation []
  (git/build-observation
   {:observation/status :complete
    :repo/path "/tmp/synthetic-repo"
    :git/base sha-a :git/head sha-b :git/tree sha-c
    :git/branch "main" :git/upstream nil
    :git/clean? true :git/dirty-files []
    :changes []
    :git/version "git version 2.44.0"
    :commands ["git --version"]}))

(defn- test-registry []
  (runner/validate-registry!
   {:registry/version 1
    :commands {"true-probe"
               {:command/id "true-probe"
                :command/executable "/bin/true"
                :command/args []
                :command/workdir "/tmp"
                :command/timeout-seconds 30
                :command/stdout-cap-bytes 65536
                :command/stderr-cap-bytes 65536
                :command/applicability "Synthetic test-only probe."
                :runner/shell false}}}))

(defn- real-evidence []
  "An Evidence record from a real runner run (the checked-in truth
   for T6 evidence recording)."
  (runner-adapter/run!
   {:registry (test-registry)
    :command/id "true-probe"
    :args {}
    :candidate {:candidate/base nil :candidate/head nil :candidate/tree nil}
    :run/id "obs-test-run-1"}))

(defn- base-inputs [n]
  {:event/id (str "evt-obs-" n) :stream/id "synthetic-project"
   :dedup/key (str "submit-obs-" n) :producer "synthetic-runner"
   :observed/time (* n 1000) :ingested/time (+ (* n 1000) 5)})

(defn- scenario-inputs [n]
  (assoc (base-inputs n) :scenario (assoc f/scenario :now (+ 1100 n))))

;; ------------------------------------------------------------------
;; T6: ledger validation of the new record kinds

(deftest record-observation-validation
  (let [observation (synthetic-observation)
        env (ledger/record-observation nil (assoc (base-inputs 1) :observation observation))]
    (testing "a valid observation records with content-derived candidate id"
      (is (= :observation (get-in env [:payload :record/kind])))
      (is (= observation (get-in env [:payload :observation])))
      (is (= (model/candidate-id observation) (:candidate/id env)))
      (is (= env (ledger/validate-envelope! env))))
    (testing "malformed observations are :invalid and can never be written"
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 2) :observation
                                               (dissoc observation :trust))))))
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 2) :observation
                                               (assoc observation :observation/kind :bogus))))))
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 2) :observation "not-a-map")))))
      (is (= :invalid (error-kind #(ledger/record-observation nil (base-inputs 2))))))
    (testing "trust forgery is rejected: cannot masquerade as trusted remote CI"
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 2) :observation
                                               (assoc observation :trust :trust/remote-ci))))))
      (is (= :invalid (error-kind #(ledger/record-observation
                                    nil (assoc (base-inputs 2) :observation
                                               (assoc observation :trust :allow)))))))))

(deftest record-evidence-validation
  (let [evidence (real-evidence)]
    (testing "the real run record is honestly marked"
      (is (= :trust/local-diagnostic (:trust evidence)))
      (is (= :completed (:run/outcome evidence)))
      (is (:run/complete? evidence)))
    (let [env (ledger/record-evidence nil (assoc (base-inputs 3) :evidence evidence))]
      (testing "a valid evidence record records with content-derived candidate id"
        (is (= :evidence-record (get-in env [:payload :record/kind])))
        (is (= evidence (get-in env [:payload :evidence])))
        (is (= (model/candidate-id evidence) (:candidate/id env)))
        (is (= env (ledger/validate-envelope! env))))
      (testing "malformed evidence is :invalid and can never be written"
        (is (= :invalid (error-kind #(ledger/record-evidence
                                      nil (assoc (base-inputs 4) :evidence
                                                 (dissoc evidence :run/id))))))
        (is (= :invalid (error-kind #(ledger/record-evidence
                                      nil (assoc (base-inputs 4) :evidence
                                                 (assoc evidence :run/outcome :bogus))))))
        (is (= :invalid (error-kind #(ledger/record-evidence
                                      nil (assoc (base-inputs 4) :evidence 42))))))
      (testing "trust forgery is rejected"
        (is (= :invalid (error-kind #(ledger/record-evidence
                                      nil (assoc (base-inputs 4) :evidence
                                                 (assoc evidence :trust :trust/remote-ci))))))))))

(deftest observation-evidence-contribute-no-world-events
  (let [observation (synthetic-observation)
        evidence (real-evidence)
        obs-env (assoc (ledger/record-observation nil (assoc (base-inputs 1) :observation observation)) :seq 0)
        ev-env (assoc (ledger/record-evidence obs-env (assoc (base-inputs 2) :evidence evidence)) :seq 1)]
    (testing "observations and evidence contribute zero 0001 events to the world fold"
      (is (= [] (ledger/extract-events obs-env)))
      (is (= [] (ledger/extract-events ev-env)))
      (is (= {:revision 0} (select-keys (ledger/ledger-world [obs-env ev-env]) [:revision]))))))

(deftest observation-evidence-ledger-roundtrip
  (let [path (temp-db)]
    (try
      (let [handle (store/open! path {:create true})]
        (try
          (let [observation (synthetic-observation)
                evidence (real-evidence)
                s1 (store/append! handle (ledger/record-scenario nil (scenario-inputs 1)))
                o1 (store/append! handle (ledger/record-observation s1 (assoc (base-inputs 2) :observation observation)))
                e1 (store/append! handle (ledger/record-evidence o1 (assoc (base-inputs 3) :evidence evidence)))
                s2 (store/append! handle (ledger/record-scenario e1 (scenario-inputs 4)))
                stored [s1 o1 e1 s2]
                envelopes (store/read-range handle 0 3)]
            (testing "envelopes round-trip through the store hash-chained and sequence-ordered"
              (is (= [0 1 2 3] (mapv :seq envelopes)))
              (is (= (mapv :event/id stored) (mapv :event/id envelopes)))
              (is (:chain/valid? (ledger/verify-chain envelopes))))
            (testing "replay shows the observations and evidence behind the decisions"
              (let [report (ledger/replay-report
                            {:source {:kind :ledger :path path}
                             :schema/version (:schema/version (store/ledger-identity handle))
                             :envelopes envelopes
                             :snapshot nil})]
                (is (= [{:seq 1 :event/id (:event/id o1) :observation observation}]
                       (:observations report)))
                (is (= [{:seq 2 :event/id (:event/id e1) :evidence evidence}]
                       (:evidence report)))
                (is (= [true true] (mapv :reproduced? (:decisions report))))))
            (testing "observations do not change 0001/0002 decision bytes or the world"
              (let [plain (ledger/replay-report
                           {:source {:kind :ledger :path path}
                            :schema/version (:schema/version (store/ledger-identity handle))
                            :envelopes [s1 (assoc (ledger/record-scenario s1 (scenario-inputs 4)) :seq 1)]
                            :snapshot nil})
                    mixed (ledger/replay-report
                           {:source {:kind :ledger :path path}
                            :schema/version (:schema/version (store/ledger-identity handle))
                            :envelopes envelopes
                            :snapshot nil})]
                (is (= (:world/digest plain) (:world/digest mixed)))
                (is (= (mapv :decision/id (:decisions plain))
                       (mapv :decision/id (:decisions mixed))))))
            (testing ":event/id and dedup/key reuse are rejected deterministically"
              (is (= :duplicate (error-kind #(store/append!
                                              handle (ledger/record-observation
                                                      s2 (assoc (base-inputs 2)
                                                                :observation observation))))))
              (is (= :duplicate (error-kind #(store/append!
                                              handle (ledger/record-evidence
                                                      s2 (-> (base-inputs 9)
                                                             (assoc :dedup/key (:dedup/key (base-inputs 3)))
                                                             (assoc :evidence evidence)))))))))
          (finally (store/close! handle))))
      (finally (delete-db! path)))))

;; ------------------------------------------------------------------
;; T5: CLI exit contracts

(deftest cli-observe-git
  (let [dir (init-repo!)]
    (write! dir "a.txt" "first\n")
    (commit! dir "first commit")
    (write! dir "b.txt" "second\n")
    (commit! dir "second commit")
    (let [repo (.getAbsolutePath ^File dir)]
      (testing "valid observation exits 0 with the EDN report"
        (let [{:keys [exit output]} (cli/run ["observe-git" "--repo" repo "--base" "HEAD~1"])]
          (is (= 0 exit))
          (is (= :complete (:observation/status output)))
          (is (= :trust/local-diagnostic (:trust output)))
          (is (= 1 (count (get-in output [:value :changes :changes]))))
          (is (= :added (get-in output [:value :changes :changes 0 :change/kind])))))
      (testing "malformed arguments exit 4"
        (is (= 4 (:exit (cli/run ["observe-git"]))))
        (is (= 4 (:exit (cli/run ["observe-git" "--repo"]))))
        (is (= 4 (:exit (cli/run ["observe-git" "--repo" repo "--base"]))))
        (is (= 4 (:exit (cli/run ["observe-git" "--repo" repo "--base" "HEAD" "extra"]))))
        (is (= 4 (:exit (cli/run ["observe-git" "--repo" repo "--bogus" "x"]))))
        (is (= 4 (:exit (cli/run ["observe-git" "--repo" repo "trailing"])))))
      (testing "a missing repository is invalid input (exit 4)"
        (is (= 4 (:exit (cli/run ["observe-git" "--repo" "/tmp/axiom-no-such-repo-0003"])))))))
  (testing "a directory that is not a git repository yields an :incomplete report (exit 5)"
    (let [plain (tmp-dir "axiom-notrepo-")
          {:keys [exit output]} (cli/run ["observe-git" "--repo" (.getAbsolutePath ^File plain)])]
      (is (= 5 exit))
      (is (= :incomplete (:observation/status output)))
      (is (some? (get output :observation/failing-step))))))

(deftest cli-digest
  (let [file (File/createTempFile "axiom-digest-test" ".bin")]
    (try
      (let [bytes (utf8 "synthetic artifact bytes \u00e9\n")]
        (with-open [out (io/output-stream file)] (.write out bytes))
        (let [path (.getAbsolutePath file)
              expected (model/sha256-bytes bytes)]
          (testing "valid digest exits 0 with SHA-256 over the exact bytes"
            (let [{:keys [exit output]} (cli/run ["digest" "--path" path])]
              (is (= 0 exit))
              (is (= expected (:artifact/digest output)))
              (is (= "application/octet-stream" (:artifact/media-type output)))
              (is (= (alength ^bytes bytes) (:artifact/size-bytes output)))
              (is (= path (:artifact/location output)))))
          (testing "the digest matches the independent sha256sum implementation"
            (let [{:keys [exit out]} (sh/sh "sha256sum" path)]
              (is (zero? exit))
              (is (= (str "sha256:" (first (str/split (str/trim out) #"\s+")))
                     (:artifact/digest (:output (cli/run ["digest" "--path" path])))))))
          (testing "an explicit validated media type is recorded"
            (let [{:keys [exit output]} (cli/run ["digest" "--path" path "--media-type" "text/plain"])]
              (is (= 0 exit))
              (is (= "text/plain" (:artifact/media-type output)))))
          (testing "malformed arguments exit 4"
            (is (= 4 (:exit (cli/run ["digest"]))))
            (is (= 4 (:exit (cli/run ["digest" "--path"]))))
            (is (= 4 (:exit (cli/run ["digest" "--path" path "extra"]))))
            (is (= 4 (:exit (cli/run ["digest" "--path" path "--media-type"]))))
            (is (= 4 (:exit (cli/run ["digest" "--path" path "--media-type" "not a media type!"]))))
            (is (= 4 (:exit (cli/run ["digest" "--path" "/tmp/axiom-no-such-file-0003"])))))
          (testing "a directory is not a digestible artifact (exit 4)"
            (is (= 4 (:exit (cli/run ["digest" "--path" (.getAbsolutePath (tmp-dir "axiom-digest-dir-"))])))))))
      (finally (.delete file)))))

(deftest cli-run-command
  (testing "a completed probe exits 0 with the Evidence record"
    (let [{:keys [exit output]} (cli/run ["run" "--command" "true-probe"])]
      (is (= 0 exit))
      (is (= :trust/local-diagnostic (:trust output)))
      (is (= :completed (:run/outcome output)))
      (is (= :pass (:run/result output)))
      (is (true? (:run/complete? output)))))
  (testing "structured arguments render into the run"
    (let [{:keys [exit output]} (cli/run ["run" "--command" "echo-probe" "--args" "message=hello"])]
      (is (= 0 exit))
      (is (= :pass (:run/result output)))
      (is (= {"message" "hello"} (:run/request-args output))))
    (let [{:keys [exit output]} (cli/run ["run" "--command" "false-probe"])]
      (is (= 0 exit))
      (is (= :fail (:run/result output)))))
  (testing "invalid input exits 4"
    (is (= 4 (:exit (cli/run ["run"]))))
    (is (= 4 (:exit (cli/run ["run" "--command"]))))
    (is (= 4 (:exit (cli/run ["run" "--command" "true-probe" "extra"]))))
    (is (= 4 (:exit (cli/run ["run" "--command" "no-such-probe"]))))
    (is (= 4 (:exit (cli/run ["run" "--command" "true-probe" "--args" "malformed"]))))
    (is (= 4 (:exit (cli/run ["run" "--command" "true-probe" "--args"]))))
    (is (= 4 (:exit (cli/run ["run" "--command" "echo-probe" "--args" "message=a" "message=b"]))))
    (is (= 4 (:exit (cli/run ["run" "--command" "sleep-probe" "--args" "seconds=999"])))))
  (testing "a timed-out run emits the honest :incomplete record and exits 5"
    (let [{:keys [exit output]} (cli/run ["run" "--command" "sleep-probe" "--args" "seconds=30"])]
      (is (= 5 exit))
      (is (= :timed-out (:run/outcome output)))
      (is (= :timeout-seconds (:run/bound-exceeded output)))
      (is (= :incomplete (:run/result output)))
      (is (false? (:run/complete? output))))))
