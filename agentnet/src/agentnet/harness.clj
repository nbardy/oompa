(ns agentnet.harness
  "Harness configuration registry.

   Each harness is a data map describing how to invoke a CLI agent.
   All harness-specific knowledge lives here — binary names, flag syntax,
   stdin behavior, session strategy, output parsing.

   To add a new harness: add one entry to `registry`.
   No case/switch/if-on-harness anywhere else in the codebase.

   Harness = Codex | Claude | Opencode | Gemini
   δ(harness) → config map → builder functions → command vector"
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Binary Resolution (moved from worker.clj — shared across all harnesses)
;; =============================================================================

(def ^:private binary-paths* (atom {}))

(defn resolve-binary!
  "Resolve the absolute path of a CLI binary. Caches result.
   Throws if binary not found on PATH.
   ProcessBuilder with :dir can fail to find bare command names on macOS/babashka,
   so we resolve once via `which` and cache."
  [name]
  (or (get @binary-paths* name)
      (let [result (try
                     (process/sh ["which" name] {:out :string :err :string})
                     (catch Exception _ {:exit -1 :out "" :err ""}))
            path (when (zero? (:exit result))
                   (str/trim (:out result)))]
        (if path
          (do (swap! binary-paths* assoc name path)
              path)
          (throw (ex-info (str "Binary not found on PATH: " name) {:binary name}))))))

;; =============================================================================
;; Opencode Output Parsing (harness-specific, lives here not in worker.clj)
;; =============================================================================

(defn- parse-opencode-run-output
  "Parse `opencode run --format json` NDJSON output.
   Returns {:session-id string|nil, :text string|nil}."
  [s]
  (let [raw (or s "")
        events (->> (str/split-lines raw)
                    (keep (fn [line]
                            (try
                              (json/parse-string line true)
                              (catch Exception _
                                nil))))
                    doall)
        session-id (or (some #(or (:sessionID %)
                                   (:sessionId %)
                                   (get-in % [:part :sessionID])
                                   (get-in % [:part :sessionId]))
                             events)
                       (some-> (re-find #"(ses_[A-Za-z0-9]+)" raw) second))
        text (->> events
                  (keep (fn [event]
                          (let [event-type (or (:type event) (get-in event [:part :type]))
                                chunk (or (:text event) (get-in event [:part :text]))]
                            (when (and (= event-type "text")
                                       (string? chunk)
                                       (not (str/blank? chunk)))
                              chunk))))
                  (str/join ""))]
    {:session-id session-id
     :text (when-not (str/blank? text) text)}))

;; =============================================================================
;; Env Helpers (harness-specific config from environment)
;; =============================================================================

(defn- opencode-attach-url
  "Optional opencode server URL for run --attach mode."
  []
  (let [url (or (System/getenv "OOMPA_OPENCODE_ATTACH")
                (System/getenv "OPENCODE_ATTACH"))]
    (when (and url (not (str/blank? url)))
      url)))

;; =============================================================================
;; Harness Registry — THE canonical source of all harness-specific knowledge
;; =============================================================================
;;
;; Each entry describes one CLI agent harness. Fields:
;;
;;   :binary        - CLI binary name (resolved via `which`)
;;   :base-cmd      - subcommand + fixed flags after binary name
;;   :auto-approve  - flags to skip all confirmation prompts
;;   :model-flag    - flag name for model selection
;;   :prompt-via    - how prompt is delivered:
;;                      :cli-arg  → appended as last CLI arg
;;                      :cli-sep  → appended after separator (e.g. "--")
;;                      :flag     → passed as value to :prompt-flag
;;                      :stdin    → piped via process stdin
;;   :prompt-flag   - flag name when :prompt-via is :flag (e.g. "-p")
;;   :prompt-sep    - separator string when :prompt-via is :cli-sep (e.g. "--")
;;   :stdin         - what to pass as process stdin:
;;                      :close   → "" (close immediately, prevent hang)
;;                      :prompt  → the prompt text
;;   :cwd-flag      - CLI flag for working directory (nil = use :dir on process)
;;   :session       - session ID strategy:
;;                      :uuid      → generate UUID
;;                      :extracted → parse from output (opencode NDJSON)
;;                      :implicit  → harness manages sessions by cwd (gemini)
;;   :session-flags-fn  - (fn [{:keys [session-id]}]) → flags for initial session
;;   :resume-fn         - (fn [{:keys [session-id]}]) → flags for resuming
;;   :output        - output format: :plain or :ndjson
;;   :format-flags  - extra flags for structured output (only when :format? true)
;;   :extra-flags-fn - (fn [opts]) → additional flags from env/config

(def registry
  {:codex
   {:binary       "codex"
    :base-cmd     ["exec"]
    :auto-approve ["--dangerously-bypass-approvals-and-sandbox"
                   "--skip-git-repo-check"]
    :model-flag   "--model"
    :prompt-via   :cli-sep
    :prompt-sep   "--"
    :stdin        :close
    :cwd-flag     "-C"
    :session      :uuid
    :session-flags-fn nil
    :resume-fn    nil
    :output       :plain
    :format-flags nil
    :extra-flags-fn
    (fn [{:keys [reasoning]}]
      (when reasoning
        ["-c" (str "reasoning.effort=\"" reasoning "\"")]))}

   :claude
   {:binary       "claude"
    :base-cmd     []
    :auto-approve ["--dangerously-skip-permissions"]
    :model-flag   "--model"
    :prompt-via   :flag
    :prompt-flag  "-p"
    :stdin        :prompt
    :cwd-flag     nil
    :session      :uuid
    :session-flags-fn
    (fn [{:keys [session-id]}]
      (when session-id
        ["--session-id" session-id]))
    :resume-fn
    ;; --resume takes session-id as its value; combining --session-id + --resume
    ;; is rejected by Claude CLI unless --fork-session is also passed.
    (fn [{:keys [session-id]}]
      (when session-id
        ["--resume" session-id]))
    :output       :plain
    :format-flags nil
    :extra-flags-fn nil}

   :opencode
   {:binary       "opencode"
    :base-cmd     ["run"]
    :auto-approve []
    :model-flag   "-m"
    :prompt-via   :cli-arg
    :stdin        :close
    :cwd-flag     nil
    :session      :extracted
    :session-flags-fn nil
    :resume-fn
    (fn [{:keys [session-id]}]
      (when session-id
        ["-s" session-id "--continue"]))
    :output       :ndjson
    :format-flags ["--format" "json" "--print-logs" "--log-level" "WARN"]
    :extra-flags-fn
    (fn [_]
      (let [url (opencode-attach-url)]
        (when url
          ["--attach" url])))}

   :gemini
   {:binary       "gemini"
    :base-cmd     []
    :auto-approve ["--yolo"]
    :model-flag   "-m"
    :prompt-via   :flag
    :prompt-flag  "-p"
    :stdin        :close
    :cwd-flag     nil
    :session      :implicit
    :session-flags-fn nil
    :resume-fn
    (fn [_]
      ["--resume" "latest"])
    :output       :plain
    :format-flags nil
    :extra-flags-fn nil}})

;; =============================================================================
;; Registry Access
;; =============================================================================

(defn get-config
  "Look up harness config. Throws on unknown harness (no silent fallback)."
  [harness-kw]
  (or (get registry harness-kw)
      (throw (ex-info (str "Unknown harness: " harness-kw
                           ". Known: " (str/join ", " (map name (keys registry))))
                      {:harness harness-kw}))))

(defn known-harnesses
  "Set of all registered harness keywords."
  []
  (set (keys registry)))

;; =============================================================================
;; Command Builder — composes registry data into command vectors
;; =============================================================================

(defn build-cmd
  "Build full CLI command vector from harness config + opts.

   opts keys:
     :cwd         - working directory (absolute path)
     :model       - model name (optional)
     :reasoning   - reasoning effort level (codex only, optional)
     :session-id  - session identifier (optional)
     :resume?     - whether to resume existing session
     :prompt      - prompt text
     :format?     - include structured output flags (default false)"
  [harness-kw {:keys [cwd model reasoning session-id resume? prompt format?]}]
  (let [{:keys [binary base-cmd auto-approve model-flag cwd-flag
                prompt-via prompt-flag prompt-sep
                session-flags-fn resume-fn format-flags extra-flags-fn]}
        (get-config harness-kw)]
    (cond-> [(resolve-binary! binary)]
      ;; Subcommand + fixed flags
      (seq base-cmd)           (into base-cmd)
      ;; Auto-approve flags
      (seq auto-approve)       (into auto-approve)
      ;; Structured output flags (opt-in, e.g. opencode --format json)
      (and format? (seq format-flags)) (into format-flags)
      ;; Working directory via CLI flag (codex uses -C; others use :dir on process)
      (and cwd-flag cwd)       (into [cwd-flag cwd])
      ;; Model
      model                    (into [model-flag model])
      ;; Extra env/config flags (reasoning, --attach, etc.)
      (and extra-flags-fn
           (seq (extra-flags-fn {:reasoning reasoning})))
      (into (extra-flags-fn {:reasoning reasoning}))
      ;; Session flags — resume takes priority over initial session
      (and resume? resume-fn
           (seq (resume-fn {:session-id session-id})))
      (into (resume-fn {:session-id session-id}))
      (and (not resume?) session-flags-fn session-id
           (seq (session-flags-fn {:session-id session-id})))
      (into (session-flags-fn {:session-id session-id}))
      ;; Prompt delivery
      (and (= prompt-via :flag) prompt-flag prompt)
      (into [prompt-flag prompt])
      (and (= prompt-via :cli-sep) prompt-sep prompt)
      (into [prompt-sep prompt])
      (and (= prompt-via :cli-arg) prompt)
      (conj prompt))))

(defn process-stdin
  "Return the :in value for process/sh.
   :close → \"\" (close stdin immediately to prevent hang).
   :prompt → the prompt text (claude delivers prompt via stdin)."
  [harness-kw prompt]
  (let [{:keys [stdin]} (get-config harness-kw)]
    (case stdin
      :prompt prompt
      :close  "")))

;; =============================================================================
;; Session Management
;; =============================================================================

(defn make-session-id
  "Generate initial session-id based on harness strategy.
   :uuid → random UUID string.
   :extracted → nil (will be parsed from output after first run).
   :implicit → nil (harness manages sessions by cwd, e.g. gemini)."
  [harness-kw]
  (let [{:keys [session]} (get-config harness-kw)]
    (when (= session :uuid)
      (str/lower-case (str (java.util.UUID/randomUUID))))))

;; =============================================================================
;; Output Parsing
;; =============================================================================

(defn parse-output
  "Parse agent output. For :ndjson harnesses, extracts session-id and text.
   For :plain, returns output as-is.
   Returns {:output string, :session-id string}."
  [harness-kw raw-output session-id]
  (let [{:keys [output]} (get-config harness-kw)]
    (if (= output :ndjson)
      (let [parsed (parse-opencode-run-output raw-output)]
        {:output     (or (:text parsed) raw-output)
         :session-id (or (:session-id parsed) session-id)})
      {:output     raw-output
       :session-id session-id})))

;; =============================================================================
;; Probe / Health Check
;; =============================================================================

(defn build-probe-cmd
  "Build minimal command to test if a harness+model is accessible.
   Sends '[_HIDE_TEST_] say ok' and checks for exit 0."
  [harness-kw model]
  (let [{:keys [binary base-cmd auto-approve model-flag
                prompt-via prompt-flag prompt-sep]}
        (get-config harness-kw)
        test-prompt "[_HIDE_TEST_] say ok"]
    (cond-> [binary]
      (seq base-cmd)     (into base-cmd)
      (seq auto-approve) (into auto-approve)
      model              (into [model-flag model])
      ;; Prompt delivery (same logic as build-cmd)
      (and (= prompt-via :flag) prompt-flag)
      (into [prompt-flag test-prompt])
      (and (= prompt-via :cli-sep) prompt-sep)
      (into [prompt-sep test-prompt])
      (= prompt-via :cli-arg)
      (conj test-prompt))))

(defn check-available
  "Check if harness CLI binary is available on PATH."
  [harness-kw]
  (let [{:keys [binary]} (get-config harness-kw)]
    (try
      (let [{:keys [exit]} (process/sh [binary "--version"] {:out :string :err :string})]
        (zero? exit))
      (catch Exception _
        ;; Some CLIs (like gemini) may error on --version due to config issues
        ;; but still exist on PATH. Fall back to `which`.
        (try
          (let [{:keys [exit]} (process/sh ["which" binary] {:out :string :err :string})]
            (zero? exit))
          (catch Exception _ false))))))
