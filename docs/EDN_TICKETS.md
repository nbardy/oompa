# Writing EDN Tickets for Oompa

Oompa swarms consume atomic tasks defined as Clojure EDN (Extensible Data Notation) files in `tasks/pending/`. 

To prevent parsing errors and crashing workers, Planners and TPM agents must strictly adhere to the following rules when generating `.edn` tickets.

## 1. The Structure
Every ticket must be a valid EDN map with specific keys.

```clojure
{:id "C1-example-ticket-id"
 :description "A plain-text, high-level summary of the task. Do not put math or code here."
 :completed-by nil
 :difficulty :medium
 :acceptance "1. Step one.
2. Step two.
3. Step three."
 :summary "A brief 3-5 word title"
 :target_files ["src/example.py" "tests/test_example.py"]
}
```

## 2. Keep EDN Files Lean (Avoid Complex Escape Characters)
**The EDN parser will crash if it encounters invalid or complex escape sequences (e.g., from LaTeX, code blocks, or nested formats).**

*   **Wrong:** Putting dense architectural logic, complex mathematical formulas (like `\sigma` or `\hat`), or multi-line code blocks directly into the `:description` or `:acceptance` string.
*   **Right:** `:description "Implement the high-frequency volatility oracle. See docs/research_notes/volatility.md for the exact mathematical formulas and edge cases."`

Keep the `.edn` strings plain text and brief. Use markdown files (`.md`) to store any complex requirements, equations, deep technical contexts, or dense acceptance criteria. Simply point the developer to that specific `.md` file in the EDN ticket.

## 3. String Escaping
Values in the `.edn` map are strings enclosed in double quotes `""`. 
If you need to quote something inside the string, use single quotes `''` or properly escape the double quotes `""`.

*   **Wrong:** `:acceptance "Fix the "Gamma Explosion" bug."` (Breaks the parser)
*   **Right:** `:acceptance "Fix the 'Gamma Explosion' bug."`
*   **Right:** `:acceptance "Fix the "Gamma Explosion" bug."`

## 4. Scope & Size
Tickets should be atomic. A single ticket should represent `< 1 hour` of work for a single agent. If a task requires refactoring 5 different systems, break it into 5 separate `.edn` files.
