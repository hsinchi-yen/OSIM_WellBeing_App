# Changelog

## 0.4.0 (versionCode 14) — 2026-10-25

Implements the P0 and P1 items of `../improve-plan.md`.

Control and safety
- Preset moves end on the sofa's own "reached" report instead of a fixed time (the old 8 s voice
  cap made 坐式／躺平 stop half-way); a 40 s watchdog, faults, a stale status and link loss stop a
  move too. 沙發 needs the footrest home as well. (M01)
- 全身按摩 starts only after 躺平 is reported; every step has its own deadline; "直接開始" is gone.
  15/30 min: the toggle is sent only if the sofa reports the other duration and never re-sent.
  AutoLevel3 is applied after the start with OSIM's own post-start settings. (M02, §5.2)
- Stop pre-empts the frame in flight and its retries; acks are matched by sequence number, so a
  late reply cannot resume old work. (M03)
- One `ControlPolicy` for buttons, voice and flows: operable link, fresh status (≤ 6 s), no fault,
  not in the background, not while returning. (M04, S01)
- "Operable" only after the first valid status; every GATT callback and delayed task is tied to a
  connection generation; timeouts for connect, discovery, subscription and the first status;
  failed notification enabling is detected. An NFC-tapped serial is never swapped for another
  sofa. (S01, §4.5)
- Minimising the app stops the massage, returns to the sofa preset and resets the next-session
  defaults, from a `connectedDevice` foreground service whose notification has 停止. Blocked on a
  fault or an unknown status; an unfinished return is shown at the next start. (B01, §7)

Voice
- New parser with parameters (15/30 min, areas, foot/seat functions) and reasons: negations,
  questions, compound commands, unsupported or conflicting durations never act; Stop always wins.
  Exact words beat matches by sound; matches by sound only from the best candidate and only when
  they cover the utterance; conflicting candidates are asked back. (V01, V02)
- One-tap recognition with a state machine (permission → listening → result), cancel, no second
  job, results after cancel dropped; readable error messages; TTS language checked; package
  visibility for the recogniser and TTS. Replies come from the controller's events: understood →
  sent → confirmed by the sofa's status. (V03–V05)
- Homophone matching is skipped below Android 10 instead of crashing (android.icu Transliterator
  is API 29; this crash existed in 0.3.x).

Screen
- Names 坐式／躺平／沙發（待機） everywhere (screen, replies, notification, guide). (U01)
- Bottom bar 語音控制／全身按摩／停止 above the system navigation; 暫停／繼續 as one button on the
  status card. (U02, U05)
- Posture cards: tap = automatic to the preset, long-press = hold, TalkBack actions; 48 dp+ targets;
  larger type; stacked cards on narrow screens or large font. Manual settings folded into
  back / foot / seat groups; music and guide as secondary entries; fault, stale-status and return
  banners. (U03, U04)
- Manual card shows what the sofa reports while manual massage runs; adjustments say whether they
  went to the sofa or only to the next start. (S02)
- Diagnostics: read-only, masked copy to clipboard, transcripts off by default, raw hex only in
  debug builds. Adaptive launcher icon. (D02)

Fixed after an independent review of the controller / UI glue
- The 250 ms clock was re-armed by every status (≈ every 100 ms while a preset streams), so the
  40 s watchdog and every deadline never ran during a move; the clock is now fixed-rate.
- Minimising (and cancelling a full-body preparation) now drops queued commands and sends a stop
  first, so a start key already on its way cannot start a massage after the app left; the return
  stops a massage that shows up while it is moving, then continues.
- A Stop (also from the notification) cancels a voice recognition that was still listening.
- 停止 is on the guide and diagnostics pages too while connected.
- Disconnecting from the menu or an NFC tap while connected now cancels the flows quietly and stops
  the foreground service.
- The foreground service is never stopped before it reached startForeground() (a crash otherwise).
- Log timestamps are formatted on one thread; a partial wake lock keeps the CPU up during the
  return with the screen off (≤ 2 min).
- Sofa selection: the sofa has two Bluetooth modules and the NFC tag may carry the product serial,
  so a tap that matches no Bluetooth name falls back to the last / only sofa module nearby.
- Large font sizes: no clipped title, labels above values, full button labels.

Build
- Gradle wrapper (8.7), `BuildConfig`, `lifecycle-process`; lint clean (no errors); 105 unit tests.
- Debug builds: `--es demo massage|manual|fault|sofa` shows the control screen without a sofa
  (nothing can be sent) for layout checks.

## 0.3.3 (versionCode 13) — 2026-10-24
Spotify opens directly (package visibility); fuzzy pinyin voice matching; ottoman display;
1 s polling during a fault.
