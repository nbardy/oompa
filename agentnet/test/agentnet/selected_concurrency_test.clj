(ns agentnet.selected-concurrency-test
  "Regression (2026-10-22): the six-worker beta swarm ran only one worker,
   because selected-writer-lock wrapped every worker's whole cycle including
   the model run. Source-only workers must overlap; GPU-capable workers and the
   host integration window must stay exclusive."
  (:require [agentnet.worker :as worker]
            [clojure.test :refer [deftest is run-tests]])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(def ^:private cycle-lock #'worker/with-selected-cycle-lock)
(def ^:private turn! #'worker/integration-turn!)
(def ^:private release! #'worker/release-integration!)

(defn- worker [id gpu?] {:swarm-id "s" :id id :can-claim-gpu gpu?})

(defn- overlapped?
  "True when B's cycle body starts while A's body is still running."
  [a b]
  (let [b-started (CountDownLatch. 1)
        fa (future (cycle-lock a #(.await b-started 2 TimeUnit/SECONDS)))
        _ (Thread/sleep 100)
        fb (future (cycle-lock b #(.countDown b-started)))]
    (let [overlap @fa] @fb overlap)))

(deftest source-only-cycles-run-concurrently
  (is (true? (overlapped? (worker "w1" false) (worker "w2" false)))))

(deftest gpu-capable-cycles-stay-exclusive
  ;; B cannot start until A's await times out, so A observes no overlap.
  (is (false? (overlapped? (worker "w0" true) (worker "w9" true)))))

(deftest integration-window-admits-one-worker
  (let [a (worker "w1" false) b (worker "w2" false)]
    (try
      (is (true? (turn! a)))
      (is (true? (turn! a)) "re-entrant for the owner across polling steps")
      (is (false? (turn! b)))
      (release! b)
      (is (false? (turn! b)) "a non-owner release must not free the window")
      (release! a)
      (is (true? (turn! b)))
      (finally (release! a) (release! b)))))

(let [{:keys [fail error]} (run-tests 'agentnet.selected-concurrency-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
