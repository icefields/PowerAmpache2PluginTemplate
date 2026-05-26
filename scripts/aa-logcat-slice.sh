#!/usr/bin/env bash
# Capture a named, time-bounded logcat slice for one agent iteration.
#
# Usage:
#   ./scripts/aa-logcat-slice.sh start <name>     # clear buffer, begin background capture
#   ./scripts/aa-logcat-slice.sh stop  <name>     # stop, then tail and print summary
#   ./scripts/aa-logcat-slice.sh tail  <name>     # tail current slice without stopping
#   ./scripts/aa-logcat-slice.sh path  <name>     # print log file path
#   ./scripts/aa-logcat-slice.sh list             # list active and recent slices
#
# Slices are stored under .cursor/aa-logcat/<name>-<timestamp>.log
# Active slice metadata: .cursor/aa-logcat/<name>.active   (lines: PID=, LOG=, START=)
#
# Filters (default): plugin + host package PIDs, plus tags:
#   Pa2MediaLibraryService, Pa2DataFetchService, MusicFetcher,
#   MediaSession*, MediaBrowser*, MediaLibrary*, AndroidAuto*, CarApp*
# Override with TAGS="..." env (space-separated logcat -s spec) or PIDS_ONLY=1.

set -euo pipefail

PLUGIN_PKG="${PLUGIN_PKG:-luci.sixsixsix.powerampache2.plugin}"
HOST_PKG="${HOST_PKG:-luci.sixsixsix.powerampache2}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SLICE_DIR="${SLICE_DIR:-$REPO_ROOT/.cursor/aa-logcat}"
mkdir -p "$SLICE_DIR"

TAIL_LINES="${TAIL_LINES:-80}"
DEFAULT_TAGS=(
  "Pa2MediaLibraryService:V"
  "Pa2DataFetchService:V"
  "MusicFetcher:V"
  "MediaSession:V"
  "MediaSessionService:V"
  "MediaBrowser:V"
  "MediaBrowserService:V"
  "MediaLibrarySession:V"
  "MediaLibraryService:V"
  "AndroidAuto:V"
  "CarApp:V"
  "AndroidRuntime:E"
)

_adb() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

_resolve_pid() {
  local pkg="$1"
  _adb shell pidof "$pkg" 2>/dev/null | tr -d '\r' | awk '{print $1}'
}

_active_file() { echo "$SLICE_DIR/$1.active"; }

_cmd_start() {
  local name="${1:?name required}"
  local active; active="$(_active_file "$name")"
  if [[ -f "$active" ]]; then
    local prev_pid; prev_pid="$(awk -F= '/^PID=/{print $2; exit}' "$active" 2>/dev/null || true)"
    if [[ -n "${prev_pid:-}" ]] && kill -0 "$prev_pid" 2>/dev/null; then
      echo "error: slice '$name' is already active (PID $prev_pid). Run: $0 stop $name" >&2
      exit 1
    fi
    rm -f "$active"
  fi

  local ts; ts="$(date +%Y%m%dT%H%M%S)"
  local logf="$SLICE_DIR/${name}-${ts}.log"

  echo "Clearing logcat buffer..."
  _adb logcat -c || true

  local plugin_pid; plugin_pid="$(_resolve_pid "$PLUGIN_PKG" || true)"
  local host_pid;   host_pid="$(_resolve_pid "$HOST_PKG" || true)"

  # adb logcat accepts at most ONE --pid arg. Prefer the plugin PID (the agent's
  # primary target). Host-side activity is still captured via tag filters
  # (Pa2DataFetchService, etc.).
  local args=()
  local using_pid=0
  if [[ -n "${plugin_pid:-}" ]]; then
    args+=(--pid="$plugin_pid")
    using_pid=1
  fi

  # CRITICAL: adb logcat AND-combines --pid and -s <tags>. The PA2 plugin logs
  # mostly via System.out (and the host via custom tags like 'lucie'), so a tag
  # whitelist drops everything. When we have a PID, capture all output from
  # that process. Only fall back to tag filtering when no PID is resolvable
  # (cold start, plugin idle), or the caller explicitly opts in via TAGS=...
  if [[ -n "${TAGS:-}" ]]; then
    # Caller explicitly specified tags — honour them.
    # shellcheck disable=SC2086
    set -- $TAGS
    args+=(-s "$@")
  elif [[ "${PIDS_ONLY:-0}" == "1" || "$using_pid" == "1" ]]; then
    # PID-scoped capture: no tag filter (we want everything from the process).
    :
  else
    # No PID available and no explicit TAGS — fall back to default tag list
    # so we still catch system-level errors (AndroidRuntime:E etc).
    args+=(-s "${DEFAULT_TAGS[@]}")
  fi

  args+=(-v threadtime)

  echo "Starting logcat slice '$name' -> $logf"
  echo "  filters: ${args[*]}"
  if [[ -n "${plugin_pid:-}${host_pid:-}" ]]; then
    echo "  pids: plugin=${plugin_pid:-<not_running>} host=${host_pid:-<not_running>}"
  else
    echo "  pids: <none yet — capturing by tag only; restart slice once app is up for PID-scoped logs>"
  fi

  # Background capture, detached from this shell so the orchestrator returns.
  # nohup cannot invoke a shell function, so dispatch on ANDROID_SERIAL inline.
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    nohup adb -s "$ANDROID_SERIAL" logcat "${args[@]}" >>"$logf" 2>&1 &
  else
    nohup adb logcat "${args[@]}" >>"$logf" 2>&1 &
  fi
  local pid=$!
  {
    echo "PID=$pid"
    echo "LOG=$logf"
    echo "START=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "PLUGIN_PID=${plugin_pid:-}"
    echo "HOST_PID=${host_pid:-}"
  } > "$active"

  echo "Slice '$name' started (PID $pid). Stop with: $0 stop $name"
}

