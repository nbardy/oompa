# Writing JSON Tickets for Oompa

Oompa swarms consume atomic tasks defined as JSON files in `tasks/pending/`.

To prevent parsing errors and queue drift, planners and TPM agents should follow these rules when generating `.json` tickets.

## 1. The Structure

Every ticket must be a valid JSON object with stable string keys.

```json
{
  "id": "C1-example-ticket-id",
  "description": "A plain-text, high-level summary of the task. Do not put math or code here.",
  "completed_by": null,
  "difficulty": "medium",
  "acceptance": [
    "Step one.",
    "Step two.",
    "Step three."
  ],
  "summary": "A brief 3-5 word title",
  "target_files": ["src/example.py", "tests/test_example.py"]
}
```

## 2. Keep JSON Tickets Lean

Do not embed dense architectural logic, code blocks, or complex math directly into long string fields. Put dense specs in `.md` files and reference them from the task.

## 3. Use Plain Strings And Arrays

Prefer arrays for acceptance criteria and file lists. Keep values easy to diff and easy to parse.

## 4. Scope And Size

Tickets should be atomic. A single ticket should represent under one hour of focused work for one agent. Split large refactors into multiple JSON tickets.
