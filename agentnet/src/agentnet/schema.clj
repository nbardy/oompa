(ns agentnet.schema
  "Data schemas for AgentNet using simple predicates.

   Design Philosophy (Hickey-style):
   - Data is the interface
   - Schemas are documentation
   - Validate at boundaries, trust inside
   - Prefer maps over positional args

   Note: Using simple predicates instead of Malli to avoid
   external dependencies (Malli needs Java for deps.clj)."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Primitive Validators
;; =============================================================================

(defn non-blank-string? [x]
  (and (string? x) (not (str/blank? x))))

(defn pos-int? [x]
  (and (int? x) (pos? x)))

(defn nat-int? [x]
  (and (int? x) (>= x 0)))

(defn timestamp? [x]
  (nat-int? x))

(defn file-path? [x]
  (non-blank-string? x))

(defn git-branch? [x]
  (non-blank-string? x))

(defn git-sha? [x]
  (and (string? x) (re-matches #"^[a-f0-9]{7,40}$" x)))

;; =============================================================================
;; Enum Validators
;; =============================================================================

(def agent-types #{:codex :claude})
(def agent-roles #{:proposer :reviewer :cto})
(def task-statuses #{:pending :in-progress :review :approved :merged :failed :blocked})
(def worktree-statuses #{:available :busy :dirty :stale})
(def review-verdicts #{:approved :needs-changes :rejected})
(def merge-strategies #{:fast-forward :no-ff :squash :rebase})
(def conflict-resolutions #{:ours :theirs :manual :abort})

(defn agent-type? [x] (contains? agent-types x))
(defn agent-role? [x] (contains? agent-roles x))
(defn task-status? [x] (contains? task-statuses x))
(defn worktree-status? [x] (contains? worktree-statuses x))
(defn review-verdict? [x] (contains? review-verdicts x))
(defn merge-strategy? [x] (contains? merge-strategies x))
(defn conflict-resolution? [x] (contains? conflict-resolutions x))

;; =============================================================================
;; Map Validators
;; =============================================================================

(defn has-keys? [m required-keys]
  (every? #(contains? m %) required-keys))

(defn valid-task? [x]
  (and (map? x)
       (has-keys? x [:id :summary])
       (non-blank-string? (:id x))
       (non-blank-string? (:summary x))))

(defn valid-worktree? [x]
  (and (map? x)
       (has-keys? x [:id :path :branch :status])
       (non-blank-string? (:id x))
       (file-path? (:path x))
       (git-branch? (:branch x))
       (worktree-status? (:status x))))

(defn valid-agent-config? [x]
  (and (map? x)
       (has-keys? x [:type])
       (agent-type? (:type x))))

(defn valid-review-feedback? [x]
  (and (map? x)
       (has-keys? x [:verdict])
       (review-verdict? (:verdict x))))

(defn valid-merge-result? [x]
  (and (map? x)
       (has-keys? x [:status :source-branch :target-branch])
       (contains? #{:merged :conflict :failed :skipped} (:status x))))

(defn valid-orchestrator-config? [x]
  (and (map? x)
       (has-keys? x [:worker-count :agent-type])
       (pos-int? (:worker-count x))
       (agent-type? (:agent-type x))))

;; =============================================================================
;; Validation Helpers
;; =============================================================================

(defn validate
  "Validate data against a predicate, return [valid? errors]"
  [pred data context]
  (if (pred data)
    [true nil]
    [false {:context context :data data :error "Validation failed"}]))

(defn assert-valid
  "Throw if data doesn't match predicate"
  [pred data context]
  (when-not (pred data)
    (throw (ex-info (str "Invalid " context)
                    {:context context
                     :data data
                     :type (type data)}))))

;; =============================================================================
;; Schema Reference (for documentation)
;; =============================================================================

;; TaskId          <- non-blank-string?
;; AgentType       <- agent-type? (:codex, :claude)
;; AgentRole       <- agent-role? (:proposer, :reviewer, :cto)
;; TaskStatus      <- task-status?
;; WorktreeStatus  <- worktree-status?
;; ReviewVerdict   <- review-verdict?
;; MergeStrategy   <- merge-strategy?
;; ConflictResolution <- conflict-resolution?
;; GitBranch       <- git-branch?
;; GitSha          <- git-sha?
;;
;; Task            <- {:id string, :summary string, :targets [string], ...}
;; Worktree        <- {:id string, :path string, :branch string, :status keyword}
;; AgentConfig     <- {:type :codex|:claude, :model string, :sandbox keyword}
;; ReviewFeedback  <- {:verdict :approved|:needs-changes|:rejected, :comments [string]}
;; MergeResult     <- {:status :merged|:conflict|:failed, :source-branch string, ...}
;; OrchestratorConfig <- {:worker-count int, :agent-type keyword, :dry-run bool}
