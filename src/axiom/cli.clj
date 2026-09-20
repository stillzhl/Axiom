(ns axiom.cli
  (:require [axiom.contract :as contract]
            [axiom.nomos :as nomos])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CodingErrorAction StandardCharsets)
           (java.nio.file Files Paths)))

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
                    :message "axiom {validate|evaluate|status|next} --input SCENARIO.edn | axiom explain --input SCENARIO.edn [--decision DECISION_ID]"}})

(def ^:private read-commands #{"validate" "evaluate" "status" "next"})

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
