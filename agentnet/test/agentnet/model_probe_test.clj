(ns agentnet.model-probe-test
  (:require [agentnet.cli :as cli] [agentnet.harness :as harness] [clojure.test :as t]))
(t/deftest probe-uses-actual-worker-execution-contract
  (let [seen (atom nil)]
    (with-redefs [harness/build-probe-cmd (fn [& _] (throw (Exception. "raw argv lost prompt stdin")))
                  harness/run-command! (fn [provider opts] (reset! seen [provider opts])
                                         {:exit 0 :out "ok" :err ""})]
      (t/is (true? ((deref (resolve (symbol "agentnet.cli/probe-model"))) :codex "gpt-6.1-sol")))
      (t/is (= :codex (first @seen)))
      (t/is (= "[_HIDE_TEST_] say ok" (:prompt (second @seen))))
      (t/is (= 60000 (:timeout-ms (second @seen)))))))
(t/deftest failed-runtime-remains-a-refusal
  (with-redefs [harness/run-command! (fn [& _] {:exit 124 :out "" :err "timeout"})]
    (t/is (false? ((deref (resolve (symbol "agentnet.cli/probe-model"))) :codex "gpt-6.1-sol")))))
(let [{:keys [fail error]} (t/run-tests 'agentnet.model-probe-test)]
  (when (pos? (+ fail error)) (throw (ex-info "Probe regression" {}))))
