#!/usr/bin/env bash
# Android Auto DHU via ADB tunnel (older but reliable when USB AOAP or SDL/Wayland misbehaves).
#
# On the phone FIRST:
#   Android Auto → tap version / About repeatedly until Developer mode appears
#   → ⋮ menu → Developer settings → enable "Start head unit server"
#   → Start the head unit server from the notification or the same menu (wording varies by AA version)
#
# Then connect USB (or use wireless debugging with adb connect), and:
#   adb forward tcp:5277 tcp:5277
#   ./scripts/run-dhu-adb-tunnel.sh
#
# Ref: https://developer.android.com/training/cars/testing/dhu

set -euo pipefail

if [[ "$(uname -s)" != Darwin ]] && [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  echo "error: DISPLAY and WAYLAND_DISPLAY are both unset. DHU needs a graphical session." >&2
  exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  # Auto-detect canonical SDK locations: macOS first, then Linux.
  if [[ -x "${HOME}/Library/Android/sdk/extras/google/auto/desktop-head-unit" ]]; then
    export ANDROID_HOME="${HOME}/Library/Android/sdk"
  elif [[ -x "${HOME}/Android/Sdk/extras/google/auto/desktop-head-unit" ]]; then
    export ANDROID_HOME="${HOME}/Android/Sdk"
  else
    echo "error: ANDROID_HOME is not set" >&2
    echo "  macOS default: export ANDROID_HOME=\$HOME/Library/Android/sdk" >&2
    echo "  Linux default: export ANDROID_HOME=\$HOME/Android/Sdk" >&2
    exit 1
  fi
fi

DHU="$ANDROID_HOME/extras/google/auto/desktop-head-unit"
if [[ ! -x "$DHU" ]]; then
  echo "error: DHU not found at $DHU — install with: sdkmanager \"extras;google;auto\"" >&2
  exit 1
fi

# Same SDL hint as run-dhu-usb.sh (DHU uses SDL + X11; Wayland often needs XWayland explicitly).
if [[ -n "${WAYLAND_DISPLAY:-}" && -z "${SDL_VIDEODRIVER:-}" ]]; then
  export SDL_VIDEODRIVER=x11
fi
export XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}"

if ! adb devices | grep -q 'device$'; then
  echo "error: no authorized adb device; connect phone and accept USB debugging" >&2
  exit 1
fi

echo "Forwarding tcp:5277 (DHU) → phone tcp:5277..."
adb forward tcp:5277 tcp:5277

# CRITICAL: DHU 2.0 has an interactive '> ' command prompt and exits when stdin
# closes. We pipe `tail -f /dev/null` into DHU so its prompt never sees EOF;
# without this, DHU dies before TLS / browse traffic can establish.
# Use DHU_BACKGROUND=1 to detach (survives shell exit); otherwise foreground.
if [[ -n "${DHU_BACKGROUND:-}" ]]; then
  _log="${DHU_LOG:-/tmp/dhu-run.log}"
  _pidf="${DHU_PID_FILE:-/tmp/dhu.pid}"
  echo "Starting DHU detached (log: $_log, pid file: $_pidf)..."
  nohup bash -c 'tail -f /dev/null | "$@"' _ "$DHU" "$@" >>"$_log" 2>&1 &
  _wrapper_pid=$!
  for _i in 1 2 3 4 5; do
    sleep 1
    _dhu_pid="$(pgrep -x desktop-head-unit 2>/dev/null | head -1 || true)"
    if [[ -n "${_dhu_pid:-}" ]]; then break; fi
  done
  if [[ -n "${_dhu_pid:-}" ]]; then
    echo "$_dhu_pid" >"$_pidf"
  else
    echo "$_wrapper_pid" >"$_pidf"
  fi
  echo "DHU PID $(cat "$_pidf")  (wrapper PID $_wrapper_pid)"
  exit 0
fi

echo "Starting DHU (ADB transport). If the window closes immediately, ensure Head Unit Server is running on the phone."
# Foreground: if stdin is a terminal, run DHU directly so user can type at the
# '> ' prompt; otherwise keep stdin open so DHU does not exit on EOF.
if [[ -t 0 ]] || [[ -n "${DHU_INTERACTIVE:-}" ]]; then
  exec "$DHU" "$@"
else
  exec bash -c 'tail -f /dev/null | "$@"' _ "$DHU" "$@"
fi
