(ns agentnet.buddy-tasks
  "Scoped Buddy task adapter. No filesystem queue, owner credentials or database access."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def ^:dynamic *transport* nil) ; tests: (fn [tool arguments] decoded-tool-result)
(def ^:dynamic *deadline-ms* nil)
(defonce ^:private settings (atom nil))
(defonce ^:private claims (atom {}))
(defonce ^:private created (atom #{}))
(defonce ^:private initialized? (atom false))
(defonce ^:private scan-cache (atom {})) ; shared per-task scan snapshots, see list-all
;; Ids under root_task_ids (roots, containers and work items) seen by the latest
;; scan. Membership is re-derived from canonical parentId on every scan, so a
;; child created under a root mid-run is admitted at the next scan.
(defonce ^:private discovered (atom #{}))

(defn- id-list? [value] (and (sequential? value) (every? #(and (string? %) (not (str/blank? %))) value)))

(defn configure! [config]
  (doseq [field [:workspace_id :buddy_id]]
    (when (str/blank? (get config field))
      (throw (ex-info (str "Buddy task backend requires " (name field)) {:field field}))))
  ;; Selection = explicit task_ids (ordered) + work items under root_task_ids.
  ;; At least one key must be present; an explicit empty task_ids still means
  ;; "only tasks created here". Neither key is ever guessed.
  (when-not (or (contains? config :task_ids) (contains? config :root_task_ids))
    (throw (ex-info "Buddy task backend requires explicit task_ids and/or root_task_ids (an empty task_ids list admits only tasks created here)" {})))
  (when (and (contains? config :task_ids) (not (id-list? (:task_ids config))))
    (throw (ex-info "Buddy task backend task_ids must be a list of task IDs" {:field :task_ids})))
  (when (and (contains? config :root_task_ids)
             (not (and (id-list? (:root_task_ids config)) (seq (:root_task_ids config)))))
    (throw (ex-info "Buddy task backend root_task_ids must be a nonempty list of task IDs" {:field :root_task_ids})))
  (reset! settings (merge {:url_env "OOMPA_BUDDY_MCP_URL"
                          :token_env "OOMPA_BUDDY_MCP_TOKEN"
                          :task_ids [] :root_task_ids []} config))
  (reset! claims {})
  (reset! created #{})
  (reset! discovered #{})
  (reset! initialized? false)
  (reset! scan-cache {})
  nil)

(defn- rpc! [method params]
  (let [{:keys [url_env token_env]} @settings
        url (or (:url @settings) (System/getenv url_env))
        token (System/getenv (or (:authorization_env @settings) token_env))]
    (when (or (str/blank? url) (str/blank? token))
      (throw (ex-info (str "Buddy MCP transport requires " url_env " and " token_env
                           " from a supported scoped turn grant; no local fallback") {:type :transport-unconfigured})))
    (let [uri (java.net.URI. url)]
      (when-not (and (= "http" (.getScheme uri))
                     (#{"127.0.0.1" "localhost" "[::1]"} (.getHost uri))
                     (= "/mcp" (.getPath uri))
                     (nil? (.getRawUserInfo uri)) (nil? (.getQuery uri)))
        (throw (ex-info "Buddy MCP URL must be the supported loopback /mcp endpoint" {}))))
    (let [id (str (random-uuid))
          response (try
                     (http/post url {:headers {"Authorization" (if (str/starts-with? token "Bearer ") token (str "Bearer " token))
                                               "Content-Type" "application/json"
                                               "Accept" "application/json, text/event-stream"
                                               "MCP-Protocol-Version" "2025-03-26"}
                                     :body (json/generate-string {:jsonrpc "2.0" :id id :method method :params params})
                                     :timeout (if *deadline-ms*
                                                (max 1 (min 30000 (- *deadline-ms* (System/currentTimeMillis))))
                                                30000)
                                     :throw false :follow-redirects :never})
                     (catch Exception e
                       (throw (ex-info (str "Buddy MCP request failed (" (.getName (class e)) ": " (.getMessage e)
                                            "); check scoped grant and endpoint")
                                       {:type :transport-error}))))]
      (when-not (= 200 (:status response))
        (throw (ex-info "Buddy MCP refused request; check scoped turn grant (never owner credentials)"
                        {:type :transport-error :http-status (:status response)})))
      (let [body (:body response)
            messages (if (str/starts-with? (str/trim body) "{")
                       [(json/parse-string body true)]
                       (->> (str/split body #"\r?\n\r?\n")
                            (keep (fn [event]
                                    (let [data (->> (str/split-lines event)
                                                    (keep #(when (str/starts-with? % "data:") (str/trim (subs % 5))))
                                                    (str/join "\n"))]
                                      (when (seq data) (json/parse-string data true)))))))
            message (first (filter #(= id (:id %)) messages))]
        (when (or (nil? message) (:error message))
          (throw (ex-info "Buddy MCP returned an invalid or rejected JSON-RPC response" {:type :protocol-error})))
        (:result message)))))

(def ^:private read-retry-delays-ms [500 1000 2000 4000])

(defn- transport-error? [e] (= :transport-error (:type (ex-data e))))

(defn- rpc-read!
  "Reads are idempotent, so a transport failure (connection reset, timeout under
   a burst of parallel workers) is retried with jittered backoff. Writes are never
   retried here: a lost response could hide an applied CAS write."
  [method params]
  (loop [delays read-retry-delays-ms]
    (let [outcome (try {:value (rpc! method params)}
                       (catch clojure.lang.ExceptionInfo e
                         (if (and (transport-error? e) (seq delays)) {:retry e} (throw e))))]
      (if (contains? outcome :value)
        (:value outcome)
        (do (Thread/sleep (+ (first delays) (rand-int (first delays))))
            (recur (rest delays)))))))

(defn- call! [tool arguments]
  (if *transport*
    (*transport* tool arguments)
    (do
      (when-not @initialized?
        (rpc-read! "initialize" {:protocolVersion "2025-03-26" :capabilities {}
                             :clientInfo {:name "oompa-buddy-tasks" :version "1"}})
        (reset! initialized? true))
      (let [result ((if (= "tasks" tool) rpc-read! rpc!) "tools/call" {:name tool :arguments arguments})
            body (:text (first (:content result)))]
        (when (:isError result)
          (throw (ex-info (str "Buddy " tool " rejected: " body)
                          {:type (if (and body (re-find #"revision[_ ]conflict" body)) :revision-conflict :tool-error)})))
        (when-not (string? body)
          (throw (ex-info "Buddy tool response has no primary JSON content" {:type :protocol-error})))
        (json/parse-string body true)))))

(defn- selection-kind
  "How a task entered this run's scope: :explicit (task_ids), :created (by this
   process) or :discovered (under a root_task_id). nil = outside the scope.
   Explicit wins so owner_by_task still binds a listed task that is also under a root."
  [id]
  (cond (some #{id} (:task_ids @settings)) :explicit
        (contains? @created id) :created
        (contains? @discovered id) :discovered))
(defn- allowed? [id] (some? (selection-kind id)))
(defn- owner-matches? [task]
  (case (selection-kind (:id task))
    :explicit (= (or (get-in @settings [:owner_by_task (:id task)])
                     (get-in @settings [:owner_by_task (keyword (:id task))])
                     (:buddy_id @settings)) (:ownerId task))
    :created (= (:buddy_id @settings) (:ownerId task))
    ;; Discovered tasks carry their own canonical owner; the host grant bounds
    ;; them by ancestry, not by an owner list.
    :discovered (and (string? (:ownerId task)) (not (str/blank? (:ownerId task))))
    nil false))
(defn- validate-task [task]
  (when-not (and (map? task) (allowed? (:id task))
                 (= (:workspace_id @settings) (:workspaceId task))
                 (owner-matches? task)
                 (pos-int? (:revision task))
                 (boolean? (:paused task))
                 (#{"open" "in_progress" "blocked" "review" "done" "cancelled"} (:status task)))
    (throw (ex-info "Buddy task is malformed or outside the configured owner/workspace/task scope" {:type :scope-error})))
  (assoc task :summary (:title task) :description (:doneCriteria task)
         :acceptance [(:doneCriteria task)] :_buddy true))

(defn get-task [id]
  (when-not (allowed? id)
    (throw (ex-info "Task ID is outside the configured Buddy task allowlist" {:type :scope-error :id id})))
  (validate-task (:task (call! "tasks" {:action {:kind "get" :taskId id}}))))

;; Every worker thread scans the whole selection on each loop. Per-task reads
;; from ten workers x forty tasks hammered the Buddy server and bursts dropped
;; connections (swarm 00733e4f). A scan is now ONE `tasks list` call shared by
;; all workers for a short window; claims stay safe because every write is a CAS
;; on the observed revision, and a stale read only costs a conflict and rescan.
(def ^:private scan-ttl-ms 3000)

(defn- remember! [task]
  (swap! scan-cache assoc-in [:tasks (:id task)] task)
  task)

;; Two server row shapes exist: Buddies >= ee1b1b0 returns full records from the
;; Oompa-scoped list (one call is the whole scan); older servers return summary
;; rows without revision/criteria, which need one `get` each. Delete the summary
;; branch once every Buddies server running Oompa is at or past ee1b1b0.
(defn- scan-rows [rows]
  (if (every? :revision rows)
    (mapv validate-task rows)
    (mapv #(get-task (:id %)) rows)))

(def ^:private closed-statuses #{"done" "cancelled"})

(defn subtree
  "Pure expansion of root_task_ids over one scan's rows (canonical parentId).
   Returns {:members #{every id reachable from a root, roots included}
            :work-items [ids in depth-first pre-order]}.
   Work-item rule (owner request 2026-10-24, \"point at a parent task and do
   all of its children\"):
     - a root is a container if it has any child, else it is itself a work item;
     - a non-root task with an open (not done/cancelled) child is a container;
       a non-root task with no open children is a work item.
   Containers are never claimed. Paused/blocked/done/cancelled work items are
   still listed; runnable? already refuses them, exactly as for explicit tasks.
   Child order is the server's row order (creation order), so it is stable."
  [rows root-ids]
  (let [by-id (into {} (map (juxt :id identity)) rows)
        children (group-by :parentId rows)
        work-item? (fn [id root?]
                     (let [kids (get children id)]
                       (if root?
                         (empty? kids)
                         (not-any? #(not (closed-statuses (:status %))) kids))))]
    (loop [stack (mapv (fn [id] [id true]) (reverse root-ids))
           members #{} items []]
      (if-let [[id root?] (peek stack)]
        (do
          (when-not (contains? by-id id)
            (throw (ex-info (str "Task " id " under root_task_ids is missing from the scoped Buddy scan")
                            {:type :scope-error :id id})))
          ;; A parentId cycle (or a root nested under another root) would revisit;
          ;; refuse a cycle loudly, but let a nested root count once.
          (if (contains? members id)
            (if root?
              (recur (pop stack) members items)
              (throw (ex-info "Buddy task parentId graph has a cycle" {:type :scope-error :id id})))
            (recur (into (pop stack) (map (fn [kid] [(:id kid) false])) (reverse (get children id)))
                   (conj members id)
                   (if (work-item? id root?) (conj items id) items))))
        {:members members :work-items items}))))

(defn- scan! []
  (let [rows (call! "tasks" {:action {:kind "list" :scope {:workspace (:workspace_id @settings)}
                                      :include "all"}})
        ;; Membership must be known before validation: validate-task admits a
        ;; discovered row only if it is reachable from a configured root.
        {:keys [members work-items]} (subtree rows (:root_task_ids @settings))
        _ (reset! discovered members)
        tasks (into {} (map (juxt :id identity)) (scan-rows rows))]
    (reset! scan-cache {:at (System/currentTimeMillis) :tasks tasks :work-items work-items})
    tasks))

(defn- snapshot []
  (let [{:keys [at] :as cached} @scan-cache]
    ;; Injected test transports are in-memory and model instant visibility.
    (if (and at (nil? *transport*) (< (- (System/currentTimeMillis) at) scan-ttl-ms))
      cached
      (do (scan!) @scan-cache))))

(defn list-all []
  ;; Explicit selection order is execution priority, then discovered work items
  ;; under root_task_ids (depth-first), then locally created tasks.
  ;; Deduplication must not reorder selected IDs.
  (let [{:keys [tasks work-items]} (snapshot)]
    (mapv (fn [id]
            (or (get tasks id)
                (throw (ex-info (str "Selected task " id " is missing from the scoped Buddy scan")
                                {:type :scope-error :id id}))))
          (distinct (concat (:task_ids @settings) work-items (sort @created))))))

(defn selection-dependencies [] (:dependencies @settings))

(defn- dependencies-done? [task]
  ;; Current Buddy Task has no dependency field. Explicit dependencies are admitted only
  ;; through the scoped config; reject unknown incoming dependency formats rather than guess.
  (and (not-any? #(contains? task %) [:dependencies :dependsOn :depends_on])
       (every? #(= "done" (:status (get-task %)))
               (or (get-in @settings [:dependencies (:id task)])
                   (get-in @settings [:dependencies (keyword (:id task))]) []))))

(defn runnable? [task]
  (and (= "open" (:status task)) (false? (:paused task)) (dependencies-done? task)))
(defn list-pending [] (filterv runnable? (list-all)))
(defn list-current [] (filterv #(= "in_progress" (:status %)) (list-all)))
(defn list-complete [] (filterv #(= "done" (:status %)) (list-all)))

(defn- update! [task changes]
  (remember!
   (validate-task
    (call! "task_write" {:write {:kind "update" :taskId (:id task)
                                  :baseRevision (:revision task) :changes changes}
                         :key (str "oompa-" (random-uuid))}))))

(defn claim-task! [candidate]
  ;; Keep the revision the caller observed: two claimants can never both win.
  (let [task (validate-task candidate)]
    (when (runnable? task)
      (try
        (let [claimed (update! task {:status "in_progress"})]
          (when-not (= "in_progress" (:status claimed))
            (throw (ex-info "Buddy claim did not enter in_progress" {})))
          (swap! claims assoc (:id task) (:revision claimed))
          claimed)
        (catch clojure.lang.ExceptionInfo e
          (if (= :revision-conflict (:type (ex-data e))) nil (throw e)))))))

(defn claim-by-id! [id {:keys [claim-denial-fn]}]
  (if-not (allowed? id)
    {:status :not-found :id id}
    (let [task (get-task id)]
      (cond
        (= "in_progress" (:status task)) {:status :already-taken :id id}
        (not (runnable? task)) {:status :not-claimable :id id :reason "Buddy task is paused, blocked, closed, in review or has unfinished dependencies"}
        :else
        (if-let [denial (when claim-denial-fn (claim-denial-fn task))]
          (merge {:status :not-claimable :id id}
                 (if (map? denial) denial {:reason (str denial)}))
          {:status (if (claim-task! task) :claimed :already-taken) :id id})))))

(defn- release! [task status & [details]]
  (let [id (:id task)
        owned-revision (get @claims id)]
    ;; Startup cleanup must never recycle another process's work.
    (when owned-revision
      (let [fresh (get-task id)]
        (when-not (and (= owned-revision (:revision fresh))
                       (= "in_progress" (:status fresh)) (false? (:paused fresh)))
          (throw (ex-info "Buddy task changed since this process claimed it; re-read and reconcile" {:type :revision-conflict :id id})))
        (let [result (update! fresh (merge details {:status status}))]
          (swap! claims dissoc id)
          result)))))

(defn complete-task! [task]
  ;; A model/merge signal is only a review handoff, never independent acceptance.
  (release! task "review"))
(defn unclaim-task! [task] (release! task "open"))
(defn block-task! [task result]
  (when-not (and (string? (:reason result)) (not (str/blank? (:reason result)))
                 (or (nil? (:requiredOwnerAction result)) (string? (:requiredOwnerAction result))))
    (throw (ex-info "A canonical hold requires a nonempty reason and optional owner action" {})))
  (release! task "blocked" (cond-> {:blockedReason (:reason result)}
                            (not (str/blank? (:requiredOwnerAction result)))
                            (assoc :nextAction (:requiredOwnerAction result)))))

(defn release-abandoned!
  "Explicit requeue of a claim this process is giving up (attempt budget,
   timeout, execution failure, rejected block, run exit). Swarm 455987ea
   (2026-10-24) went idle because abandoned claims stayed `in_progress`, which
   the selected graph audit correctly refuses to steal (execution-custody-
   unverified). The release is canonical data on the task: status `open` plus a
   nextAction naming the retained source and last findings.

   Only this process's own claim at its exact claimed revision is released
   (release! re-reads and CASes). A claim held by anyone else is reported as
   :not-owned and left untouched, so a live worker's claim is never stolen.
   Returns {:kind :released :task t} | {:kind :not-owned}."
  [task next-action]
  (when (str/blank? next-action)
    (throw (ex-info "An abandoned-claim release requires a nonempty nextAction" {:id (:id task)})))
  (if (contains? @claims (:id task))
    {:kind :released :task (release! task "open" {:nextAction next-action})}
    {:kind :not-owned}))

(defn restore-claim!
  "Restore only an explicitly selected persisted execution at its exact revision.
   No scan/recycle of foreign in-progress tasks is permitted."
  [id revision]
  (let [task (get-task id)]
    (when-not (and (= revision (:revision task)) (= "in_progress" (:status task))
                   (false? (:paused task)))
      (throw (ex-info "Persisted Buddy execution no longer matches its canonical claim"
                      {:type :revision-conflict :id id})))
    (swap! claims assoc id revision)
    task))

(defn request-acceptance!
  "The run-owned host executes the pinned oracle and integrates before done.
   Worker/model credentials cannot issue the done task_write transition."
  [task workspace source-commit]
  (let [id (:id task)]
    (when-not (= (:revision task) (get @claims id))
      (throw (ex-info "Acceptance requires this execution's exact canonical claim" {:id id})))
    (let [result (call! "oompa" {:action {:kind "accept" :taskId id
                                         :baseRevision (:revision task)
                                         :sourceCommit source-commit
                                         :workspacePath (:path workspace)}})]
      (when (= "accepted" (:kind result))
        (let [accepted (validate-task (:task result))]
          (when-not (= "done" (:status accepted))
            (throw (ex-info "Host acceptance did not return canonical done" {:id id})))
          (swap! claims dissoc id)))
      (when-not (#{"validating" "accepted" "rejected" "blocked"} (:kind result))
        (throw (ex-info "Host returned an unknown executable acceptance state" {:id id})))
      result)))

(defn create-task! [task]
  (when (or (seq (:dependencies task)) (seq (:files task)))
    (throw (ex-info "Buddy create supports title and done criteria; dependencies/files require an explicit supported task contract" {})))
  (when (:parentId task) (get-task (:parentId task)))
  (let [result (call! "task_write"
                      {:write (cond-> {:kind "create" :ownerId (:buddy_id @settings)
                                       :title (:summary task)
                                       :doneCriteria (str/join "\n" (remove str/blank? (concat [(:description task)] (:acceptance task))))}
                                (:parentId task) (assoc :parentId (:parentId task)))
                       :key (str "oompa-create-" (random-uuid))})]
    (swap! created conj (:id result))
    (validate-task result)))

(defn all-complete? []
  (every? #(#{"done" "cancelled"} (:status %)) (list-all)))
