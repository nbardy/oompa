(ns agentnet.notes
  "Filesystem-based queue helpers for the agent_notes workflow."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def NOTES
  "Root directory that stores all note queues."
  (io/file "agent_notes"))

(defn- fm-frontmatter
  "Parse lightweight YAML-ish front-matter between --- lines.
  Returns a map of keyword -> string, or {} when not present or malformed."
  [^String s]
  (try
    (let [[_ block] (re-find #"(?s)^---\n(.*?)\n---\n" s)]
      (if-not block
        {}
        (->> (str/split-lines block)
             (keep #(when-let [[_ k v] (re-matches #"^\s*([A-Za-z0-9_]+)\s*:\s*(.+)\s*$" %)]
                      [(keyword k) (str/trim v)]))
             (into {}))))
    (catch Exception _
      {})))

(defn- read-note
  "Return a metadata map for note file `f` with parsed front-matter."
  [^java.io.File f]
  (let [s  (slurp f)
        fm (fm-frontmatter s)]
    {:file   (.getAbsolutePath f)
     :name   (.getName f)
     :dir    (.getName (.getParentFile f))
     :mtime  (.lastModified f)
     :fm     fm
     :status (keyword (or (:status fm) "green"))
     :id     (or (:id fm)
                 (second (re-find #"__([^_]+)__" (.getName f))))
     :targets (let [t (:targets fm)]
                (when (and t (string? t) (not (str/blank? t)))
                  (let [parts (-> t
                                  (str/replace #"[\[\]\"]" "")
                                  (str/split #",\s*"))]
                    (->> parts
                         (remove str/blank?)
                         vec))))}))

(defn list-notes
  "Return newest-first vector of note metadata maps from `subdir`."
  [subdir]
  (let [d (io/file NOTES subdir)]
    (when (.exists d)
      (->> (.listFiles d)
           (filter (fn [^java.io.File f]
                     (and (.isFile f)
                          (not (.startsWith (.getName f) ".")))))
           (map read-note)
           (sort-by :mtime >)
           vec))))

(defn green-ready
  "Return newest-first notes from ready_for_review/ that are not marked red."
  []
  (->> (list-notes "ready_for_review")
       (remove #(= (:status %) :red))))

(defn proposed-green
  "Return green proposals sorted by rank when available."
  []
  (->> (list-notes "proposed_tasks")
       (filter #(= (:status %) :green))
       (sort-by (comp #(try
                         (Integer/parseInt %)
                         (catch Exception _
                           9999))
                      :rank :fm))))

(defn cto-feedback-latest
  "Return up to 20 newest feedback notes from notes_FROM_CTO/."
  []
  (take 20 (list-notes "notes_FROM_CTO")))

(defn scratch-latest
  "Return scratch notes touched within RECENT-SEC seconds, newest first."
  [recent-sec]
  (let [cut (- (System/currentTimeMillis) (* 1000 recent-sec))]
    (->> (list-notes "scratch")
         (filter #(>= (:mtime %) cut))
         (take 50)
         vec)))

(defn pick-files-for-prompt
  "Select distinct filenames that should be surfaced in agent prompts.
  recent-sec defaults to 120 seconds when unspecified."
  [{:keys [recent-sec]}]
  (let [recent (or recent-sec 120)
        a (map :name (take 10 (green-ready)))
        b (map :name (take 10 (proposed-green)))
        c (map :name (take 5 (cto-feedback-latest)))
        d (map :name (take 5 (scratch-latest recent)))]
    (->> (concat a b c d)
         distinct
         vec)))

(defn mark-note-red!
  "Ensure the given note map is marked red in place.
  Expects the note to have been produced by read-note."
  [note]
  (let [f (io/file (:file note))
        s (slurp f)
        [_ front body] (re-matches #"(?s)^---\n(.*?)\n---\n(.*)$" s)
        ensure-trailing-newline (fn [text] (if (str/blank? text) "" (str text (when-not (str/ends-with? text "\n") "\n"))))]
    (if front
      (let [lines (str/split-lines front)
            has-status? (some #(re-matches #"(?i)^\s*status\s*:" %) lines)
            updated-lines (if has-status?
                            (map #(if (re-matches #"(?i)^\s*status\s*:" %)
                                    "status: red"
                                    %)
                                 lines)
                            (conj (vec lines) "status: red"))
            new-front (str/join "\n" updated-lines)]
        (spit f (str "---\n" (ensure-trailing-newline new-front) "---\n" body)))
      (spit f (str "---\nstatus: red\n---\n" s)))))
