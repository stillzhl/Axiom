(ns axiom.cli
  (:require [axiom.contract :as contract]
            [axiom.ledger :as ledger]
            [axiom.model :as model]
            [axiom.nomos :as nomos]
            [axiom.store :as store]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CodingErrorAction StandardCharsets)
           (java.nio.file Files Paths)
           (java.util.concurrent TimeUnit)))

(defn- read-input [path]
  (with-open [stream (Files/newInputStream (Paths/get path (make-array String 0))
                                          (make-array java.nio.file.OpenOption 0))]
    (let [bytes (.readNBytes stream (inc contract/max-bytes))]
      (when (> (alength bytes) contract/max-bytes)
        (throw (ex-info "EDN byte limit exceeded" {:axiom/error :invalid})))
      (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                      (.onMalformedInput CodingErrorAction/REPORT)
                      (.onUnmappableCharacter CodingErrorAction/REPORT))]
        (try
          (contract/read-data (str (.decode decoder (ByteBuffer/wrap bytes))))
          (catch java.nio.charset.CharacterCodingException _
            (throw (ex-info "Invalid UTF-8 input" {:axiom/error :invalid}))))))))

(defn- usage []
  {:exit 4 :output {:error :usage
                    :message (str "axiom {validate|evaluate|status|next} --input SCENARIO.edn"
                                  " | axiom explain --input SCENARIO.edn [--decision DECISION_ID]"
                                  " | axiom replay --ledger LEDGER.db [--through SEQ]"
                                  " | axiom replay --bundle BUNDLE.edn"
                                  " | axiom export-bundle --ledger LEDGER.db --output BUNDLE.edn [--through SEQ]")}})

