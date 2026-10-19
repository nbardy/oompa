(ns agentnet.cambium
  "Cambium lifecycle shared by iteration workers and the legacy pool.
   Creation never replaces an existing workspace. Removal fails closed."
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- git [root & args]
  (process/sh (into ["git"] args) {:dir root :out :string :err :string}))

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
  (let [workspace-name (str "oompa-" (str/replace label #"[^A-Za-z0-9_.-]" "-")
                            "-" (java.util.UUID/randomUUID))
        path (workspace-path root workspace-name)
        branch (str branch-prefix workspace-name)
        _ (.mkdirs (.getParentFile (io/file path)))
        _ (mark-owner! root workspace-name)
        result (apply git root (cond-> ["cambium" "add" "-b" branch "-name" workspace-name "-ephemeral" path]
                                 start-point (conj start-point)))]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Cambium creation failed; workspace retained if partially created: "
                           path " (" (str/trim (str (:err result))) ")")
                      {:dir workspace-name :branch branch :path path :result result})))
    {:dir workspace-name :name workspace-name :branch branch :path path :project-root root})))

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
                     (not (zero? (:exit merged))) :unmerged)))]
    (if reason
      (do (println (str "[worktree] Preserved " path " (" branch "): " (clojure.core/name reason)))
          {:removed? false :reason reason :workspace workspace})
      (let [result (process/sh ["cambium" "remove" workspace-name]
                               {:dir root :out :string :err :string})]
        (if (zero? (:exit result))
          (do (let [owner (owner-file root workspace-name)]
                (when (.exists owner) (io/delete-file owner)))
              {:removed? true})
          (do (println (str "[worktree] Cambium removal failed; preserved " path ": " (:err result)))
              {:removed? false :reason :remove-failed :result result :workspace workspace}))))))
