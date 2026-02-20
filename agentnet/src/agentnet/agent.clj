(ns agentnet.agent
  "Unified agent execution abstraction.

   Supports multiple agent backends (Codex, Claude) with consistent interface.
   Each agent type has different CLI syntax but produces same output format.

   Design:
     - Agents are pure functions: Prompt -> AgentResult
     - Side effects (file changes) happen in worktree
     - Timeouts and retries handled at this layer
     - Output parsing extracts structured feedback

   Supported Backends:
     :codex  - OpenAI Codex CLI (codex exec)
     :claude - Anthropic Claude CLI (claude -p)
     :opencode - opencode CLI (opencode run)"
  (:require [agentnet.schema :as schema]
            [babashka.process :as process]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Function Specs
;; =============================================================================

;; invoke : AgentConfig, AgentRole, Prompt, Worktree -> AgentResult
;; Execute agent with prompt in worktree context

;; build-prompt : Task, Context, AgentRole -> Prompt
;; Construct prompt from task, context, and role template

;; parse-output : String, AgentRole -> ParsedOutput
;; Extract structured data from agent output

;; =============================================================================
;; Types (documentation)
;; =============================================================================

;; AgentResult:
;;   {:exit int
;;    :stdout string
;;    :stderr string
;;    :duration-ms int
;;    :timed-out? boolean}

;; ParsedOutput:
;;   {:verdict :approved|:needs-changes|:rejected
;;    :comments [string]
;;    :files-changed [string]
;;    :error string}

;; =============================================================================
;; Agent Backend Implementations
;; =============================================================================

(defmulti build-command
  "Build CLI command for agent type"
  (fn [agent-type _config _prompt _cwd] agent-type))

(defmethod build-command :codex
  [_ {:keys [model sandbox timeout-seconds]} prompt cwd]
  (cond-> ["codex" "exec" "--full-auto" "--skip-git-repo-check"]
    model (into ["--model" model])
    cwd (into ["-C" cwd])
    sandbox (into ["--sandbox" (name sandbox)])
    true (conj "--" prompt)))

(defmethod build-command :claude
  [_ {:keys [model timeout-seconds]} prompt cwd]
  ;; Claude uses stdin for prompt via -p flag
  (cond-> ["claude" "-p"]
    model (into ["--model" model])
    true (conj "--dangerously-skip-permissions")))

(defn- opencode-attach-url
  "Optional opencode server URL for run --attach mode."
  []
  (let [url (or (System/getenv "OOMPA_OPENCODE_ATTACH")
                (System/getenv "OPENCODE_ATTACH"))]
    (when (and url (not (str/blank? url)))
      url)))

(defmethod build-command :opencode
  [_ {:keys [model]} prompt cwd]
  (let [attach (opencode-attach-url)]
    (cond-> ["opencode" "run"]
      model (into ["-m" model])
      attach (into ["--attach" attach])
      true (conj prompt))))

(defmethod build-command :gemini
  [_ {:keys [model]} prompt cwd]
  (cond-> ["gemini" "--yolo"]
    model (into ["-m" model])
    true (into ["-p" prompt])))

(defmethod build-command :default
  [agent-type _ _ _]
  (throw (ex-info (str "Unknown agent type: " agent-type)
                  {:agent-type agent-type})))

;; =============================================================================
;; Process Execution
;; =============================================================================

(defn- now-ms []
  (System/currentTimeMillis))

(defn- truncate [s limit]
  (if (and s (> (count s) limit))
    (str (subs s 0 limit) "...[truncated]")
    s))

(defn- run-process
  "Execute command with timeout, return AgentResult"
  [{:keys [cmd cwd stdin timeout-ms]}]
  (let [start (now-ms)
        timeout (or timeout-ms 300000)  ; 5 min default
        opts (cond-> {:out :string
                      :err :string
                      :timeout timeout}
               cwd (assoc :dir cwd)
               stdin (assoc :in stdin))
        result (try
                 (process/sh cmd opts)
                 (catch java.util.concurrent.TimeoutException _
                   {:exit -1 :out "" :err "Timeout exceeded" :timed-out true})
                 (catch Exception e
                   {:exit -1 :out "" :err (.getMessage e)}))]
    {:exit (:exit result)
     :stdout (truncate (:out result) 10000)
     :stderr (truncate (:err result) 5000)
     :duration-ms (- (now-ms) start)
              :timed-out? (boolean (:timed-out result))}))

;; =============================================================================
;; Prompt Loading Helpers
;; =============================================================================

(defn- file-canonical-path
  "Resolve a path for cache keys and cycle detection."
  [path]
  (try
    (.getCanonicalPath (io/file path))
    (catch Exception _
      path)))

(def ^:private prompt-file-cache
  "Cache for prompt include expansion."
  (atom {}))

(def ^:private include-directive-pattern
  #"(?m)^\s*#oompa_directive:include_file\s+\"([^\"]+)\"\s*$")

(defn- read-file-cached
  "Read a prompt file once and cache by canonical path."
  [path]
  (when path
    (if-let [cached (get @prompt-file-cache path)]
      cached
      (let [f (io/file path)]
        (when (.exists f)
          (let [content (slurp f)]
            (swap! prompt-file-cache assoc path content)
            content))))))

(defn- resolve-include-path
  "Resolve an include path relative to the file that declares it."
  [source-path include-path]
  (let [source-file (io/file source-path)
        base-dir (.getParentFile source-file)]
    (if (or (str/starts-with? include-path "/")
            (and (> (count include-path) 1)
                 (= (nth include-path 1) \:)) ; Windows drive letter
            (str/starts-with? include-path "~"))
      include-path
      (if base-dir
        (str (io/file base-dir include-path))
        include-path))))

(defn- expand-includes
  "Expand #oompa_directive:include_file directives recursively.

   Directive syntax:
   #oompa_directive:include_file \"relative/or/absolute/path.md\"

   Includes are resolved relative to the prompt file containing the directive.
   Cycles are guarded by a simple visited-set."
  ([raw source-path]
   (expand-includes raw source-path #{}))
  ([raw source-path visited]
   (let [source-canonical (file-canonical-path source-path)
         lines (str/split-lines (or raw ""))
         visited' (conj visited source-canonical)]
     (str/join
      "\n"
      (mapcat
       (fn [line]
         (if-let [match (re-matches include-directive-pattern line)]
           (let [include-target (second match)
                 include-path (resolve-include-path source-canonical include-target)
                 include-canonical (file-canonical-path include-path)
                 included (and (not (str/blank? include-path))
                               (read-file-cached include-canonical))]
             (cond
               (str/blank? include-target)
               ["[oompa] Empty include target in prompt directive"]

               (contains? visited' include-canonical)
               [(format "[oompa] Skipping already-included file: \"%s\"" include-target)]

               (not included)
               [(format "[oompa] Could not include \"%s\"" include-target)]

               :else
               (cons (format "We have included the content of file: \"%s\" below"
                             include-target)
                     (str/split-lines
                      (expand-includes included include-canonical visited')))))
           [line]))
       lines)))))

(defn- load-prompt-file
  "Load a prompt file and expand include directives."
  [path]
  (when path
    (when-let [f (io/file path)]
      (when (.exists f)
        (expand-includes (slurp f) (file-canonical-path path))))))

;; =============================================================================
;; Output Parsing
;; =============================================================================

(defn- extract-verdict
  "Extract review verdict from agent output"
  [output]
  (cond
    (re-find #"(?i)\bAPPROVED\b" output) :approved
    (re-find #"(?i)\bREJECTED\b" output) :rejected
    (re-find #"(?i)\bNEEDS[_-]?CHANGES\b" output) :needs-changes
    (re-find #"(?i)\bFIX\s*:" output) :needs-changes
    (re-find #"(?i)\bVIOLATION\s*:" output) :needs-changes
    :else nil))

(defn done-signal?
  "Check if output contains __DONE__ signal"
  [output]
  (boolean (re-find #"__DONE__" (or output ""))))

(defn merge-signal?
  "Check if output contains COMPLETE_AND_READY_FOR_MERGE signal"
  [output]
  (boolean (re-find #"COMPLETE_AND_READY_FOR_MERGE" (or output ""))))

(defn parse-claim-signal
  "Extract task IDs from CLAIM(...) signal in output.
   Returns vector of task ID strings, or nil if no CLAIM signal found.
   Format: CLAIM(task-001, task-003, task-005)"
  [output]
  (when-let [match (re-find #"CLAIM\(([^)]+)\)" (or output ""))]
    (->> (str/split (second match) #",")
         (map str/trim)
         (remove str/blank?)
         vec)))

(defn- extract-comments
  "Extract bullet-point comments from output"
  [output]
  (->> (str/split-lines output)
       (filter #(re-find #"^\s*[-*]\s+" %))
       (map #(str/replace % #"^\s*[-*]\s+" ""))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn- extract-files-changed
  "Extract list of files that were modified"
  [output]
  (->> (re-seq #"(?m)^[AMD]\s+(.+)$" output)
       (map second)
       vec))

(defn parse-output
  "Parse agent output into structured format"
  [output role]
  (case role
    :reviewer
    {:verdict (or (extract-verdict output) :needs-changes)
     :comments (extract-comments output)}

    :proposer
    {:files-changed (extract-files-changed output)
     :comments (extract-comments output)}

    ;; default
    {:comments (extract-comments output)}))

;; =============================================================================
;; Main API
;; =============================================================================

(defn invoke
  "Execute agent with prompt in worktree context.

   Arguments:
     config   - AgentConfig with :type, :model, :sandbox, :timeout-seconds
     role     - :proposer, :reviewer, or :cto
     prompt   - String prompt to send to agent
     worktree - Worktree map with :path

   Returns AgentResult with :exit, :stdout, :stderr, :duration-ms, :timed-out?"
  [{:keys [type] :as config} role prompt worktree]
  (schema/assert-valid schema/valid-agent-config? config "AgentConfig")
  (let [cwd (:path worktree)
        cmd (build-command type config prompt cwd)
        ;; For Claude, prompt goes via stdin
        stdin (when (= type :claude) prompt)
        timeout-ms (* 1000 (or (:timeout-seconds config) 300))]
    (run-process {:cmd cmd
                  :cwd cwd
                  :stdin stdin
                  :timeout-ms timeout-ms})))

(defn invoke-and-parse
  "Execute agent and parse output into structured format"
  [config role prompt worktree]
  (let [result (invoke config role prompt worktree)
        parsed (when (zero? (:exit result))
                 (parse-output (:stdout result) role))]
    (assoc result :parsed parsed)))

;; =============================================================================
;; Prompt Building
;; =============================================================================

(defn- load-template
  "Load prompt template from config/prompts/"
  [role]
  (let [filename (str "config/prompts/" (name role) ".md")
        f (io/file filename)]
    (when (.exists f)
      (load-prompt-file filename))))

(defn load-custom-prompt
  "Load a custom prompt file. Returns content or nil."
  [path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (load-prompt-file path)))))

(defn tokenize
  "Replace {tokens} in template with values from context map.
   Keys can be keywords or strings; values are stringified."
  [template tokens]
  (reduce (fn [acc [k v]]
            (str/replace acc
                         (re-pattern (java.util.regex.Pattern/quote
                                       (str "{" (name k) "}")))
                         (str v)))
          template
          tokens))

(defn build-prompt
  "Build prompt from task, context, and role.

   Arguments:
     task    - Task map with :id, :summary, :targets
     context - Context map with :queue_md, :recent_files_md, etc.
     role    - :proposer, :reviewer, or :cto
     opts    - {:custom-prompt \"path/to/prompt.md\"}

   Returns prompt string ready for agent"
  ([task context role] (build-prompt task context role {}))
  ([task context role {:keys [custom-prompt]}]
   (let [template (or (load-custom-prompt custom-prompt)
                      (load-template role)
                      (load-template :engineer))  ; fallback
         tokens (merge context
                       {:task_id (:id task)
                        :summary (:summary task)
                        :targets (str/join ", " (or (:targets task) ["*"]))
                        :mode_hint (if (= role :reviewer) "review" "propose")})]
     (if template
       (tokenize template tokens)
       ;; Fallback: simple prompt
       (str "Task: " (:summary task) "\n"
            "Targets: " (:targets task) "\n"
            "Role: " (name role))))))

;; =============================================================================
;; Agent Health Check
;; =============================================================================

(defn check-available
  "Check if agent backend is available"
  [agent-type]
  (let [cmd (case agent-type
              :codex ["codex" "--version"]
              :claude ["claude" "--version"]
              :opencode ["opencode" "--version"]
              :gemini ["gemini" "--version"]
              ["echo" "unknown"])]
    (try
      (let [{:keys [exit]} (process/sh cmd {:out :string :err :string})]
        (zero? exit))
      (catch Exception _
        ;; Some CLIs (like gemini) may error on --version due to config issues
        ;; but still exist on PATH. Fall back to `which`.
        (try
          (let [{:keys [exit]} (process/sh ["which" (first cmd)] {:out :string :err :string})]
            (zero? exit))
          (catch Exception _ false))))))

(defn select-backend
  "Select first available backend from preference list"
  [preferences]
  (first (filter check-available preferences)))

;; =============================================================================
;; Convenience Wrappers
;; =============================================================================

(defn propose!
  "Run proposer agent on task in worktree"
  [config task context worktree]
  (let [prompt (build-prompt task context :proposer)]
    (invoke-and-parse config :proposer prompt worktree)))

(defn review!
  "Run reviewer agent on changes in worktree"
  [config task context worktree]
  (let [prompt (build-prompt task context :reviewer)]
    (invoke-and-parse config :reviewer prompt worktree)))
