(ns agentnet.buddy-owner-scope-test
  (:require [agentnet.tasks :as tasks]
            [agentnet.buddy-tasks :as buddy]
            [clojure.test :as t]))

(t/deftest configured-task-owners-remain-authoritative
  (let [config {:type "buddies" :workspace_id "w" :buddy_id "lead"
                :task_ids ["product-task" "science-task"]
                :owner_by_task {:product-task "product" :science-task "science"}}
        rows (atom {"product-task" {:id "product-task" :ownerId "product" :workspaceId "w"
                                    :revision 2 :paused false :status "open" :title "Product"}
                    "science-task" {:id "science-task" :ownerId "science" :workspaceId "w"
                                    :revision 3 :paused false :status "blocked" :title "Science"}})]
    (try
      (tasks/configure! config)
      (binding [buddy/*transport* (fn [tool args]
                                    (assert (= "tasks" tool))
                                    {:task (get @rows (get-in args [:action :taskId]))})]
        (t/is (= ["product-task"] (mapv :id (tasks/list-pending))))
        (t/is (= "science" (:ownerId (tasks/get-task "science-task"))))
        (swap! rows assoc-in ["product-task" :ownerId] "unexpected-owner")
        (t/is (thrown? Exception (tasks/get-task "product-task"))))
      (finally (tasks/configure! nil)))))

(let [{:keys [fail error]} (t/run-tests 'agentnet.buddy-owner-scope-test)]
  (when (pos? (+ fail error)) (throw (ex-info "Owner scope regression" {}))))
