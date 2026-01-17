# Agent Notes Workflow

This repository hosts a lightweight, tactile workflow for engineers and CTO reviewers to coordinate through the filesystem. The flow relies on a green/red queue, simple naming rules, and clear lifecycle states so everyone can feel the work moving.

---

## 1) Folder layout

```
agent_notes/
  scratch/                 # freeform journaling, ideation, chatty notes
    2025-10-29__eng-1__exp-note-taking.md
  ready_for_review/        # green queue: items CTO should review now
    2025-10-29__task-217__feat-auth.md
  notes_FROM_CTO/          # feedback & directives from CTO
    2025-10-29__cto-1__review-task-217.md
  proposed_tasks/          # big, motivating tasks (not tiny JIRA crumbs)
    2025-10-29__tt-001__unify-data-model.md

finished_to_review.md      # CTO’s running ledger of completed reviews (append-only)
```

### Naming convention

`YYYY-MM-DD__<id-or-author>__<slug>.md` keeps sorting intuitive and editing easy from any editor.

---

## 2) Minimal front-matter (optional but powerful)

At the top of any actionable note, allow YAML-lite so tools can parse it while humans happily ignore it.

```markdown
---
id: task-217
type: review|proposal|task
targets: ["src/auth/", "docs/api.md"]   # hint for sparse checkout & diff guard
branch: "rev/task-217"                  # branch for review/fix loops
status: green|red                       # queue color
owner: eng-1
created_at: 2025-10-29T13:27:00Z
---

# Title...
```

If front-matter is absent, the orchestrator derives defaults from the filename.

---

## 3) The red/green queue (one bit, clear behavior)

- **green** → ready to act (CTO should review; Engineer should implement)
- **red** → blocked or needs fix; do **not** merge

Setting rules:

* Files in `ready_for_review/` default to **green** unless front-matter sets `status: red`.
* When the CTO finds issues, they:
  * write feedback in `notes_FROM_CTO/<…>.md`
  * flip the item to **red** (`status: red`) and/or move it back to `scratch/`
  * if code exists on a branch, set `branch: rev/<id>` and leave no `.agent/APPROVED` token, which prevents merge.

---

## 4) Lifecycles

### Review track (CTO as gatekeeper)

```
scratch → ready_for_review (green) → CTO reviews
    └──(reject)→ red (stay in ready_for_review OR move to scratch)
    └──(approve)→ record line in finished_to_review.md → if patch exists & APPROVED → merge
```

### Task track (CTO curates big work)

```
proposed_tasks (brain-dump) → CTO stack-ranks 100 → mark top N as green
    → Engineer pulls green tasks first
```

---

## 5) “Latest relevant” fetch policy

On agent launch (CTO or Engineer):

1. Read newest files in `ready_for_review/` where `status ≠ red` (or missing).
2. Read newest feedback in `notes_FROM_CTO/` for your identity or the note’s `id`.
3. Read newest items in `proposed_tasks/`, surfacing `status = green` first.
4. Read newest items in `scratch/` touched in the last ~120 seconds as soft context only.

Pass these filenames into the agent prompt and expose `agent_notes/` with `--add-dir agent_notes` so agents can open them.

---

## 6) Merge rule

> **If the review fails, we don’t merge the branch.**

Mechanically: no `.agent/APPROVED` token means the orchestrator blocks the merge; the CTO flips `status: red` in the note while leaving feedback.

---

## 7) The CTO’s two powers

1. **Create a new review task**
   * Write a file in `ready_for_review/` (`type: review`) with an `id` and `branch: rev/<id>` when code exists.
2. **Curate big work**
   * Dump large, motivating tasks into `proposed_tasks/` (one file or many), add `rank: <number>` in front-matter, mark the top N as `status: green`. Engineers pull **green** first.

---

## 8) ASCII state diagram (review track)

```
 [scratch] --(promote)--> [ready_for_review:green] -- CTO review -->
       |                                                    |
       | (insufficient)                                     | (approve)
       v                                                    v
 [ready_for_review:red]  <-----------------------   [finished_to_review.md append]
       |                               (notes_FROM_CTO/<…>.md, no APPROVED token)
       └-------(rework/iterate)--------> [ready_for_review:green]
```

---

## 9) Prompts (short & aligned)

### Engineer prompt

