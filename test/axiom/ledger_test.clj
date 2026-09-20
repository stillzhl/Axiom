(ns axiom.ledger-test
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.contract :as contract]
            [axiom.fixtures :as f]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.nomos :as nomos]))

(defn- error-kind [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:axiom/error (ex-data e)))))

(def ^:private base-inputs
  {:event/id "evt-001" :stream/id "synthetic-project" :dedup/key "submit-001"
   :producer "synthetic-runner" :observed/time 1000 :ingested/time 1001
   :scenario f/scenario})

(defn- stored
  "Builds n chained envelopes and stamps :seq, as the store would."
  [inputs-seq]
  (loop [prev nil done [] seq 0 remaining inputs-seq]
    (if (empty? remaining)
      done
      (let [stored-env (assoc (ledger/record-scenario prev (first remaining)) :seq seq)]
        (recur stored-env (conj done stored-env) (inc seq) (rest remaining))))))

(deftest envelope-validation
  (let [env (ledger/record-scenario nil base-inputs)]
    (is (= env (ledger/validate-envelope! env)))
    (doseq [[label mutate] {"bad event id" #(assoc % :event/id "no spaces!")
                            "bad stream id" #(assoc % :stream/id "")
                            "bad dedup key" #(assoc % :dedup/key "bad key!")
                            "bad producer" #(assoc % :producer "bad producer!")
                            "negative observed time" #(assoc % :observed/time -1)
                            "bad envelope version" #(assoc % :schema/version 99)
                            "bad candidate id" #(assoc % :candidate/id "nope")
                            "bad prev hash" #(assoc % :prev/hash "nope")
                            "unknown record kind" #(assoc-in % [:payload :record/kind] :bogus)
                            "missing field" #(dissoc % :producer)
                            "extra field" #(assoc % :extra 1)}]
      (testing label
        (is (= :invalid (error-kind #(ledger/validate-envelope! (mutate env)))))))
    (testing "payload digest mismatch is rejected"
      (is (= :invalid (error-kind #(ledger/validate-envelope!
                                    (assoc env :payload/digest
                                           "sha256:0000000000000000000000000000000000000000000000000000000000000000"))))))))

(deftest record-scenario-evaluates
  (let [env (ledger/record-scenario nil base-inputs)
        recorded (get-in env [:payload :decision])]
    (is (= (:decision/id (nomos/evaluate f/scenario)) (:decision/id recorded)))
    (is (= (model/candidate-id f/candidate) (:candidate/id env)))
    (is (= "" (:prev/hash env)))
    (testing "chained envelope links to predecessor"
      (let [prev (assoc env :seq 0)
            next (ledger/record-scenario prev (assoc base-inputs :event/id "evt-002" :dedup/key "submit-002"))]
        (is (= (ledger/chain-digest prev) (:prev/hash next)))))
    (testing "an invalid scenario never becomes an envelope"
      (is (= :invalid (error-kind #(ledger/record-scenario
                                    nil (assoc base-inputs :scenario
                                               (assoc f/scenario :task "no-such-task")))))))))

(deftest record-event-shape
  (let [inputs {:event/id "evt-010" :stream/id "synthetic-project" :dedup/key "submit-010"
                :producer "synthetic-runner" :observed/time 1000 :ingested/time 1001
                :candidate/id (model/candidate-id f/candidate) :event f/evidence}
        env (ledger/record-event nil inputs)]
    (is (= :event (get-in env [:payload :record/kind])))
    (is (= f/evidence (get-in env [:payload :event])))
    (testing "claim events are accepted"
      (is (= f/claim (get-in (ledger/record-event
                              nil (assoc inputs :event/id "evt-011" :dedup/key "submit-011"
                                         :event f/claim))
                             [:payload :event]))))
    (doseq [[label mutate] {"unknown event type" #(assoc-in % [:event :type] :bogus)
                            "bad candidate id" #(assoc % :candidate/id "nope")}]
      (testing label
        (is (= :invalid (error-kind #(ledger/record-event nil (mutate inputs)))))))))

(deftest chain-verification
  (let [envs (stored [base-inputs
                      (assoc base-inputs :event/id "evt-002" :dedup/key "submit-002" :observed/time 900)
                      (assoc base-inputs :event/id "evt-003" :dedup/key "submit-003" :observed/time 2000)])]
    (testing "valid chain"
      (let [result (ledger/verify-chain envs)]
        (is (:chain/valid? result))
        (is (= 3 (:event/count result)))
        (is (= (ledger/chain-digest (last envs)) (:head/hash result)))))
    (testing "empty ledger is a valid empty chain"
      (is (= {:chain/valid? true :head/hash "" :event/count 0} (ledger/verify-chain []))))
    (testing "tampered payload breaks the chain"
      (is (= :operational (error-kind #(ledger/verify-chain
                                        (assoc-in envs [1 :payload :scenario :now] 9999))))))
    (testing "sequence gap is an operational failure"
      (is (= :operational (error-kind #(ledger/verify-chain
                                        [(first envs) (assoc (nth envs 2) :seq 7)])))))
    (testing "broken prev link is an operational failure"
      (is (= :operational (error-kind #(ledger/verify-chain
                                        (assoc-in envs [2 :prev/hash]
                                                  "sha256:0000000000000000000000000000000000000000000000000000000000000000"))))))))

(deftest snapshot-equivalence
  (let [envs (stored (mapv (fn [n] (assoc base-inputs :event/id (str "evt-" n)
                                                     :dedup/key (str "submit-" n)
                                                     :observed/time (* n 10)))
                           (range 5)))
        snap (ledger/build-snapshot (subvec envs 0 3))]
    (testing "snapshot covers its prefix"
      (is (= 2 (:snapshot/seq snap)))
      (is (= ledger/reducer-version (:reducer/version snap)))
      (is (= (model/digest (ledger/ledger-world (subvec envs 0 3))) (:world/digest snap)))
      (is (true? (ledger/verify-snapshot! snap (subvec envs 0 3)))))
    (testing "restore + replay equals full replay"
      (let [full (model/digest (ledger/ledger-world envs))
            restored (model/digest (ledger/restore-world snap (subvec envs 3)))]
        (is (= full restored))))
    (testing "tampered snapshot world is rejected"
      (is (= :operational (error-kind #(ledger/verify-snapshot!
                                        (assoc snap :world {:revision 999}) (subvec envs 0 3))))))
    (testing "wrong reducer version is rejected"
      (is (= :operational (error-kind #(ledger/verify-snapshot!
                                        (assoc snap :reducer/version "other") (subvec envs 0 3))))))
    (testing "head hash mismatch is rejected"
      (is (= :operational (error-kind #(ledger/verify-snapshot!
                                        (assoc snap :head/hash "sha256:0000000000000000000000000000000000000000000000000000000000000000")
                                        (subvec envs 0 3))))))))

(deftest out-of-order-observed-times
  (testing "reduction follows sequence order, not observed time"
    (let [envs (stored [(assoc base-inputs :event/id "evt-001" :dedup/key "submit-001" :observed/time 3000)
                        (assoc base-inputs :event/id "evt-002" :dedup/key "submit-002" :observed/time 1000)
                        (assoc base-inputs :event/id "evt-003" :dedup/key "submit-003" :observed/time 2000)])
          world (ledger/ledger-world envs)]
      (is (= 3 (:revision world)))
      (is (= ["ev-001" "ev-001" "ev-001"] (mapv :event/id (:evidence world)))))))

(deftest replay-report-reproduction
  (let [envs (stored [base-inputs (assoc base-inputs :event/id "evt-002" :dedup/key "submit-002")])
        snap (ledger/build-snapshot [(first envs)])
        report (ledger/replay-report {:source {:kind :ledger :path "test.db"}
                                      :schema/version 2 :envelopes envs :snapshot snap})]
    (is (= :replay (:report report)))
    (is (= 2 (get-in report [:ledger :event/count])))
    (is (= 1 (get-in report [:ledger :through/seq])))
    (is (= (model/digest (ledger/ledger-world envs)) (:world/digest report)))
    (is (= [true true] (mapv :reproduced? (:decisions report))))
    (is (= [(:decision/id (nomos/evaluate f/scenario))
            (:decision/id (nomos/evaluate f/scenario))]
           (mapv :decision/id (:decisions report))))
    (is (= 0 (get-in report [:snapshot/used :snapshot/seq])))
    (testing "an incompatible snapshot is ignored, not fatal"
      (let [bad (assoc snap :reducer/version "bogus")
            report (ledger/replay-report {:source {:kind :ledger :path "test.db"}
                                          :schema/version 2 :envelopes envs :snapshot bad})]
        (is (:snapshot/ignored? report))
        (is (nil? (:snapshot/used report)))
        (is (= (model/digest (ledger/ledger-world envs)) (:world/digest report)))))))

(deftest bundle-round-trip
  (let [envs (stored [base-inputs (assoc base-inputs :event/id "evt-002" :dedup/key "submit-002")])
        engine {:axiom/version "test" :axiom/commit "abc" :clojure/version "1" :reducer/version ledger/reducer-version}
        bundle (ledger/export-bundle-data {:engine engine :schema/version 2 :envelopes envs :snapshot nil})]
    (testing "valid bundle reads back"
      (let [read (ledger/read-bundle-data bundle)]
        (is (= (:bundle/digest bundle) (:bundle/digest read)))
        (is (= 2 (count (:events read))))))
    (testing "tampered digest is an operational failure"
      (is (= :operational (error-kind #(ledger/read-bundle-data (assoc bundle :bundle/digest "sha256:00"))))))
    (testing "tampered event payload is an operational failure"
      (is (= :operational (error-kind #(ledger/read-bundle-data
                                        (assoc-in bundle [:events 0 :payload :scenario :now] 4242))))))
    (testing "unknown bundle version is an operational failure"
      (is (= :operational (error-kind #(ledger/read-bundle-data
                                        (let [b (assoc bundle :bundle/version 99)]
                                          (assoc b :bundle/digest (model/digest (dissoc b :bundle/digest)))))))))
    (testing "bundle replay reproduces ledger decision digests"
      (let [from-ledger (mapv :decision/id (:decisions (ledger/replay-report
                                                        {:source {:kind :ledger :path "x"}
                                                         :schema/version 2 :envelopes envs :snapshot nil})))
            from-bundle (mapv :decision/id (:decisions (ledger/replay-report
                                                        {:source {:kind :bundle :path "x"}
                                                         :schema/version (get-in bundle [:ledger :schema/version])
                                                         :envelopes (:events bundle)
                                                         :snapshot (:snapshot bundle)})))]
        (is (= from-ledger from-bundle))))))
