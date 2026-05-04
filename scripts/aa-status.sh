#!/usr/bin/env bash
# Print machine-parseable Android Auto / plugin status for the agent loop.
# Output is plain KEY=VALUE lines (no JSON dependency). Stable keys; new keys
# are appended over time so existing parsers keep working.
#
# Usage:
#   ./scripts/aa-status.sh
#   ANDROID_SERIAL=<serial> ./scripts/aa-status.sh
#
# Keys emitted:
#   ADB_OK            yes|no
#   DEVICE_COUNT      integer (devices in 'device' state)
#   DEVICE_SERIAL     selected serial (ANDROID_SERIAL, or sole device, or "ambiguous")
#   PLUGIN_INSTALLED  yes|no|unknown
#   PLUGIN_VERSION    versionName from dumpsys (or empty)
#   PLUGIN_VERSION_CODE versionCode from dumpsys (or empty)
#   HOST_INSTALLED    yes|no|unknown
#   DHU_RUNNING       yes|no
#   DHU_PIDS          space-separated PIDs (empty if none)
#   DHU_LOG           path
#   DHU_LOG_SIZE      bytes
#   DHU_LOG_TAIL_LINES integer (always 5 when log exists)

set -euo pipefail

PLUGIN_PKG="${PLUGIN_PKG:-luci.sixsixsix.powerampache2.plugin}"
HOST_PKG="${HOST_PKG:-luci.sixsixsix.powerampache2}"
DHU_LOG="${DHU_LOG:-/tmp/dhu-run.log}"

if ! command -v adb >/dev/null 2>&1; then
  echo "ADB_OK=no"
  echo "ERROR=adb_not_found"
  exit 1
fi
echo "ADB_OK=yes"

# Collect devices in 'device' state. Portable across bash 3.2 (macOS).
_devs=()
while IFS= read -r _d; do
  [[ -n "$_d" ]] && _devs+=("$_d")
done < <(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}')
echo "DEVICE_COUNT=${#_devs[@]}"

_serial=""
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  _serial="$ANDROID_SERIAL"
elif [[ ${#_devs[@]} -eq 1 ]]; then
  _serial="${_devs[0]}"
elif [[ ${#_devs[@]} -gt 1 ]]; then
  _serial="ambiguous"
fi
echo "DEVICE_SERIAL=$_serial"

_adb_pkg() {
  if [[ -n "$_serial" && "$_serial" != "ambiguous" ]]; then
    adb -s "$_serial" "$@"
  else
    return 1
  fi
}

_is_installed() {
  local pkg="$1"
  _adb_pkg shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$pkg"
}

_dump_version() {
  # dumpsys package output looks like:
  #   versionCode=6 minSdk=30 targetSdk=36
  #   versionName=1.6
  # We only want the value of the first whitespace-delimited token.
  local pkg="$1"
  _adb_pkg shell dumpsys package "$pkg" 2>/dev/null | tr -d '\r' | awk '
    function val(field, line,   s) {
      s = line
      sub(/^[[:space:]]*[A-Za-z]+=/, "", s)   # strip "versionCode="/"versionName="
      sub(/[[:space:]].*$/, "", s)             # keep only first token
      return s
    }
    /versionCode=/ && vc=="" { vc=val("versionCode", $0) }
    /versionName=/ && vn=="" { vn=val("versionName", $0) }
    END { print vn "|" vc }
  '
}

if [[ -n "$_serial" && "$_serial" != "ambiguous" ]]; then
  if _is_installed "$PLUGIN_PKG"; then
    echo "PLUGIN_INSTALLED=yes"
    _dv="$(_dump_version "$PLUGIN_PKG" || true)"
    _vn="${_dv%%|*}"   # left of |  = versionName
    _vc="${_dv##*|}"   # right of | = versionCode
    echo "PLUGIN_VERSION=${_vn:-}"
    echo "PLUGIN_VERSION_CODE=${_vc:-}"
  else
    echo "PLUGIN_INSTALLED=no"
    echo "PLUGIN_VERSION="
    echo "PLUGIN_VERSION_CODE="
  fi
  if _is_installed "$HOST_PKG"; then
    echo "HOST_INSTALLED=yes"
  else
    echo "HOST_INSTALLED=no"
  fi
else
  echo "PLUGIN_INSTALLED=unknown"
  echo "PLUGIN_VERSION="
  echo "PLUGIN_VERSION_CODE="
  echo "HOST_INSTALLED=unknown"
fi

# DHU status
_dhu_pids="$(pgrep -x desktop-head-unit 2>/dev/null | tr '\n' ' ' || true)"
_dhu_pids="${_dhu_pids%% }"
if [[ -n "$_dhu_pids" ]]; then
  echo "DHU_RUNNING=yes"
else
  echo "DHU_RUNNING=no"
fi
echo "DHU_PIDS=$_dhu_pids"
echo "DHU_LOG=$DHU_LOG"
if [[ -f "$DHU_LOG" ]]; then
  if [[ "$(uname -s)" == Darwin ]]; then
    _sz="$(stat -f%z "$DHU_LOG" 2>/dev/null || echo 0)"
  else
    _sz="$(stat -c%s "$DHU_LOG" 2>/dev/null || echo 0)"
  fi
  echo "DHU_LOG_SIZE=$_sz"
  echo "DHU_LOG_TAIL_LINES=5"
  echo "--- DHU_LOG_TAIL ---"
  tail -5 "$DHU_LOG" || true
  echo "--- /DHU_LOG_TAIL ---"
else
  echo "DHU_LOG_SIZE=0"
  echo "DHU_LOG_TAIL_LINES=0"
fi
