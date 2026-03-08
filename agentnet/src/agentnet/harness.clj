(ns agentnet.harness
  "Harness configuration registry.

   Each harness is a data map describing how to invoke a CLI agent.
   CLI flag syntax is owned by agent-cli (shared with claude-web-view).
   This module owns: stdin behavior, session strategy, output parsing.

   Harness = Codex | Claude | Opencode | Gemini
   δ(harness) → config map → agent-cli JSON → command vector"
  (:require [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; =============================================================================
;; Binary Resolution (shared across all harnesses)
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
                     (catch Exception _ {:exit -1 :out "" :err ""}))]
        (if (zero? (:exit result))
          (let [path (str/trim (:out result))]
            (swap! binary-paths* assoc name path)
            path)
          (throw (ex-info (str "Binary not found on PATH: " name) {:binary name}))))))

;; =============================================================================
;; NDJSON Output Parsing (harness-specific, lives here not in agent-cli)
;; =============================================================================

(defn- extract-session-id
  "Best-effort session ID extraction from agent-cli output.
   Prefer normalized `session.started`, but fall back to raw harness events that
   the shared agent-cli path also understands (notably Gemini `init/session_id`)."
  [events]
  (or (some->> events
               (keep #(when (= "session.started" (:type %))
                        (:sessionId %)))
               last)
      (some->> events
               (keep (fn [event]
                       (or (:session_id event)
                           (:sessionId event)
                           (get-in event [:part :session_id])
                           (get-in event [:part :sessionId]))))
               last)))

