(ns agentnet.task-execution
  "Independent executable gates. A model completion token cannot construct acceptance."
  (:require [agentnet.bounded-process :as bounded]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema "oompa.task-validation/v1")
(defn now-ms [] (System/currentTimeMillis))
(defn remaining-ms [deadline] (max 0 (- deadline (now-ms))))
(defn- terminal-json-content
  "Only a terminal JSON fence may supply a result; other fences are logs."
  [output]
  (let [lines (str/split-lines (str/trim output))]
    (loop [i 0 fence nil]
      (if (= i (count lines))
        (when-not fence (str/trim output))
        (let [line (str/trim (nth lines i))]
          (if fence
            (if (= line (:delimiter fence))
              (if (= i (dec (count lines)))
                (when (:json? fence)
                  ;; Retain prefix nesting: a fence inside an unfinished log
                  ;; object must not promote its nested payload to a result.
                  (str/join "\n" (concat (take (dec (:start fence)) lines)
                                         (subvec (vec lines) (:start fence) i))))
                (recur (inc i) nil))
              (recur (inc i) fence))
            (if-let [[_ delimiter language] (re-matches #"(`{3,}|~{3,})(.*)" line)]
              (recur (inc i) {:delimiter delimiter :start (inc i)
                             :json? (contains? #{"" "json"} (str/trim language))})
              (recur (inc i) nil))))))))

(defn- terminal-object
  "Find a line-delimited, top-level JSON object ending at EOF. Track lexical
   nesting and string escapes so nested/log objects cannot become candidates."
  [text]
  (when text
    (let [text (str/trim text) n (count text)]
      (loop [i 0 stack [] quoted? false escaped? false start nil eligible? false candidate nil line-start 0]
        (if (= i n)
          (when (and (empty? stack) candidate (= (second candidate) n))
            (subs text (first candidate) n))
          (let [c (.charAt text i) next-line (if (= c \newline) (inc i) line-start)]
            (cond
              quoted?
              (recur (inc i) stack (if (and (= c \") (not escaped?)) false true)
                     (and (= c \\) (not escaped?)) start eligible? candidate next-line)

              (and (seq stack) (= c \"))
              (recur (inc i) stack true false start eligible? candidate next-line)

              (contains? #{\{ \[} c)
              (recur (inc i) (conj stack c) false false (if (empty? stack) i start)
                     (if (empty? stack) (and (= c \{) (str/blank? (subs text line-start i))) eligible?)
                     candidate next-line)

              (and (seq stack) (contains? #{\} \]} c))
              (when (= (peek stack) (if (= c \}) \{ \[))
                (let [remaining (pop stack)]
                  (recur (inc i) remaining false false start eligible?
                         (if (and (empty? remaining) eligible?) [start (inc i)] candidate) next-line)))

              :else (recur (inc i) stack false false start eligible? candidate next-line))))))))

(defn blocked-result
  "A selected-task hold signal only. Parse terminal structured JSON, never
   prose or an embedded earlier log. Supports whole objects, JSON lines, and
   terminal JSON fences, including multiline objects after progress commentary."
  [output task-id]
  (let [candidate (terminal-object (terminal-json-content (or output "")))
        result (when candidate (try (json/parse-string candidate true) (catch Exception _ nil)))]
    (when (and (map? result) (= "blocked" (:status result))
               (string? (:reason result)) (not (str/blank? (:reason result)))
               (false? (:taskComplete result)) (false? (:readyForMergeValidation result))
               (or (nil? (:requiredOwnerAction result)) (string? (:requiredOwnerAction result)))
               (or (nil? (:task_id result)) (= task-id (:task_id result))))
      result)))
(defn- sha256 [bytes]
  (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes))))
(defn file-sha256 [path] (sha256 (java.nio.file.Files/readAllBytes (.toPath (io/file path)))))
(defn key-string [value] (if (keyword? value) (subs (str value) 1) value))
(defn validator-for
  "Explicit per-task validator, else the owner-pinned default_validator. The
   default is a named config key (never inferred), so absence of both still
   means \"no oracle\" and the selected loop refuses to claim the task."
  [config id]
  (or (get-in config [:validators id]) (get-in config [:validators (keyword id)])
      (:default_validator config)))
(defn validator-digest [validator]
  ;; Rejection checkpoint policy can be tightened without pretending the
  ;; source/command oracle changed. Host config authority forbids weakening it.
  (sha256 (.getBytes (pr-str (into (sorted-map) (dissoc validator :reconcile_on_rejection))) "UTF-8")))

(defn validate-config!
  [config]
  (doseq [key [:wall_time_seconds :attempt_timeout_seconds]]
    (when-not (and (integer? (get config key)) (pos? (get config key)))
      (throw (ex-info (str "task_execution requires positive " (name key)) {:field key}))))
  (when-not (map? (:validators config))
    (throw (ex-info "task_execution requires an explicit validators map" {})))
  (doseq [[id policy] (:task_readiness config)]
    (when-not (and (map? policy)
                   (or (nil? (:role policy)) (contains? #{"consumer" "observed"} (:role policy)))
                   (or (nil? (:blocker_class policy)) (contains? #{"internal" "external" "acceptance"} (:blocker_class policy)))
                   (every? #(or (nil? (get policy %)) (and (string? (get policy %)) (not (str/blank? (get policy %)))))
                           [:reason_code :next_action :required_owner]))
      (throw (ex-info "task_readiness requires typed role/blocker classification and nonempty owner/action metadata" {:task-id id}))))
  (doseq [[id validator] (cond-> (vec (:validators config))
                            (contains? config :default_validator)
                            (conj [:default_validator (:default_validator config)]))]
    (when-not (and (sequential? (:command validator)) (seq (:command validator))
                   (every? #(and (string? %) (not (str/blank? %))) (:command validator))
                   (integer? (:timeout_seconds validator)) (pos? (:timeout_seconds validator))
                   (or (nil? (:reconcile_on_rejection validator)) (boolean? (:reconcile_on_rejection validator)))
                   (map? (:source_files validator)) (seq (:source_files validator))
                   (every? (fn [[path digest]]
                             (and (or (string? path) (keyword? path))
                                  (string? digest) (re-matches #"[0-9a-f]{64}" digest)))
                           (:source_files validator)))
      (throw (ex-info "Each validator requires command argv, timeout_seconds, and pinned source_files SHA-256 map"
                      {:task-id id}))))
  config)

(defn require-discovery-validator!
  "root_task_ids admits tasks that do not exist at launch, so no per-task
   validators map can cover them. Refuse at startup unless the owner pinned a
   task_execution.default_validator; never run discovered work unvalidated."
  [backend execution-config]
  (when (and (seq (:root_task_ids backend)) (not (map? (:default_validator execution-config))))
    (throw (ex-info "task_backend.root_task_ids requires task_execution.default_validator (discovered tasks have no explicit validator entry)"
                    {:type :missing-default-validator :root_task_ids (:root_task_ids backend)})))
  nil)

(defn owner-window-environment []
  {:authorization_id (System/getenv "OOMPA_OWNER_WINDOW_AUTHORIZATION_ID")
   :expires_at_ms (try (some-> (System/getenv "OOMPA_OWNER_WINDOW_EXPIRES_AT_MS") Long/parseLong)
                       (catch Exception _ nil))})

(defn resume-budget
  "Delegated resume never extends a budget. Only matching host-injected metadata
   for a new explicit owner launch can replace an expired deadline; keep history."
  [saved config deadline]
  (if-let [window (:owner_window config)]
    (let [authority (owner-window-environment)
          prior (:deadline_ms saved)
          same? (= (:authorization_id window) (get-in saved [:owner_window :authorization_id]))]
      (when-not (and (string? (:authorization_id window)) (not (str/blank? (:authorization_id window)))
                     (= (:authorization_id window) (:authorization_id authority))
                     (integer? (:expires_at_ms window)) (= (:expires_at_ms window) (:expires_at_ms authority))
                     (> (:expires_at_ms window) (now-ms))
                     (if same? (<= prior (:expires_at_ms window)) (= prior (:prior_deadline_ms window))))
        (throw (ex-info "New owner window lacks matching host authority or exact prior deadline" {:reason :owner-window-unverified})))
      (cond-> (assoc saved :deadline_ms (min deadline (:expires_at_ms window) (if same? prior Long/MAX_VALUE))
                           :owner_window window)
        (not same?) (update :deadline_history (fnil conj [])
                            {:deadline_ms prior :owner_window (:owner_window saved)})))
    (assoc saved :deadline_ms (min deadline (:deadline_ms saved)))))

(defn git!
  [cwd deadline & args]
  (let [result (bounded/run! (into ["git"] args) {:dir cwd} (max 1 (min 30000 (remaining-ms deadline))))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Git source verification failed" {:command args :result result})))
    (str/trim (:out result))))

(defn source-pin!
  "Independently pin a clean committed candidate and immutable validator files."
  [cwd validator deadline]
  (when-not (str/blank? (git! cwd deadline "status" "--porcelain" "--untracked-files=all"))
    (throw (ex-info "Consumer validation requires a clean committed source tree" {:reason :dirty-source})))
  (let [root (.getCanonicalFile (io/file cwd))
        commit (git! cwd deadline "rev-parse" "HEAD")
        files (into (sorted-map)
                    (for [[path expected] (:source_files validator)
                          :let [path (key-string path)
                                file (io/file root path)
                                canonical (.getCanonicalFile file)]]
                      (do
                        (when (or (.isAbsolute (io/file path))
                                  (not (.startsWith (.toPath canonical) (.toPath root)))
                                  (not (.isFile file))
                                  (java.nio.file.Files/isSymbolicLink (.toPath file)))
                          (throw (ex-info "Pinned validator source is missing or outside the source tree" {:path path})))
                        (git! cwd deadline "ls-files" "--error-unmatch" "--" path)
                        (let [actual (file-sha256 file)]
                          (when-not (= expected actual)
                            (throw (ex-info "Pinned validator source changed; lead must independently intake a new pin"
                                            {:reason :validator-pin-mismatch :path path :expected expected :actual actual})))
                          [path actual]))))]
    {:source_commit commit :source_files files}))

(defn validate!
  "Run the configured command in clean cwd, verify source before/after, and check
   its typed JSON report. The command receives task/source identity through env.
   Gate failure stays rejected; missing or changed oracle stays blocked."
  [cwd task validator deadline]
  (if-not validator
    {:schema schema :task_id (:id task) :status "blocked" :reason "validator-unconfigured"}
    (try
      (let [pin (source-pin! cwd validator deadline)
            timeout-ms (min (* 1000 (:timeout_seconds validator)) (remaining-ms deadline))
            result (bounded/run! (vec (:command validator))
                                 {:dir cwd :extra-env {"OOMPA_TASK_ID" (:id task)
                                                      "OOMPA_SOURCE_COMMIT" (:source_commit pin)}}
                                 (max 1 timeout-ms))
            after (source-pin! cwd validator deadline)
            report (try (json/parse-string (:out result) true) (catch Exception _ nil))
            stable? (= pin after)
            typed? (and (= schema (:schema report))
                        (= (:id task) (:task_id report))
                        (= (:source_commit pin) (:source_commit report))
                        (boolean? (:accepted report)))
            accepted? (and stable? (not (:timed-out? result)) (zero? (:exit result))
                           typed? (true? (:accepted report)))]
        (merge pin {:schema schema :kind "executable-validator" :task_id (:id task)
                    :task_revision (:revision task) :validator_digest (validator-digest validator)
                    :command (vec (:command validator)) :exit (:exit result)
                    :timed_out (boolean (:timed-out? result)) :duration_ms (:duration-ms result)
                    :status (cond (not stable?) "blocked" accepted? "accepted" :else "rejected")
                    :reason (cond (not stable?) "source-changed-during-validation"
                                  (:timed-out? result) "validator-timeout"
                                  (not (zero? (:exit result))) "validator-failed"
                                  (not typed?) "invalid-validator-report"
                                  (not accepted?) "consumer-rejected" :else "consumer-accepted")
                    :report report :stdout (:out result) :stderr (:err result)}))
      (catch Exception e
        {:schema schema :kind "executable-validator" :task_id (:id task) :status "blocked"
         :reason (or (some-> (ex-data e) :reason name) "source-verification-failed")
         :error (.getMessage e)}))))

(defn accepted? [receipt]
  (and (= schema (:schema receipt)) (= "executable-validator" (:kind receipt))
       (= "accepted" (:status receipt)) (= 0 (:exit receipt)) (false? (:timed_out receipt))
       (string? (:source_commit receipt)) (re-matches #"[0-9a-f]{40,64}" (:source_commit receipt))
       (map? (:source_files receipt)) (seq (:source_files receipt))
       (true? (get-in receipt [:report :accepted]))
       (= (:task_id receipt) (get-in receipt [:report :task_id]))
       (= (:source_commit receipt) (get-in receipt [:report :source_commit]))))
