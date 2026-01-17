---
id: fact-001
type: task
status: green
rank: 1
targets: ["src/factorial.py", "tests/test_factorial.py"]
owner: eng-1
created_at: 2025-11-07T10:45:00Z
---

# Task: Build a factorial demo program

We need a tiny sample program that demonstrates the full agent loop:

- Implement a pure `factorial(n: int) -> int` helper inside `src/factorial.py`.
- Support CLI usage: `python src/factorial.py 5` should print `120` and add a trailing newline.
- Handle invalid inputs with a non-zero exit code and an error message on stderr.
- Add a focused unit test (Pytest is fine) in `tests/test_factorial.py`.

Keep the code dependency-free (standard library only) and document how to run it in the file header.
