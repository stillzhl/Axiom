(ns axiom.adapters.git
  "The ONLY namespace that invokes the `git` executable (spec 0003 R8).

   Read-only commands only: `git --version`, `git rev-parse` (HEAD /
   branch / upstream / tree SHA / merge-base / toplevel), `git status
   --porcelain=v1 -z` (NUL-delimited, no locale dependence) and `git
   diff --raw -z -M <base> <rev>` plus `git cat-file -p` for symlink
   target bytes (read, never followed). No fetch, checkout, or any
   mutation. Every command line is recorded verbatim in the
   observation's provenance.

   All git output is untrusted text until validated: the observation is
   assembled with the pure `axiom.git` port and validated with
   `axiom.git/validate-observation!` before it is returned. A git
   command that exits nonzero or emits unparseable output yields an
   `:observation/incomplete` observation with the failing step named —
   never a silently partial diff."

  (:require [axiom.git :as git]
            [axiom.model :as model]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file Files LinkOption)
           (java.util.concurrent TimeUnit)))

(def ^:private command-timeout-seconds 30)

(defn- run-git
  "Runs one git command (argv is the full vector starting with
   \"git\"). Returns {:exit :out :err :command}; the command line is
   the argv joined with spaces, recorded verbatim for provenance.
   Never throws: spawn failures and timeouts are returned as
   {:exit -1} with a :reason."
  [argv]
  (let [command (str/join " " argv)]
    (try
      (let [proc (-> (ProcessBuilder. ^java.util.List argv)
                     (.redirectErrorStream false)
                     (.start))
            finished (.waitFor proc command-timeout-seconds TimeUnit/SECONDS)
            out (slurp (.getInputStream proc) :encoding "UTF-8")
            err (slurp (.getErrorStream proc) :encoding "UTF-8")
            exit (if finished (.exitValue proc) (do (.destroyForcibly proc) -1))]
        (cond-> {:exit exit :out out :err err :command command}
          (not finished) (assoc :reason :timeout)))
      (catch Exception e
        {:exit -1 :out "" :err (str e) :command command :reason :spawn-failed}))))