_cmd_stop() {
  local name="${1:?name required}"
  local active; active="$(_active_file "$name")"
  if [[ ! -f "$active" ]]; then
    echo "error: no active slice named '$name'" >&2
    exit 1
  fi
  local pid logf
  pid="$(awk -F= '/^PID=/{print $2; exit}' "$active")"
  logf="$(awk -F= '/^LOG=/{print $2; exit}' "$active")"
  if [[ -n "${pid:-}" ]] && kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    sleep 1
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$active"

  if [[ ! -f "$logf" ]]; then
    echo "warning: log file missing: $logf"
    exit 1
  fi

  if [[ "$(uname -s)" == Darwin ]]; then
    local sz; sz="$(stat -f%z "$logf" 2>/dev/null || echo 0)"
  else
    local sz; sz="$(stat -c%s "$logf" 2>/dev/null || echo 0)"
  fi
  local lines; lines="$(wc -l <"$logf" | tr -d '[:space:]' || echo 0)"
  local errors warns
  errors="$(grep -cE ' E [A-Za-z0-9_./:]+:' "$logf" 2>/dev/null || true)"
  warns="$(grep -cE ' W [A-Za-z0-9_./:]+:'  "$logf" 2>/dev/null || true)"
  errors="${errors:-0}"
  warns="${warns:-0}"

  echo "=== AA-LOGCAT-SLICE-RESULT ==="
  echo "NAME=$name"
  echo "LOG=$logf"
  echo "BYTES=$sz"
  echo "LINES=$lines"
  echo "ERRORS=$errors"
  echo "WARNINGS=$warns"
  echo "--- TAIL_${TAIL_LINES} ---"
  tail -"$TAIL_LINES" "$logf" || true
  echo "--- /TAIL ---"
  echo "=== /AA-LOGCAT-SLICE-RESULT ==="
}

_cmd_tail() {
  local name="${1:?name required}"
  local active; active="$(_active_file "$name")"
  if [[ ! -f "$active" ]]; then
    echo "error: no active slice named '$name'" >&2
    exit 1
  fi
  local logf; logf="$(awk -F= '/^LOG=/{print $2; exit}' "$active")"
  tail -"$TAIL_LINES" "$logf" || true
}

_cmd_path() {
  local name="${1:?name required}"
  local active; active="$(_active_file "$name")"
  if [[ -f "$active" ]]; then
    awk -F= '/^LOG=/{print $2; exit}' "$active"
  else
    # Fall back to most recent named slice file. Slice names are
    # constrained to ^[a-z0-9][a-z0-9._-]*$ in aa-iterate-slice.sh, so
    # parsing `ls` output is safe here.
    # shellcheck disable=SC2012
    ls -1t "$SLICE_DIR/${name}-"*.log 2>/dev/null | head -1
  fi
}

_cmd_list() {
  echo "Slice dir: $SLICE_DIR"
  echo "Active slices:"
  for f in "$SLICE_DIR"/*.active; do
    [[ -e "$f" ]] || { echo "  (none)"; break; }
    echo "  - $(basename "$f" .active)"
    sed 's/^/      /' "$f"
  done
  echo "Recent log files:"
  # shellcheck disable=SC2012
  ls -1t "$SLICE_DIR"/*.log 2>/dev/null | head -10 | sed 's/^/  /' || echo "  (none)"
}

case "${1:-}" in
  start) shift; _cmd_start "$@" ;;
  stop)  shift; _cmd_stop  "$@" ;;
  tail)  shift; _cmd_tail  "$@" ;;
  path)  shift; _cmd_path  "$@" ;;
  list)  shift; _cmd_list  "$@" ;;
  -h|--help|help|"") sed -n '2,18p' "$0" ;;
  *) echo "error: unknown command: $1" >&2; sed -n '2,18p' "$0" >&2; exit 1 ;;
esac
