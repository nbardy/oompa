(ns agentnet.cambium
  "Cambium lifecycle shared by iteration workers and the legacy pool.
   Creation never replaces an existing workspace. Removal fails closed."
  (:require [babashka.process :as process]
            [agentnet.bounded-process :as bounded]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:dynamic *deadline-ms* nil)
(defn- run!
  ([argv opts] (run! argv opts 30000))
  ([argv opts operation-limit-ms]
   (if *deadline-ms*
     (let [remaining (- *deadline-ms* (System/currentTimeMillis))]
       (if (pos? remaining)
         (bounded/run! argv opts (min operation-limit-ms remaining))
         {:exit 124 :out "" :err "Run deadline exhausted before Cambium command"
          :timed-out? true :duration-ms 0}))
     (process/sh argv opts))))
(defn- git [root & args]
  (run! (into ["git"] args) {:dir root :out :string :err :string}))

(defn project-root []
  (let [result (git "." "rev-parse" "--show-toplevel")]
    (when-not (zero? (:exit result))
      (throw (ex-info "Cannot resolve project root" result)))
    (str/trim (:out result))))

(defn workspace-path
  "Keep workspaces beside, never inside, the canonical project checkout."
  [root workspace-name]
  (let [project (.getCanonicalFile (io/file root))]
    (.getPath (io/file (.getParentFile project) ".oompa-worktrees"
                            (str (.getName project) "-" (Integer/toUnsignedString (.hashCode (.getPath project)) 16))
                            workspace-name))))

(defn- owner-file [root workspace-name]
  (io/file (str (workspace-path root workspace-name) ".owner.json")))

(defn- mark-owner! [root workspace-name]
  (spit (owner-file root workspace-name)
        (json/generate-string {:pid (.pid (java.lang.ProcessHandle/current))})))

(defn- active-owner? [root workspace-name]
  (let [file (owner-file root workspace-name)]
    (when (.exists file)
      (try
        (let [pid (:pid (json/parse-string (slurp file) true))
              owner (java.lang.ProcessHandle/of (long pid))]
          (and (not= pid (.pid (java.lang.ProcessHandle/current)))
               (.isPresent owner) (.isAlive (.get owner))))
        (catch Exception _ true)))))

(defn list-workspaces
  "Discover registered ephemeral Oompa workspaces belonging to this checkout."
  [root]
  (let [result (git root "cambium" "list" "-json")
        canonical (.getCanonicalPath (io/file root))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Cambium workspace discovery failed" result)))
    (->> (json/parse-string (:out result) true)
         (filter #(and (:ephemeral %) (str/starts-with? (:name %) "oompa-")
                       (= canonical (.getCanonicalPath (io/file (:repository_root %))))))
         (mapv #(assoc % :dir (:name %) :project-root root
                        :active? (boolean (active-owner? root (:name %))))))))

(defn claim-existing!
  "Record a salvage runner as owner before resuming an inactive workspace."
  [root workspace]
  (when (active-owner? root (:name workspace))
    (throw (ex-info "Workspace still has an active owner" {:workspace workspace})))
  (mark-owner! root (:name workspace))
  workspace)

(defn create!
  ([root label branch-prefix] (create! root label branch-prefix nil))
  ([root label branch-prefix start-point]
  (let [resolved (git root "rev-parse" "--verify" (str (or start-point "HEAD") "^{commit}"))
        source (str/trim (:out resolved))
        _ (when-not (and (zero? (:exit resolved)) (re-matches #"[a-f0-9]{40,64}" source))
            (throw (ex-info "Cannot resolve exact Cambium starting source" {:result resolved})))
        workspace-name (str "oompa-" (str/replace label #"[^A-Za-z0-9_.-]" "-")
                            "-" (java.util.UUID/randomUUID))
        path (workspace-path root workspace-name)
        branch (str branch-prefix workspace-name)
        _ (.mkdirs (.getParentFile (io/file path)))
        _ (mark-owner! root workspace-name)
        ;; Creation materializes tracked files and clones dependency layers; it
        ;; is not a small Git query. Keep the original absolute run deadline.
        result (run! ["git" "cambium" "add" "-b" branch "-name" workspace-name "-ephemeral" path source]
                     {:dir root :out :string :err :string} 600000)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Cambium creation failed; workspace retained if partially created: "
                           path " (" (str/trim (str (:err result))) ")")
                      {:dir workspace-name :branch branch :path path :source_commit source :result result})))
    (let [created (git path "rev-parse" "HEAD")]
      (when-not (and (zero? (:exit created)) (= source (str/trim (:out created))))
        (throw (ex-info "Cambium created unexpected source; workspace retained"
                        {:dir workspace-name :branch branch :path path :source_commit source :result created}))))
    {:dir workspace-name :name workspace-name :branch branch :path path :project-root root
     :source_commit source})))

(def harvested-output-dir
  "Ignored run output that must outlive the workspace (wave_sim convention)."
  "output")

