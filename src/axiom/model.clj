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
   the strict reader to an equal value; used for durable storage.
   Namespace-map printing (`#:ns{...}`) is disabled: the strict reader
   rejects `#` dispatch forms, so the durable text must never contain
   them (uniformly-namespaced maps, e.g. an observation's :producer,
   would otherwise fail to round-trip)."
  [x]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-readably* true *print-dup* false
            *print-namespace-maps* false]
    (pr-str x)))

(defn- hex-bytes [^bytes bs]
  (apply str (map #(format "%02x" (bit-and 0xff %)) bs)))

(defn digest [x]
  (str "sha256:"
       (hex-bytes (.digest (MessageDigest/getInstance "SHA-256")
                           (.getBytes (canonical x) StandardCharsets/UTF_8)))))

(defn sha256-bytes
  "SHA-256 over exact raw bytes, returning \"sha256:<64hex>\". Artifact
   content identity: the bytes are hashed raw, NOT through the 0001
   canonical EDN encoding (that is what `digest` is for). Pure; the caller
   supplies the bytes — no I/O happens here."
  [bs]
  (when-not (bytes? bs)
    (invalid! "sha256-bytes requires a byte array" {:value-type (str (type bs))}))
  (str "sha256:" (hex-bytes (.digest (MessageDigest/getInstance "SHA-256") bs))))

(defn candidate-id [candidate]
  (digest candidate))