(defn current-commit-sha
  "Best-effort engine commit identity for export bundles; informational
   only, never load-bearing. (Moved here from `axiom.cli` so that only
   this namespace invokes git — spec 0003 R8. Behavior unchanged.)"
  []
  (try
    (let [proc (-> (ProcessBuilder. ^java.util.List ["git" "rev-parse" "HEAD"])
                   (.redirectErrorStream true)
                   (.start))
          finished (.waitFor proc 5 TimeUnit/SECONDS)
          out (str/trim (slurp (.getInputStream proc)))]
      (if (and finished (re-matches #"[0-9a-f]{40}" out)) out "unknown"))
    (catch Exception _ "unknown")))

;; ------------------------------------------------------------------
;; Parsing (untrusted text in, data out; failures become :err maps)

(defn- err
  "A named step failure, carried in ex-data and converted to an
   :observation/incomplete observation by observe!."
  [command exit reason]
  {:command command :exit exit :reason (name reason)})

(defn- step-failure!
  [command exit reason]
  (throw (ex-info "Git step failed" {:axiom/error :step-failed
                                     :err (err command exit reason)})))

(defn- split-nul
  "Splits NUL-delimited git output into tokens, dropping the single
   trailing empty token git emits."
  [out]
  (let [tokens (str/split out #"\u0000" -1)]
    (if (and (seq tokens) (= "" (peek tokens)))
      (pop tokens)
      tokens)))

(def ^:private raw-header-re
  #"^:([0-7]{6}) ([0-7]{6}) ([0-9a-f]{40}) ([0-9a-f]{40}) ([A-Z])[0-9]*$")

(defn- link-dir-of
  "Repo-relative directory containing path (\"\" for the repo root)."
  [path]
  (let [idx (str/last-index-of path "/")]
    (if idx (subs path 0 idx) "")))

(defn- symlink-target
  "Reads a symlink blob's target bytes without following the link.
   Returns the target string, or nil when the bytes are not usable as
   text (the entry is then classified :path/unsafe)."
  [cat-run blob-sha]
  (let [{:keys [exit out]} (cat-run "cat-file" "-p" blob-sha)]
    (when (zero? exit)
      (let [target (str/replace out #"\r?\n$" "")]
        (try (model/valid-text! target) target
             (catch clojure.lang.ExceptionInfo _ nil))))))

(defn- enrich-entry
  "Adds symlink-target / submodule-commit / unsafe classification to a
   raw-diff entry map. cat-run runs `git cat-file -p` (recorded in
   provenance by the caller)."
  [cat-run entry relevant-sha relevant-path]
  (let [path-kind (:path/kind entry)]
    (cond
      (= :symlink path-kind)
      (let [target (symlink-target cat-run relevant-sha)]
        (if (some? target)
          (merge entry (git/classify-symlink-target (link-dir-of relevant-path) target))
          (assoc entry :path/kind :unsafe
                 :path/target nil
                 :path/unsafe-reason :unresolvable-target)))

      (= :submodule path-kind)
      (assoc entry :path/commit relevant-sha)

      :else entry)))

(defn- parse-raw-diff
  "Parses `git diff --raw -z -M` output into change-entry maps.
   Throws a step failure naming the diff command on unparseable
   output; the caller reports :observation/incomplete."
  [cat-run diff-command diff-exit out]
  (let [fail #(step-failure! diff-command diff-exit %)]
    (loop [tokens (split-nul out) entries []]
      (if (empty? tokens)
        entries
        (let [header (first tokens)
              m (re-matches raw-header-re header)]
          (when-not m (fail :diff-unparseable))
          (let [[_ old-mode new-mode old-sha new-sha status] m
                kind (git/change-kind-from-status (first status))]
            (when-not kind (fail :diff-unknown-status))
            (let [renamed? (= :renamed kind)
                  need (if renamed? 3 2)]
              (when (< (count tokens) need) (fail :diff-truncated))
              (let [paths (subvec (vec tokens) 1 need)
                    rest-tokens (subvec (vec tokens) need)
                    [old-path new-path] (case kind
                                          :added [nil (first paths)]
                                          :deleted [(first paths) nil]
                                          :renamed [(first paths) (second paths)]
                                          [(first paths) (first paths)])]
                ;; --raw -z rename order is old-then-new (verified
                ;; against git 2.x); porcelain -z reverses it.
                (let [path-kind (if (= :deleted kind)
                                  (git/path-kind-from-mode old-mode)
                                  (git/path-kind-from-mode new-mode))
                      relevant-sha (if (= :deleted kind) old-sha new-sha)
                      relevant-path (or new-path old-path)
                      base {:change/kind kind
                            :path/kind path-kind
                            :change/old-path old-path
                            :change/new-path new-path
                            :change/old-mode old-mode
                            :change/new-mode new-mode
                            :path/target nil
                            :path/commit nil
                            :path/unsafe-reason nil}]
                  (recur rest-tokens
                         (conj entries (enrich-entry cat-run base relevant-sha relevant-path))))))))))))

;; ------------------------------------------------------------------
;; Worktree (porcelain) classification — never follows symlinks

(def ^:private nofollow
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- classify-worktree-path
  "Classifies a dirty worktree path without following symlinks or
   recursing into submodules. root is a java.nio Path."
  [root path]
  (let [base {:path path :path/target nil :path/unsafe-reason nil}
        p (.resolve root path)]
    (cond
      (Files/isSymbolicLink p)
      (let [target (try (str (Files/readSymbolicLink p))
                        (catch Exception _ nil))]
        (merge base (if (some? target)
                      (git/classify-symlink-target (link-dir-of path) target)
                      {:path/kind :unsafe :path/target nil
                       :path/unsafe-reason :unresolvable-target})))

      (and (Files/isDirectory p nofollow)
           (or (Files/exists (.resolve p ".git") nofollow)
               (Files/isDirectory (.resolve p ".git") nofollow)))
      (assoc base :path/kind :submodule)

      (Files/isDirectory p nofollow)
      (assoc base :path/kind :directory)

      :else
      (assoc base :path/kind :file))))

(defn- parse-porcelain
  "Parses `git status --porcelain=v1 -z` into {:clean? :dirty-files}
   (repo-relative path strings). For renames the -z order is
   new-then-old; the worktree holds the new path, which is what the
   dirty list records. Throws a step failure on unparseable output."
  [status-command status-exit out]
  (let [fail #(step-failure! status-command status-exit %)
        tokens (split-nul out)]
    (loop [remaining tokens paths []]
      (if (empty? remaining)
        {:clean? (empty? paths) :dirty-files (vec (distinct paths))}
        (let [token (first remaining)]
          (when (< (count token) 4)
            (fail :status-unparseable))
          (let [renamed? (contains? #{\R \C} (nth token 0))
                path (subs token 3)
                remaining (if renamed?
                            (do (when (< (count remaining) 2)
                                  (fail :status-truncated-rename))
                                (nnext remaining))
                            (rest remaining))
                path (if (str/ends-with? path "/")
                       (subs path 0 (dec (count path)))
                       path)]
            (recur remaining (conj paths path))))))))

;; ------------------------------------------------------------------
;; Observation assembly

(defn observe!
  "Read-only observation of a local git worktree.

   Arguments: {:repo path :base rev?} — :repo is required; :base is a
   revision (default: the merge-base of HEAD and the upstream when an
   upstream exists; when there is no upstream the caller must pass
   :base).

   Returns a validated observation map (via `axiom.git`), with
   :observation/status :complete, or :incomplete naming the failing
   step when a git command exits nonzero or emits unparseable output.

   Throws :invalid for missing repositories or a missing required base;
   throws :operational when a reported path escapes the worktree root
   (rejected, never normalized)."
  [{:keys [repo base]}]
  (when (or (not (string? repo)) (str/blank? repo))
    (model/invalid! "Missing repository path" {:repo repo}))
  (let [repo-file (io/file repo)]
    (when-not (.isDirectory repo-file)
      (model/invalid! "Repository path is not a directory" {:repo repo})))
  (let [repo-abs (.getAbsolutePath (io/file repo))
        commands (atom [])
        run (fn [& argv]
              (let [res (run-git (into ["git" "-C" repo-abs] argv))]
                (swap! commands conj (:command res))
                res))
        progress (atom {:repo/path repo-abs :git/base nil :git/head nil :git/tree nil
                        :git/branch nil :git/upstream nil
                        :git/clean? false :git/dirty-files [] :changes []
                        :git/version "unknown"})
        incomplete (fn [{:keys [command exit reason]}]
                     (git/build-observation
                      (assoc @progress
                             :observation/status :incomplete
                             :commands @commands
                             :observation/failing-step
                             {:command command :exit exit :reason reason})))]
    (try
      ;; 1. git version (provenance).
      (let [{:keys [exit out command]} (run "--version")
            version (str/trim out)]
        (if (and (zero? exit) (re-matches #"git version \S+" version))
          (swap! progress assoc :git/version version)
          (step-failure! command exit :version-step)))
      ;; 2. Worktree root (also proves this is a git repository).
      (let [{:keys [exit out command]} (run "rev-parse" "--show-toplevel")
            root (str/trim out)]
        (if (and (zero? exit) (not (str/blank? root)))
          (swap! progress assoc :repo/path root)
          (step-failure! command exit :toplevel-step)))
      ;; 3. Head commit SHA.
      (let [{:keys [exit out command]} (run "rev-parse" "HEAD")
            sha (str/trim out)]
        (if (and (zero? exit) (re-matches #"[0-9a-f]{40}" sha))
          (swap! progress assoc :git/head sha)
          (step-failure! command exit :head-step)))
      ;; 4. Branch and upstream (an absent upstream is not a failure).
      (let [{:keys [exit out]} (run "rev-parse" "--abbrev-ref" "HEAD")
            branch (str/trim out)]
        (when (and (zero? exit) (not (str/blank? branch)))
          (swap! progress assoc :git/branch branch)))
      (let [{:keys [exit out]} (run "rev-parse" "--abbrev-ref" "--symbolic-full-name" "@{u}")
            upstream (str/trim out)]
        (when (and (zero? exit) (not (str/blank? upstream)))
          (swap! progress assoc :git/upstream upstream)))
      ;; 5. Head tree SHA — the tree of the HEAD *commit*. A dirty
      ;;    worktree never gets a synthesized tree SHA (R9).
      (let [{:keys [exit out command]} (run "rev-parse" "HEAD^{tree}")
            sha (str/trim out)]
        (if (and (zero? exit) (re-matches #"[0-9a-f]{40}" sha))
          (swap! progress assoc :git/tree sha)
          (step-failure! command exit :tree-step)))
      ;; 6. Base revision: caller-supplied, else merge-base with upstream.
      (let [upstream (:git/upstream @progress)]
        (cond
          (some? base)
          (let [{:keys [exit out command]} (run "rev-parse" "--verify" (str base "^{commit}"))
                sha (str/trim out)]
            (if (and (zero? exit) (re-matches #"[0-9a-f]{40}" sha))
              (swap! progress assoc :git/base sha)
              (step-failure! command exit :base-step)))

          (some? upstream)
          (let [{:keys [exit out command]} (run "merge-base" "HEAD" "@{u}")
                sha (str/trim out)]
            (if (and (zero? exit) (re-matches #"[0-9a-f]{40}" sha))
              (swap! progress assoc :git/base sha)
              (step-failure! command exit :base-step)))

          :else
          (model/invalid! "No upstream and no :base given; pass an explicit :base revision"
                          {:repo repo})))
      ;; 7. Worktree status (clean vs dirty + dirty file list).
      (let [{:keys [exit out command]} (run "status" "--porcelain=v1" "-z")]
        (if (zero? exit)
          (let [root (.toPath (io/file (:repo/path @progress)))
                {:keys [clean? dirty-files]} (parse-porcelain command exit out)]
            (swap! progress assoc
                   :git/clean? clean?
                   :git/dirty-files (mapv #(classify-worktree-path root %) dirty-files)))
          (step-failure! command exit :status-step)))
      ;; 8. Change enumeration against base. --raw (not --name-status)
      ;;    carries modes, so symlinks/submodules stay typed (R2);
      ;;    rename detection stays on so renames keep old->new (R1);
      ;;    --no-abbrev keeps object SHAs at full length for parsing.
      (let [{:keys [git/base git/head]} @progress
            {:keys [exit out command]} (run "diff" "--raw" "-z" "-M" "--no-abbrev" base head)]
        (if (zero? exit)
          (let [cat-run (fn [& argv]
                          (let [res (run-git (into ["git" "-C" repo-abs] argv))]
                            (swap! commands conj (:command res))
                            res))
                entries (parse-raw-diff cat-run command exit out)]
            (swap! progress assoc :changes entries))
          (step-failure! command exit :diff-step)))
      ;; Complete observation (validated by the pure port).
      (git/build-observation (assoc @progress :observation/status :complete
                                    :commands @commands))
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (cond
            ;; Input/validation errors propagate unchanged.
            (contains? #{:invalid :operational} (:axiom/error data))
            (throw e)
            ;; Git step failures become incomplete observations.
            (= :step-failed (:axiom/error data))
            (incomplete (:err data))
            :else (throw e)))))))
