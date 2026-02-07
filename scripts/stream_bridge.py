#!/usr/bin/env python3
"""Provider stream bridge for Oompa workers.

Wraps Claude/Codex CLI commands to:
- enable JSON stream modes
- parse tool/text events for live TTY output
- emit final assistant text on stdout for existing Oompa parsing
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import threading
from dataclasses import dataclass
from typing import List, Optional, Sequence


class Color:
    RESET = "\033[0m"
    DIM = "\033[2m"
    CYAN = "\033[36m"
    GREEN = "\033[32m"
    MAGENTA = "\033[35m"


def env_enabled(name: str, default: bool = True) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.lower() not in {"0", "false", "no", "off"}


def pick_real_binary(provider: str) -> Optional[str]:
    env_key = "OOMPA_REAL_CLAUDE_BIN" if provider == "claude" else "OOMPA_REAL_CODEX_BIN"
    env_value = os.getenv(env_key)
    if env_value and os.path.exists(env_value):
        return env_value

    found = shutil.which(provider)
    if found:
        return found

    return None


def extract_worktree_from_prompt(prompt: str) -> Optional[str]:
    if not prompt:
        return None
    match = re.search(r"Worktree:\s*(\S+)", prompt)
    if match:
        return match.group(1)
    return None


def parse_codex_worktree(args: Sequence[str]) -> Optional[str]:
    for index, token in enumerate(args):
        if token == "-C" and index + 1 < len(args):
            return args[index + 1]
    return None


def normalize_label(path: str) -> str:
    if not path:
        return "unknown"
    base = os.path.basename(path.rstrip("/"))
    return base.lstrip(".") or base or "unknown"


def truncate(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    return text[: limit - 3] + "..."


@dataclass
class BridgeConfig:
    provider: str
    real_bin: str
    args: List[str]
    prompt_text: str
    worker_label: str


class TuiWriter:
    def __init__(self, provider: str, worker_label: str):
        self.provider = provider
        self.worker_label = worker_label
        self._stream = None
        self._buffer = ""

        if not env_enabled("OOMPA_TUI", default=True):
            return

        try:
            self._stream = open("/dev/tty", "w", buffering=1)
        except OSError:
            if env_enabled("OOMPA_TUI_STDERR", default=False):
                self._stream = sys.stderr

    def _prefix(self) -> str:
        return f"{Color.DIM}[{self.worker_label}]{Color.RESET} {Color.MAGENTA}{self.provider}{Color.RESET}"

    def line(self, text: str, color: str = "") -> None:
        if not self._stream:
            return
        if color:
            payload = f"{self._prefix()} {color}{text}{Color.RESET}\n"
        else:
            payload = f"{self._prefix()} {text}\n"
        self._stream.write(payload)

    def text_chunk(self, chunk: str) -> None:
        if not self._stream or not chunk:
            return

        self._buffer += chunk
        while "\n" in self._buffer:
            line, self._buffer = self._buffer.split("\n", 1)
            self.line(line)

    def flush(self) -> None:
        if not self._stream:
            return
        if self._buffer:
            self.line(self._buffer)
            self._buffer = ""


class ClaudeCollector:
    def __init__(self, tui: TuiWriter):
        self.tui = tui
        self.final_text_parts: List[str] = []
        self.stream_text_parts: List[str] = []
        self.raw_fallback: List[str] = []
        self._tool_seen = set()

    def _show_tool(self, name: str, payload: object) -> None:
        key = (name, json.dumps(payload, sort_keys=True, default=str))
        if key in self._tool_seen:
            return
        self._tool_seen.add(key)

        if isinstance(payload, dict):
            file_path = payload.get("file_path")
            command = payload.get("command")
            if file_path:
                self.tui.line(f"TOOL {name}: {file_path}", Color.CYAN)
                return
            if command:
                self.tui.line(f"TOOL {name}: {truncate(command, 90)}", Color.CYAN)
                return

        self.tui.line(f"TOOL {name}", Color.CYAN)

    def feed(self, line: str) -> None:
        stripped = line.strip()
        if not stripped:
            return

        try:
            event = json.loads(stripped)
        except json.JSONDecodeError:
            self.raw_fallback.append(line)
            return

        if event.get("type") == "stream_event":
            inner = event.get("event", {})
            event_type = inner.get("type")

            if event_type == "content_block_start":
                block = inner.get("content_block", {})
                if block.get("type") == "tool_use":
                    self._show_tool(str(block.get("name", "tool")), block)
                return

            if event_type == "content_block_delta":
                delta = inner.get("delta", {})
                if delta.get("type") == "text_delta":
                    text = str(delta.get("text", ""))
                    if text:
                        self.stream_text_parts.append(text)
                        self.tui.text_chunk(text)
                return

            if event_type == "content_block_stop":
                self.tui.flush()
                return

            return

        if event.get("type") == "assistant":
            message = event.get("message", {})
            for block in message.get("content", []):
                kind = block.get("type")
                if kind == "text":
                    text = str(block.get("text", ""))
                    if text:
                        self.final_text_parts.append(text)
                elif kind == "tool_use":
                    self._show_tool(str(block.get("name", "tool")), block.get("input", {}))
            return

        if event.get("type") == "result" and event.get("subtype") == "error":
            err = str(event.get("error") or event.get("result") or "Claude error")
            self.raw_fallback.append(err + "\n")

    def final_text(self) -> str:
        if self.final_text_parts:
            return "\n".join(part for part in self.final_text_parts if part.strip()).strip()

        streamed = "".join(self.stream_text_parts).strip()
        if streamed:
            return streamed

        return "".join(self.raw_fallback).strip()


class CodexCollector:
    def __init__(self, tui: TuiWriter):
        self.tui = tui
        self.final_text_parts: List[str] = []
        self.raw_fallback: List[str] = []

    def _show_started(self, item: dict) -> None:
        kind = item.get("type")
        if kind == "command_execution":
            command = str(item.get("command", ""))
            self.tui.line(f"TOOL shell: {truncate(command, 90)}", Color.CYAN)
        elif kind == "web_search":
            self.tui.line("TOOL web_search", Color.CYAN)
        elif kind == "mcp_tool_call":
            name = str(item.get("name", "mcp_tool"))
            self.tui.line(f"TOOL {name}", Color.CYAN)

    def _show_completed(self, item: dict) -> None:
        kind = item.get("type")

        if kind == "agent_message":
            text = str(item.get("text", ""))
            if text:
                self.final_text_parts.append(text)
                self.tui.text_chunk(text)
                self.tui.flush()
            return

        if kind == "command_execution":
            exit_code = item.get("exit_code")
            suffix = "" if exit_code in (None, 0) else f" (exit {exit_code})"
            self.tui.line(f"DONE shell{suffix}", Color.GREEN)
            return

        if kind == "file_change":
            changes = item.get("changes") or []
            if isinstance(changes, list) and changes:
                rendered = ", ".join(
                    f"{c.get('kind', '?')}:{c.get('path', '?')}" for c in changes if isinstance(c, dict)
                )
                self.tui.line(f"DONE file_change: {truncate(rendered, 120)}", Color.GREEN)
            else:
                self.tui.line("DONE file_change", Color.GREEN)
            return

        if kind == "web_search":
            self.tui.line("DONE web_search", Color.GREEN)

    def feed(self, line: str) -> None:
        stripped = line.strip()
        if not stripped:
            return

        try:
            event = json.loads(stripped)
        except json.JSONDecodeError:
            self.raw_fallback.append(line)
            return

        event_type = event.get("type")
        if event_type == "item.started":
            item = event.get("item")
            if isinstance(item, dict):
                self._show_started(item)
            return

        if event_type == "item.completed":
            item = event.get("item")
            if isinstance(item, dict):
                self._show_completed(item)
            return

        if event_type == "turn.failed":
            err = str(event.get("error", "Codex turn failed"))
            self.raw_fallback.append(err + "\n")

    def final_text(self) -> str:
        if self.final_text_parts:
            return "\n".join(part for part in self.final_text_parts if part.strip()).strip()
        return "".join(self.raw_fallback).strip()


def prepare_claude_args(args: Sequence[str]) -> tuple[list[str], bool]:
    arg_list = list(args)
    is_prompt_mode = "-p" in arg_list or "--print" in arg_list
    if not is_prompt_mode:
        return arg_list, False

    if "--verbose" not in arg_list:
        arg_list.append("--verbose")
    if "--output-format" not in arg_list:
        arg_list.extend(["--output-format", "stream-json"])
    if "--include-partial-messages" not in arg_list:
        arg_list.append("--include-partial-messages")

    return arg_list, True


def prepare_codex_args(args: Sequence[str]) -> tuple[list[str], bool, str]:
    arg_list = list(args)
    if not arg_list or arg_list[0] != "exec":
        return arg_list, False, ""

    prompt_text = ""
    if "--" in arg_list:
        marker_index = arg_list.index("--")
        if marker_index + 1 < len(arg_list):
            prompt_text = arg_list[marker_index + 1]

    if "--json" not in arg_list:
        if "--" in arg_list:
            marker_index = arg_list.index("--")
            arg_list = arg_list[:marker_index] + ["--json"] + arg_list[marker_index:]
        else:
            arg_list.append("--json")

    return arg_list, True, prompt_text


def passthrough(real_bin: str, args: Sequence[str]) -> int:
    completed = subprocess.run([real_bin, *args])
    return completed.returncode


def run_bridge(config: BridgeConfig) -> int:
    process = subprocess.Popen(
        [config.real_bin, *config.args],
        stdin=subprocess.PIPE if config.provider == "claude" else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
    )

    if config.provider == "claude" and process.stdin is not None:
        process.stdin.write(config.prompt_text)
        process.stdin.close()

    stderr_chunks: List[str] = []

    def read_stderr() -> None:
        if not process.stderr:
            return
        for chunk in process.stderr:
            stderr_chunks.append(chunk)

    stderr_thread = threading.Thread(target=read_stderr, daemon=True)
    stderr_thread.start()

    tui = TuiWriter(config.provider, config.worker_label)
    collector = ClaudeCollector(tui) if config.provider == "claude" else CodexCollector(tui)

    if process.stdout is not None:
        for line in process.stdout:
            collector.feed(line)

    process.wait()
    stderr_thread.join(timeout=1.0)
    tui.flush()

    final_text = collector.final_text()
    if final_text:
        sys.stdout.write(final_text)
        if not final_text.endswith("\n"):
            sys.stdout.write("\n")
        sys.stdout.flush()

    if stderr_chunks:
        sys.stderr.write("".join(stderr_chunks))
        sys.stderr.flush()

    return process.returncode


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Oompa provider stream bridge")
    parser.add_argument("--provider", required=True, choices=["claude", "codex"])
    parser.add_argument("args", nargs=argparse.REMAINDER)
    return parser.parse_args(argv)


def main(argv: Sequence[str]) -> int:
    namespace = parse_args(argv)
    args = list(namespace.args)
    if args and args[0] == "--":
        args = args[1:]

    real_bin = pick_real_binary(namespace.provider)
    if not real_bin:
        sys.stderr.write(f"oompa bridge: could not find real {namespace.provider} binary\n")
        return 127

    if namespace.provider == "claude":
        prepared_args, use_bridge = prepare_claude_args(args)
        prompt_text = sys.stdin.read() if use_bridge else ""
        worker_source = extract_worktree_from_prompt(prompt_text) or os.getcwd()
    else:
        prepared_args, use_bridge, prompt_text = prepare_codex_args(args)
        worker_source = parse_codex_worktree(prepared_args) or extract_worktree_from_prompt(prompt_text) or os.getcwd()

    if not use_bridge:
        return passthrough(real_bin, args)

    config = BridgeConfig(
        provider=namespace.provider,
        real_bin=real_bin,
        args=prepared_args,
        prompt_text=prompt_text,
        worker_label=normalize_label(worker_source),
    )
    return run_bridge(config)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