(def ^:private read-commands #{"validate" "evaluate" "status" "next"})

(defn- parse-through
  "Parses a --through sequence argument; nil when malformed."
  [s]
  (try (let [n (Long/parseLong ^String s)]
         (when (>= n 0) n))
       (catch NumberFormatException _ nil)))

(defn- resolve-through
  "Resolves the --through argument against the ledger head. Missing means
   the full ledger. Out-of-range values are invalid input."
  [through-s last-seq]
  (let [through (if (some? through-s)
                  (or (parse-through through-s)
                      (model/invalid! "Invalid --through sequence" {:value through-s}))
                  last-seq)]
    (when (or (< through -1) (> through last-seq))
      (model/invalid! "Through sequence out of range" {:through through :head last-seq}))
    through))

(defn- open-ledger!
  "Opens a ledger read-only: never creates, never migrates. A missing file
   is invalid input; a newer-than-supported schema is operational."
  [path]
  (store/open! path {:create false :migrate false}))

(defn- git-commit
  "Best-effort engine commit identity for export bundles; informational
   only, never load-bearing."
  []
  (try
    (let [proc (-> (ProcessBuilder. ^java.util.List ["git" "rev-parse" "HEAD"])
                   (.redirectErrorStream true)
                   (.start))
          finished (.waitFor proc 5 TimeUnit/SECONDS)
          out (str/trim (slurp (.getInputStream proc)))]
      (if (and finished (re-matches #"[0-9a-f]{40}" out)) out "unknown"))
    (catch Exception _ "unknown")))

(defn- replay-ledger! [path through-s]
  (let [handle (open-ledger! path)]
    (try
      (let [identity (store/ledger-identity handle)
            last-seq (dec (:event/count identity))
            through (resolve-through through-s last-seq)
            envelopes (if (>= through 0) (store/read-range handle 0 through) [])
            report (ledger/replay-report
                    {:source {:kind :ledger :path path}
                     :schema/version (:schema/version identity)
                     :envelopes envelopes
                     :snapshot (store/latest-snapshot handle)})]
        {:exit 0 :output report})
      (finally (store/close! handle)))))

(defn- read-bundle-file!
  [path]
  (try (read-input path)
       (catch java.io.IOException _
         (model/invalid! "Cannot read bundle file" {:path path}))))

(defn- replay-bundle! [path]
  (let [bundle (ledger/read-bundle-data (read-bundle-file! path))
        report (ledger/replay-report
                {:source {:kind :bundle :path path}
                 :schema/version (get-in bundle [:ledger :schema/version])
                 :envelopes (:events bundle)
                 :snapshot (:snapshot bundle)})]
    {:exit 0 :output report}))

(defn- export-bundle! [path output through-s]
  (let [handle (open-ledger! path)]
    (try
      (let [identity (store/ledger-identity handle)
            last-seq (dec (:event/count identity))
            through (resolve-through through-s last-seq)
            envelopes (if (>= through 0) (store/read-range handle 0 through) [])
            snapshot (store/latest-snapshot handle)
            usable (when (and snapshot (<= (:snapshot/seq snapshot) through)) snapshot)
            engine {:axiom/version model/engine-version
                    :axiom/commit (git-commit)
                    :clojure/version (clojure-version)
                    :reducer/version ledger/reducer-version}
            bundle (ledger/export-bundle-data
                    {:engine engine
                     :schema/version (:schema/version identity)
                     :envelopes envelopes
                     :snapshot usable})]
        (spit output (model/edn-str bundle))
        {:exit 0 :output {:exported? true :path output
                          :bundle/digest (:bundle/digest bundle)
                          :through/seq through
                          :event/count (count envelopes)}})
      (finally (store/close! handle)))))

(defn run [args]
  (try
    (let [[command & operands] args]
      (cond
        (contains? read-commands command)
        (if (and (= 2 (count operands)) (= "--input" (first operands)))
          (let [scenario (read-input (second operands))]
            (case command
              "validate" (do (contract/validate-scenario! scenario)
                             {:exit 0 :output {:valid? true :mode :offline-advisory}})
              "evaluate" (let [decision (nomos/evaluate scenario)]
                           {:exit ({:allow 0 :deny 2 :defer 3} (:result decision)) :output decision})
              "status" {:exit 0 :output (nomos/status-report scenario)}
              "next" {:exit 0 :output (nomos/next-report scenario)}))
          (usage))

        (= "explain" command)
        (let [[flag path flag2 id] operands]
          (if (and (= "--input" flag) (string? path)
                   (or (nil? flag2) (and (= "--decision" flag2) (string? id))))
            (let [scenario (read-input path)
                  decision (nomos/evaluate scenario)]
              {:exit 0 :output (nomos/explain-decision decision id)})
            (usage)))

        (= "replay" command)
        (let [[flag path flag2 through] operands]
          (if (and (= "--ledger" flag) (string? path)
                   (or (and (nil? flag2) (= 2 (count operands)))
                       (and (= "--through" flag2) (string? through) (= 4 (count operands)))))
            (replay-ledger! path through)
            (let [[bflag bpath] operands]
              (if (and (= "--bundle" bflag) (string? bpath) (= 2 (count operands)))
                (replay-bundle! bpath)
                (usage)))))

        (= "export-bundle" command)
        (let [[f1 v1 f2 v2 f3 v3] operands]
          (if (and (= "--ledger" f1) (string? v1)
                   (= "--output" f2) (string? v2)
                   (or (and (nil? f3) (= 4 (count operands)))
                       (and (= "--through" f3) (string? v3) (= 6 (count operands)))))
            (export-bundle! v1 v2 v3)
            (usage)))

        :else (usage)))
    (catch clojure.lang.ExceptionInfo e
      {:exit (if (= :invalid (:axiom/error (ex-data e))) 4 5)
       :output {:error (or (:axiom/error (ex-data e)) :operational) :message (.getMessage e)
                :details (dissoc (ex-data e) :axiom/error)}})
    (catch java.io.IOException _
      {:exit 5 :output {:error :operational :message "Cannot read input file"}})))

(defn -main [& args]
  (let [{:keys [exit output]} (run args)]
    (prn output)
    (shutdown-agents)
    (System/exit exit)))