(defn- parse-unified-jsonl-output
  "Parse unified JSONL emitted by `agent-cli run`.
   Returns {:session-id string|nil, :text string|nil, :warning string|nil}."
  [s]
  (let [raw (or s "")
        events (->> (str/split-lines raw)
                    (keep (fn [line]
                            (try
                              (json/parse-string line true)
                              (catch Exception _
                                nil))))
                    doall)
        event-types (->> events (keep :type) distinct (take 8) vec)
        session-id (extract-session-id events)
        text (->> events
                  (keep #(when (= "text.delta" (:type %)) (:text %)))
                  (remove str/blank?)
                  (str/join ""))
        stderr-text (->> events
                         (keep #(when (= "stderr" (:type %)) (:text %)))
                         (remove str/blank?)
                         (str/join ""))
        errors (->> events
                    (keep #(when (= "error" (:type %)) (:message %)))
                    (remove str/blank?)
                    vec)
        warning (cond
                  (and (not (str/blank? raw)) (empty? events))
                  "agent-cli returned non-empty output, but no unified JSONL events were parsed."

                  (seq errors)
                  (str "agent-cli reported error events: " (str/join " | " (take 3 errors)))

                  (and (seq events) (str/blank? text))
                  (str "agent-cli returned unified events, but no text deltas were extracted"
                       " (types=" (if (seq event-types)
                                    (str/join "," event-types)
                                    "unknown")
                       ").")

                  :else nil)]
    {:session-id session-id
     :text (cond
             (not (str/blank? text)) text
             (seq errors) (str/join "\n" errors)
             (not (str/blank? stderr-text)) stderr-text
             :else nil)
     :warning warning
     :raw-snippet (when-not (str/blank? raw)
                    (subs raw 0 (min 400 (count raw))))}))

;; =============================================================================
;; Env Helpers
;; =============================================================================

(defn- opencode-attach-url
  "Optional opencode server URL for run --attach mode."
  []
  (let [url (or (System/getenv "OOMPA_OPENCODE_ATTACH")
                (System/getenv "OPENCODE_ATTACH"))]
    (when (and url (not (str/blank? url)))
      url)))

;; =============================================================================
;; Harness Registry — oompa-specific behavior fields only
;; =============================================================================
;;
;; CLI flag syntax (model flags, session flags, bypass, prompt delivery)
;; is owned by agent-cli. This registry only tracks:
;;
;;   :stdin   - what to pass as process stdin (:close or :prompt)
;;   :session - session ID strategy (:uuid, :extracted, :implicit)
;;   :output  - output format (:plain or :ndjson)

(def ^:private gemini-behavior
  {:stdin :close :session :extracted :output :ndjson})

(def registry
  (merge
   {:codex    {:stdin :close   :session :uuid      :output :plain}
    :claude   {:stdin :prompt  :session :uuid      :output :plain}
    :opencode {:stdin :close   :session :extracted  :output :ndjson}
    :gemini   gemini-behavior}
   {:gemini1 gemini-behavior
    :gemini2 gemini-behavior
    :gemini3 gemini-behavior}))

(defn- gemini-alias?
  [harness-kw]
  (and (keyword? harness-kw)
       (re-matches #"^gemini\\d+$" (name harness-kw))))

(defn valid-harness?
  "True for explicit registry entries and any `geminiNN` alias."
  [harness-kw]
  (or (contains? (set (keys registry)) harness-kw)
      (gemini-alias? harness-kw)))

;; =============================================================================
;; Registry Access
;; =============================================================================

(defn get-config
  "Look up harness config. Throws on unknown harness (no silent fallback)."
  [harness-kw]
  (or (get registry harness-kw)
      (when (gemini-alias? harness-kw) gemini-behavior)
      (throw (ex-info (str "Unknown harness: " harness-kw
                           ". Known: " (str/join ", " (map name (keys registry))))
                      {:harness harness-kw}))))

(defn known-harnesses
  "Set of all registered harness keywords."
  []
  (set (keys registry)))

;; =============================================================================
;; Command Builder — delegates to agent-cli via JSON input
;; =============================================================================

(defn- add-extra-args
  "Attach harness-specific extraArgs to the JSON input map.
   Only opencode needs extra flags (--format json, --attach)."
  [m harness-kw opts]
  (if (= harness-kw :opencode)
    (let [url (opencode-attach-url)
          extra (cond-> []
                  (:format? opts) (into ["--format" "json" "--print-logs" "--log-level" "WARN"])
                  url             (into ["--attach" url]))]
      (if (seq extra) (assoc m :extraArgs extra) m))
    m))

(defn build-cmd
  "Build CLI command vector via agent-cli JSON input.
   Used for probe/debug flows. Execution should prefer `run-command!`."
  [harness-kw opts]
  (let [input (-> {:harness (name harness-kw)
                   :bypassPermissions true}
                  (cond->
                    (:model opts)      (assoc :model (:model opts))
                    (:prompt opts)     (assoc :prompt (:prompt opts))
                    (:session-id opts) (assoc :sessionId (:session-id opts))
                    (:resume? opts)    (assoc :resume true)
                    (:cwd opts)        (assoc :cwd (:cwd opts))
                    (:reasoning opts)  (assoc :reasoning (:reasoning opts)))
                  (add-extra-args harness-kw opts)
                  json/generate-string)
        {:keys [exit out err]} (process/sh ["agent-cli" "build" "--input" "-"]
                                           {:in input :out :string :err :string})]
    (when-not (zero? exit)
      (throw (ex-info (str "agent-cli: " (str/trim err)) {:exit exit})))
    (let [argv (:argv (json/parse-string out true))]
      (vec (cons (resolve-binary! (first argv)) (rest argv))))))

(defn process-stdin
  "Return the :in value for process/sh.
   :close → \"\" (close stdin immediately to prevent hang).
   :prompt → the prompt text (claude delivers prompt via stdin)."
  [harness-kw prompt]
  (let [{:keys [stdin]} (get-config harness-kw)]
    (case stdin
      :prompt prompt
      :close  "")))

(defn run-command!
  "Execute a harness through `agent-cli run`, which emits unified JSONL events."
  [harness-kw opts]
  (let [input (-> {:harness (name harness-kw)
                   :mode "conversation"
                   :prompt (:prompt opts)
                   :cwd (:cwd opts)
                   :yolo true}
                  (cond->
                    (:model opts)      (assoc :model (:model opts))
                    (and (:session-id opts) (not (:resume? opts))) (assoc :sessionId (:session-id opts))
                    (and (:session-id opts) (:resume? opts))       (assoc :resumeSessionId (:session-id opts))
                    (:reasoning opts)  (assoc :reasoningEffort (:reasoning opts))
                    (or (= harness-kw :gemini) (gemini-alias? harness-kw))
                    (assoc :debugRawEvents true))
                  (add-extra-args harness-kw opts)
                  json/generate-string)]
    (process/sh ["agent-cli" "run" "--input" "-"]
                {:in input :out :string :err :string})))

;; =============================================================================
;; Session Management
;; =============================================================================

(defn make-session-id
  "Generate initial session-id based on harness strategy.
   :uuid → random UUID string.
   :extracted → nil (will be parsed from output after first run).
   :implicit → nil (harness manages sessions by cwd)."
  [harness-kw]
  (let [{:keys [session]} (get-config harness-kw)]
    (when (= session :uuid)
      (str/lower-case (str (java.util.UUID/randomUUID))))))

;; =============================================================================
;; Output Parsing
;; =============================================================================

(defn parse-output
  "Parse unified JSONL output from `agent-cli run`.
   Returns {:output string, :session-id string}."
  [harness-kw raw-output session-id]
  (let [parsed (parse-unified-jsonl-output raw-output)]
    {:output     (or (:text parsed) raw-output)
     :session-id (or (:session-id parsed) session-id)
     :warning    (:warning parsed)
     :raw-snippet (:raw-snippet parsed)}))

;; =============================================================================
;; Probe / Health Check — delegates to agent-cli check
;; =============================================================================

(defn check-available
  "Check if harness CLI binary is available on PATH via agent-cli."
  [harness-kw]
  (try
    (let [{:keys [exit out]} (process/sh ["agent-cli" "check" (name harness-kw)]
                                         {:out :string :err :string})]
      (when (zero? exit)
        (:available (json/parse-string out true))))
    (catch Exception _ false)))

(defn build-probe-cmd
  "Build minimal command to test if a harness+model is accessible.
   Delegates to build-cmd with a '[_HIDE_TEST_] say ok' probe prompt."
  [harness-kw model]
  (build-cmd harness-kw {:model model :prompt "[_HIDE_TEST_] say ok"}))
