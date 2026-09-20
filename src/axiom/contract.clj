(ns axiom.contract
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [axiom.model :as model])
  (:import (java.io PushbackReader StringReader)))

(def max-bytes 262144)
(def max-depth 32)
(def max-nodes 20000)

(defn- ensure! [condition message data]
  (when-not condition (model/invalid! message data)))

(defn- preflight! [text]
  (ensure! (<= (alength (.getBytes ^String text "UTF-8")) max-bytes)
           "EDN byte limit exceeded" {})
  (loop [chars (seq text) depth 0 quoted? false escaped? false comment? false]
    (when-let [c (first chars)]
      (cond
        comment? (recur (next chars) depth false false (not (= c \newline)))
        quoted? (cond
                  escaped? (recur (next chars) depth true false false)
                  (= c \\) (recur (next chars) depth true true false)
                  (= c \") (recur (next chars) depth false false false)
                  :else (recur (next chars) depth true false false))
        (= c \;) (recur (next chars) depth false false true)
        (= c \") (recur (next chars) depth true false false)
        (= c \\) (model/invalid! "Character literals are unsupported" {})
        (= c \#) (do (ensure! (= (second chars) \{) "EDN tags and dispatch forms are unsupported" {})
                      (recur (next chars) depth false false false))
        (#{\[ \{ \(} c) (do (ensure! (< depth max-depth) "EDN nesting limit exceeded" {})
                              (recur (next chars) (inc depth) false false false))
        (#{\] \} \)} c) (recur (next chars) (dec depth) false false false)
        :else (recur (next chars) depth false false false)))))

(defn check-value!
  "Also bounds callers that bypass the text reader."
  [value]
  (loop [pending [[value 0]] count 0]
    (when-let [[x depth] (peek pending)]
      (ensure! (< count max-nodes) "EDN node limit exceeded" {})
      (ensure! (<= depth max-depth) "EDN nesting limit exceeded" {})
      (ensure! (or (nil? x) (boolean? x) (string? x) (keyword? x)
                   (and (integer? x) (<= Long/MIN_VALUE x Long/MAX_VALUE))
                   (vector? x) (set? x) (map? x)) "Unsupported EDN type" {})
      (when (string? x) (model/valid-text! x))
      (when (keyword? x)
        (model/valid-text! (name x))
        (when-let [n (namespace x)] (model/valid-text! n)))
      (let [children (cond (map? x) (mapcat identity x)
                           (coll? x) x
                           :else [])]
        (recur (into (pop pending) (map #(vector % (inc depth)) children)) (inc count)))))
  value)

(defn read-data [text]
  (preflight! text)
  (try
    (with-open [reader (PushbackReader. (StringReader. text))]
      (let [eof (Object.)
            options {:eof eof :readers {} :default (fn [& _] (model/invalid! "Tagged value" {}))}
            value (edn/read options reader)]
        (ensure! (not (identical? eof value)) "Empty EDN input" {})
        (ensure! (identical? eof (edn/read options reader)) "Trailing EDN value" {})
        (check-value! value)))
    (catch RuntimeException e
      (if (:axiom/error (ex-data e)) (throw e)
          (model/invalid! "Invalid EDN syntax" {})))))

(defn- shape! [value fields context]
  (ensure! (map? value) "Expected a map" {:context context})
  (ensure! (= (set (keys value)) fields) "Missing or unknown fields"
           {:context context :expected (sort fields) :actual (sort-by str (keys value))}))

(defn- id? [value] (and (string? value) (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]{0,127}" value))))
(defn- ids? [value] (and (set? value) (every? id? value)))
(defn- digest? [value] (and (string? value) (boolean (re-matches #"sha256:[0-9a-f]{64}" value))))
(defn- sha? [value] (and (string? value) (boolean (re-matches #"[0-9a-f]{40}|[0-9a-f]{64}" value))))
(defn- time? [value] (and (integer? value) (<= 0 value Long/MAX_VALUE)))

(defn path? [path]
  (and (string? path) (not (str/blank? path))
       (not (re-find #"[\\\p{Cntrl}:]" path))
       (every? #(not (#{"" "." ".."} %)) (str/split path #"/" -1))))

(defn- prefix? [path]
  (and (string? path) (str/ends-with? path "/") (path? (subs path 0 (dec (count path))))))

(defn- records! [records fields context]
  (ensure! (vector? records) "Expected a vector" {:context context})
  (doseq [record records]
    (shape! record fields context)
    (ensure! (id? (:id record)) "Invalid ID" {:context context}))
  (ensure! (= (count records) (count (set (map :id records)))) "Duplicate IDs" {:context context}))

(defn validate-contract! [contract]
  (check-value! contract)
  (shape! contract #{:schema/version :project/id :specs :tasks :obligations} :contract)
  (ensure! (= 1 (:schema/version contract)) "Unsupported contract version" {})
  (ensure! (id? (:project/id contract)) "Invalid project ID" {})
  (records! (:specs contract) #{:id :revision :state :approval} :specs)
  (records! (:tasks contract) #{:id :spec :depends-on :scope :obligations} :tasks)
  (records! (:obligations contract) #{:id :kind :suite :profile} :obligations)
  (let [spec-ids (set (map :id (:specs contract)))
        task-map (into {} (map (juxt :id identity) (:tasks contract)))
        obligation-ids (set (map :id (:obligations contract)))]
    (doseq [spec (:specs contract)]
      (ensure! (and (digest? (:revision spec))
                    (#{:draft :accepted :verified :retired} (:state spec))
                    (or (nil? (:approval spec)) (id? (:approval spec)))) "Invalid spec" {:id (:id spec)}))
    (doseq [obligation (:obligations contract)]
      (ensure! (and (= :test-suite (:kind obligation)) (id? (:suite obligation))
                    (id? (:profile obligation))) "Unsupported obligation" {:id (:id obligation)}))
    (ensure! (seq (:tasks contract)) "Contract must declare tasks" {})
    (doseq [task (:tasks contract)]
      (ensure! (contains? spec-ids (:spec task)) "Unresolved spec" {:id (:id task)})
      (ensure! (and (ids? (:depends-on task))
                    (set/subset? (:depends-on task) (set (keys task-map)))) "Unresolved dependency" {:id (:id task)})
      (ensure! (and (ids? (:obligations task)) (seq (:obligations task))
                    (set/subset? (:obligations task) obligation-ids)) "Empty or unresolved obligation gate" {:id (:id task)})
      (ensure! (and (set? (:scope task)) (seq (:scope task)) (every? prefix? (:scope task))) "Invalid scope prefixes" {:id (:id task)}))
    ;; Kahn elimination avoids recursion on a maliciously deep graph.
    (loop [remaining (into {} (map (fn [[id task]] [id (:depends-on task)]) task-map))]
      (when (seq remaining)
        (let [ready (set (for [[id deps] remaining :when (empty? deps)] id))]
          (ensure! (seq ready) "Dependency cycle" {})
          (recur (into {} (for [[id deps] remaining :when (not (ready id))]
                           [id (set/difference deps ready)])))))))
  contract)

(def candidate-fields #{:repository :base :head :tested-commit :tested-tree
                        :contract-digest :policy-digest :recipe-digest})

(defn- candidate! [candidate]
  (shape! candidate candidate-fields :candidate)
  (ensure! (id? (:repository candidate)) "Invalid candidate repository" {})
  (doseq [field [:base :head :tested-commit :tested-tree]]
    (ensure! (sha? (get candidate field)) "Invalid Git identity" {:field field}))
  (doseq [field [:contract-digest :policy-digest :recipe-digest]]
    (ensure! (digest? (get candidate field)) "Invalid candidate digest" {:field field})))

(defn validate-scenario! [scenario]
  (check-value! scenario)
  (shape! scenario #{:schema/version :contract :policy :candidate :events :changes :task :now} :scenario)
  (ensure! (= 1 (:schema/version scenario)) "Unsupported scenario version" {})
  (let [{:keys [contract policy candidate events changes task now]} scenario
        validated (validate-contract! contract)
        obligations (set (map :id (:obligations validated)))]
    (shape! policy #{:schema/version :accepted-states :producers :recipe-digest :max-age-seconds} :policy)
    (ensure! (and (= 1 (:schema/version policy))
                  (set? (:accepted-states policy)) (seq (:accepted-states policy))
                  (set/subset? (:accepted-states policy) #{:accepted :verified})
                  (ids? (:producers policy)) (seq (:producers policy))
                  (digest? (:recipe-digest policy)) (time? (:max-age-seconds policy))
                  (pos? (:max-age-seconds policy))) "Invalid policy" {})
    (candidate! candidate)
    (ensure! (= (:project/id contract) (:repository candidate)) "Repository mismatch" {})
    (ensure! (= (:contract-digest candidate) (model/digest contract)) "Contract digest mismatch" {})
    (ensure! (= (:policy-digest candidate) (model/digest policy)) "Policy digest mismatch" {})
    (ensure! (= (:recipe-digest candidate) (:recipe-digest policy)) "Recipe digest mismatch" {})
    (ensure! (some #(= task (:id %)) (:tasks contract)) "Unknown task" {})
    (ensure! (time? now) "Invalid evaluation time" {})
    (shape! changes #{:complete? :files} :changes)
    (ensure! (and (boolean? (:complete? changes)) (vector? (:files changes))) "Invalid changes" {})
    (doseq [file (:files changes)]
      (shape! file #{:kind :old-path :new-path :old-mode :new-mode} :change)
      (ensure! (#{:add :modify :delete :rename} (:kind file)) "Unknown change kind" {})
      (doseq [[path mode] [[(:old-path file) (:old-mode file)] [(:new-path file) (:new-mode file)]]]
        (ensure! (or (and (nil? path) (nil? mode))
                     (and (path? path) (string? mode) (re-matches #"[0-7]{6}" mode))) "Invalid path or mode" {}))
      (ensure! (case (:kind file)
                 :add (and (nil? (:old-path file)) (:new-path file))
                 :delete (and (:old-path file) (nil? (:new-path file)))
                 :modify (and (:old-path file) (= (:old-path file) (:new-path file)))
                 :rename (and (:old-path file) (:new-path file)
                              (not= (:old-path file) (:new-path file)))) "Inconsistent change paths" {}))
    (records! events #{:id :type :payload} :events)
    (doseq [{:keys [type payload]} events]
      (case type
        :claim (do (shape! payload #{:actor :statement} :claim)
                   (ensure! (and (id? (:actor payload)) (string? (:statement payload))) "Invalid claim" {}))
        :evidence (do
                    (shape! payload #{:candidate :obligation :producer :recipe-digest :suite :profile
                                      :attempt :result :observed-at} :evidence)
                    (candidate! (:candidate payload))
                    (ensure! (and (contains? obligations (:obligation payload)) (id? (:producer payload))
                                  (digest? (:recipe-digest payload)) (id? (:suite payload)) (id? (:profile payload))
                                  (time? (:attempt payload)) (pos? (:attempt payload))
                                  (#{:pass :fail :skipped :cancelled} (:result payload))
                                  (time? (:observed-at payload))) "Invalid evidence" {}))
        (model/invalid! "Unknown event type" {:type type}))))
  scenario)
