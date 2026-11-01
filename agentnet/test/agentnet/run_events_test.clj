(ns agentnet.run-events-test
  "started.json / stopped.json writers validated against schemas/*.schema.json.
   Regression guards for two drifts found 2026-11-01:
   - stopped.json: the selected path wrote four reasons (and queue_audit fields)
     the schema never listed.
   - started.json: cmd-swarm, write-started! and salvage used three different
     reviewer keys, so no reviewer reached started.json and salvaged workers
     ran unreviewed."
  (:require [agentnet.cli :as cli]
            [agentnet.runs :as runs]
            [agentnet.worker :as worker]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :as t]))

;; Minimal draft-07 subset: the keywords these schemas actually use.
(defn- schema-errors [schema value path]
  (let [types (let [t (:type schema)] (cond (nil? t) nil (string? t) #{t} :else (set t)))
        type-of (cond (nil? value) "null" (string? value) "string" (integer? value) "integer"
                      (boolean? value) "boolean" (map? value) "object" (sequential? value) "array"
                      :else "other")
        type-ok? (or (nil? types) (contains? types type-of))]
    (concat
      (when-not type-ok? [(str path ": type " type-of " not in " types)])
      (when (and (:enum schema) (not (some #{value} (:enum schema))))
        [(str path ": " (pr-str value) " not in enum")])
      (when (and (:pattern schema) (string? value) (not (re-find (re-pattern (:pattern schema)) value)))
        [(str path ": " value " does not match " (:pattern schema))])
      (when (and (:minimum schema) (number? value) (< value (:minimum schema)))
        [(str path ": below minimum")])
      (when-let [alts (:oneOf schema)]
        (when-not (= 1 (count (filter #(empty? (schema-errors % value path)) alts)))
          [(str path ": matches not exactly one oneOf branch")]))
      (when (and type-ok? (map? value))
        (concat
          (for [k (:required schema) :when (not (contains? value (keyword k)))]
            (str path ": missing required " k))
          (mapcat (fn [[k v]]
                    (if-let [sub (get (:properties schema) k)]
                      (schema-errors sub v (str path "." (name k)))
                      (when (false? (:additionalProperties schema))
                        [(str path ": unexpected property " (name k))])))
                  value)))
      (when (and type-ok? (sequential? value) (:items schema))
        (mapcat #(schema-errors (:items schema) %2 (str path "[" %1 "]")) (range) value)))))

(defn- load-schema [file]
  (json/parse-string (slurp (io/file "schemas" file)) true))

(defn- with-runs-root [f]
  (let [root (str (fs/create-temp-dir {:prefix "oompa-run-events-"}))]
    (try
      (with-redefs-fn {#'runs/run-dir (fn [swarm-id] (str root "/" swarm-id))}
        #(f root))
      (finally (fs/delete-tree root)))))

(defn- read-json [path] (json/parse-string (slurp path) true))

(t/deftest stopped-schema-reasons-equal-writer-reasons
  (t/is (= (set (map name runs/stop-reasons))
           (set (get-in (load-schema "stopped.schema.json") [:properties :reason :enum])))))

(t/deftest written-stopped-json-validates-against-schema
  (with-runs-root
    (fn [root]
      (let [outcome (#'worker/worker-terminal-outcome
                     {:id "w0" :status :wall-time-exhausted :cycles-completed 2 :merges 1
                      :errors 0 :rejections 1 :recycled 0 :claims 3
                      :queue_audit {:fingerprint "abc" :pending 2} :queue_audit_changed true
                      :error "deadline"})]
        (doseq [reason runs/stop-reasons]
          (runs/write-stopped! "s1" reason :worker-outcomes [outcome] :pending-count 2)
          (t/is (empty? (schema-errors (load-schema "stopped.schema.json")
                                       (read-json (str root "/s1/stopped.json")) "stopped"))
                (name reason))))
      (t/is (thrown? clojure.lang.ExceptionInfo (runs/write-stopped! "s1" :made-up))))))

(t/deftest started-json-carries-each-workers-reviewers-into-salvage
  (with-runs-root
    (fn [root]
      (let [config {:reviewers ["claude:claude-opus-5-5:high"]}
            generic (#'cli/parse-reviewers-config config)
            workers [(cli/configured-worker 0 {:model "codex:gpt-5.5:medium" :max_cycle 2
                                               :reviewers [{:model "codex:gpt-5.5:high" :prompt "r.md"}]}
                                            "s2" config generic)
                     (cli/configured-worker 1 {:model "codex:gpt-5.5" :max_cycle 2 :needs_review false}
                                            "s2" config generic)]]
        (runs/write-started! "s2" {:workers workers :planner-config nil :config-file "oompa.json"})
        (let [started (runs/read-started "s2")]
          (t/is (empty? (schema-errors (load-schema "started.schema.json") started "started")))
          ;; Salvage rebuilds the identical review gate, reasoning included.
          (t/is (= (:reviewers (first workers))
                   (cli/started-worker-reviewers (first (:workers started)))))
          (t/is (= 2 (count (:reviewers (first workers)))))
          (t/is (= [] (cli/started-worker-reviewers (second (:workers started))))))
        ;; A pre-fix started.json has no reviewer list: refuse, never salvage unreviewed.
        (t/is (thrown? clojure.lang.ExceptionInfo
                       (cli/started-worker-reviewers {:id "w0" :harness "codex" :model "m"})))))))
