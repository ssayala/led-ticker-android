# CLAUDE.md — LED Ticker Android app

Guidance for Claude Code working in this folder. This is an open-source
(Apache-2.0) repo, developed cloned as the `android/` subfolder of the firmware
repo (`esp32-led-simple`), which gitignores it. The firmware, `BLE_PROTOCOL.md`
(the shared wire contract), and the `tools/led.py` CLI sit one level up at
`../` during development.

Native Android (Kotlin + Jetpack Compose + Material 3, minSdk 31 / compile+target
36) port of the iOS app. Same five tabs, same BLE contract, native Android feel
(Material You dynamic color, bottom nav, system pairing dialog).

## Build & test

PlatformIO is unrelated here. Use the Gradle wrapper:

```bash
./gradlew assembleDebug          # build debug APK
./gradlew testDebugUnitTest      # host unit tests (Payloads wire-format parity)
./gradlew installDebug           # build + install on a connected device/emulator
```

The machine has only JDK 21 (no 17), so the build targets 17 bytecode without a
toolchain — run Gradle with `JAVA_HOME=/opt/android-studio/jbr` and
`ANDROID_HOME=$HOME/Android/Sdk`. The `Pixel_9a` emulator has no BLE, so the app
auto-runs simulated-device mode there (`BleManager.isEmulator()`).

## Layering (mirrors the iOS app)

- `model/Payloads.kt` (+ `WeatherLocation.kt`) — **pure**, dependency-free wire
  format. The byte-for-byte source of truth is the firmware's `BLE_PROTOCOL.md`
  and `tools/led.py`; keep them in lockstep and covered by `app/src/test/`.
- `ble/BleManager.kt` — Android `BluetoothGatt` transport, owned by the
  `Application`. One serial GATT op queue. Leads with system bonding; falls back
  to an in-app PIN write to the Auth characteristic (`authRequired`/`submitPin`).
- `data/AppState.kt` — `ViewModel` holding Compose snapshot state, the analogue
  of the iOS `AppState`. Persists SSID/API-key/presets; never the Wi-Fi password.
- `ui/*Screen.kt` — one Compose screen per tab, gated on `ConnectionState.Ready`.

## Rules

- Keep parity with the iOS app's behaviour (dirty tracking, optimistic writes,
  prereq gating, debounced category writes, countdown timer). When the protocol
  changes, update `Payloads.kt` + its tests first.
- The firmware repo's committed docs may link to this public Android repo, but
  keep **iOS**-specific details out of them (iOS is App-Store-only, no public
  source) — app-specific guidance lives here, in this repo.
- BLE callbacks marshal onto the main scope before touching state; writes are
  gated on `authed` (Auth char write is the only exception).
