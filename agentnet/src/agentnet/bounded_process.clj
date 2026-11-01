(ns agentnet.bounded-process
  "Subprocess deadlines shared by model attempts and executable acceptance gates."
  (:require [babashka.process :as process]))

(defn run!
  "Run argv and kill the whole process tree at timeout-ms. No shell interpolation."
  [argv opts timeout-ms]
  (when-not (and (integer? timeout-ms) (pos? timeout-ms))
    (throw (ex-info "Process timeout must be a positive integer in milliseconds" {})))
  (let [started (System/currentTimeMillis)
        p (process/process argv (merge {:out :string :err :string} opts))
        result (deref p timeout-ms ::timeout)]
    (if (= ::timeout result)
      (do
        ;; Snapshot descendants before killing the parent: otherwise reparenting
        ;; can hide children which still hold files, GPU access, or stdout open.
        (let [children (with-open [stream (.descendants (:proc p))]
                         (vec (iterator-seq (.iterator stream))))]
          (doseq [child (reverse children)] (.destroyForcibly child))
          (.destroyForcibly (:proc p)))
        (process/destroy-tree p)
        {:exit 124 :out "" :err "Process deadline exceeded; process tree terminated"
         :timed-out? true :duration-ms (- (System/currentTimeMillis) started)})
      (assoc result :timed-out? false :duration-ms (- (System/currentTimeMillis) started)))))
