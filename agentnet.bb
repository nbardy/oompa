#!/usr/bin/env bb

(ns agentnet
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [agentnet.notes :as notes]))

;; -----------------------------------------------------------------------------
;; Paths & basic helpers
;; -----------------------------------------------------------------------------

(def tasks-path "config/tasks.edn")
(def policy-path "config/policy.edn")
(def prompts-dir "config/prompts")
(def runs-dir "runs")

(defn now-ms [] (System/currentTimeMillis))

(defn read-edn [path]
  (let [f (io/file path)]
    (when (.exists f)
      (with-open [r (java.io.PushbackReader. (io/reader f))]
        (edn/read {:eof nil} r)))))

(defn load-tasks []
  (or (read-edn tasks-path) []))

(defn load-policy []
  (or (read-edn policy-path)
      {:allow ["src/**" "scripts/**" "docs/**" "config/**"]
       :deny []
       :max-lines-added nil
       :max-lines-deleted nil
       :max-files nil}))

(defn load-prompts []
  (let [slurp-if (fn [name]
                   (let [f (io/file prompts-dir (str name ".md"))]
                     (when (.exists f) (slurp f))))]
    {:engineer (slurp-if "engineer")
     :reviewer (slurp-if "reviewer")
     :cto (slurp-if "cto")}))

(defn ensure-dir! [^java.io.File f]
  (.mkdirs f)
  f)

(defn ensure-agent-dir! [cwd]
  (ensure-dir! (io/file cwd ".agent")))

(defn patch-file [cwd]
  (io/file cwd ".agent/patch.diff"))

(defn meta-file [cwd]
  (io/file cwd ".agent/meta.json"))

(defn truncate [s limit]
  (when (seq s)
    (let [s (str/trim s)]
      (if (> (count s) limit)
        (str (subs s 0 limit) "...")
        s))))

