# Oompa Loompas Documentation

Welcome to the documentation for Oompa Loompas, a robust architecture for running swarms of autonomous agents.

## Core Reading
- [SWARM_PHILOSOPHY.md](./SWARM_PHILOSOPHY.md) - The theoretical foundation of how to structure intelligence. Read this first.
- [SWARM_GUIDE.md](./SWARM_GUIDE.md) - The practical guide to translating philosophy into `oompa.json` configuration.
- [JSON_TICKETS.md](./JSON_TICKETS.md) - The current rules for how planners and TPM agents should construct JSON task tickets.
- [SYSTEMS_DESIGN.md](./SYSTEMS_DESIGN.md) - The design of the core agent network.
- [OOMPA.md](./OOMPA.md) - The CLI interface and runtime manual.

## To Dump All Context
If you want to feed all the core Oompa Loompa documentation into an LLM context window at once, you can run the following command from the root of this repository:

```bash
cat docs/SWARM_PHILOSOPHY.md docs/SWARM_GUIDE.md docs/JSON_TICKETS.md docs/SYSTEMS_DESIGN.md docs/OOMPA.md > docs_all.txt
```
