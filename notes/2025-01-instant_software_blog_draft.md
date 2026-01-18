# How Oompa Loompas Make Software Development Instant

**Status:** Draft
**Written:** January 2025

---

## The Thesis

You won't be programming loops for long. Ralph will die.

Soon you'll just have an Anthropic harness that writes software instantly.

---

## The Double Exponential Collapse

[GRAPHIC: Curved manifold showing double exponential collapse]

**X-axis:** Model capability (speed + accuracy)
**Y-axis:** Time to software completion

Two forces compound:

### Force 1: Speed
As model speed goes up → time to completion goes down (linear)

### Force 2: Accuracy (the sneaky one)
As model accuracy goes up → speed goes up via TWO mechanisms:

1. **Less loops needed** - Always right first time. No retry cycles.
2. **Better chunking** - Smarter models predict what parts are needed and how they integrate. More parallelizable work.

```
Parallelization Rate = (fewer loops needed) × (more chunkable work)
```

These multiply. Double exponential.

---

## The Endgame

Eventually we fan out 1000s of agents to write your software in seconds.

Not science fiction. The math:
- 1000 agents × 10 seconds each = 10,000 agent-seconds of work
- Wall clock time: 10 seconds
- Result: Entire codebase

---

## The Hidden Truth About Token Economics

**VLLM can already output your entire codebase in seconds.**

What people don't realize:
- VLLM outputs millions of tokens on a single GPU
- Batchwise parallelization + PAGE KV cache = massive efficiency
- LLMs are WAY more efficient batchwise than you think
- Token cost is way cheaper than LLM provider margins suggest

The bottleneck isn't compute. It's orchestration.

That's why Oompa Loompas matter.

---

## The Graphic Idea

```
                    ┌─────────────────────────────────────┐
                    │                                     │
  Time to           │  ╲                                  │
  Software          │   ╲                                 │
  Completion        │    ╲                                │
                    │     ╲  ← linear (speed alone)       │
                    │      ╲                              │
                    │       ╲                             │
                    │        ╲                            │
                    │         ╲╲                          │
                    │           ╲╲  ← double exponential  │
                    │             ╲╲   (speed × accuracy) │
                    │               ╲╲                    │
                    │                 ╲__                 │
                    │                    ──___            │
                    │                         ───────    │
                    └─────────────────────────────────────┘
                              Model Capability →
```

The curve doesn't just bend. It collapses.

---

## Blog Structure

1. **Hook:** "What if software was instant?"
2. **The Death of Ralph:** Single-agent loops are a transitional phase
3. **The Double Exponential:** Why accuracy compounds with speed
4. **The Parallelization Formula:** `rate = loops⁻¹ × chunks`
5. **The Economics:** VLLM, batching, and why tokens are cheaper than you think
6. **The Implementation:** Show oompa_loompas.sh (7 lines)
7. **The Future:** 1000 agents, 10 seconds, entire codebase

---

## Key Quotes to Work In

> "You won't be programming loops for long. Ralph will die."

> "The bottleneck isn't compute. It's orchestration."

> "VLLM can output your entire codebase in seconds. We just haven't built the harness yet."

> "Parallelization rate = (fewer loops needed) × (more chunkable work)"

---

## Related

- oompa_loompas.sh - the 7-line implementation
- notes/2025-01-instant_software_blog_idea.md - planning notes
- notes/2025-01-myth_of_long_running_tasks.md - theoretical foundation
