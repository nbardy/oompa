(ns agentnet.task-execution
  "Independent executable gates. A model completion token cannot construct acceptance."
  (:require [agentnet.bounded-process :as bounded]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def schema "oompa.task-validation/v1")
(defn now-ms [] (System/currentTimeMillis))
(defn remaining-ms [deadline] (max 0 (- deadline (now-ms))))
(defn blocked-result
  "A selected-task hold signal only. Parse explicit JSON, never prose or an
   acceptance claim. Supports a whole object, JSON lines, and fenced JSON."
  [output task-id]
  (let [output (or output "")
        candidates (concat [output] (str/split-lines output)
                           (map second (re-seq #"(?s)```(?:json)?\s*\n(.*?)```" output)))]
    (some (fn [candidate]
            (let [result (try (json/parse-string candidate true) (catch Exception _ nil))]
              (when (and (map? result) (= "blocked" (:status result))
                         (string? (:reason result)) (not (str/blank? (:reason result)))
                         (false? (:taskComplete result)) (false? (:readyForMergeValidation result))
                         (or (nil? (:requiredOwnerAction result)) (string? (:requiredOwnerAction result)))
                         (or (nil? (:task_id result)) (= task-id (:task_id result))))
                result)))
          candidates)))
(defn- sha256 [bytes]
  (format "%064x" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes))))
(defn file-sha256 [path] (sha256 (java.nio.file.Files/readAllBytes (.toPath (io/file path)))))
(defn key-string [value] (if (keyword? value) (subs (str value) 1) value))
(defn validator-for [config id]
  (or (get-in config [:validators id]) (get-in config [:validators (keyword id)])))
(defn validator-digest [validator] (sha256 (.getBytes (pr-str (into (sorted-map) validator)) "UTF-8")))

(defn validate-config!
  [config]
  (doseq [key [:wall_time_seconds :attempt_timeout_seconds]]
    (when-not (and (integer? (get config key)) (pos? (get config key)))
      (throw (ex-info (str "task_execution requires positive " (name key)) {:field key}))))
  (when-not (map? (:validators config))
    (throw (ex-info "task_execution requires an explicit validators map" {})))
  (doseq [[id validator] (:validators config)]
    (when-not (and (sequential? (:command validator)) (seq (:command validator))
                   (every? #(and (string? %) (not (str/blank? %))) (:command validator))
                   (integer? (:timeout_seconds validator)) (pos? (:timeout_seconds validator))
                   (map? (:source_files validator)) (seq (:source_files validator))
                   (every? (fn [[path digest]]
                             (and (or (string? path) (keyword? path))
                                  (string? digest) (re-matches #"[0-9a-f]{64}" digest)))
                           (:source_files validator)))
      (throw (ex-info "Each validator requires command argv, timeout_seconds, and pinned source_files SHA-256 map"
                      {:task-id id}))))
  config)

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
