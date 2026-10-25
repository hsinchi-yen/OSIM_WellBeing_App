# uDiva 沙發（clean-room Android app）

Controls an **OSIM uDiva SOFA** (internal model uDiva4) over Bluetooth LE — no OSIM cloud, account,
health or shopping. Kotlin + Jetpack Compose, Traditional Chinese UI, built from the
reverse-engineered protocol in `../findings/PROTOCOL.md` (not from OSIM's code).

## v0.4.0 — what it does

| Area | Behaviour |
|---|---|
| Connect | Auto-connects the last sofa on launch. NFC tap: the tapped sofa if its tag serial is in a Bluetooth name, otherwise the last / only sofa module nearby (the tag may hold the product serial, and one sofa has two modules). Several unknown modules → you pick. "Operable" only after the first valid status. |
| Postures | 坐式／躺平／沙發（待機）. **Tap**: moves until the sofa reports the preset (watchdog 40 s). **Hold**: moves only while held. 沙發（待機） stops the massage first. |
| 全身按摩 | 15 or 30 min (screen chips or voice). Moves to 躺平, confirms arrival, sets the duration (toggle only if needed, never re-sent), starts Signature, waits for program 16, then applies AutoLevel3 (shoulder/back/lumbar 3, foot/seat AUTO). |
| 手動按摩 | Back / foot / seat groups; changes go out at once while manual runs, otherwise apply at the next start. |
| Stop | Bottom bar, voice or notification. Pre-empts the frame in flight and any retries; nothing restarts by itself. |
| Minimise | Home / other app / screen off / Spotify → stop massage → back to the sofa preset → reset next-session defaults (15 min, AutoLevel3). Not on a fault or a stale status. Foreground service with a 停止 action. |
| Voice | One tap = one recognition. Parameters (15/30 min, areas, foot/seat functions); negations, questions, two commands at once and unsupported durations never run anything. Homophones by pinyin (Android 10+). Word list: `../dist/VOICE-COMMANDS.md`. |
| Faults | Fault card on top (code, meaning, OSIM's advice); no movement or massage start while the sofa reports one. |
| Diagnostics | Read-only, with a masked copy-to-clipboard; raw hex writing only in debug builds; voice transcripts only logged when switched on. |

## Structure

```
ui/Screens.kt        Compose screens (connect, control, guide, diagnostics)
MainActivity         permissions, NFC, voice input, Spotify — no sofa state
SofaApplication      owns the session for the process; app-minimised detection
SofaService          connectedDevice foreground service + notification Stop
SofaController       every operation: policy → flow → link; user-facing events
ControlPolicy        operable / fresh status / fault / background / returning
MoveToTarget         one preset move (HOLD or AUTO), ends on the sofa's "reached" bit
FullBodySession      全身按摩 15/30 min with AutoLevel3, every wait bounded
ReturnToSofaFlow     the minimise flow
MbLink               mainboard write discipline (acks by seq, retries, stream, Stop pre-emption)
SofaConnection       BLE scan/connect/MTU/notify, connection generations, timeouts
OsimProtocol / SofaStatus   frames, key codes, status decoding
VoiceParser          speech → VoiceCommand (or a reason not to act)
Vocabulary (Names)   the names used on screen, in replies and in the notification
```

The flows, the policy, the parser and `MbLink` are pure Kotlin and unit-tested on the JVM.

## Build and test

JDK 17 and the Android SDK (platform 35, build-tools 35.0.0); `local.properties` points `sdk.dir` at it.

```
./gradlew testDebugUnitTest lintDebug assembleDebug      # or ../tools/gradle-8.7/bin/gradle …
# APK: app/build/outputs/apk/debug/app-debug.apk
```

On the development PC the JDK's download of lint's 55 MB `kotlin-compiler-31.6.0.jar` fails with
"Tag mismatch!"; that file was fetched with curl, checked against Google's `.sha1`, and is served
from `../tools/m2-local` by an init script:

```
../tools/gradle-8.7/bin/gradle --init-script ../tools/gradle-init/local-m2.init.gradle.kts lintDebug
```

Unit tests replay the real OSIM capture in `app/src/test/resources/capture_mb.txt` (251 key frames,
261 status packets) and run the flows on a fake clock. Lint has no errors; its warnings (targetSdk
not the newest, newer AndroidX versions, `mipmap-anydpi-v26`) are deliberate for this release.

## Install

```
adb install -r ../dist/uDiva-Sofa-Control-v0.4.0-debug.apk
```

Then follow `../dist/HOW-TO-TEST.md`.

## Safety

The sofa has motors. The app sends only commands confirmed from OSIM's app and capture. Moves stop
on the sofa's own arrival report, on release (hold), on Stop, on a fault, when the status goes stale
or the link drops, and at the latest after the watchdog. Keep the space around the sofa and under the
footrest clear; the sofa's power switch is the last resort.
