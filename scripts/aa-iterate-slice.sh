#!/usr/bin/env bash
# Top-level orchestrator for one agent iteration ("slice").
# An agent runs this AFTER editing files under app/, then prints reproduction
# steps to the human, then runs:  ./scripts/aa-logcat-slice.sh stop <name>
# to harvest the logcat for that slice.
#
# Usage:
#   ./scripts/aa-iterate-slice.sh -n <slice-name> [-d "description"] [options]
#
# Options:
#   -n NAME           slice name (required; lower-kebab-case recommended)
#   -d DESC           one-line description (shown in the banner)
#   --rebuild         uninstall plugin first (clean slate), then install
#   --no-iter         skip dev-aa-ui-iteration.sh (just start the logcat slice; assumes app already up)
#   --no-dhu          pass NO_DHU=1 to dev-aa-ui-iteration.sh (skip DHU restart)
#   --no-main         pass NO_MAIN=1 to dev-aa-ui-iteration.sh (skip MainActivity launch)
#   --tags "T:V ..."  override logcat tag filters (forwarded to aa-logcat-slice.sh as TAGS env)
#   --pids-only       only filter logcat by package PIDs, no tag filter (forwarded as PIDS_ONLY=1)
#   -h, --help        show this help
#
# Env passthrough: ANDROID_SERIAL, PLUGIN_PKG, HOST_PKG.
#
# Exit codes: 0 success (slice started; orchestrator returns control to the agent).
#             non-zero if any sub-step failed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

NAME=""
DESC=""
REBUILD=0
NO_ITER=0
NO_DHU_FLAG=0
NO_MAIN_FLAG=0
TAGS_OVERRIDE=""
PIDS_ONLY_OVERRIDE=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) NAME="$2"; shift 2 ;;
    -d) DESC="$2"; shift 2 ;;
    --rebuild)    REBUILD=1; shift ;;
    --no-iter)    NO_ITER=1; shift ;;
    --no-dhu)     NO_DHU_FLAG=1; shift ;;
    --no-main)    NO_MAIN_FLAG=1; shift ;;
    --tags)       TAGS_OVERRIDE="$2"; shift 2 ;;
    --pids-only)  PIDS_ONLY_OVERRIDE=1; shift ;;
    -h|--help)    sed -n '2,28p' "$0"; exit 0 ;;
    *) echo "error: unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "$NAME" ]]; then
  echo "error: -n <slice-name> is required" >&2
  echo "Try: $0 --help" >&2
  exit 1
fi
if ! [[ "$NAME" =~ ^[a-z0-9][a-z0-9._-]*$ ]]; then
  echo "error: slice name must match ^[a-z0-9][a-z0-9._-]*$ (got: $NAME)" >&2
  exit 1
fi

_banner() {
  local kv
  printf '\n=== AA-SLICE-BEGIN ===\n'
  printf 'NAME=%s\n' "$NAME"
  printf 'DESC=%s\n' "${DESC:-}"
  printf 'BRANCH=%s\n' "$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
  printf 'COMMIT=%s\n' "$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  printf 'TIMESTAMP=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'REBUILD=%d\n' "$REBUILD"
  printf 'NO_ITER=%d\n' "$NO_ITER"
  printf 'NO_DHU=%d\n' "$NO_DHU_FLAG"
  printf 'NO_MAIN=%d\n' "$NO_MAIN_FLAG"
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    kv="ANDROID_SERIAL=${ANDROID_SERIAL}"
  else
    kv="ANDROID_SERIAL=<auto>"
  fi
  printf '%s\n' "$kv"
  printf '=== /AA-SLICE-BEGIN ===\n\n'
}

_status_block() {
  echo "--- AA-STATUS ---"
  bash "$SCRIPT_DIR/aa-status.sh" || true
  echo "--- /AA-STATUS ---"
}

_banner

if [[ "$REBUILD" == "1" ]]; then
  echo ">> [rebuild] uninstalling plugin first"
  bash "$SCRIPT_DIR/aa-uninstall.sh" || {
    echo "warning: uninstall step failed (continuing)" >&2
  }
fi

if [[ "$NO_ITER" != "1" ]]; then
  echo ">> running dev-aa-ui-iteration.sh"
  _env=()
  if [[ "$NO_DHU_FLAG"  == "1" ]]; then _env+=(NO_DHU=1); fi
  if [[ "$NO_MAIN_FLAG" == "1" ]]; then _env+=(NO_MAIN=1); fi
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then _env+=("ANDROID_SERIAL=$ANDROID_SERIAL"); fi
  # bash 3.2 (macOS) under `set -u` rejects ${arr[@]} when arr is empty;
  # the ${arr[@]+...} guard expands to nothing when unset.
  if ! env ${_env[@]+"${_env[@]}"} bash "$SCRIPT_DIR/dev-aa-ui-iteration.sh"; then
    echo "error: dev-aa-ui-iteration.sh failed; aborting slice" >&2
    exit 1
  fi
else
  echo ">> [--no-iter] skipping dev-aa-ui-iteration.sh"
fi

# Give the plugin a moment to spin up so logcat can resolve its PID.
sleep 2

echo ">> starting logcat slice"
_lc_env=()
if [[ -n "$TAGS_OVERRIDE" ]]; then _lc_env+=("TAGS=$TAGS_OVERRIDE"); fi
if [[ "$PIDS_ONLY_OVERRIDE" == "1" ]]; then _lc_env+=("PIDS_ONLY=1"); fi
if [[ -n "${ANDROID_SERIAL:-}" ]]; then _lc_env+=("ANDROID_SERIAL=$ANDROID_SERIAL"); fi
env ${_lc_env[@]+"${_lc_env[@]}"} bash "$SCRIPT_DIR/aa-logcat-slice.sh" start "$NAME"

echo
_status_block
echo
echo "=== AA-SLICE-READY ==="
echo "NAME=$NAME"
echo "ACTION_NEXT=human_observes_dhu"
echo "STOP_CMD=./scripts/aa-logcat-slice.sh stop $NAME"
echo "TAIL_CMD=./scripts/aa-logcat-slice.sh tail $NAME"
echo "PATH_CMD=./scripts/aa-logcat-slice.sh path $NAME"
echo "=== /AA-SLICE-READY ==="
