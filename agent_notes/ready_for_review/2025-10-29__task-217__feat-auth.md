---
id: task-217
type: review
targets: ["src/auth/", "docs/api.md"]
branch: "rev/task-217"
status: green
owner: eng-1
created_at: 2025-10-29T13:27:00Z
---

# Feature: tighten auth middleware

## Context
- introduces stricter session validation
- includes doc updates for refreshed token lifecycle

## Ready for review checklist
- [x] Unit tests updated for `SessionValidator`
- [x] API docs mention rotated refresh tokens
- [ ] CTO sign-off (this review)

## Summary
Please confirm the new middleware behavior and ensure documentation aligns with our security messaging.
