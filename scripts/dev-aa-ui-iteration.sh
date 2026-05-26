#!/usr/bin/env bash
# One-shot loop for Android Auto / DHU UI work: install fresh debug build, reset the plugin process,
# optionally relaunch the host entrypoint, restart Desktop Head Unit (USB AOAP).
#
# Scope: agents use this after fixing code under app/ (and rebuilding). Human inspects DHU and reports the next bug.
#
# Prerequisites: USB debugging, phone unlocked, DHU installed (sdkmanager "extras;google;auto").
# With multiple adb devices, set ANDROID_SERIAL to the USB device id from `adb devices`.
#
# Usage:
#   ./scripts/dev-aa-ui-iteration.sh
#   ANDROID_SERIAL=202512000740 ./scripts/dev-aa-ui-iteration.sh
#   INSTALL_ONLY=1 ./scripts/dev-aa-ui-iteration.sh    # skip DHU stop/start (build + reset app only)
#   NO_DHU=1 ./scripts/dev-aa-ui-iteration.sh          # same as INSTALL_ONLY=1
#   NO_MAIN=1 ./scripts/dev-aa-ui-iteration.sh         # do not am start MainActivity after force-stop
#
# Ref: scripts/run-dhu-usb.sh, AGENTS.md (DHU)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PLUGIN_PKG="${PLUGIN_PKG:-luci.sixsixsix.powerampache2.plugin}"
INSTALL_ONLY="${INSTALL_ONLY:-${NO_DHU:-0}}"
NO_MAIN="${NO_MAIN:-0}"

# macOS: DHU is Cocoa-based; DISPLAY may be unset in IDE terminals — still fine for DHU.
if [[ "$(uname -s)" != Darwin ]] && [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  echo "error: DISPLAY and WAYLAND_DISPLAY are both unset (DHU needs a graphical session)." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -f "$REPO_ROOT/local.properties" ]]; then
    # Use POSIX [[:space:]] — BSD awk on macOS does not understand \s.
    _sdk="$(awk -F= '/^[[:space:]]*sdk\.dir=/{gsub(/\r/,""); sub(/^[[:space:]]+/,"",$2); print $2; exit}' "$REPO_ROOT/local.properties")"
    if [[ -n "$_sdk" ]]; then
      export ANDROID_HOME="$_sdk"
    fi
  fi
fi
# Auto-detect canonical SDK locations: macOS first (Library/Android/sdk), then Linux (Android/Sdk).
if [[ -z "${ANDROID_HOME:-}" && -x "${HOME}/Library/Android/sdk/extras/google/auto/desktop-head-unit" ]]; then
  export ANDROID_HOME="${HOME}/Library/Android/sdk"
fi
if [[ -z "${ANDROID_HOME:-}" && -x "${HOME}/Android/Sdk/extras/google/auto/desktop-head-unit" ]]; then
  export ANDROID_HOME="${HOME}/Android/Sdk"
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo "error: ANDROID_HOME unset — set it or add sdk.dir to local.properties" >&2
  echo "  macOS default: \$HOME/Library/Android/sdk" >&2
  echo "  Linux default: \$HOME/Android/Sdk" >&2
  exit 1
fi

_adb() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

echo "=== [1/5] Stopping Desktop Head Unit (if running) ==="
pkill -x desktop-head-unit 2>/dev/null || true
sleep 1

echo "=== [2/5] Building and installing :app:installDebug ==="
(
  cd "$REPO_ROOT"
  # ANDROID_SERIAL is honored by the Android Gradle plugin for install when a single device is ambiguous.
  export ANDROID_SERIAL
  ./gradlew :app:installDebug
)

echo "=== [3/5] Force-stopping plugin package ($PLUGIN_PKG) ==="
_adb shell am force-stop "$PLUGIN_PKG" || true

if [[ "$NO_MAIN" != "1" ]]; then
  echo "=== [4/5] Launching MainActivity (opens Power Ampache 2 host when installed; activity may finish) ==="
  _adb shell am start -n "${PLUGIN_PKG}/.MainActivity" || {
    echo "warning: MainActivity start failed — open the host app manually if needed." >&2
  }
  sleep 2
else
  echo "=== [4/5] Skipping MainActivity (NO_MAIN=1) ==="
fi

if [[ "$INSTALL_ONLY" == "1" ]]; then
  echo "=== [5/5] Skipping DHU (INSTALL_ONLY=1 or NO_DHU=1) ==="
  echo "Done. Install + app reset complete."
  exit 0
fi

echo "=== [5/5] Starting DHU (USB AOAP, background) ==="
export ANDROID_HOME
if [[ -n "${WAYLAND_DISPLAY:-}" && -z "${SDL_VIDEODRIVER:-}" ]]; then
  export SDL_VIDEODRIVER=x11
fi
export XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}"

(
  cd "$REPO_ROOT"
  DHU_BACKGROUND=1 ./scripts/run-dhu-usb.sh
)

if [[ "$(uname -s)" == Darwin ]]; then
  echo "Done. DHU should be up; use Android Auto on the head unit to open the plugin. Logs: tail -f /tmp/dhu-run.log"
else
  echo "Done. DHU should be up; use Android Auto on the head unit to open the plugin. Logs: journalctl --user -n 80  (or tail -f /tmp/dhu-run.log if started via nohup)"
fi
