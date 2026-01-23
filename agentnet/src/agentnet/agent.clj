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
     :claude - Anthropic Claude CLI (claude -p)"
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
      (slurp f))))

(defn load-custom-prompt
  "Load a custom prompt file. Returns content or nil."
  [path]
  (when path
    (let [f (io/file path)]
      (when (.exists f)
        (slurp f)))))

(defn- tokenize
  "Replace {tokens} in template with values from context map"
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
              ["echo" "unknown"])]
    (try
      (let [{:keys [exit]} (process/sh cmd {:out :string :err :string})]
        (zero? exit))
      (catch Exception _
        false))))

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
