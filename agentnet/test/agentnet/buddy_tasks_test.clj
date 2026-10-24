(ns agentnet.buddy-tasks-test
  (:require [agentnet.tasks :as tasks]
            [agentnet.buddy-tasks :as buddy]
            [agentnet.filesystem-tasks :as filesystem]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures run-tests]]))

(def config {:type "buddies" :workspace_id "workspace" :buddy_id "buddy"
             :task_ids ["one" "two"]})
(defn task [id status]
  {:id id :workspaceId "workspace" :ownerId "buddy" :title id
   :doneCriteria "independent acceptance" :status status :paused false :revision 1})
(defn fake-transport [store calls]
  (fn [tool args]
    (swap! calls conj [tool args])
    (case tool
      "tasks" (if (= "list" (get-in args [:action :kind]))
                (vec (vals @store))
                {:task (get @store (get-in args [:action :taskId]))})
      "task_write"
      (let [{:keys [kind taskId baseRevision changes] :as write} (:write args)]
        (locking store
          (if (= "create" kind)
            (let [created (assoc (task "created" "open") :title (:title write)
                                 :doneCriteria (:doneCriteria write))]
              (swap! store assoc "created" created)
              created)
            (let [old (get @store taskId)]
              (when-not (= baseRevision (:revision old))
                (throw (ex-info "revision conflict" {:type :revision-conflict})))
              (let [updated (merge old changes {:revision (inc baseRevision)})]
                (swap! store assoc taskId updated)
                updated))))))))

(use-fixtures :each (fn [f] (try (tasks/configure! config) (f)
                                 (finally (tasks/configure! nil)))))

