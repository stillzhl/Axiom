(ns axiom.adapters.worktree
  "Isolated worktree lifecycle for spec 0006 T5 (R3).

   Worker execution happens only in an isolated worktree — a fresh
   checkout of the pinned base, structurally confined so the
   worktree root is the only writable area, and never shared
   between tasks. The worker is untrusted code: everything it
   produces flows through the proposal and capability path (R5).

   The worktree identity is deterministic:
   `wt-<16 hex of SHA-256(task-id \"/\" base-identity)>` — two tasks
   never share a worktree identity, and re-creating for the same
   (task, base) is idempotent. The pinned base identity (e.g. a
   commit SHA) is recorded on the worktree map; populating the
   checkout from the object store is the caller's job — for tests,
   `:worktree/seed` names a directory to copy in.

   All functions return `{:worktree/ok true ...}` or
   `{:worktree/ok false, :worktree/reason <named>}` maps; they never
   throw for malformed input or confinement violations. Only the
   worktree root is ever written; path resolution is lexical and
   structural (no symlink following at resolve time — symlinks are
   analyzed on the worktree diff at patch admission, T6).

   API:

     (create-worktree {:worktree/task-id id
                       :worktree/base-identity base
                       :worktree/root dir
                       [:worktree/seed dir]})
       Creates (or reuses) the isolated worktree. Returns the
       worktree map.

     (resolve-path worktree path)
       Lexically resolves `path` inside the worktree. Escapes
       (`..` above the root, absolute paths, blank paths) are
       refused with `:path-escape` — never resolved.

     (destroy-worktree worktree)
       Recursively deletes the worktree directory. Idempotent;
       only the worktree's own path is ever deleted."
  (:require [axiom.model :as model]
            [clojure.string :as str])
  (:import (java.nio.file Files Path Paths
                          StandardCopyOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)
           (java.nio.file FileVisitResult SimpleFileVisitor)))

(defn- non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn worktree-id
  "Deterministic worktree identity for (task-id, base-identity):
   `wt-<16 hex>`. Pure."
  [task-id base-identity]
  (str "wt-" (subs (model/digest (str task-id "/" base-identity)) 7 23)))

(defn- copy-tree!
  "Recursively copies `src` into `dst` (which must not exist)."
  [^Path src ^Path dst]
  (Files/walkFileTree
   src
   (proxy [SimpleFileVisitor] []
     (preVisitDirectory [dir _attrs]
       (Files/createDirectories (.resolve dst (.relativize src ^Path dir))
                                (into-array FileAttribute []))
       FileVisitResult/CONTINUE)
     (visitFile [file _attrs]
       (Files/copy ^Path file (.resolve dst (.relativize src ^Path file))
                   (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
       FileVisitResult/CONTINUE))))

(defn- delete-tree!
  "Recursively deletes `path`; no-op when absent."
  [^Path path]
  (when (Files/exists path (into-array java.nio.file.LinkOption []))
    (Files/walkFileTree
     path
     (proxy [SimpleFileVisitor] []
       (visitFile [file _attrs]
         (Files/delete ^Path file)
         FileVisitResult/CONTINUE)
       (postVisitDirectory [dir _exc]
         (Files/delete ^Path dir)
         FileVisitResult/CONTINUE)))))

(defn create-worktree
  "Creates (or reuses) the isolated worktree for the task.
   `:worktree/task-id`, `:worktree/base-identity` (the pinned base)
   and `:worktree/root` (the parent directory) are required;
   `:worktree/seed` optionally names a directory whose contents are
   copied in (the test seam for the pinned checkout).

   Returns `{:worktree/ok true, :worktree/id, :worktree/path,
   :worktree/task-id, :worktree/base-identity, :worktree/root}`.
   Malformed input yields `:malformed`; an unreadable seed yields
   `:seed-unavailable`. Re-creating for the same (task, base)
   returns the existing worktree — idempotent."
  [{:worktree/keys [task-id base-identity root seed] :as _inputs}]
  (cond
    (not (and (non-blank-string? task-id)
              (non-blank-string? base-identity)
              (non-blank-string? root)))
    {:worktree/ok false :worktree/reason :malformed}

    (and seed (not (Files/isDirectory (Paths/get ^String seed (into-array String []))
                                      (into-array java.nio.file.LinkOption []))))
    {:worktree/ok false :worktree/reason :seed-unavailable}

    :else
    (let [id (worktree-id task-id base-identity)
          path (.resolve (Paths/get ^String root (into-array String [])) ^String id)]
      (when-not (Files/exists path (into-array java.nio.file.LinkOption []))
        (Files/createDirectories path (into-array java.nio.file.attribute.FileAttribute []))
        (when seed
          (copy-tree! (Paths/get ^String seed (into-array String [])) path)))
      {:worktree/ok true
       :worktree/id id
       :worktree/path (str path)
       :worktree/task-id task-id
       :worktree/base-identity base-identity
       :worktree/root root})))

(defn resolve-path
  "Lexically resolves `path` inside the worktree's root. The
   worktree root is the only resolvable area: `..` above the root,
   absolute paths and blank paths are refused with `:path-escape`
   and never resolved. Returns `{:worktree/ok true,
   :worktree/path <absolute>}`. Pure apart from no I/O at all —
   this is string-level confinement; existence is not checked."
  [{:worktree/keys [path] :as _worktree} rel-path]
  (cond
    (not (non-blank-string? path))
    {:worktree/ok false :worktree/reason :malformed}

    (not (string? rel-path))
    {:worktree/ok false :worktree/reason :malformed}

    (or (str/blank? rel-path) (str/starts-with? rel-path "/"))
    {:worktree/ok false :worktree/reason :path-escape}

    :else
    (let [segments (str/split rel-path #"/")
          resolved (reduce (fn [acc seg]
                             (cond
                               (or (= seg "") (= seg ".")) acc
                               (= seg "..") (if (seq acc) (pop acc) (reduced ::escape))
                               :else (conj acc seg)))
                           []
                           segments)]
      (if (= resolved ::escape)
        {:worktree/ok false :worktree/reason :path-escape}
        {:worktree/ok true
         :worktree/path (str path "/" (str/join "/" resolved))}))))

(defn destroy-worktree
  "Recursively deletes the worktree directory. Idempotent — a
   missing directory is still `:worktree/ok true`. Only the
   worktree's own path (`root/id`) is ever deleted; anything else
   yields `:malformed` and deletes nothing."
  [{:worktree/keys [id path root] :as _worktree}]
  (if-not (and (non-blank-string? id)
               (non-blank-string? path)
               (non-blank-string? root)
               (= path (str (.resolve (Paths/get ^String root (into-array String []))
                                      ^String id))))
    {:worktree/ok false :worktree/reason :malformed}
    (do (delete-tree! (Paths/get ^String path (into-array String [])))
        {:worktree/ok true
         :worktree/id id})))
