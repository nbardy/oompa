(ns agentnet.reviewer-effort-test
  (:require [agentnet.worker :as worker]
            [agentnet.harness :as harness]
            [agentnet.cli :as cli]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.test :as t]))

(t/deftest reviewer-config-retains-requested-effort
  (t/is (= [{:harness :claude :model "claude-opus-5-5" :reasoning "high" :prompts []}]
           (#'cli/parse-reviewers-config {:reviewers [{:model "claude:claude-opus-5-5:high"}]}))))

(t/deftest review-dispatch-passes-effort
  (let [seen (atom nil)]
    (with-redefs [process/sh (fn [& _] {:exit 0 :out "" :err ""})
                  harness/run-command! (fn [_ opts] (reset! seen opts)
                                         {:exit 0 :out "VERDICT: APPROVED" :err ""})
                  harness/parse-output (fn [_ out _] {:output out})]
      (t/is (#'worker/explicit-approval?
             (#'worker/run-reviewer! {:id "w0" :swarm-id "test"
                                      :reviewers [{:harness :claude :model "claude-opus-5-5" :reasoning "high"}]}
                                     "/tmp/test-worktree" [])))
      (t/is (= "high" (:reasoning @seen)))
      (t/is (re-find #"Your final response MUST start with exactly one of these standalone lines:" (:prompt @seen)))
      (t/is (re-find #"Include exactly one verdict line in the final response" (:prompt @seen)))
      (t/is (= "claude-opus-5-5" (:model @seen))))))

(t/deftest review-stream-boundary-keeps-explicit-verdict-gate
  (let [response (atom "") final-message (atom nil)]
    (with-redefs [process/sh (fn [& _] {:exit 0 :out "" :err ""})
                  harness/run-command! (fn [_ opts]
                                         (t/is (true? (:debug-raw-events? opts)))
                                         {:exit 0 :out @response
                                          :err (if @final-message
                                                 (str "[agent-cli raw claude stdout] "
                                                      (json/generate-string
                                                       {:type "assistant" :parent_tool_use_id nil
                                                        :message {:content [{:type "text"
                                                                             :text "I checked the source and config settings."}]}})
                                                      "\n[agent-cli raw claude stdout] "
                                                      (json/generate-string
                                                       {:type "assistant" :parent_tool_use_id nil
                                                        :message {:content [{:type "text" :text @final-message}]}})
                                                      "\n")
                                                 "")})
                  harness/parse-output (fn [_ out _] {:output out})]
      (let [reviewer {:id "w0" :swarm-id "test"
                      :reviewers [{:harness :claude :model "fixture"}]}
            review (fn [output final]
                     (reset! response output)
                     (reset! final-message final)
                     (#'worker/run-reviewer! reviewer "/tmp/test-worktree" []))]
        (let [approved (review (str "I checked the source and config settings.VERDICT: APPROVED\n\n"
                                    "The candidate is ready for execution admission.")
                               "VERDICT: APPROVED\n\nThe candidate is ready for execution admission.")]
          (t/is (= "VERDICT: APPROVED\n\nThe candidate is ready for execution admission."
                   (:output approved)))
          (t/is (#'worker/explicit-approval? approved)))
        (let [rejected (review "The candidate needs repair.VERDICT: NEEDS_CHANGES\nFix the timeout."
                               "VERDICT: NEEDS_CHANGES\nFix the timeout.")]
          (t/is (= "VERDICT: NEEDS_CHANGES\nFix the timeout." (:output rejected)))
          (t/is (not (#'worker/explicit-approval? rejected))))
        (t/is (not (#'worker/explicit-approval?
                    (review "The candidate appears approved, but I have no final verdict." nil))))
        (t/is (not (#'worker/explicit-approval?
                    (review "A quoted example.VERDICT: APPROVED\n" nil))))
        (t/is (not (#'worker/explicit-approval?
                    (review "Here is a quoted example:\nVERDICT: APPROVED\n" nil))))
        (t/is (not (#'worker/explicit-approval?
                    (review "VERDICT: NEEDS_CHANGES\nLater prose says.VERDICT: APPROVED\n"
                            "VERDICT: NEEDS_CHANGES\nLater prose says.VERDICT: APPROVED\n"))))))))

(t/deftest claude-and-codex-execution-map-effort-to-real-runtime-input
  (let [seen (atom nil)]
    (with-redefs [process/sh (fn [_ opts] (reset! seen (json/parse-string (:in opts) true))
                             {:exit 0 :out "" :err ""})]
      (harness/run-command! :claude {:model "claude-opus-5-5" :reasoning "high"
                                    :debug-raw-events? true :prompt "Review"})
      (t/is (= ["--effort" "high"] (:extraArgs @seen)))
      (t/is (true? (:debugRawEvents @seen)))
      (t/is (not (contains? @seen :reasoningEffort)))
      (harness/run-command! :codex {:model "gpt-6.1-sol" :reasoning "xhigh" :prompt "Implement"})
      (t/is (= "xhigh" (:reasoningEffort @seen))))))


(t/deftest strict-final-verdict-contract-controls
  (doseq [output ["VERDICT: APPROVED\nVERDICT: APPROVED"
                  "VERDICT: APPROVED\nVERDICT: NEEDS_CHANGES"
                  "VERDICT: NEEDS_CHANGES\nVERDICT: APPROVED"
                  "No verdict." "VERDICT: APPROVED trailing prose"
                  "```\nVERDICT: APPROVED\n```"
                  "Prose first.\nVERDICT: APPROVED"]]
    (t/is (not (#'worker/explicit-approval? {:verdict :approved :exit 0 :output output})) output))
  (t/is (not (#'worker/explicit-approval? {:verdict :approved :exit 1 :output "VERDICT: APPROVED"})))
  (t/is (#'worker/explicit-approval? {:verdict :approved :exit 0
                                    :output "VERDICT: APPROVED\nEvidence follows."})))

(t/deftest last-top-level-final-refusal-wins
  (let [raw (fn [text parent]
              (str "[agent-cli raw claude stdout] "
                   (json/generate-string {:type "assistant" :parent_tool_use_id parent
                                          :message {:content [{:type "text" :text text}]}})
                   "\n"))
        stderr (str (raw "VERDICT: APPROVED" nil)
                    (raw "VERDICT: NEEDS_CHANGES\nMissing evidence." nil)
                    (raw "VERDICT: APPROVED" "child-tool")
                    "[agent-cli raw claude stdout] {broken\n")]
    (with-redefs [process/sh (fn [& _] {:exit 0 :out "" :err ""})
                  harness/run-command! (fn [& _] {:exit 0 :out "VERDICT: APPROVED" :err stderr})
                  harness/parse-output (fn [_ out _] {:output out})]
      (let [review (#'worker/run-reviewer! {:id "w0" :swarm-id "test"
                                           :reviewers [{:harness :claude :model "fixture"}]}
                                          "/tmp/test-worktree" [])]
        (t/is (= "VERDICT: NEEDS_CHANGES\nMissing evidence." (:output review)))
        (t/is (not (#'worker/explicit-approval? review)))))))

;; Optional local replay of the original immutable review receipts; no fixture
;; copies or historical normalization. See the existing overnight owner note.
(t/deftest retained-review-replay
  (doseq [path *command-line-args*]
    (let [review (json/parse-string (slurp path) true)]
      (t/is (= "approved" (:verdict review)))
      (t/is (not (#'worker/explicit-approval? (assoc review :verdict :approved :exit 0))) path))))

(let [{:keys [fail error]} (t/run-tests 'agentnet.reviewer-effort-test)]
  (when (pos? (+ fail error)) (throw (ex-info "Reviewer effort tests failed" {}))))

;; Regression (audit 2026-11-01): the queue path's run-review! mapped any
;; case-insensitive \bAPPROVED\b to :approved, so "NOT APPROVED" merged. The
;; queue verdict must now use the same strict parse as the selected path.
(t/deftest queue-reviewer-verdict-uses-strict-approval
  (let [verdict (fn [out]
                  (with-redefs [process/sh (fn [& _] {:exit 0 :out "" :err ""})
                                harness/run-command! (fn [_ _] {:exit 0 :out out :err ""})
                                harness/parse-output (fn [_ o _] {:output o})]
                    (:verdict (#'worker/run-reviewer!
                               {:id "w0" :swarm-id "test"
                                :reviewers [{:harness :codex :model "gpt-5.5"}]}
                               "/tmp/test-worktree" []))))]
    (t/is (= :approved (verdict "VERDICT: APPROVED\nLooks good.")))
    (doseq [out ["NOT APPROVED"
                 "not approved yet"
                 "APPROVED"
                 "VERDICT: NOT APPROVED"
                 "The candidate appears approved, but I have no final verdict."
                 "A quoted example.VERDICT: APPROVED\n"
                 "Here is a quoted example:\nVERDICT: APPROVED\n"
                 "VERDICT: NEEDS_CHANGES\nLater prose says.VERDICT: APPROVED\n"
                 "VERDICT: APPROVED\nVERDICT: NEEDS_CHANGES\n"]]
      (t/is (= :needs-changes (verdict out)) out))))
