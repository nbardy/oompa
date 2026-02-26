#!/usr/bin/env bash
# Copy tracked code files in this repo to clipboard with file headers.
#
# Usage:
#   ./scripts/copy-repo-code.sh
#   ./scripts/copy-repo-code.sh --all-files

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./scripts/copy-repo-code.sh
  ./scripts/copy-repo-code.sh --all-files

Default mode:
  Copies implementation-oriented files only (src, scripts, bins, tests, and
  top-level shell/babashka entrypoints).

--all-files:
  Copies all tracked text files in the repository.
EOF
}

mode="implementation"
if [ "${1:-}" = "--all-files" ]; then
  mode="all"
  shift
fi

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if [ "$#" -gt 0 ]; then
  usage >&2
  exit 1
fi

if ! command -v git >/dev/null 2>&1; then
  echo "Error: git is required." >&2
  exit 1
fi

if ! command -v pbcopy >/dev/null 2>&1; then
  echo "Error: pbcopy is not available on this system." >&2
  exit 1
fi

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(git -C "$script_dir" rev-parse --show-toplevel 2>/dev/null || true)
if [ -z "$repo_root" ]; then
  echo "Error: could not determine git repo root." >&2
  exit 1
fi

tmp_file=$(mktemp)
cleanup() {
  rm -f "$tmp_file"
}
trap cleanup EXIT

copied=0
skipped=0

if [ "$mode" = "all" ]; then
  file_source_cmd=(git -C "$repo_root" ls-files -z)
else
  file_source_cmd=(
    git -C "$repo_root" ls-files -z --
    "src/**"
    "agentnet/src/**"
    "bin/**"
    "scripts/**"
    "tests/**"
    "agentnet/test/**"
    "*.sh"
    "*.bb"
    "bb.edn"
    "package.json"
  )
fi

while IFS= read -r -d '' file; do
  full_path="$repo_root/$file"
  [ -f "$full_path" ] || continue

  # Skip files that look binary.
  if ! grep -Iq . "$full_path"; then
    skipped=$((skipped + 1))
    continue
  fi

  copied=$((copied + 1))
  {
    printf '===== FILE: %s =====\n' "$file"
    cat "$full_path"
    printf '\n\n'
  } >> "$tmp_file"
done < <("${file_source_cmd[@]}")

if [ "$copied" -eq 0 ]; then
  echo "No tracked text files found to copy." >&2
  exit 1
fi

pbcopy < "$tmp_file"
bytes=$(wc -c < "$tmp_file" | tr -d '[:space:]')
echo "Copied $copied files ($bytes bytes) to clipboard in '$mode' mode. Skipped $skipped binary files."
