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
;; Opencode Output Parsing (harness-specific, lives here not in agent-cli)
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

(def registry
  {:codex    {:stdin :close   :session :uuid      :output :plain}
   :claude   {:stdin :prompt  :session :uuid      :output :plain}
   :opencode {:stdin :close   :session :extracted  :output :ndjson}
   :gemini   {:stdin :close   :session :implicit   :output :plain}})

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
   Sends a JSON dict to `agent-cli build --input -`, parses the CommandSpec,
   and resolves the binary to an absolute path.

   agent-cli owns all CLI flag syntax (session create/resume, model decomposition,
   bypass flags, prompt delivery). This function just maps oompa opts to JSON."
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