```
# Engineer

You prioritize:
1) GREEN tasks in agent_notes/proposed_tasks/
2) GREEN review notes in agent_notes/ready_for_review/

Files to consult:
{files}

If task selected:
- Implement minimal correct change, touching only: {targets}
- Write unified patch to `.agent/patch.diff` and `.agent/meta.json`
- Do not modify repo files directly; only `.agent/*`

If doing review tidy-up:
- Make the smallest possible fix to address feedback in notes_FROM_CTO
```

### CTO prompt

```
# CTO

You act as reviewer and consolidator.

Review mode triggers when you see GREEN items in agent_notes/ready_for_review/.
- Read listed files:
{files}
- If acceptable, create `.agent/APPROVED`.
- Else, write feedback in notes_FROM_CTO/<id>.md and set that note's status to red.

Propose mode triggers when the queue is empty:
- Create or refine tasks in agent_notes/proposed_tasks/ with big, motivating scope.
- Rank and mark GREEN up to N non-overlapping tasks.
```

---

## 10) Drop-in Clojure functions (scan & pick)

Add these helpers to `agentnet.core` (or an adjacent namespace) to replace the earlier “recent notes” scanner.

```clj
(ns agentnet.notes
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def NOTES (io/file "agent_notes"))

(defn- fm-frontmatter
  "Parse very light YAML-ish front-matter between --- lines. Returns map or {}."
  [^String s]
  (try
    (let [[_ block] (re-find #"(?s)^---\n(.*?)\n---\n" s)]
      (if-not block {}
        (->> (str/split-lines block)
             (keep #(when-let [[_ k v] (re-matches #"^\s*([A-Za-z0-9_]+)\s*:\s*(.+)\s*$" %)]
                      [(keyword k) (str/trim v)]))
             (into {}))))
    (catch Exception _ {})))

(defn- read-note [^java.io.File f]
  (let [s (slurp f) fm (fm-frontmatter s)]
    {:file (.getAbsolutePath f)
     :name (.getName f)
     :dir  (.getName (.getParentFile f))
     :mtime (.lastModified f)
     :fm fm
     :status (keyword (or (:status fm) "green"))
     :id (or (:id fm) (second (re-find #"__([^_]+)__" (.getName f))))
     :targets (when-let [t (:targets fm)]
                (-> t (str/replace #"[\[\]\"]" "") (str/split #",\s*") (remove str/blank?) vec))}))

(defn list-notes [subdir]
  (let [d (io/file NOTES subdir)]
    (when (.exists d)
      (->> (.listFiles d)
           (filter #(.isFile ^java.io.File %))
           (map read-note)
           (sort-by :mtime >)
           vec))))

(defn green-ready []
  (->> (list-notes "ready_for_review")
       (remove #(= (:status %) :red))))

(defn proposed-green []
  (->> (list-notes "proposed_tasks")
       (filter #(= (:status %) :green))
       ;; optional: sort by rank if present
       (sort-by (comp #(try (Integer/parseInt %) (catch Exception _ 9999))
                      :rank :fm))))

(defn cto-feedback-latest []
  (take 20 (list-notes "notes_FROM_CTO")))

(defn scratch-latest [recent-sec]
  (let [cut (- (System/currentTimeMillis) (* 1000 recent-sec))]
    (->> (list-notes "scratch")
         (filter #(>= (:mtime %) cut))
         (take 50)
         vec)))

(defn pick-files-for-prompt
  "Select a small list of filenames to stuff into prompts."
  [{:keys [recent-sec]}]
  (let [a (map :name (take 10 (green-ready)))
        b (map :name (take 10 (proposed-green)))
        c (map :name (take 5 (cto-feedback-latest)))
        d (map :name (take 5 (scratch-latest (or recent-sec 120))))]
    (->> (concat a b c d) distinct vec)))
```

### Using them in your loops

**Engineer** (pull a task first, else tidy a ready review item):

```clj
(let [files (agentnet.notes/pick-files-for-prompt {:recent-sec 120})
      ready (agentnet.notes/green-ready)
      tasks (agentnet.notes/proposed-green)
      chosen (first (or tasks ready))]
  ;; inject {files} and chosen :targets into the prompt
)
```

**CTO** (prefer reviewing if any green items exist):

```clj
(let [ready (agentnet.notes/green-ready)]
  (if (seq ready)
    ;; REVIEW mode → run cto prompt with {mode_hint "review"}
    ;; On reject: write notes_FROM_CTO file and set status:red in the source note
    ;; On approve: drop APPROVED token; orchestrator merges
    ;; Optional: append a line to finished_to_review.md
    ;; else → PROPOSE mode: curate proposed_tasks and mark green
    ))
```

### Helper to flip status to red (on review fail)

```clj
(defn mark-note-red! [note]
  (let [f (io/file (:file note))
        s (slurp f)]
    (if (re-find #"(?m)^status\s*:" s)
      (spit f (str (str/replace s #"(?m)^(status\s*:\s*).*$" "$1red") "\n"))
      (spit f (str "---\nstatus: red\n---\n" s)))))
```

---

## 11) CTO “finished ledger”

`finished_to_review.md` should be append-only in this format:

```markdown
- 2025-10-29 13:43Z | APPROVED | task-217 | ready_for_review/2025-10-29__task-217__feat-auth.md | patch_sha256=a1b2...
```

The orchestrator (or CTO loop) appends one line per approval.

---

## 12) Policy summary

- **Green files in `ready_for_review/` are the queue.**
- **CTO approves by dropping `.agent/APPROVED`**; orchestrator merges and appends to the ledger.
- **CTO rejects by writing `notes_FROM_CTO/<id>.md` + flipping the note to `status: red`.**
- **Engineer works from `proposed_tasks/` (green) first**; if empty, pick `ready_for_review/` (green) tidy-ups.
- **If review fails: no token ⇒ no merge.**

No brokers, no databases—just a few folders and clear rules.
