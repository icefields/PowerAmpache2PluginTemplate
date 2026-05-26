#!/usr/bin/env bash
# Run Android Auto Desktop Head Unit 2.x over USB (Android Open Accessory / AOA).
# This is Google's recommended path for DHU 2.x and does NOT use:
#   - adb forward tcp:5277 tcp:5277
#   - Android Auto → "Start head unit server" (that flow is for ADB tunneling only)
#
# Prerequisites: phone USB-connected, screen unlocked, USB debugging on.
# If DHU cannot attach to USB, try: adb kill-server   (then run this script; restart adb later if needed)
#
# Ref: https://developer.android.com/training/cars/testing/dhu#connection-aoap

set -euo pipefail

# Need a graphical session for SDL on Linux/Wayland. macOS DHU uses Cocoa; DISPLAY is often unset there (still OK).
if [[ "$(uname -s)" != Darwin ]] && [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
  echo "error: DISPLAY and WAYLAND_DISPLAY are both unset. DHU needs your desktop session (e.g. export DISPLAY=:0)." >&2
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

# libc++ loader-path check is Linux-specific (Arch packs libc++ separately; macOS has Apple's).
# Skip on macOS where ldd is not available and the linker resolution is different.
if [[ "$(uname -s)" != Darwin ]]; then
  if ! ldd "$DHU" 2>/dev/null | grep -q 'libc++.so.1 => /usr/lib'; then
    if [[ -n "${LD_LIBRARY_PATH:-}" ]]; then
      echo "warning: LD_LIBRARY_PATH is set; if DHU fails to start, run: unset LD_LIBRARY_PATH" >&2
    fi
  fi
fi

# DHU is SDL-based; on Wayland, leaving SDL to pick a driver often causes an instant window close.
# Force X11 through XWayland unless the user already set SDL_VIDEODRIVER.
if [[ -n "${WAYLAND_DISPLAY:-}" && -z "${SDL_VIDEODRIVER:-}" ]]; then
  export SDL_VIDEODRIVER=x11
fi

# X11 auth for some setups (SSH, systemd user session).
export XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}"

# Resolve USB serial: never call desktop-head-unit with a bare "--usb" (invalid with multiple adb devices).
_usb_serial=""
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  _usb_serial="$ANDROID_SERIAL"
elif [[ $# -ge 1 && "$1" != -* ]]; then
  _usb_serial="$1"
  shift
fi

# Portable without bash 4 mapfile (macOS ships bash 3.2).
_adb_devs=()
while IFS= read -r _dev; do
  [[ -n "${_dev:-}" ]] && _adb_devs+=("$_dev")
done < <(adb devices 2>/dev/null | awk '/\tdevice$/{print $1}' || true)
if [[ -z "$_usb_serial" ]]; then
  if [[ ${#_adb_devs[@]} -eq 1 ]]; then
    _usb_serial="${_adb_devs[0]}"
  else
    echo "error: multiple adb devices (${#_adb_devs[@]}). Set ANDROID_SERIAL or pass the USB phone serial:" >&2
    echo "  ANDROID_SERIAL=<serial> $0" >&2
    echo "  $0 <serial>   # e.g. serial from: adb devices" >&2
    exit 1
  fi
fi

# Detached launch: IDE/agent shells often tear down `nohup … &` children when the task ends — DHU vanishes.
# systemd-run --user --scope keeps DHU in a user transient scope (survives Cursor/terminal exit). Fallback: nohup + log.
# Use: DHU_BACKGROUND=1 ./scripts/run-dhu-usb.sh
# Opt out of systemd: DHU_NO_SYSTEMD=1
if [[ -n "${DHU_BACKGROUND:-}" ]]; then
  _log="${DHU_LOG:-/tmp/dhu-run.log}"
  _pidf="${DHU_PID_FILE:-/tmp/dhu.pid}"
  if [[ -x /usr/bin/systemd-run ]] && [[ -z "${DHU_NO_SYSTEMD:-}" ]] && /usr/bin/systemd-run --user --version >/dev/null 2>&1; then
    echo "Starting DHU via systemd user scope (survives IDE/terminal exit)..."
    /usr/bin/systemd-run --user --scope --no-block \
      --setenv=DISPLAY="${DISPLAY:-:0}" \
      --setenv=WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-}" \
      --setenv=SDL_VIDEODRIVER="${SDL_VIDEODRIVER:-}" \
      --setenv=XAUTHORITY="${XAUTHORITY:-$HOME/.Xauthority}" \
      --setenv=ANDROID_HOME="${ANDROID_HOME}" \
      "$DHU" --usb="${_usb_serial}" "$@"
    echo "DHU started. Follow logs: journalctl --user -f"
    exit 0
  fi
  echo "Starting DHU detached (log: $_log, pid file: $_pidf)..."
  # CRITICAL: DHU 2.0 has an interactive '> ' command prompt and exits if stdin
  # closes (EOF). nohup with default stdin redirection closes stdin immediately,
  # so DHU dies before TLS handshake can complete — even though USB AOAP and TLS
  # would otherwise succeed. We hold stdin open by piping `tail -f /dev/null`
  # into DHU; tail emits no data but keeps the read end of the pipe open
  # indefinitely so DHU's prompt never sees EOF.
  # Track DHU's actual pid (not the wrapper's) for cleanup. We launch DHU
  # directly inside `bash -c` and capture its pid via `$!` of the wrapper, then
  # discover the real DHU child through pgrep.
  nohup bash -c 'tail -f /dev/null | "$@"' _ "$DHU" --usb="${_usb_serial}" "$@" >>"$_log" 2>&1 &
  _wrapper_pid=$!
  # Give DHU a moment to start so we can resolve its pid.
  for _i in 1 2 3 4 5; do
    sleep 1
    _dhu_pid="$(pgrep -x desktop-head-unit 2>/dev/null | head -1 || true)"
    if [[ -n "${_dhu_pid:-}" ]]; then break; fi
  done
  if [[ -n "${_dhu_pid:-}" ]]; then
    echo "$_dhu_pid" >"$_pidf"
  else
    # Fall back to the wrapper pid; aa-logcat-slice and pkill -x will still work.
    echo "$_wrapper_pid" >"$_pidf"
  fi
  echo "DHU PID $(cat "$_pidf")  (wrapper PID $_wrapper_pid)"
  exit 0
fi

# Foreground path. If stdin is a real terminal, run DHU directly so the user
# can type at DHU's interactive '> ' prompt (daynight, mic, etc.). If stdin
# is not a terminal (CI, agent shells, IDE terminals with closed stdin),
# apply the keep-alive wrapper so DHU does not exit on EOF.
if [[ -t 0 ]] || [[ -n "${DHU_INTERACTIVE:-}" ]]; then
  exec "$DHU" --usb="${_usb_serial}" "$@"
else
  exec bash -c 'tail -f /dev/null | "$@"' _ "$DHU" --usb="${_usb_serial}" "$@"
fi
