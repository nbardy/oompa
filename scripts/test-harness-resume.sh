#!/usr/bin/env bash
# test-harness-resume.sh — Smoke test session create + resume for each harness.
#
# For each available harness CLI:
#   1. Create a session: "Remember the word FOOBAR"
#   2. Resume that session: "What word were you supposed to remember?"
#   3. Check the response contains FOOBAR
#
# Usage:
#   ./scripts/test-harness-resume.sh           # test all available
#   ./scripts/test-harness-resume.sh claude     # test one harness
#
# Requires the CLI binaries to be on PATH.

set -euo pipefail

PASS=0
FAIL=0
SKIP=0

# Unset CLAUDECODE so we can run nested claude sessions
unset CLAUDECODE 2>/dev/null || true

green()  { printf "\033[32m%s\033[0m\n" "$*"; }
red()    { printf "\033[31m%s\033[0m\n" "$*"; }
yellow() { printf "\033[33m%s\033[0m\n" "$*"; }

check_binary() {
  command -v "$1" >/dev/null 2>&1
}

# ── Claude ────────────────────────────────────────────────────────────────────

test_claude() {
  local uuid
  uuid=$(python3 -c "import uuid; print(str(uuid.uuid4()))")

  echo "  Session ID: $uuid"

  # Step 1: Create session
  echo "  Creating session..."
  local create_out
  create_out=$(claude --dangerously-skip-permissions \
    --session-id "$uuid" \
    -p "Remember the word FOOBAR. Say only: OK, remembered." 2>&1)
  local create_exit=$?

  if [ $create_exit -ne 0 ]; then
    red "  FAIL: create exited $create_exit"
    echo "  Output: $create_out"
    return 1
  fi
  echo "  Create output: $create_out"

  # Step 2: Resume session
  echo "  Resuming session..."
  local resume_out
  resume_out=$(claude --dangerously-skip-permissions \
    --resume "$uuid" \
    -p "What word were you supposed to remember? Say only the word." 2>&1)
  local resume_exit=$?

  if [ $resume_exit -ne 0 ]; then
    red "  FAIL: resume exited $resume_exit"
    echo "  Output: $resume_out"
    return 1
  fi
  echo "  Resume output: $resume_out"

  # Step 3: Check for FOOBAR
  if echo "$resume_out" | grep -qi "FOOBAR"; then
    green "  PASS: resume recalled FOOBAR"
    return 0
  else
    red "  FAIL: resume did not recall FOOBAR"
    return 1
  fi
}

# ── Opencode ──────────────────────────────────────────────────────────────────

test_opencode() {
  # Step 1: Create session, extract session-id from NDJSON
  echo "  Creating session..."
  local create_out
  create_out=$(opencode run --format json --print-logs --log-level WARN \
    "Remember the word FOOBAR. Say only: OK, remembered." 2>&1)
  local create_exit=$?

  if [ $create_exit -ne 0 ]; then
    red "  FAIL: create exited $create_exit"
    return 1
  fi

  # Extract session ID from NDJSON
  local sid
  sid=$(echo "$create_out" | grep -o '"sessionI[Dd]":"[^"]*"' | head -1 | cut -d'"' -f4)
  if [ -z "$sid" ]; then
    # Try alternate pattern
    sid=$(echo "$create_out" | grep -oE 'ses_[A-Za-z0-9]+' | head -1)
  fi

  if [ -z "$sid" ]; then
    red "  FAIL: could not extract session ID"
    echo "  Output: $create_out"
    return 1
  fi
  echo "  Session ID: $sid"

  # Step 2: Resume
  echo "  Resuming session..."
  local resume_out
  resume_out=$(opencode run --format json --print-logs --log-level WARN \
    -s "$sid" --continue \
    "What word were you supposed to remember? Say only the word." 2>&1)
  local resume_exit=$?

  if [ $resume_exit -ne 0 ]; then
    red "  FAIL: resume exited $resume_exit"
    return 1
  fi

  if echo "$resume_out" | grep -qi "FOOBAR"; then
    green "  PASS: resume recalled FOOBAR"
    return 0
  else
    red "  FAIL: resume did not recall FOOBAR"
    echo "  Output: $resume_out"
    return 1
  fi
}

# ── Gemini ────────────────────────────────────────────────────────────────────

test_gemini() {
  # Gemini uses implicit sessions (by cwd) and --resume latest
  local tmpdir
  tmpdir=$(mktemp -d)

  echo "  Working dir: $tmpdir"

  # Step 1: Create session
  echo "  Creating session..."
  local create_out
  create_out=$(cd "$tmpdir" && gemini --yolo \
    -p "Remember the word FOOBAR. Say only: OK, remembered." 2>&1)
  local create_exit=$?

  if [ $create_exit -ne 0 ]; then
    red "  FAIL: create exited $create_exit"
    rm -rf "$tmpdir"
    return 1
  fi
  echo "  Create output: $create_out"

  # Step 2: Resume
  echo "  Resuming session..."
  local resume_out
  resume_out=$(cd "$tmpdir" && gemini --yolo --resume latest \
    -p "What word were you supposed to remember? Say only the word." 2>&1)
  local resume_exit=$?

  rm -rf "$tmpdir"

  if [ $resume_exit -ne 0 ]; then
    red "  FAIL: resume exited $resume_exit"
    return 1
  fi

  if echo "$resume_out" | grep -qi "FOOBAR"; then
    green "  PASS: resume recalled FOOBAR"
    return 0
  else
    red "  FAIL: resume did not recall FOOBAR"
    echo "  Output: $resume_out"
    return 1
  fi
}

# ── Codex ─────────────────────────────────────────────────────────────────────

test_codex() {
  # Codex has resume-fn: nil — resume is not supported.
  # This is a known limitation, not a silent skip. Fail so it stays visible.
  red "  FAIL: codex does not support session resume (resume-fn is nil in registry)"
  return 1
}

# ── Runner ────────────────────────────────────────────────────────────────────

run_test() {
  local harness=$1
  echo ""
  echo "=== Testing $harness ==="

  if ! check_binary "$harness"; then
    yellow "  SKIP: $harness not found on PATH"
    SKIP=$((SKIP + 1))
    return
  fi

  local result=0
  "test_$harness" || result=$?

  if [ $result -eq 0 ]; then
    PASS=$((PASS + 1))
  else
    FAIL=$((FAIL + 1))
  fi
}

# ── Main ──────────────────────────────────────────────────────────────────────

HARNESSES="${1:-claude codex opencode gemini}"

echo "Harness Resume Smoke Tests"
echo "=========================="

for h in $HARNESSES; do
  run_test "$h"
done

echo ""
echo "=========================="
echo "Results: $(green "$PASS pass"), $(red "$FAIL fail"), $(yellow "$SKIP skip")"

if [ $FAIL -gt 0 ]; then
  exit 1
fi
