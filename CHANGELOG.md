# Changelog

## 0.5.0 (versionCode 16) — 2026-11-02

Massage by body part, a wider voice vocabulary and a tabbed screen. **No new key code**, and the
protocol, link and movement code is unchanged. The zone behaviour has not been tried at a sofa yet.

- **Zones:** 肩部、背部、腰部、腿足、臀部 as a view over the existing manual settings. 「只按摩…」 or
  「腰部按摩」 runs one zone from idle and never moves the sofa; where it has to be, the app says so.
- **Backrest and footrest stay presets.** Their independent keys are decompile-only and never
  appear in the capture, so they are not sent; 「腳抬高」「椅背往後」 are refused with a reason, and a
  test fails if such a key is added.
- **Voice:** zone massage, levels 1–5 (「腰部力度調到 4」「背部最輕」), 「再強一點」 for the part adjusted
  last, technique, rolling and width, and read-only questions (「還剩幾分鐘」「力度多少」). A whole
  stop word stops while still listening. Android 13+ tells the recogniser what to expect.
- **Fixed:** 「開始足部按摩」 started a full-body massage, which reclines. 「太大力了」 read as stronger.
  Intensity changed on screen during an auto program was kept for later instead of sent. Waiting
  for a confirmation armed no timeout, so a missing report went unnoticed.
- **Screen:** 總覽／部位按摩 tabs with a body map, a status card with the time left, and layouts for
  a 360 dp phone at font scale 1.3. Back on 部位按摩 returns to 總覽.
- **Private data:** one sofa's serial and Bluetooth addresses are gone from the source and its
  history; a build can take a serial from the untracked `local.properties`.

## 0.4.1 (versionCode 15) — 2026-10-25

Three behaviours changed by the user after reviewing 0.4.0 (they replace the plan's §7 return
flow and the launch auto-connect):
- **Minimising changes nothing on the sofa.** A massage runs to its end, a tap move or a full-body
  preparation finishes (a held move stops — no finger is on it any more). The foreground service
  keeps the link; the notification shows the sofa's state (「躺平・全身按摩 進行中・約剩 12 分鐘」)
  and has 停止. Idle for 60 s in the background → the link is let go. Coming back (or any new
  connection) shows the current state. The return-to-sofa flow and its banners are removed.
- **A tap moves in one go; the user steps in only if it stops.** Tapping the moving card stops the
  movement only (a massage carries on); 停止 still stops everything. A move stopped on the way
  (tap, 停止, watchdog, lost status) offers 「繼續到…」 / 「回到…」 (where it started) /
  「留在這裡」; voice 「繼續」 continues it when no massage is paused.
- **No connection on launch.** Only an NFC tap or 「搜尋沙發」 connects; coming back from another app
  reconnects (also a cold start ≤ 30 min after switching away while connected). The menu's
  中斷連線 cancels any automatic reconnection.

## 0.4.0 (versionCode 14) — 2026-10-25 (not distributed; superseded by 0.4.1)

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
