(ns axiom.adapters.runner
  "The ONLY namespace that spawns processes (spec 0003 R8).

   Loads the checked-in command registry
   (`resources/axiom/run-registry.edn`; an explicit registry map may be
   passed instead, which is what tests do), spawns each command via
   ProcessBuilder with an argv vector — never a shell string —
   enforces the registry's timeout (kill on expiry) and output byte
   caps, supports cancellation, and builds the Evidence record with the
   pure `axiom.runner` port. Every record is validated before it is
   returned.

   Outcomes: :completed (exit code recorded, even non-zero — a failed
   diagnostic is still a completed run, reported honestly as
   :result :fail), :timed-out, :cancelled, :output-capped. Every
   outcome except a clean :completed within bounds is marked
   :run/complete? false / :run/result :incomplete in the record and can
   never satisfy an evidence obligation.

   Captured output is digested with `axiom.model/sha256-bytes`; the
   digest format matches artifact digests so a later slice can record
   the bytes as an artifact under the same digest.

   API:

     (run! {:registry registry-or-nil :command/id id :args {...}
            :candidate {...} [:run/id id] [:applicability note]})
       Runs one approved command synchronously and returns the
       Evidence record map. A nil :registry loads the checked-in
       registry. Throws :invalid for unknown command IDs or bad
       arguments, :operational when the process cannot be spawned.

     (start! request)
       Starts a run asynchronously. Returns {:run/id id :future fut
       :cancel! f :plan validated-plan}. Deref :future for the
       Evidence record (a failed run throws the cause through the
       usual future ExecutionException wrapper).

     (cancel! started)
       Requests cancellation of a run started with start!. Returns
       true when the run was still active, false when it had already
       settled. Cancellation is prompt (the wait loop polls every
       50 ms) but best-effort at the race: a process that exits just
       as cancellation is requested is reported :cancelled."

  (:refer-clojure :exclude [run!])
  (:require [axiom.contract :as contract]
            [axiom.model :as model]
            [axiom.runner :as runner]
            [clojure.java.io :as io])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.util UUID)
           (java.util.concurrent ExecutionException TimeUnit)))

(def registry-resource "axiom/run-registry.edn")

(defn- operational! [message data cause]
  (throw (ex-info message (assoc data :axiom/error :operational) cause)))

(defn load-registry!
  "Loads the checked-in command registry resource and validates it
   with the pure port. Throws :operational when the resource is
   missing or unreadable, :invalid when its contents fail validation."
  []
  (let [url (io/resource registry-resource)]
    (when-not url
      (operational! "Checked-in run registry not found on the classpath"
                    {:resource registry-resource} nil))
    (try
      (runner/validate-registry! (contract/read-data (slurp url)))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (operational! "Checked-in run registry is unreadable"
                      {:resource registry-resource} e)))))