(defn bulletize [items]
  (if (seq items)
    (str/join "\n" (map #(str "- " %) items))
    "- (empty)"))

(defn targets->str [targets]
  (if (seq targets)
    (str/join ", " targets)
    "(any)"))

;; -----------------------------------------------------------------------------
;; Context pack
;; -----------------------------------------------------------------------------

(defn queue-md [tasks]
  (bulletize
   (map (fn [{:keys [id summary]}]
          (format "`%s` • %s" id summary))
        tasks)))

(defn hotspot-md [recent]
  (bulletize (map #(format "`%s`" %) recent)))

(defn build-context [tasks]
  (let [recent (notes/pick-files-for-prompt {:recent-sec 180})
        queue (queue-md tasks)
        hotspots (hotspot-md recent)
        next-work (bulletize (map (fn [{:keys [id summary]}]
                                    (format "Work: %s — %s" id summary))
                                  (take 5 tasks)))
        yaml (str/join
              "\n"
              ["--- # ACH-lite"
               "repo: {branch: feature/mega-branch}"
               "queue:"
               (if (= queue "- (empty)") "  - []" (->> (str/split-lines queue) (map #(str "  " %)) (str/join "\n")))
               "hotspots:"
               (if (= hotspots "- (empty)") "  - []" (->> (str/split-lines hotspots) (map #(str "  " %)) (str/join "\n")))
               "next_work:"
               (->> (str/split-lines next-work) (map #(str "  " %)) (str/join "\n"))
               "diffstat:"
               "  - []"
               "mode: propose"
               "---"]) ]
    {:ach_yaml (str yaml "\n")
     :queue_md queue
     :recent_files_md hotspots
     :diffstat_md "- (quiet)"
     :next_work_md next-work}))

(defn tokenize [template tokens]
  (reduce (fn [acc [k v]]
            (str/replace acc (re-pattern (java.util.regex.Pattern/quote (str "{" (name k) "}"))) (str v)))
          template
          tokens))

;; -----------------------------------------------------------------------------
;; Subprocess helpers
;; -----------------------------------------------------------------------------

(defn run-command
  ([cmd] (run-command cmd {}))
  ([cmd opts]
   (process/sh cmd (merge {:out :string :err :string} opts))))

(defn run-codex [{:keys [cwd prompt role]}]
  (let [cmd (cond-> ["codex" "exec" "--full-auto" "--skip-git-repo-check"
                     "-C" (or cwd ".") "--sandbox" "workspace-write"]
              true (conj "--" prompt))
        label (or role "agent")]
    (println (format "[codex] %s running..." label))
    (let [{:keys [exit out err]} (run-command cmd {:out :inherit :err :inherit})]
      {:exit exit
       :stdout (truncate out 400)
       :stderr (truncate err 400)})))

(defn git-cmd [cwd & args]
  (run-command (into ["git" "-C" (or cwd ".")] args)))

;; -----------------------------------------------------------------------------
;; Patch + meta helpers
;; -----------------------------------------------------------------------------

(defn sha256-file [^java.io.File f]
  (when (.exists f)
    (with-open [is (java.io.FileInputStream. f)]
      (let [md (java.security.MessageDigest/getInstance "SHA-256")
            buffer (byte-array 8192)]
        (loop []
          (let [read (.read is buffer)]
            (when (pos? read)
              (.update md buffer 0 read)
              (recur))))
        (format "%064x" (BigInteger. 1 (.digest md)))))))

(defn parse-patch-files [^java.io.File patch]
  (when (.exists patch)
    (->> (line-seq (io/reader patch))
         (keep (fn [line]
                 (cond
                   (str/starts-with? line "+++ b/") (subs line 6)
                   (str/starts-with? line "--- a/") (subs line 6)
                   :else nil)))
         (map #(str/replace % #"^\./" ""))
         distinct
         vec)))

(defn ensure-meta! [cwd task files opts]
  (let [meta-path (meta-file cwd)]
    (when (or (not (.exists meta-path))
              (pos? (:force opts 0)))
      (let [payload {:task_id (:id task)
                     :summary (:summary task)
                     :files files
                     :generated_at (now-ms)}]
        (spit meta-path (json/generate-string payload {:pretty true}))))))

(defn capture-patch! [cwd task]
  (ensure-agent-dir! cwd)
  (let [patch (patch-file cwd)]
    (when (or (not (.exists patch)) (zero? (.length patch)))
      (let [{:keys [exit out]} (git-cmd cwd "diff" "--no-ext-diff")]
        (if (or (not (zero? exit)) (str/blank? out))
          (println (format "[warn] %s: no diff to capture" (:id task)))
          (do (spit patch out)
              (println (format "[capture] %s wrote .agent/patch.diff" (:id task)))))))
    patch))

(defn load-meta-files [cwd]
  (let [meta-path (meta-file cwd)]
    (when (.exists meta-path)
      (try
        (:files (json/parse-string (slurp meta-path) true))
        (catch Exception _ nil)))))

;; -----------------------------------------------------------------------------
;; Policy enforcement
;; -----------------------------------------------------------------------------

(defn glob->regex [glob]
  (let [placeholder "__DS__"
        escaped (-> glob
                    (str/replace "." "\\.")
                    (str/replace "**" placeholder)
                    (str/replace "*" "[^/]*")
                    (str/replace placeholder ".*"))]
    (re-pattern (str "^" escaped "$"))))

(defn matches? [globs path]
  (boolean (some #(re-matches (glob->regex %) path) globs)))

(defn diff-metrics-ok? [{:keys [max-lines-added max-lines-deleted max-files]} {:keys [adds dels files]}]
  (let [limit (fn [n default] (or n default))]
    (and (<= adds (limit max-lines-added Long/MAX_VALUE))
         (<= dels (limit max-lines-deleted Long/MAX_VALUE))
         (<= files (limit max-files Long/MAX_VALUE)))))

(defn check-policy [policy files]
  (let [{:keys [allow deny]} policy
        allow (or allow [])
        deny (or deny [])
        denied (filter #(matches? deny %) files)
        not-allowed (when (seq allow)
                      (filter #(not (matches? allow %)) files))]
    {:denied (vec denied)
     :not-allowed (vec not-allowed)}))

(defn policy-ok? [{:keys [denied not-allowed]}]
  (and (empty? denied) (empty? not-allowed)))

;; -----------------------------------------------------------------------------
;; Merge helpers
;; -----------------------------------------------------------------------------

(defn merge-patch! [cwd task {:keys [dry-run]}]
  (let [patch (patch-file cwd)
        hash (sha256-file patch)]
    (cond
      (not (.exists patch)) {:status :missing-patch}
      dry-run {:status :skipped :hash hash :message "dry-run: merge skipped"}
      :else
      (let [{apply-exit :exit apply-err :err} (git-cmd cwd "apply" "--index" (.getPath patch))]
        (if (zero? apply-exit)
          (let [commit-msg (format "agent:%s merge" (:id task))
                {commit-exit :exit commit-err :err} (git-cmd cwd "commit" "-m" commit-msg)]
            (if (zero? commit-exit)
              {:status :merged :hash hash}
              (do (println (format "[merge] commit failed: %s" (truncate commit-err 200)))
                  {:status :commit-failed :hash hash})))
          (do (println (format "[merge] git apply failed: %s" (truncate apply-err 200)))
              {:status :apply-failed :hash hash}))))))

;; -----------------------------------------------------------------------------
;; Logging / persistence
;; -----------------------------------------------------------------------------

(defn sanitize-result [result]
  (-> result
      (select-keys [:task-id :summary :status :targets :files :policy :propose-exit :review-exit :merge :timestamp :hash])
      (update :policy #(when % (select-keys % [:denied :not-allowed])))))

(defn save-run! [results]
  (ensure-dir! (io/file runs-dir))
  (let [timestamp (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss");
        fname (format "run-%s.jsonl"
                      (.format timestamp (java.time.LocalDateTime/now)))
        file (io/file runs-dir fname)]
    (doseq [row results]
      (spit file (str (json/generate-string (sanitize-result row)) "\n") :append true))
    (.getPath file)))

(defn latest-run-file []
  (let [dir (io/file runs-dir)]
    (when (.exists dir)
      (let [files (filter #(.isFile %) (.listFiles dir))]
        (->> files
             (sort-by #(.lastModified ^java.io.File %))
             last)))))

(defn status! []
  (if-let [file (latest-run-file)]
    (let [rows (with-open [r (io/reader file)]
                 (doall (map #(json/parse-string % true) (line-seq r))))
          counts (frequencies (map :status rows))]
      (println (format "Latest run: %s" (.getName file)))
      (doseq [[status cnt] counts]
        (println (format "  %s: %d" (name status) cnt)))
      (println (format "Total tasks: %d" (count rows))))
    (println "No runs recorded yet.")))

;; -----------------------------------------------------------------------------
;; Task processing
;; -----------------------------------------------------------------------------

(defn process-task [env task]
  (let [{:keys [prompts context policy opts]} env
        cwd (or (:cwd task) ".")
        targets (:targets task)
        tokens (merge context
                      {:task_id (:id task)
                       :summary (:summary task)
                       :targets (targets->str targets)
                       :approval_token (:approval-token opts "APPROVED")
                       :mode_hint "propose"})
        engineer-prompt (tokenize (:engineer prompts) tokens)
        _ (println (format "[engineer] %s" (:id task)))
        engineer (run-codex {:cwd cwd :prompt engineer-prompt :role "engineer"})
        patch (capture-patch! cwd task)
        files (or (load-meta-files cwd)
                  (let [paths (parse-patch-files patch)]
                    (ensure-meta! cwd task paths {:force 1})
                    paths))
        reviewer-prompt (tokenize (:reviewer prompts) (assoc tokens :mode_hint "review"))
        _ (println (format "[reviewer] %s" (:id task)))
        reviewer (run-codex {:cwd cwd :prompt reviewer-prompt :role "reviewer"})
        policy-report (when (seq files) (check-policy policy files))
        approved? (zero? (:exit reviewer))
        policy-ok (or (nil? policy-report) (policy-ok? policy-report))
        status (cond
                 (not approved?) :needs-fix
                 (not policy-ok) :policy-violation
                 :else :approved)
        merge-report (when (= status :approved)
                       (merge-patch! cwd task opts))
        final-status (cond
                       (and (= status :approved)
                            (= (:status merge-report) :merged)) :merged
                       (= status :approved) (:status merge-report)
                       :else status)
        hash (sha256-file patch)
        result {:task-id (:id task)
                :summary (:summary task)
                :targets targets
                :timestamp (now-ms)
                :status final-status
                :propose-exit (:exit engineer)
                :review-exit (:exit reviewer)
                :policy policy-report
                :files files
                :merge merge-report
                :hash hash}]
    (when (and policy-report (not policy-ok))
      (binding [*out* *err*]
        (println (format "[policy] %s violations: %s"
                         (:id task)
                         (str/join ", " (concat (:denied policy-report) (:not-allowed policy-report)))))))
    result))

(defn run-orchestrator [opts]
  (let [tasks (or (:tasks opts) (load-tasks))
        prompts (load-prompts)
        policy (load-policy)
        context (build-context tasks)
        env {:prompts prompts
             :policy policy
             :context context
             :opts opts}
        worker-count (:workers opts 1)
        task-ch (async/chan)
        result-ch (async/chan)]
    (dotimes [_ worker-count]
      (async/go-loop []
        (when-let [task (async/<! task-ch)]
          (async/>! result-ch (process-task env task))
          (recur))))
    (async/go
      (doseq [task tasks]
        (async/>! task-ch task))
      (async/close! task-ch))
    (loop [remaining (count tasks)
           acc []]
      (if (zero? remaining)
        (do (async/close! result-ch)
            acc)
        (if-let [res (async/<!! result-ch)]
          (recur (dec remaining) (conj acc res))
          acc)))))

;; -----------------------------------------------------------------------------
;; CLI commands
;; -----------------------------------------------------------------------------

(defn lint-notes! []
  (let [dirs ["ready_for_review" "notes_FROM_CTO" "scratch" "proposed_tasks"]
        notes-list (mapcat notes/list-notes dirs)
        missing (->> notes-list (filter #(str/blank? (:id %))) (map :name) seq)]
    (if (seq missing)
      (do (binding [*out* *err*]
            (println "Missing :id in notes:" (str/join ", " missing)))
          (System/exit 1))
      (println "Notes OK."))))

(defn context! []
  (let [ctx (build-context (load-tasks))]
    (println (:ach_yaml ctx))))

(defn run! [opts]
  (let [results (run-orchestrator opts)
        logfile (save-run! results)]
    (doseq [{:keys [task-id status]} results]
      (println (format "%s -> %s" task-id (name status))))
    (println (format "Run log written to %s" logfile))
    results))

(defn usage []
  (str/join
   "\n"
   ["Usage: ./agentnet.bb <command> [options]"
    "Commands:"
    "  run            Run the orchestrator once"
    "  prompt         Run once with an ad-hoc natural-language prompt"
    "  lint-notes     Validate front matter"
    "  context        Print ACH context block"
    "  status         Summarize latest run"
    "Options:"
    "  --workers N    Parallel workers (default 1)"
    "  --dry-run      Skip git apply/commit"
    "  --targets CSV  Limit prompt-run targets (e.g., src,tests)"]))

(defn parse-targets [s]
  (->> (str/split s #",")
       (map str/trim)
       (remove str/blank?)
       vec))

(defn parse-opts [args]
  (loop [opts {:workers 1}
         remaining args]
    (if-let [a (first remaining)]
      (cond
        (= a "--workers")
        (if-let [val (second remaining)]
          (recur (assoc opts :workers (Integer/parseInt val)) (nnext remaining))
          (throw (ex-info "--workers requires a value" {})))

        (= a "--dry-run")
        (recur (assoc opts :dry-run true) (next remaining))

        (= a "--targets")
        (if-let [val (second remaining)]
          (recur (assoc opts :targets (parse-targets val)) (nnext remaining))
          (throw (ex-info "--targets requires a CSV value" {})))

        (= a "--")
        {:opts opts :args (next remaining)}

        (str/starts-with? a "--")
        (throw (ex-info (str "Unknown option: " a) {}))

        :else
        {:opts opts :args remaining})
      {:opts opts :args []})))

(defn prompt-task [text {:keys [targets]}]
  {:id (format "prompt-%d" (now-ms))
   :summary text
   :targets (or (not-empty targets) ["src" "tests" "docs"])
   :priority 1
   :dependencies []})

(defn prompt! [text opts]
  (let [clean (str/trim text)]
    (when (str/blank? clean)
      (println "Prompt text is required.")
      (System/exit 1))
    (run! (assoc opts :tasks [(prompt-task clean opts)]))))

(defn -main [& args]
  (let [[cmd & more] args
        {:keys [opts args]} (parse-opts more)]
    (case cmd
      "run" (if (seq args)
              (do (println "Unexpected arguments to run:" (str/join " " args))
                  (System/exit 1))
              (run! opts))
      "prompt" (prompt! (str/join " " args) opts)
      "lint-notes" (lint-notes!)
      "context" (context!)
      "status" (status!)
      (do (println (usage)) (System/exit 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
