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
  "Ignored run output that must outlive the workspace (wave_sim convention).
   Cambium's --harvest moves it into the primary checkout before removal.
   Why: `git status --porcelain` omits ignored files, so swarm 455987ea lost
   the raw gauges of a paid WC1 GPU run on removal (2026-10-24)."
  "output")

(def ^:private cambium-refusals
  "Cambium `remove --safe` refusal reasons (exit 3). The guard ladder
   (unreadable, in-progress, branch-changed, dirty, unmerged, output harvest)
   lives in Cambium; oompa only maps its JSON reason to the keyword callers log."
  #{"unreadable" "in-progress" "branch-changed" "dirty" "salvage-failed"
    "unmerged" "output-conflict" "output-unreadable"})

(defn- refusal-reason
  "Exit 3 with a known JSON reason is a deliberate preservation; anything else
   (crash, usage error, unknown reason) is :remove-failed, never a removal."
  [{:keys [exit out]}]
  (let [reason (when (= 3 exit)
                 (try (:reason (json/parse-string out true))
                      (catch Exception _ nil)))]
    (if (contains? cambium-refusals reason) (keyword reason) :remove-failed)))

(defn remove!
  "Remove only an inactive Cambium workspace that Cambium's guarded removal
   proves loses nothing: clean, no operation in progress, still on its
   branch, integrated into the project checkout's HEAD, ignored output/
   harvested. Keep the branch (Cambium's default), even after removal."
  [root {:keys [path branch name dir current-task] :as workspace}]
  (let [workspace-name (or name dir)
        expected (when workspace-name (workspace-path root workspace-name))
        managed? (and expected path
                      (= (.getCanonicalPath (io/file expected))
                         (.getCanonicalPath (io/file path))))
        ;; Only what Cambium cannot know: oompa task ownership and process owner.
        held (cond
               current-task :active
               (not managed?) :unmanaged
               (active-owner? root workspace-name) :active-owner)
        result (when-not held
                 (run! ["cambium" "remove" "--safe" "--json" "--require-merged" "HEAD"
                        "--harvest" harvested-output-dir workspace-name]
                       {:dir root :out :string :err :string}))]
    (cond
      held
      (do (println (str "[worktree] Preserved " path " (" branch "): " (clojure.core/name held)))
          {:removed? false :reason held :workspace workspace})

      (zero? (:exit result))
      (do (let [owner (owner-file root workspace-name)]
            (when (.exists owner) (io/delete-file owner)))
          {:removed? true})

      :else
      (let [reason (refusal-reason result)]
        (println (str "[worktree] Preserved " path " (" branch "): " (clojure.core/name reason)
                      " " (str/trim (str (:err result)))))
        {:removed? false :reason reason :result result :workspace workspace}))))
