# Scripts

Helper scripts used by the Oompa harness.

Current scripts:
- `stream_bridge.py` - Claude/Codex JSON stream parser and TTY renderer
- `claude_tui.sh` - Claude wrapper that routes through `stream_bridge.py`
- `codex_tui.sh` - Codex wrapper that routes through `stream_bridge.py`

Enable in runs with:

```bash
OOMPA_TUI=1 ./swarm.bb swarm
```
