(ns axiom.kernel-test
  (:require [clojure.test :refer [deftest is testing]]
            [axiom.cli :as cli]
            [axiom.contract :as contract]
            [axiom.fixtures :as f]
            [axiom.model :as model]
            [axiom.nomos :as nomos]
            [axiom.world :as world]))

(defn result [s] (:result (nomos/evaluate s)))
(defn invalid? [s]
  (try (nomos/evaluate s) false
       (catch clojure.lang.ExceptionInfo e (= :invalid (:axiom/error (ex-data e))))))

(deftest bounded-data-reader
  (is (= {:a [1 "#not-a-tag"] :b #{true nil}} (contract/read-data "{:a [1 \"#not-a-tag\"] :b #{true nil}} ;comment")))
  (doseq [input ["" "{} {}" "#=(System/exit 0)" "#inst \"2020-01-01\"" "#foo {}"
                 "#_{} {}" "(inc 1)" "1.2" "1/2" "symbol" "\\a" "{:a 1 :a 2}"
                 "9223372036854775808" "[" "{]"
                 (str (apply str (repeat 33 "[")) "0" (apply str (repeat 33 "]")))
                 (str "\"" (apply str (repeat contract/max-bytes "é")) "\"")
                 (str "[" (apply str (repeat 20001 "0 ")) "]")]]
    (testing (subs input 0 (min 50 (count input)))
      (is (thrown? clojure.lang.ExceptionInfo (contract/read-data input))))))

(deftest canonical-identity
  (is (= (model/digest {:a #{1 2} :b [3]}) (model/digest (array-map :b [3] :a #{2 1}))))
  (is (not= (model/digest [1 2]) (model/digest #{1 2})))
  (is (not= (model/digest "a") (model/digest :a)))
  (is (not= (model/digest 1) (model/digest "1")))
  (is (thrown? clojure.lang.ExceptionInfo (model/digest 1.0)))
  (is (thrown? clojure.lang.ExceptionInfo (model/digest (str (char 55296)))))
  (is (thrown? clojure.lang.ExceptionInfo (contract/read-data "\"\\ud800\"")))
  (is (= (model/canonical {:a #{1 2}})
         (binding [*print-length* 1 *print-level* 1] (model/canonical {:a #{2 1}})))))

(deftest strict-schemas
  (doseq [s [(assoc f/scenario :extra true)
             (assoc-in f/scenario [:contract :extra] true)
             (assoc-in f/scenario [:contract :schema/version] 99)
             (update-in f/scenario [:contract :tasks] conj (first (:tasks f/contract)))
             (assoc-in f/scenario [:contract :tasks 0 :depends-on] #{"missing"})
             (assoc-in f/scenario [:contract :tasks 0 :depends-on] #{"T-001"})
             (assoc-in f/scenario [:contract :tasks 0 :obligations] #{})
             (assoc-in f/scenario [:contract :tasks 0 :obligations] #{"missing"})
             (assoc-in f/scenario [:contract :specs 0 :state] :invented)
             (assoc-in f/scenario [:contract :tasks 0 :scope] #{"../"})
             (assoc-in f/scenario [:policy :producers] #{})
             (assoc-in f/scenario [:policy :accepted-states] #{:draft})
             (assoc-in f/scenario [:events 0 :payload :trusted] true)
             (update f/scenario :events conj f/evidence)
             (assoc-in f/scenario [:events 0 :payload :attempt] 0)
             (assoc-in f/scenario [:events 0 :payload :result] :unknown-new-value)
             (assoc-in f/scenario [:events 0 :type] :approval)
             (assoc-in f/scenario [:candidate :policy-digest] (model/digest "relaxed"))
             (assoc-in f/scenario [:candidate :contract-digest] (model/digest "changed"))
             (assoc f/scenario :task "missing")]]
    (is (invalid? s))))

(deftest malformed-field-types
  (doseq [path [[:contract] [:contract :specs] [:contract :tasks] [:contract :obligations]
                [:policy] [:candidate] [:events] [:changes] [:changes :files]
                [:contract :tasks 0 :depends-on] [:contract :tasks 0 :scope]
                [:contract :tasks 0 :obligations] [:events 0 :payload]
                [:policy :accepted-states] [:policy :producers]]
          bad [nil true 0 "bad" :bad [] #{} {}]]
    ;; Some empty collections are valid (events, file list, dependencies).
    (when-not (and (empty? (if (coll? bad) bad [bad]))
                   (or (and (vector? bad) (#{[:events] [:changes :files]} path))
                       (and (set? bad) (= path [:contract :tasks 0 :depends-on]))))
      (is (invalid? (assoc-in f/scenario path bad)) (str path " " (pr-str bad))))))

(deftest admission-corpus
  (doseq [[label s expected]
          [["sufficient" f/scenario :allow]
           ["missing" (assoc f/scenario :events []) :defer]
           ["claim-only" (assoc f/scenario :events [f/claim]) :defer]
           ["failed" (assoc-in f/scenario [:events 0 :payload :result] :fail) :deny]
           ["stale" (assoc f/scenario :now 1301) :defer]
           ["age boundary" (assoc f/scenario :now 1300) :allow]
           ["future" (assoc f/scenario :now 999) :defer]
           ["skipped" (assoc-in f/scenario [:events 0 :payload :result] :skipped) :defer]
           ["cancelled" (assoc-in f/scenario [:events 0 :payload :result] :cancelled) :defer]
           ["forged producer" (assoc-in f/scenario [:events 0 :payload :producer] "agent") :defer]
           ["wrong recipe" (assoc-in f/scenario [:events 0 :payload :recipe-digest] (model/digest "other")) :defer]
           ["wrong suite" (assoc-in f/scenario [:events 0 :payload :suite] "other") :defer]
           ["wrong profile" (assoc-in f/scenario [:events 0 :payload :profile] "other") :defer]
           ["incomplete scope" (assoc-in f/scenario [:changes :complete?] false) :defer]
           ["symlink" (assoc-in f/scenario [:changes :files 0 :new-mode] "120000") :defer]
           ["submodule" (assoc-in f/scenario [:changes :files 0 :new-mode] "160000") :defer]
           ["outside scope" (assoc-in f/scenario [:changes :files]
                                      [{:kind :add :old-path nil :old-mode nil :new-path "secrets/key" :new-mode "100644"}]) :deny]
           ["draft" (f/rebind (assoc-in f/scenario [:contract :specs 0 :state] :draft)) :deny]
           ["no approval" (f/rebind (assoc-in f/scenario [:contract :specs 0 :approval] nil)) :defer]]]
    (testing label
      (let [decision (nomos/evaluate s)]
        (is (= expected (:result decision)))
        (is (every? #(and (:rule %) (:reason %) (:status %)) (:rules decision)))
        (is (= :offline-advisory (:mode decision)))))))

(deftest candidate-mutation-invalidates-evidence
  (doseq [field [:repository :head :base :tested-commit :tested-tree :contract-digest :policy-digest :recipe-digest]]
    (let [old (get f/candidate field)
          altered (cond (= field :repository) "another-repo"
                        (.startsWith ^String old "sha256:") (model/digest (name field))
                        :else (apply str (repeat 40 "e")))]
      (is (= :defer (result (assoc-in f/scenario [:events 0 :payload :candidate field] altered)))))))

(deftest reruns-and-conflicts
  (let [failed (-> f/evidence (assoc :id "ev-002") (assoc-in [:payload :attempt] 2)
                   (assoc-in [:payload :result] :fail))
        conflict (assoc-in failed [:payload :attempt] 1)]
    (is (= :deny (result (assoc f/scenario :events [f/evidence failed]))))
    (is (= :deny (result (assoc f/scenario :events [failed f/evidence]))))
    (is (= :defer (result (assoc f/scenario :events [f/evidence conflict]))))
    (is (= :defer (result (assoc f/scenario :events
                                [f/evidence (-> failed
                                                (assoc-in [:payload :result] :pass)
                                                (assoc-in [:payload :recipe-digest] (model/digest "changed")))]))))
    (is (= :allow (result (assoc f/scenario :events [(assoc-in f/evidence [:payload :result] :fail)
                                                   (assoc-in failed [:payload :result] :pass)]))))))

(deftest dependency-gates
  (let [s (-> f/scenario
              (update-in [:contract :tasks] conj {:id "T-002" :spec "S-001" :depends-on #{"T-001"}
                                                :scope #{"src/"} :obligations #{"O-002"}})
              (update-in [:contract :obligations] conj {:id "O-002" :kind :test-suite :suite "synthetic-unit" :profile "jdk17"})
              (update :events conj (-> f/evidence (assoc :id "ev-002") (assoc-in [:payload :obligation] "O-002")))
              (assoc :task "T-002") f/rebind)]
    (is (= :allow (result s)))
    (is (= :defer (result (update s :events #(vec (rest %))))))
    (is (= :deny (result (assoc-in s [:events 0 :payload :result] :fail))))
    (is (invalid? (assoc-in s [:contract :tasks 0 :depends-on] #{"T-002"})))
    (is (= :dependency-unknown (get-in (nomos/evaluate (update s :events #(vec (rest %)))) [:rules 1 :reason])))))

(deftest path-safety
  (doseq [path ["../src/x" "/src/x" "src/../secret" "src//x" "src/./x" "src\\x" "C:/src/x" "src/x\n" "src/"]]
    (is (false? (boolean (contract/path? path)))))
  (is (contract/path? "src/中文.clj"))
  (let [renamed {:kind :rename :old-path "outside/x" :new-path "src/x" :old-mode "100644" :new-mode "100644"}]
    (is (= :deny (result (assoc-in f/scenario [:changes :files] [renamed])))))
  (is (= :allow (result (assoc-in f/scenario [:changes :files]
                                [{:kind :delete :old-path "src/x" :new-path nil :old-mode "100644" :new-mode nil}])))))

(deftest replay-and-generated-invariants
  (is (= (world/replay (:events f/scenario)) (world/replay (:events f/scenario))))
  (is (= (nomos/evaluate f/scenario) (nomos/evaluate f/scenario)))
  (doseq [i (range 100)]
    (let [claims (mapv #(assoc f/claim :id (str "claim-" %)) (range i))
          s (assoc f/scenario :events claims)]
      (is (= :defer (result s)))
      (is (= :allow (result (update s :events conj f/evidence))))
      (is (= :deny (result (update s :events conj (assoc-in f/evidence [:payload :result] :fail)))))
      (is (= (model/digest (into (sorted-map) (:candidate s)))
             (model/digest (into (array-map) (reverse (seq (:candidate s))))))))))

(deftest cli-results
  (is (= 4 (:exit (cli/run []))))
  (is (= 5 (:exit (cli/run ["evaluate" "--input" "does-not-exist.edn"])))))