(deftest buddy-mode-never-enters-filesystem-queue
  (let [store (atom {"one" (task "one" "open") "two" (task "two" "blocked")})
        calls (atom [])]
    (binding [buddy/*transport* (fake-transport store calls)]
      (with-redefs [io/file (fn [& _] (throw (ex-info "filesystem queue accessed" {})))]
        (tasks/ensure-dirs!)
        (tasks/migrate-from-tasks-json!)
        (is (= ["one"] (mapv :id (tasks/list-pending))))
        (is (= :claimed (:status (tasks/claim-by-id! "one"))))
        (is (= ["one"] (tasks/complete-by-ids! ["one"])))
        (is (= "review" (get-in @store ["one" :status])))
        (is (false? (tasks/all-complete?)))
        (is (= [] (tasks/list-complete)))
        (is (= "created" (:id (tasks/create-task! {:summary "new" :acceptance ["passes"]}))))
        (is (= "buddy" (get-in @store ["created" :ownerId])))))))

(deftest explicit-selection-order-controls-claims-without-bypassing-dependencies
  (tasks/configure! (assoc config :task_ids ["two" "one" "two"] :dependencies {"two" ["one"]}))
  (let [store (atom {"one" (task "one" "open") "two" (task "two" "open")})
        calls (atom [])]
    (binding [buddy/*transport* (fake-transport store calls)]
      (is (= ["two" "one"] (mapv :id (tasks/list-all))))
      (is (= ["one"] (mapv :id (tasks/list-pending))))
      (swap! store assoc-in ["one" :status] "done")
      (is (= ["two"] (mapv :id (tasks/list-pending))))
      (is (= "two" (:id (tasks/claim-next!))))
      (is (= "in_progress" (get-in @store ["two" :status]))))
    (tasks/configure! (assoc config :task_ids ["two" "one"]))
    (swap! store assoc-in ["one" :status] "open")
    (swap! store assoc-in ["two" :status] "open")
    (binding [buddy/*transport* (fake-transport store calls)]
      (is (= ["two" "one"] (mapv :id (tasks/list-pending))))
      (is (= "two" (:id (tasks/claim-next!))))
      (is (= "open" (get-in @store ["one" :status]))))))

(deftest compare-and-swap-claim-has-exactly-one-winner
  (let [store (atom {"one" (task "one" "open") "two" (task "two" "done")})
        calls (atom [])]
    (binding [buddy/*transport* (fake-transport store calls)]
      (let [observed (tasks/get-task "one")
            start (promise)
            contenders (mapv (fn [_] (future @start (tasks/claim-task! observed))) (range 8))]
        (deliver start true)
        (is (= 1 (count (filter some? (map deref contenders))))))
      (is (= "buddy" (get-in @store ["one" :ownerId])))
      (is (= 2 (get-in @store ["one" :revision])))
      (is (every? #(not (contains? (get-in % [1 :write :changes]) :ownerId)) @calls)))))

(deftest paused-blocked-review-and-dependent-tasks-are-not-runnable
  (doseq [candidate [(assoc (task "one" "open") :paused true)
                     (task "one" "blocked") (task "one" "review")
                     (task "one" "cancelled")
                     (assoc (task "one" "open") :dependsOn ["two"])]]
    (let [calls (atom []) store (atom {"one" candidate "two" (task "two" "done")})]
      (binding [buddy/*transport* (fake-transport store calls)]
        (is (= :not-claimable (:status (tasks/claim-by-id! "one"))))
        (is (not-any? #(= "task_write" (first %)) @calls)))))
  (tasks/configure! (assoc config :dependencies {"one" ["two"]}))
  (let [store (atom {"one" (task "one" "open") "two" (task "two" "blocked")})]
    (binding [buddy/*transport* (fake-transport store (atom []))]
      (is (empty? (tasks/list-pending)))
      (swap! store assoc-in ["two" :status] "done")
      (is (= ["one"] (mapv :id (tasks/list-pending)))))))

(deftest scope-errors-and-transport-errors-fail-closed
  (let [store (atom {"one" (assoc (task "one" "open") :ownerId "someone-else")})]
    (binding [buddy/*transport* (fake-transport store (atom []))]
      (is (thrown-with-msg? Exception #"scope" (tasks/get-task "one")))
      (is (thrown-with-msg? Exception #"allowlist" (tasks/get-task "outside")))))
  (binding [buddy/*transport* (fn [& _] (throw (ex-info "grant revoked" {})))]
    (with-redefs [io/file (fn [& _] (throw (ex-info "filesystem fallback" {})))]
      (is (thrown-with-msg? Exception #"grant revoked" (tasks/list-pending))))))

(deftest recycle-never-steals-another-process-claim
  (let [store (atom {"one" (task "one" "in_progress") "two" (task "two" "open")})
        calls (atom [])]
    (binding [buddy/*transport* (fake-transport store calls)]
      (is (= [] (tasks/recycle-all-current!)))
      (is (= :claimed (:status (tasks/claim-by-id! "two"))))
      (is (= ["two"] (tasks/recycle-tasks! #{"one" "two"})))
      (is (= "in_progress" (get-in @store ["one" :status])))
      (is (= "open" (get-in @store ["two" :status]))))))

(deftest external-edits-block-completion-and-recycle
  (let [store (atom {"one" (task "one" "open") "two" (task "two" "done")})]
    (binding [buddy/*transport* (fake-transport store (atom []))]
      (tasks/claim-by-id! "one")
      (swap! store update-in ["one" :revision] inc)
      (is (thrown-with-msg? Exception #"changed" (tasks/complete-by-ids! ["one"])))
      (is (thrown-with-msg? Exception #"changed" (tasks/recycle-tasks! #{"one"})))
      (is (= "in_progress" (get-in @store ["one" :status]))))))

(deftest filesystem-remains-default-and-buddy-config-is-explicit
  (tasks/configure! nil)
  (is (= :filesystem (tasks/backend?)))
  (is (false? (tasks/buddies?)))
  (is (thrown? Exception (tasks/configure! {:type "buddies"})))
  (is (thrown? Exception (tasks/configure! (dissoc config :task_ids)))))

(deftest restart-keeps-existing-in-progress-work-held
  (let [store (atom {"one" (task "one" "open") "two" (task "two" "done")})
        calls (atom [])]
    (binding [buddy/*transport* (fake-transport store calls)]
      (is (= :claimed (:status (tasks/claim-by-id! "one"))))
      (tasks/configure! config) ; no persisted claim ledger and no automatic reclaim
      (reset! calls [])
      (is (= [] (tasks/recycle-all-current!)))
      (is (= [] (tasks/complete-by-ids! ["one"])))
      (is (= "in_progress" (get-in @store ["one" :status])))
      (is (not-any? #(= "task_write" (first %)) @calls)))))

(deftest real-http-boundary-parses-sse-primary-content-and-refuses-auth-errors
  (tasks/configure! (assoc config :url "http://127.0.0.1:12345/mcp" :token_env "PATH"))
  (let [requests (atom [])]
    (with-redefs [http/post
                  (fn [_ options]
                    (let [request (json/parse-string (:body options) true)
                          result (if (= "initialize" (:method request))
                                   {:protocolVersion "2025-03-26" :capabilities {}}
                                   {:content [{:type "text" :text (json/generate-string {:task (task "one" "open")})}
                                              {:type "text" :text "unrelated delivery boundary"}]})]
                      (swap! requests conj request)
                      (is (= :never (:follow-redirects options)))
                      {:status 200 :body (str "event: message\ndata: "
                                              (json/generate-string {:jsonrpc "2.0" :id (:id request) :result result})
                                              "\n\n")}))]
      (is (= "one" (:id (tasks/get-task "one"))))
      (is (= ["initialize" "tools/call"] (mapv :method @requests)))
      (is (= {:name "tasks" :arguments {:action {:kind "get" :taskId "one"}}}
             (:params (second @requests)))))
    (with-redefs [http/post (fn [& _] {:status 401 :body "expired grant"})]
      (is (thrown-with-msg? Exception #"refused request" (tasks/get-task "one"))))))

(deftest filesystem-dispatch-preserves-upstream-default
  (tasks/configure! nil)
  (with-redefs [filesystem/list-pending (fn [] [{:id "local"}])]
    (is (= [{:id "local"}] (tasks/list-pending)))))

(deftest blocked-return-cas-retains-canonical-reason-owner-and-next-action
  (let [store (atom {"one" (task "one" "open") "two" (task "two" "open")}) calls (atom [])
        blocked {:reason "CUDA/replay/funding and full oracle absent"
                 :requiredOwnerAction "Lead supplies authorized CUDA target"}]
    (binding [buddy/*transport* (fake-transport store calls)]
      (tasks/claim-by-id! "one")
      (let [held (tasks/block-task! (tasks/get-task "one") blocked)]
        (is (= "blocked" (:status held)))
        (is (= (:reason blocked) (:blockedReason held)))
        (is (= (:requiredOwnerAction blocked) (:nextAction held)))
        (is (= "buddy" (:ownerId held)))
        (is (= 3 (:revision held))))
      (is (= ["two"] (mapv :id (tasks/list-pending))))
      (tasks/claim-by-id! "two")
      (swap! store update-in ["two" :revision] inc)
      (is (thrown-with-msg? Exception #"changed" (tasks/block-task! (tasks/get-task "two") blocked)))
      (is (= "in_progress" (get-in @store ["two" :status]))))))

(defn child [id parent status & {:as extra}]
  (merge (task id status) {:parentId parent :ownerId (str "owner-" id)} extra))

;; root_task_ids: the owner points a config at a parent task and Oompa works its
;; descendants. These guard the container/work-item rule and per-scan
;; re-expansion; a regression would either claim umbrella tasks (wasting a
;; worker on a whole epic) or freeze the queue at launch-time children.
(deftest root-task-ids-expand-descendants-on-every-scan
  (tasks/configure! {:type "buddies" :workspace_id "workspace" :buddy_id "buddy"
                     :root_task_ids ["root"]})
  ;; root
  ;;  +- epic (container: has open child)
  ;;  |   +- leaf-a open           -> work item, runnable
  ;;  |   +- leaf-p open, paused   -> work item, not runnable
  ;;  |   +- leaf-c cancelled      -> work item, closed
  ;;  +- finished (no open children: its only child is done) -> work item
  ;;  |   +- leaf-d done           -> work item, closed
  ;;  +- leaf-b blocked            -> work item, not runnable
  (let [store (atom (into (array-map)
                          (map (juxt :id identity))
                          [(assoc (task "root" "open") :parentId nil)
                           (child "epic" "root" "open")
                           (child "leaf-a" "epic" "open")
                           (child "leaf-p" "epic" "open" :paused true)
                           (child "leaf-c" "epic" "cancelled")
                           (child "finished" "root" "open")
                           (child "leaf-d" "finished" "done")
                           (child "leaf-b" "root" "blocked")]))
        calls (atom [])]
    ;; Eight rows keep array-map (server row) order for the order assertion.
    (binding [buddy/*transport* (fake-transport store calls)]
      (is (= ["leaf-a" "leaf-p" "leaf-c" "finished" "leaf-d" "leaf-b"] (mapv :id (tasks/list-all)))
          "roots and containers are never work items; depth-first child order")
      (is (= ["leaf-a" "finished"] (mapv :id (tasks/list-pending)))
          "paused/cancelled/done/blocked use the existing runnable? refusal")
      (is (= "owner-leaf-a" (:ownerId (tasks/get-task "leaf-a")))
          "discovered tasks keep their own canonical owner without owner_by_task")
      (testing "a child created under a container mid-run is picked up at the next scan"
        (swap! store assoc "leaf-new" (child "leaf-new" "epic" "open"))
        (is (some #{"leaf-new"} (mapv :id (tasks/list-pending)))))
      (testing "adding an open child turns a work item into a container"
        (swap! store assoc "follow-up" (child "follow-up" "finished" "open"))
        (is (not-any? #{"finished"} (mapv :id (tasks/list-all))))
        (is (some #{"follow-up"} (mapv :id (tasks/list-pending)))))
      (testing "a row outside every configured subtree is refused, not silently admitted"
        (swap! store assoc "stray" (child "stray" "elsewhere" "open"))
        (is (thrown-with-msg? Exception #"outside the configured" (tasks/list-all)))))))

(deftest root-task-ids-compose-with-explicit-task-ids
  (tasks/configure! (assoc config :task_ids ["one"] :root_task_ids ["root"]))
  (let [store (atom (array-map "one" (task "one" "open")
                               "root" (assoc (task "root" "open") :parentId nil)
                               "kid" (child "kid" "root" "open")))
        calls (atom [])]
    (binding [buddy/*transport* (fake-transport store calls)]
      (is (= ["one" "kid"] (mapv :id (tasks/list-all))) "explicit priority first")
      (is (= :claimed (:status (tasks/claim-by-id! "kid"))))))
  (testing "a root with no children is itself the work item"
    (tasks/configure! (assoc config :task_ids [] :root_task_ids ["solo"]))
    (binding [buddy/*transport* (fake-transport (atom {"solo" (assoc (task "solo" "open") :parentId nil)}) (atom []))]
      (is (= ["solo"] (mapv :id (tasks/list-pending))))))
  (testing "a configured root missing from the scoped scan is a scope error"
    (tasks/configure! (assoc config :task_ids [] :root_task_ids ["gone"]))
    (binding [buddy/*transport* (fake-transport (atom {}) (atom []))]
      (is (thrown-with-msg? Exception #"missing from the scoped Buddy scan" (tasks/list-all)))))
  (testing "config must name task_ids and/or a nonempty root_task_ids"
    (is (thrown-with-msg? Exception #"task_ids and/or root_task_ids"
                          (tasks/configure! (dissoc config :task_ids))))
    (is (thrown-with-msg? Exception #"nonempty"
                          (tasks/configure! (assoc config :root_task_ids []))))))

(let [{:keys [fail error]} (run-tests 'agentnet.buddy-tasks-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
