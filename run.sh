#!/usr/bin/env bash
# run.sh — build, install, and launch the LED Ticker app.
#
# Uses an already-connected device/emulator if there is one; otherwise boots
# the emulator AVD, waits for it, then builds (installDebug) and launches.
#
# Usage: ./run.sh [--headless] [--logcat|-l] [--avd NAME] [--release]
#   --headless     run the emulator with no window (software GPU)
#   --logcat, -l   tail the app's logcat after launching
#   --avd NAME     emulator AVD to boot (default: Pixel_9a, or $AVD)
#   --release      build/install the release variant instead of debug
#
# Env overrides: ANDROID_HOME, JAVA_HOME, AVD
set -euo pipefail
cd "$(dirname "$0")"

PACKAGE="io.github.ssayala.ledticker"
ACTIVITY=".MainActivity"
AVD="${AVD:-Pixel_9a}"
HEADLESS=0
LOGCAT=0
VARIANT="Debug"

while [ $# -gt 0 ]; do
  case "$1" in
    --headless)    HEADLESS=1 ;;
    --logcat|-l)   LOGCAT=1 ;;
    --avd)         shift; AVD="${1:?--avd needs a name}" ;;
    --release)     VARIANT="Release" ;;
    -h|--help)     sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)             echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

# --- Toolchain ---
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
[ -d /opt/android-studio/jbr ] && export JAVA_HOME="${JAVA_HOME:-/opt/android-studio/jbr}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
[ -x "$ADB" ] || { echo "adb not found at $ADB — set ANDROID_HOME" >&2; exit 1; }

have_device() { "$ADB" devices | awk 'NR>1 && $2=="device"{f=1} END{exit !f}'; }

# --- Ensure a device is available ---
if have_device; then
  echo "▶ Using connected device: $("$ADB" devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
else
  echo "▶ No device connected — booting emulator '$AVD'…"
  if ! "$EMULATOR" -list-avds | grep -qx "$AVD"; then
    echo "AVD '$AVD' not found. Available:" >&2
    "$EMULATOR" -list-avds >&2
    exit 1
  fi
  # Clear a stale multi-instance lock left by a crashed/killed run.
  rm -f "$HOME/.android/avd/$AVD.avd/"*.lock 2>/dev/null || true
  emu_flags=(-avd "$AVD" -no-boot-anim)
  [ "$HEADLESS" = 1 ] && emu_flags+=(-no-window -gpu swiftshader_indirect)
  "$EMULATOR" "${emu_flags[@]}" >/tmp/ledticker-emulator.log 2>&1 &
  echo "  emulator pid $! (log: /tmp/ledticker-emulator.log)"
  "$ADB" wait-for-device
  printf '  waiting for boot'
  for _ in $(seq 1 90); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    printf '.'; sleep 2
  done
  if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]; then
    echo; echo "emulator did not finish booting — see /tmp/ledticker-emulator.log" >&2
    exit 1
  fi
  echo " booted"
fi

# --- Build + install + launch ---
echo "▶ Building & installing (install$VARIANT)…"
./gradlew "install$VARIANT"

echo "▶ Launching $PACKAGE…"
"$ADB" shell am start -n "$PACKAGE/$ACTIVITY" >/dev/null

echo "✓ Running. (The emulator has no Bluetooth → the app uses its built-in"
echo "  simulated-device mode. Use a physical phone to talk to real hardware.)"

if [ "$LOGCAT" = 1 ]; then
  echo "▶ Tailing logcat (Ctrl-C to stop)…"
  pid="$("$ADB" shell pidof "$PACKAGE" | tr -d '\r')"
  exec "$ADB" logcat --pid="$pid"
fi
