(ns axiom.model
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest)))

(def engine-version "0.1.0-offline")

(defn invalid! [message data]
  (throw (ex-info message (assoc data :axiom/error :invalid))))

(defn valid-text! [s]
  (when-not (.canEncode (.newEncoder StandardCharsets/UTF_8) ^CharSequence s)
    (invalid! "Unpaired Unicode surrogate" {}))
  s)

(defn- encoded [x]
  (cond
    (nil? x) [:nil]
    (boolean? x) [:boolean x]
    (and (integer? x) (<= Long/MIN_VALUE x Long/MAX_VALUE)) [:integer (str x)]
    (string? x) [:string (valid-text! x)]
    (keyword? x) [:keyword (some-> (namespace x) valid-text!) (valid-text! (name x))]
    (vector? x) [:vector (mapv encoded x)]
    (set? x) [:set (vec (sort-by pr-str (map encoded x)))]
    (map? x) [:map (vec (sort-by (comp pr-str first)
                               (map (fn [[k v]] [(encoded k) (encoded v)]) x)))]
    :else (invalid! "Unsupported canonical value" {:value-type (str (type x))})))

(defn canonical [x]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-readably* true *print-dup* false]
    (pr-str [:axiom/canonical-v1 (encoded x)])))

(defn edn-str
  "Readable EDN text for a restricted-vocabulary value. Round-trips through
   the strict reader to an equal value; used for durable storage."
  [x]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-readably* true *print-dup* false]
    (pr-str x)))

(defn digest [x]
  (str "sha256:"
       (apply str (map #(format "%02x" (bit-and 0xff %))
                       (.digest (MessageDigest/getInstance "SHA-256")
                                (.getBytes (canonical x) StandardCharsets/UTF_8))))))

(defn candidate-id [candidate]
  (digest candidate))
