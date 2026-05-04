#!/usr/bin/env bash
# Cleanly remove the Power Ampache 2 plugin from the connected device.
# Force-stops the package, uninstalls (with data), and prints the resulting state.
#
# Usage:
#   ./scripts/aa-uninstall.sh                    # uninstall plugin only
#   ANDROID_SERIAL=<serial> ./scripts/aa-uninstall.sh
#   ./scripts/aa-uninstall.sh --keep-data        # uninstall but keep app data (-k)
#   ./scripts/aa-uninstall.sh --host             # also stop/clear-data of the PA2 host (does NOT uninstall host)
#
# Exit codes: 0 success, 1 transport / device error, 2 package not installed.

set -euo pipefail

PLUGIN_PKG="${PLUGIN_PKG:-luci.sixsixsix.powerampache2.plugin}"
HOST_PKG="${HOST_PKG:-luci.sixsixsix.powerampache2}"

KEEP_DATA=0
INCLUDE_HOST=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-data) KEEP_DATA=1; shift ;;
    --host) INCLUDE_HOST=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "error: unknown arg: $1" >&2; exit 1 ;;
  esac
done

_adb() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  if ! adb -s "$ANDROID_SERIAL" get-state 2>/dev/null | grep -q '^device$'; then
    echo "error: ANDROID_SERIAL=$ANDROID_SERIAL is not in 'device' state" >&2
    exit 1
  fi
elif ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "error: no adb device in 'device' state" >&2
  exit 1
fi

_is_installed() {
  _adb shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$1"
}

if _is_installed "$PLUGIN_PKG"; then
  echo "Force-stopping $PLUGIN_PKG ..."
  _adb shell am force-stop "$PLUGIN_PKG" || true

  if [[ "$KEEP_DATA" == "1" ]]; then
    echo "Uninstalling $PLUGIN_PKG (keeping data, -k) ..."
    _adb uninstall -k "$PLUGIN_PKG" || { echo "error: uninstall failed" >&2; exit 1; }
  else
    echo "Uninstalling $PLUGIN_PKG (with data) ..."
    _adb uninstall "$PLUGIN_PKG" || { echo "error: uninstall failed" >&2; exit 1; }
  fi
else
  echo "note: $PLUGIN_PKG was not installed."
  RC=2
fi

if [[ "$INCLUDE_HOST" == "1" ]]; then
  if _is_installed "$HOST_PKG"; then
    echo "Force-stopping $HOST_PKG ..."
    _adb shell am force-stop "$HOST_PKG" || true
    echo "Clearing data for $HOST_PKG (does NOT uninstall the host) ..."
    _adb shell pm clear "$HOST_PKG" || true
  else
    echo "note: $HOST_PKG (host) was not installed; skipping clear."
  fi
fi

echo "Result:"
if _is_installed "$PLUGIN_PKG"; then
  echo "  STATUS=PLUGIN_STILL_INSTALLED"
else
  echo "  STATUS=PLUGIN_REMOVED"
fi

exit "${RC:-0}"
