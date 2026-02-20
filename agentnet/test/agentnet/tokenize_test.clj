(ns agentnet.tokenize-test
  "Verify that prompt templates get all {tokens} replaced.
   Run: bb -cp agentnet/src:agentnet/test -e '(require (quote agentnet.tokenize-test)) (agentnet.tokenize-test/run-tests!)'"
  (:require [agentnet.agent :as agent]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Helpers
;; =============================================================================

;; Tokens that look like {vars} but are agent instructions, not template vars.
(def ^:private non-template-tokens
  #{"{approval_token}"})

(defn- unreplaced-tokens
  "Return seq of {token} patterns still present in text, excluding known
   non-template tokens (agent instructions that happen to use braces)."
  [text]
  (seq (remove non-template-tokens (re-seq #"\{[a-z_]+\}" text))))

(defn- load-template [path]
  (let [f (io/file path)]
    (when (.exists f) (slurp f))))

;; Mirrors what build-template-tokens produces in production:
;; - defaults: mode_hint, targets, recent_sec
;; - core/build-context: context_header, queue_md, recent_files_md, diffstat_md, next_work_md
;; - worker build-context: task_status, pending_tasks, pending_count, etc.
;; If you add a new {var} to a template, add the key here AND to build-template-tokens.
(def test-tokens
  {;; defaults in build-template-tokens
   :mode_hint      "propose"
   :targets        "src/foo.clj, src/bar.clj"
   :recent_sec     "180"
   ;; from core/build-context
   :context_header "---\nrepo: test\n---"
   :queue_md       "- `t-001` task one"
   :recent_files_md "- `foo.clj` (2m ago)"
   :diffstat_md    "- (quiet)"
   :next_work_md   "- Engineer: pick t-001"
   ;; from worker build-context
   :task_status    "Pending: 3, In Progress: 1, Complete: 0"
   :pending_tasks  "- t-001: Build the thing"})

;; =============================================================================
;; Tests
;; =============================================================================

(defn test-tokenize-basic []
  (let [template "Hello {name}, you have {count} items."
        result (agent/tokenize template {:name "Alice" :count "5"})]
    (assert (= result "Hello Alice, you have 5 items.")
            (str "Basic tokenize failed: " result))
    (assert (nil? (unreplaced-tokens result))
            (str "Unreplaced tokens remain: " (unreplaced-tokens result)))
    (println "  PASS: basic tokenize")))

(defn test-tokenize-no-tokens []
  (let [template "No tokens here."
        result (agent/tokenize template {:foo "bar"})]
    (assert (= result "No tokens here.")
            "Template without tokens should pass through unchanged")
    (println "  PASS: no-token passthrough")))

(defn test-tokenize-missing-key []
  (let [template "Hello {name}, {missing} stays."
        result (agent/tokenize template {:name "Bob"})]
    (assert (str/includes? result "Hello Bob")
            "Known token should be replaced")
    (assert (str/includes? result "{missing}")
            "Unknown token should remain as-is (not crash)")
    (println "  PASS: missing key preserved")))

(defn test-engineer-template []
  (let [template (load-template "config/prompts/engineer.md")]
    (if (nil? template)
      (println "  SKIP: config/prompts/engineer.md not found")
      (let [result (agent/tokenize template test-tokens)
            remaining (unreplaced-tokens result)]
        (assert (not (str/includes? result "{context_header}"))
                "engineer.md: {context_header} was not replaced")
        (assert (not (str/includes? result "{queue_md}"))
                "engineer.md: {queue_md} was not replaced")
        (assert (not (str/includes? result "{recent_files_md}"))
                "engineer.md: {recent_files_md} was not replaced")
        (assert (not (str/includes? result "{diffstat_md}"))
                "engineer.md: {diffstat_md} was not replaced")
        (assert (not (str/includes? result "{next_work_md}"))
                "engineer.md: {next_work_md} was not replaced")
        (assert (not (str/includes? result "{targets}"))
                "engineer.md: {targets} was not replaced")
        (assert (nil? remaining)
                (str "engineer.md: unreplaced tokens: " remaining))
        (println "  PASS: engineer.md fully tokenized")))))

(defn test-cto-template []
  (let [template (load-template "config/prompts/cto.md")]
    (if (nil? template)
      (println "  SKIP: config/prompts/cto.md not found")
      (let [result (agent/tokenize template test-tokens)
            remaining (unreplaced-tokens result)]
        (assert (not (str/includes? result "{context_header}"))
                "cto.md: {context_header} was not replaced")
        (assert (not (str/includes? result "{mode_hint}"))
                "cto.md: {mode_hint} was not replaced")
        (assert (nil? remaining)
                (str "cto.md: unreplaced tokens: " remaining))
        (println "  PASS: cto.md fully tokenized")))))

;; =============================================================================
;; Runner
;; =============================================================================

(defn run-tests! []
  (println "Running tokenize tests...")
  (test-tokenize-basic)
  (test-tokenize-no-tokens)
  (test-tokenize-missing-key)
  (test-engineer-template)
  (test-cto-template)
  (println "All tokenize tests passed."))

;; Auto-run when loaded
(run-tests!)
