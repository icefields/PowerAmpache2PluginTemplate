#!/usr/bin/env bash
# Start DHU (USB AOAP) if needed, then wait until desktop-head-unit stays running
# long enough to be considered stable. Emits notify-send on success.
#
# Usage:
#   ANDROID_HOME=~/Android/Sdk ./scripts/dhu-wait-stable.sh
#   STABLE_SECONDS=30 INSTALL_PLUGIN=0 ./scripts/dhu-wait-stable.sh   # skip reinstall
#
# Env:
#   INSTALL_PLUGIN         if 1 (default), run ./gradlew :app:installDebug first (requires adb device)
#   LAUNCH_PLUGIN_ACTIVITY if 1 (default), adb start MainActivity — opens Power Ampache 2 host if installed
#   STABLE_SECONDS         consecutive seconds process must stay alive (default 25)
#   START_DHU              if 1 (default), run run-dhu-usb.sh with DHU_BACKGROUND=1 when no DHU running
#   MAX_WAIT_FIRST_SIGHT   max seconds to wait for first DHU process (default 90)
#   DHU_LOG                tail on failure if present (default /tmp/dhu-run.log)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

INSTALL_PLUGIN="${INSTALL_PLUGIN:-1}"
LAUNCH_PLUGIN_ACTIVITY="${LAUNCH_PLUGIN_ACTIVITY:-1}"
PLUGIN_PKG="${PLUGIN_PKG:-luci.sixsixsix.powerampache2.plugin}"
STABLE_SECONDS="${STABLE_SECONDS:-25}"
START_DHU="${START_DHU:-1}"
MAX_WAIT_FIRST_SIGHT="${MAX_WAIT_FIRST_SIGHT:-90}"
DHU_LOG="${DHU_LOG:-/tmp/dhu-run.log}"

_dhu_pids() {
  pgrep -x "desktop-head-unit" 2>/dev/null || true
}

_notify() {
  local summary="DHU stable"
  local body="Plugin APK installed, host launched (if available), desktop-head-unit ran ${STABLE_SECONDS}s. Android Auto is ready to test."
  if command -v notify-send >/dev/null 2>&1; then
    notify-send -a "PowerAmpache DHU" -i dialog-information "$summary" "$body" 2>/dev/null || true
  fi
  printf '\n*** %s ***\n%s\n' "$summary" "$body"
}

# macOS DHU is Cocoa-based; DISPLAY is unset there (still fine). Only require X/Wayland on Linux.
if [[ "$(uname -s)" != Darwin ]] && [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  echo "error: DISPLAY and WAYLAND_DISPLAY unset — run from a graphical session." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -f "$REPO_ROOT/local.properties" ]]; then
    _sdk="$(awk -F= '/^[[:space:]]*sdk\.dir=/{gsub(/\r/,""); sub(/^[[:space:]]+/,"",$2); print $2; exit}' "$REPO_ROOT/local.properties")"
    if [[ -n "$_sdk" && -x "$_sdk/extras/google/auto/desktop-head-unit" ]]; then
      export ANDROID_HOME="$_sdk"
      echo "Using sdk.dir from local.properties: ANDROID_HOME=$ANDROID_HOME"
    fi
  fi
fi
# Auto-detect canonical SDK locations: macOS first (Library/Android/sdk), then Linux (Android/Sdk).
if [[ -z "${ANDROID_HOME:-}" && -x "${HOME}/Library/Android/sdk/extras/google/auto/desktop-head-unit" ]]; then
  export ANDROID_HOME="${HOME}/Library/Android/sdk"
  echo "Using ANDROID_HOME=$ANDROID_HOME (default macOS SDK path with DHU installed)"
fi
if [[ -z "${ANDROID_HOME:-}" && -x "${HOME}/Android/Sdk/extras/google/auto/desktop-head-unit" ]]; then
  export ANDROID_HOME="${HOME}/Android/Sdk"
  echo "Using ANDROID_HOME=$ANDROID_HOME (default Linux SDK path with DHU installed)"
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "error: ANDROID_HOME unset — install DHU (sdkmanager \"extras;google;auto\") or export ANDROID_HOME." >&2
  echo "  macOS default: \$HOME/Library/Android/sdk" >&2
  echo "  Linux default: \$HOME/Android/Sdk" >&2
  exit 1
fi

if [[ "${INSTALL_PLUGIN}" == "1" ]]; then
  echo "Installing plugin APK: ./gradlew :app:installDebug"
  (cd "$REPO_ROOT" && ./gradlew :app:installDebug) || {
    echo "error: installDebug failed — connect one device with USB debugging, then retry." >&2
    exit 1
  }
fi

if [[ "${LAUNCH_PLUGIN_ACTIVITY}" == "1" ]]; then
  echo "Launching plugin MainActivity (starts Power Ampache 2 host when installed)..."
  adb shell am start -n "${PLUGIN_PKG}/.MainActivity" || {
    echo "warning: adb start MainActivity failed — open the host app manually so PA2DataFetchService can bind." >&2
  }
  sleep 2
fi

if [[ "${START_DHU}" == "1" ]] && [[ -z "$(_dhu_pids)" ]]; then
  echo "Starting DHU (DHU_BACKGROUND=1)..."
  (cd "$REPO_ROOT" && DHU_BACKGROUND=1 DHU_LOG="$DHU_LOG" ./scripts/run-dhu-usb.sh) || {
    echo "error: run-dhu-usb.sh failed." >&2
    exit 1
  }
  sleep 2
fi

echo "Waiting for desktop-head-unit (first sight within ${MAX_WAIT_FIRST_SIGHT}s, then ${STABLE_SECONDS}s stable)..."
_seen=""
_stable=0
for ((elapsed = 0; elapsed < MAX_WAIT_FIRST_SIGHT + STABLE_SECONDS + 60; elapsed++)); do
  if [[ -n "$(_dhu_pids)" ]]; then
    if [[ -z "$_seen" ]]; then
      echo "DHU process detected (pid $(_dhu_pids | tr '\n' ' '))"
      _seen=1
    fi
    ((_stable++)) || true
    if ((_stable >= STABLE_SECONDS)); then
      _notify
      exit 0
    fi
  else
    if [[ -n "$_seen" ]]; then
      echo "error: DHU process exited after attach (crashed or closed)." >&2
      if [[ -f "$DHU_LOG" ]]; then
        echo "--- tail $DHU_LOG ---" >&2
        tail -40 "$DHU_LOG" >&2 || true
      fi
      echo "Try: journalctl --user -n 80 --no-pager" >&2
      exit 1
    fi
    _stable=0
    if ((elapsed >= MAX_WAIT_FIRST_SIGHT)); then
      echo "error: no desktop-head-unit within ${MAX_WAIT_FIRST_SIGHT}s." >&2
      if [[ -f "$DHU_LOG" ]]; then
        echo "--- tail $DHU_LOG ---" >&2
        tail -40 "$DHU_LOG" >&2 || true
      fi
      if [[ "$(uname -s)" == Darwin ]]; then
        echo "Hint: tail -f $DHU_LOG" >&2
      else
        echo "Hint: tail -f $DHU_LOG  (or journalctl --user -f if started via systemd-run)" >&2
      fi
      exit 1
    fi
  fi
  sleep 1
done

echo "error: timed out waiting for stable DHU." >&2
exit 1
