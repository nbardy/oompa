(ns agentnet.task-graph
  "Read-only selected graph readiness. Canonical tasks own status; no prose inference."
  (:require [agentnet.task-execution :as execution]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]))

(def schema "oompa.task-graph/v1")
(defn lookup [m id] (or (get m id) (get m (keyword id))))

(defn- canonical [value]
  (cond (map? value) (into (sorted-map) (map (fn [[k v]] [(execution/key-string k) (canonical v)]) value))
        (sequential? value) (mapv canonical value)
        :else value))

(defn- digest [value]
  (format "%064x" (java.math.BigInteger. 1
                     (.digest (java.security.MessageDigest/getInstance "SHA-256")
                              (.getBytes (json/generate-string (canonical value)) "UTF-8")))))

(defn- source-audit [root validator]
  (let [base (.getCanonicalFile (io/file root))]
    (mapv (fn [[key expected]]
            (let [path (execution/key-string key) file (io/file base path)
                  safe? (and (not (.isAbsolute (io/file path)))
                             (.startsWith (.toPath (.getCanonicalFile file)) (.toPath base))
                             (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
                  observed (try
                             (when (and safe? (.isFile file))
                               {:actual (execution/file-sha256 file)
                                :tracked (try
                                           (execution/git! root (+ (execution/now-ms) 30000)
                                                           "ls-files" "--error-unmatch" "--" path)
                                           true (catch Exception _ false))})
                             (catch Exception _ {:unreadable true}))
                  actual (:actual observed)]
              (cond-> {:path path :expected expected
                       :state (cond (not safe?) "unsafe-path" (:unreadable observed) "unreadable"
                                    (nil? actual) "missing" (not (:tracked observed)) "untracked"
                                    (not= expected actual) "mismatch" :else "ready")}
                actual (assoc :actual actual))))
          (sort-by (comp execution/key-string key) (:source_files validator)))))

(defn- cycle? [id dependencies]
  (letfn [(visit [node seen]
            (or (contains? seen node)
                (some #(visit % (conj seen node)) (lookup dependencies node))))]
    (boolean (visit id #{}))))

(defn- issue [code class action]
  {:code code :class class :action action})

(def internal-holds #{"execution-blocked" "validation-blocked" "validator-unconfigured"
                      "canonical-revision-conflict" "attempt-timeout" "attempt-budget-exhausted" "consumer-rejected"})

(defn audit!
  "Inspect only selected unresolved consumers, never execute a validator. Ready
   source work is admitted independently of other nodes' broken prerequisites.
   `dependencies` is the actual configured canonical adapter graph."
  [root config tasks dependencies held & [{:keys [claim-denial active-task-ids]}]]
  (let [by-id (into {} (map (juxt :id identity) tasks))
        items
        (mapv
          (fn [task]
            (let [id (:id task) prior (lookup held id)
                  policy (lookup (:task_readiness config) id)
                  role (or (:role policy) "consumer")
                  validator (execution/validator-for config id)
                  prepared? (boolean (some #{id} (:prepare_only_task_ids config)))
                  deps (mapv (fn [dependency]
                               {:task_id dependency :status (or (:status (get by-id dependency)) "unselected")})
                             (or (lookup dependencies id) []))
                  done? (= "done" (:status task))
                  same-hold? (and prior (= (:revision task) (or (:held_task_revision prior) (:claim_revision prior))))
                  active? (and (= "in_progress" (:status task)) (contains? (set active-task-ids) id))
                  files (when (and (not done?) validator (not active?) (not= role "observed"))
                          (source-audit (or (get-in prior [:workspace :path]) root) validator))
                  source-issues (for [file files :when (not= "ready" (:state file))]
                                  (assoc (issue (str "validator-source-" (:state file)) "internal"
                                                "Register and independently review the exact source/consumer prerequisite; amend its pin only through reviewed config authority.")
                                         :path (:path file)))
                  waiting-deps (filterv #(not= "done" (:status %)) deps)
                  owner-class (:blocker_class policy)
                  hold? (or same-hold? (= "blocked" (:status task)) (= "review" (:status task)))
                  issues (vec
                           (concat
                             (when (and (not done?) (cycle? id dependencies))
                               [(issue "dependency-cycle" "internal" "Repair the selected canonical prerequisite graph.")])
                             (for [dependency deps :when (and (not done?) (= "unselected" (:status dependency)))]
                               (assoc (issue "dependency-unselected" "internal" "Admit the existing canonical prerequisite through reviewed config scope.")
                                      :dependency (:task_id dependency)))
                             (when (and (not done?) (not= role "observed") (nil? validator))
                               [(issue "consumer-unconfigured" "internal" "Register a source/consumer preparation prerequisite; preparation alone cannot accept this parent.")])
                             source-issues
                             (when (and (not done?) hold?)
                               [(issue (or (:held_reason prior) (:reason_code policy) "canonical-hold")
                                       (cond (contains? internal-holds (:held_reason prior)) "internal"
                                             (contains? #{"internal" "external" "acceptance"} owner-class) owner-class
                                             :else "internal")
                                       (or (:next_action policy)
                                           (if (or (contains? internal-holds (:held_reason prior)) owner-class)
                                             "Reconcile the recorded hold with its accountable owner; preserve its source, session and attempt history."
                                             "Classify the canonical hold through typed owner readiness metadata; never infer retry permission from prose.")))])
                             (when (and (not= role "observed") (= "in_progress" (:status task)) (not prior) (not active?))
                               [(issue "execution-custody-unverified" "internal"
                                       "Reconcile the canonical claim with its actual live or retained execution; never steal or silently recycle it.")])
                             (when (and (not done?) (:paused task))
                               [(issue "canonical-paused" "external" "Wait for an explicit owner unpause.")])
                             (when (and (not done?) (= "cancelled" (:status task)))
                               [(issue "canonical-cancelled" "acceptance" "Accountable owner must reconcile this selected gate; cancelled is not accepted.")])
                             (when (and (not done?) (= role "observed") (not hold?) (not active?) (empty? waiting-deps))
                               [(issue (or (:reason_code policy) "owner-acceptance-required")
                                       (or owner-class "acceptance")
                                       (or (:next_action policy) "The accountable owner must record the original acceptance evidence."))])
                             (when-let [denial (and (not done?) claim-denial (claim-denial task))]
                               [(issue "worker-policy-denied" "internal" "Reconcile the selected worker policy with the canonical task; do not bypass it.")])))
                  ready? (and (= "open" (:status task)) (false? (:paused task))
                              (not same-hold?) (empty? waiting-deps) (not= role "observed")
                              (or prepared? validator)
                              (not-any? #(and (= "internal" (:class %))
                                              (not (and prepared? (= "consumer-unconfigured" (:code %))))) issues))]
              {:task_id id :revision (:revision task) :canonical_status (:status task) :owner_id (:ownerId task)
               :readiness policy
               :state (cond done? "accepted" active? "running" ready? "ready"
                            (some #(= "internal" (:class %)) issues) "needs-reconciliation"
                            :else "externally-blocked")
               :role role :preparation_only prepared? :dependencies deps :issues issues
               :source_files (or files [])
               :held (when prior (select-keys prior [:held_reason :claim_revision :held_task_revision :attempt :session_id]))}))
          tasks)
        ;; A blocked dependency propagates its real reason; it is not a license
        ;; to classify the dependent consumer as external by default.
        actions (vec (mapcat (fn [item]
                              (for [problem (:issues item) :when (= "internal" (:class problem))]
                                (merge {:task_id (:task_id item) :revision (:revision item)
                                        :required_owner (or (get-in item [:readiness :required_owner]) (:owner_id item))} problem))) items))
        state (cond (and (seq items) (every? #(= "accepted" (:state %)) items)) "accepted"
                    (some #(= "running" (:state %)) items) "running"
                    (some #(= "ready" (:state %)) items) "ready"
                    (or (empty? items) (seq actions)) "needs-reconciliation"
                    :else "externally-blocked")
        report {:schema schema :state state :items items :actions actions}]
    (assoc report :fingerprint (digest report))))

(defn ready-ids [audit]
  (set (map :task_id (filter #(= "ready" (:state %)) (:items audit)))))
