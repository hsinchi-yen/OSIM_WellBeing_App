# uDiva Sofa Control (clean-room Kotlin app)

A minimal, single-purpose Android app to control the **OSIM uDiva SOFA** (internal model
UDiva4) over Bluetooth LE — no OSIM cloud, account, health, or shopping. Built from the
reverse-engineered BLE protocol, not from OSIM's app code.

## Status: v0.1 (installable, testable)
Working now:
- **BLE scan / connect / GATT dump** — finds the sofa (★ marks likely matches), connects, and
  lists every service + characteristic so we confirm the live control service/characteristics.
- **Notifications + raw hex write** — enable notifications and send arbitrary command bytes to
  the write characteristic (also lets the phone itself verify/capture the protocol).
- **NFC tap** — reads the sofa's NDEF tag and parses the product serial.
- **Spotify launch** — opens Spotify (audio plays through the sofa once A2DP is paired).
- **zh-TW voice** — SpeechRecognizer (cmn-Hant-TW) + TextToSpeech; maps spoken commands to actions.

Pending the confirmed command bytes (from Blutter static recovery or a BLE capture):
- The **Sofa / Programs / Control** buttons and voice actions are wired through `OsimProtocol`.
  Until the command table is loaded they report "pending protocol" instead of sending unverified
  bytes. Once `OsimProtocol.loadCommandTable(...)` is populated, they work immediately.

## Install
Option A (adb): approve the "Allow USB debugging" prompt on the phone, then:
```
adb install -r dist/uDiva-Sofa-Control-v0.1-debug.apk
```
Option B (sideload): copy the APK in `dist/` to the phone and open it (allow install from this
source). Grant Bluetooth, Location (on tap), and Microphone permissions when asked.

## Build from source
Requires the Android SDK (platforms;android-35, build-tools;35.0.0) and JDK 17.
```
cd osim-sofa-app
<gradle-8.7>/bin/gradle --no-daemon assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
`local.properties` points `sdk.dir` at the Android SDK.

## How to use for protocol capture
1. Power the sofa on; Scan; tap the ★ device to connect; watch the GATT dump in the log.
2. Tap **Enable notify**, then drive the real OSIM app for each function while this app is
   connected — or send candidate bytes via **Send hex** and watch the sofa + the RX log.
3. Share the log; confirmed bytes get baked into `OsimProtocol` for the next build.

## Safety
The sofa has motors. Only send bytes confirmed from a capture; keep a hand on the physical stop.
