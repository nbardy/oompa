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

## 2. No LaTeX or Complex Math
**The EDN parser will crash if it encounters invalid escape sequences like `\sigma`, `\s`, `\hat`, etc.**

*   **Wrong:** `:description "Compute the rolling $\sigma$ using jump-diffusion."`
*   **Right:** `:description "Compute the rolling volatility (sigma) using jump-diffusion. See docs/research_notes/jump_diffusion.md for the exact math."`

Keep the `.edn` description plain text. Use markdown files (`.md`) to store any complex math, proofs, or code snippets, and simply point the developer to that file in the EDN ticket.

## 3. String Escaping
Values in the `.edn` map are strings enclosed in double quotes `""`. 
If you need to quote something inside the string, use single quotes `''` or properly escape the double quotes `""`.

*   **Wrong:** `:acceptance "Fix the "Gamma Explosion" bug."` (Breaks the parser)
*   **Right:** `:acceptance "Fix the 'Gamma Explosion' bug."`
*   **Right:** `:acceptance "Fix the "Gamma Explosion" bug."`

## 4. Scope & Size
Tickets should be atomic. A single ticket should represent `< 1 hour` of work for a single agent. If a task requires refactoring 5 different systems, break it into 5 separate `.edn` files.
