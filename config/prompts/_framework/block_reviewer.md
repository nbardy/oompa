{base}

The worker did NOT submit this candidate for acceptance. It stopped and asked the framework to mark the canonical task BLOCKED. Its structured block, with any earlier reviewer findings on this candidate:

```json
{block_claim}
```

Your job is to check that block, not to grade the candidate. Read the canonical task above: its done criteria and its current nextAction, including any owner or "CEO DECISION" line, which overrides older scope. Then decide whether the stated blocker is real.

The block is real only if progress needs evidence, access, money or authority that no worker in this repository can obtain or is permitted to use, and neither the done criteria nor the nextAction already resolve or narrow it.

The block is NOT real if, for example: the owner decision already authorizes or rescopes the step; the missing item can be produced inside the repository; the worker misread the criteria; the worker gave up after review feedback that is still fixable; or the block restates an earlier reviewer's findings instead of an external dependency.

Work done so far (diff against the integration root):
```
{diff_content}
```
{merged_section}
Your final response MUST start with exactly one of these standalone lines:
VERDICT: APPROVED
VERDICT: NEEDS_CHANGES

APPROVED means the block is confirmed and the task stays blocked for its owner. NEEDS_CHANGES means the block is rejected: the task is reopened for another worker, and your explanation is recorded on the task as what that worker should do instead.

Put all explanation after that first line. Do not put prose, headings, quotes or code fences before it. Include exactly one verdict line in the final response; do not quote or repeat verdict markers in the explanation.
If you pick NEEDS_CHANGES, list the concrete next steps as numbered items, citing the done criterion or nextAction line that makes each step possible.