(defn- same-bytes? [^java.io.File a ^java.io.File b]
  (and (= (.length a) (.length b))
       (= -1 (java.nio.file.Files/mismatch (.toPath a) (.toPath b)))))

(defn harvest-ignored-output!
  "Move git-ignored files under the workspace's output/ into the primary
   checkout's output/ at the same relative path, so cited paths stay valid.

   Why: `git status --porcelain` omits ignored files, so a workspace whose only
   new files were ignored run output looked clean and `cambium remove` deleted
   them. Swarm 455987ea lost the raw gauges of a paid WC1 GPU run this way
   (2026-10-24). Identical files are deduplicated; a destination with
   different bytes is a conflict and nothing is moved, so the caller keeps
   the workspace. Returns {:moved n :deduplicated n :conflicts [...]} or
   {:error result} when git cannot list the files."
  [path]
  (let [listed (git path "ls-files" "-z" "--others" "--ignored" "--exclude-standard"
                    "--" harvested-output-dir)
        relatives (remove str/blank? (str/split (:out listed) #"\u0000"))
        common (when (seq relatives)
                 (git path "rev-parse" "--path-format=absolute" "--git-common-dir"))]
    (cond
      (not (zero? (:exit listed))) {:error listed}
      (empty? relatives) {:moved 0 :deduplicated 0 :conflicts []}
      (not (zero? (:exit common))) {:error common}
      :else
      (let [primary (.getCanonicalFile (.getParentFile (io/file (str/trim (:out common)))))
            pairs (for [relative relatives]
                    [relative (io/file path relative) (io/file primary relative)])
            files (filter (fn [[_ ^java.io.File from]] (.isFile from)) pairs)
            conflicts (vec (for [[relative from ^java.io.File to] files
                                 :when (and (.exists to) (not (same-bytes? from to)))]
                             relative))]
        (if (or (seq conflicts)
                (= primary (.getCanonicalFile (io/file path))))
          {:moved 0 :deduplicated 0 :conflicts conflicts}
          (reduce (fn [summary [_ ^java.io.File from ^java.io.File to]]
                    (if (.exists to)
                      (do (io/delete-file from) (update summary :deduplicated inc))
                      (do (io/make-parents to)
                          (java.nio.file.Files/move (.toPath from) (.toPath to)
                                                    (into-array java.nio.file.CopyOption
                                                                [java.nio.file.LinkOption/NOFOLLOW_LINKS]))
                          (update summary :moved inc))))
                  {:moved 0 :deduplicated 0 :conflicts []}
                  files))))))

(defn remove!
  "Remove only a clean, inactive Cambium workspace whose branch is integrated.
   Keep the branch (Cambium's default), even after successful removal."
  [root {:keys [path branch name dir current-task] :as workspace}]
  (let [workspace-name (or name dir)
        expected (when workspace-name (workspace-path root workspace-name))
        managed? (and expected path
                      (= (.getCanonicalPath (io/file expected))
                         (.getCanonicalPath (io/file path))))
        reason (cond
                 current-task :active
                 (not managed?) :unmanaged
                 (active-owner? root workspace-name) :active-owner
                 :else
                 (let [status (git path "status" "--porcelain")
                       merged (git root "merge-base" "--is-ancestor" branch "HEAD")
                       checked-out (git path "symbolic-ref" "--short" "HEAD")
                       operation? (some (fn [marker]
                                          (let [result (git path "rev-parse" "--git-path" marker)]
                                            (or (not (zero? (:exit result)))
                                                (let [f (io/file (str/trim (:out result)))]
                                                  (.exists (if (.isAbsolute f) f (io/file path (.getPath f))))))))
                                        ["MERGE_HEAD" "rebase-merge" "rebase-apply" "CHERRY_PICK_HEAD" "REVERT_HEAD"])]
                   (cond
                     (not (zero? (:exit status))) :unreadable
                     (not (str/blank? (:out status))) :dirty
                     (or (not (zero? (:exit checked-out)))
                         (not= branch (str/trim (:out checked-out)))) :branch-changed
                     operation? :in-progress
                     (not (zero? (:exit merged))) :unmerged
                     :else
                     (let [harvest (harvest-ignored-output! path)]
                       (cond
                         (:error harvest) :output-unreadable
                         (seq (:conflicts harvest)) :output-conflict)))))]
    (if reason
      (do (println (str "[worktree] Preserved " path " (" branch "): " (clojure.core/name reason)))
          {:removed? false :reason reason :workspace workspace})
      (let [result (run! ["cambium" "remove" workspace-name]
                               {:dir root :out :string :err :string})]
        (if (zero? (:exit result))
          (do (let [owner (owner-file root workspace-name)]
                (when (.exists owner) (io/delete-file owner)))
              {:removed? true})
          (do (println (str "[worktree] Cambium removal failed; preserved " path ": " (:err result)))
              {:removed? false :reason :remove-failed :result result :workspace workspace}))))))
