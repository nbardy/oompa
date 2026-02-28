(ns agentnet.core
  "Context assembly utilities shared by the AgentNet orchestrator."
  (:require [agentnet.notes :as notes]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn now-ms []
  (System/currentTimeMillis))

(defn- normalize-priority
  "Coerce :priority to a sortable number.
   Accepts: integer, \"P1\"/\"P2\" strings, numeric strings, nil.
   Unknown/nil → 1000 (low priority)."
  [p]
  (cond
    (number? p) p
    (nil? p) 1000
    (string? p)
    (let [s (str/upper-case (str/trim p))]
      (cond
        (str/starts-with? s "P") (try (Integer/parseInt (subs s 1)) (catch Exception _ 1000))
        :else (try (Integer/parseInt s) (catch Exception _ 1000))))
    :else 1000))

(defn format-ago
  "Return human-readable relative time string for epoch milliseconds."
  [^long ts-ms]
  (let [delta (max 0 (- (now-ms) ts-ms))
        s (long (/ delta 1000))]
    (cond
      (< s 15) "just now"
      (< s 60) (str s "s ago")
      :else (let [m (long (/ s 60))]
              (cond
                (< m 60) (str m "m ago")
                :else (let [h (long (/ m 60))]
                        (if (< h 24)
                          (str h "h ago")
                          (str (long (/ h 24)) "d ago"))))))))

(defn- git-cmd
  "Run git command in repo and return trimmed stdout or nil on failure."
  [repo & args]
  (try
    (let [cmd (into ["git" "-C" repo] args)
          {:keys [exit out]} (process/sh cmd {:out :string :err :string})]
      (when (zero? exit)
        (str/trim out)))
    (catch Exception _ nil)))

(defn- repo-branch [repo]
  (or (git-cmd repo "rev-parse" "--abbrev-ref" "HEAD") "HEAD"))

(defn- repo-head [repo]
  (or (git-cmd repo "rev-parse" "--short" "HEAD") "HEAD"))

(defn- bulletize
  "Render a collection as Markdown bullets; fallback provided when empty."
  ([items] (bulletize items "- (empty)"))
  ([items empty-text]
   (if (seq items)
     (str/join "\n" (map #(str "- " %) items))
     empty-text)))

(defn- queue-lines [tasks]
  (->> tasks
       (sort-by (juxt (comp normalize-priority :priority) :id))
       (map (fn [{:keys [id summary]}]
              (format "`%s` • %s" id summary)))))

(defn- note->path [{:keys [dir name]}]
  (str dir "/" name))

(defn- pending-items []
  (->> (notes/green-ready)
       (map (fn [note]
              (let [paths (or (:targets note)
                              [(note->path note)])]
                {:id (or (:id note) (:name note))
                 :age (format-ago (:mtime note))
                 :files (vec (take 3 paths))})))
       (take 7)
       vec))

(defn- recent-notes [recent-sec]
  (let [cut (- (now-ms) (* 1000 (long (or recent-sec 120))))]
    (->> ["scratch" "ready_for_review" "notes_FROM_CTO"]
         (mapcat notes/list-notes)
         (filter #(>= (:mtime %) cut))
         (sort-by :mtime >)
         (take 7)
         (map (fn [note]
                {:path (note->path note)
                 :age (format-ago (:mtime note))}))
         vec)))

(defn- backlog-entries [tasks]
  (->> tasks
       (sort-by (juxt (comp normalize-priority :priority) :id))
       (map #(select-keys % [:id :summary]))
       (remove #(nil? (:id %)))
       (take 7)
       vec))

(def default-policy-rules
  ["patch-only" ".agent/* only" "minimal diff" "respect targets"])

(defn- policy-rules [policy]
  (let [allow (:allow policy)
        deny (:deny policy)
        limit+ (:max-lines-added policy)
        limit- (:max-lines-deleted policy)
        files (:max-files policy)
        custom (remove nil?
                       [(when (seq allow)
                          (str "allow:" (str/join "," allow)))
                        (when (seq deny)
                          (str "deny:" (str/join "," deny)))
                        (when limit+
                          (format "+≤%s lines" limit+))
                        (when limit-
                          (format "-≤%s lines" limit-))
                        (when files
                          (format "files≤%s" files))])]
    (->> (concat default-policy-rules custom)
         (distinct)
         (take 7)
         vec)))

(defn- next-work-suggestions [tasks pending hotspots]
  (let [top-task (first tasks)
        top-pending (first pending)
        top-hotspot (first hotspots)
        suggestions (cond-> []
                      top-pending
                      (conj (format "CTO: review %s (%s)" (:id top-pending) (:age top-pending)))

                      top-task
                      (conj (format "Engineer: pick %s — %s" (:id top-task) (:summary top-task)))

                      top-hotspot
                      (conj (format "CTO: investigate %s" (:path top-hotspot))))]
    (take 5 (if (empty? suggestions)
              ["noop"]
              suggestions))))

(defn- render-pending [items]
  (if (seq items)
    (mapcat (fn [{:keys [id age files]}]
              [(format "  - id: %s" (pr-str id))
               (format "    age: %s" age)
               (format "    files: [%s]" (str/join "," (map pr-str files)))])
            items)
    ["  - []"]))

(defn- render-backlog [items]
  (if (seq items)
    (map (fn [{:keys [id summary]}]
           (format "  - {id: %s, summary: %s}" (pr-str id) (pr-str summary)))
         items)
    ["  - []"]))

(defn- render-hotspots [items]
  (if (seq items)
    (map (fn [{:keys [path age]}]
           (format "  - {path: %s, age: %s}" (pr-str path) (pr-str age)))
         items)
    ["  - []"]))

(defn- render-next-work [items]
  (if (seq items)
    (map #(str "  - " (pr-str %)) items)
    ["  - \"noop\""]))

(defn- render-targets [targets]
  (format "targets: [%s]" (str/join "," (map pr-str (or targets [])))))

(defn- render-policy [policy]
  (format "policy: [%s]" (str/join "," (map pr-str policy))))

(defn- sanitize-mode [mode]
  (if (#{"review" "propose"} mode) mode "propose"))

(defn build-context
  "Return map of context tokens, including YAML header for prompts."
  [{:keys [tasks policy repo recent-sec targets mode-hint]}
   & {:as opts}]
  (let [repo (or repo ".")
        recent-sec (long (or recent-sec 180))
        backlog (backlog-entries tasks)
        pending (pending-items)
        hotspots (recent-notes recent-sec)
        policy-lines (policy-rules (or policy {}))
        next-work (next-work-suggestions backlog pending hotspots)
        branch (repo-branch repo)
        head (repo-head repo)
        header-lines (concat
                      ["---  # agent_context"
                       (format "repo:\n  branch: %s\n  head: %s\n  recent_window: %ss"
                               branch head recent-sec)
                       "pending:"]
                      (render-pending pending)
                      ["backlog:"]
                      (render-backlog backlog)
                      ["hotspots:"]
                      (render-hotspots hotspots)
                      [(render-targets (or targets []))
                       (render-policy policy-lines)
                       "next_work:"]
                      (render-next-work next-work)
                      [(format "mode: %s" (sanitize-mode mode-hint))
                       "---"])
        header (str (str/join "\n" header-lines) "\n")
        queue-md (bulletize (take 7 (queue-lines tasks)))
        hotspots-md (bulletize (map #(format "`%s` (%s)" (:path %) (:age %))
                                     hotspots)
                                "- (none)")
        next-work-md (bulletize next-work "- noop")
        diffstat-md "- (quiet)"]
    {:context_header header
     :ach_yaml header
     :queue_md queue-md
     :recent_files_md hotspots-md
     :next_work_md next-work-md
     :diffstat_md diffstat-md}))