(defn- prepare-plan!
  "Resolves :registry (nil selects the checked-in registry), fills a
   random :run/id when the caller did not supply one, and validates
   the request with the pure port. Throws :invalid synchronously."
  [{:keys [registry] :as request}]
  (let [reg (or registry (load-registry!))
        request' (cond-> (dissoc request :registry)
                   (nil? (:run/id request)) (assoc :run/id (str (UUID/randomUUID)))
                   ;; The pure port requires every request key present;
                   ;; a missing applicability selects the registry default.
                   (not (contains? request :applicability)) (assoc :applicability nil))]
    (runner/validate-run-request! reg request')))

;; ------------------------------------------------------------------
;; Process execution

(defn- spawn!
  "Starts the process for argv (a vector of strings, executable
   first) in workdir. Throws :operational when the executable cannot
   be started."
  [argv workdir]
  (try
    (let [pb (ProcessBuilder. ^java.util.List argv)]
      (.directory pb (io/file ^String workdir))
      (.redirectErrorStream pb false)
      (.start pb))
    (catch Exception e
      (operational! "Process spawn failed" {:workdir workdir} e))))

(defn- read-capped
  "Reads in up to cap bytes, then reads one byte past the cap to
   detect overflow. Returns {:bytes <byte[]> :capped? bool}. Stream
   errors are treated as end-of-stream: the exit code and outcome
   still tell the story."
  [^InputStream in cap]
  (let [baos (ByteArrayOutputStream.)
        buf (byte-array 8192)]
    (try
      (loop [total 0]
        (let [room (- ^long cap total)]
          (if (<= room 0)
            (let [n (.read in)]
              {:bytes (.toByteArray baos) :capped? (not (neg? n))})
            (let [n (.read in buf 0 (int (min (alength buf) room)))]
              (if (neg? n)
                {:bytes (.toByteArray baos) :capped? false}
                (do (.write baos buf 0 ^int n)
                    (recur (+ total n))))))))
      (catch Exception _
        {:bytes (.toByteArray baos) :capped? false}))))

(defn- wait-bounded
  "Waits for proc until it exits, the deadline passes, or cancel? is
   true. Returns :exited, :timed-out or :cancelled. Polls every 50 ms
   so cancellation is prompt."
  [proc deadline-ms cancel?]
  (try
    (loop []
      (cond
        (cancel?) :cancelled
        (not (.isAlive proc)) :exited
        (< (System/currentTimeMillis) deadline-ms) (do (Thread/sleep 50) (recur))
        :else :timed-out))
    (catch InterruptedException _
      :cancelled)))

(defn- execute!
  "Spawns the plan's argv and enforces timeout/cancellation/output
   caps. Returns the execution map consumed by
   `axiom.runner/build-run-record`."
  [plan cancelled?]
  (let [command (:command plan)
        timeout-seconds (:command/timeout-seconds command)
        stdout-cap (:command/stdout-cap-bytes command)
        stderr-cap (:command/stderr-cap-bytes command)
        proc (spawn! (:argv plan) (:command/workdir command))
        deadline-ms (+ (System/currentTimeMillis) (* 1000 ^long timeout-seconds))
        stdout-fut (future (read-capped (.getInputStream proc) stdout-cap))
        stderr-fut (future (read-capped (.getErrorStream proc) stderr-cap))
        waited (wait-bounded proc deadline-ms #(deref cancelled?))]
    ;; On timeout or cancellation the process is killed; on a natural
    ;; exit there is nothing to kill. Either way the streams close and
    ;; the pump futures settle.
    (when (not= waited :exited)
      (.destroyForcibly proc)
      (.waitFor proc 5 TimeUnit/SECONDS))
    (let [sout (deref stdout-fut 10000 ::pump-stuck)
          serr (deref stderr-fut 10000 ::pump-stuck)]
      (when (or (= ::pump-stuck sout) (= ::pump-stuck serr))
        (operational! "Output pumps did not settle after the process ended"
                      {:run/id (:run/id plan)} nil))
      (let [outcome (cond
                      (= waited :cancelled) :cancelled
                      (or (:capped? sout) (:capped? serr)) :output-capped
                      (= waited :timed-out) :timed-out
                      :else :completed)
            bound-exceeded (case outcome
                             :timed-out :timeout-seconds
                             :output-capped (if (:capped? sout) :stdout-cap-bytes :stderr-cap-bytes)
                             nil)]
        ;; The exit code is recorded exactly for :completed outcomes;
        ;; every other outcome carries nil (the bound or the
        ;; cancellation is the story, not an exit status).
        {:outcome outcome
         :exit (when (= outcome :completed)
                 (try (.exitValue proc) (catch Exception _ nil)))
         :stdout-bytes (:bytes sout)
         :stderr-bytes (:bytes serr)
         :bound-exceeded bound-exceeded}))))

;; ------------------------------------------------------------------
;; Public API

(defn start!
  "Starts a run asynchronously; request is the `run!` request map.
   Returns {:run/id id :future fut :cancel! f :plan validated-plan}.
   Request validation (:invalid) and registry loading happen
   synchronously, before the future is created."
  [request]
  (let [plan (prepare-plan! request)
        cancelled? (atom false)
        settled? (atom false)
        fut (future
              (try
                (let [record (runner/build-run-record plan (execute! plan cancelled?))]
                  (reset! settled? true)
                  record)
                (catch Throwable t
                  (reset! settled? true)
                  (throw t))))]
    {:run/id (:run/id plan)
     :future fut
     :plan plan
     :cancel! (fn []
                (boolean (and (not @settled?)
                              (compare-and-set! cancelled? false true))))}))

(defn cancel!
  "Requests cancellation of a run started with `start!`. Returns true
   when the run was still active, false when it had already settled."
  [started]
  (boolean ((:cancel! started))))

(defn run!
  "Runs one approved command synchronously and returns the validated
   Evidence record map. request keys:

     :registry      validated registry map, or nil to use the
                    checked-in resources/axiom/run-registry.edn
                    (tests pass an explicit registry map)
     :command/id    approved command ID
     :args          map of string slot-name -> value: exactly the
                    declared slots
     :candidate     {:candidate/base :candidate/head :candidate/tree}
                    (40-hex SHAs or nil)
     :run/id        optional run identity (a random UUID when absent)
     :applicability optional override of the registry's applicability note

   Throws :invalid for unknown command IDs or bad arguments,
   :operational when the process cannot be spawned."
  [request]
  (let [{:keys [future plan]} (start! request)
        ;; The adapter always settles a run within its timeout plus a
        ;; kill/drain grace period; this outer bound is defensive only.
        bound-ms (+ (* 1000 ^long (:command/timeout-seconds (:command plan))) 30000)]
    (try
      (let [record (deref future bound-ms ::unsettled)]
        (if (= ::unsettled record)
          (operational! "Runner did not settle within its bound"
                        {:run/id (:run/id plan)} nil)
          record))
      (catch ExecutionException e
        (throw (.getCause e))))))
