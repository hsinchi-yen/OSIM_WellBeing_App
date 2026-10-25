package com.example.osimsofa

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.osimsofa.ControlPolicy.Op
import com.example.osimsofa.MoveToTarget.Mode
import com.example.osimsofa.MoveToTarget.Result
import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.AutoProgram
import com.example.osimsofa.OsimProtocol.BackSpeed
import com.example.osimsofa.OsimProtocol.FootSeat
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.ManualSettings
import com.example.osimsofa.OsimProtocol.Posture

/**
 * What the buttons, voice and the notification do (improve-plan §8). Every operation passes
 * [ControlPolicy]; movements and sessions are the pure flows ([MoveToTarget], [FullBodySession],
 * [ReturnToSofaFlow]) fed with statuses and a clock tick; every outcome becomes one [UserEvent]
 * for the screen, the spoken reply and the notification. Main thread only; Compose-observable.
 */
class SofaController(private val conn: SofaConnection, private val store: SharedPreferences) {

    enum class Source { BUTTON, VOICE, NOTIFICATION, SYSTEM }

    data class UserEvent(val text: String, val speak: Boolean, val problem: Boolean, val at: Long)
    data class MoveView(val target: Posture, val mode: Mode)
    data class SessionView(val minutes: Int, val stage: FullBodySession.Stage, val failure: String?, val confirmed: Boolean?)
    data class ReturnView(val stage: ReturnToSofaFlow.Stage, val reason: String?)

    /** The preset being moved to right now, if any. */
    var moving by mutableStateOf<MoveView?>(null); private set
    var session by mutableStateOf<SessionView?>(null); private set
    var returning by mutableStateOf<ReturnView?>(null); private set
    var lastEvent by mutableStateOf<UserEvent?>(null); private set
    /** Next full-body duration chosen on the screen (also what the bottom bar starts). */
    var want30 by mutableStateOf(false)
    /** Settings for the next manual massage (and the running one, while manual runs). */
    var manual by mutableStateOf(ManualSettings.DEFAULTS); private set
    var inBackground by mutableStateOf(false); private set
    /** Shown until dismissed: the last return to the sofa did not finish. */
    var returnProblem by mutableStateOf<String?>(null); private set

    /** Every user-facing outcome (spoken by the app, mirrored in the notification). */
    var onEvent: ((UserEvent) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var mover: MoveToTarget? = null
    private var fullBody: FullBodySession? = null
    private var back: ReturnToSofaFlow? = null
    private val expects = ArrayList<Expect>()
    private var lastFault = 0
    private var reconnectOnForeground = false
    private val ticker = Runnable { tick() }
    private val linger = Runnable { lingerDisconnect() }

    /** A command whose effect we wait to see in the status (V05: sent ≠ done). */
    private class Expect(val until: Long, val ok: String?, val fail: String, val speak: Boolean, val check: (SofaStatus) -> Boolean)

    init {
        conn.onStatus = { onStatus(it) }
        conn.onLost = { onLinkLost(it) }
        conn.onCommandFailed = { report("沙發沒有回應「${it.label}」", problem = true) }
        returnProblem = when (store.getString(KEY_RETURN, null)) {
            RETURN_PENDING -> "上次回到沙發位置沒有完成（App 被關閉或連線中斷），請查看沙發目前的位置"
            RETURN_ABORTED -> store.getString(KEY_RETURN_REASON, null)
            else -> null
        }
    }

    // ================================================================== posture

    /** Tap (or voice): move to the preset and stop there by itself. */
    fun moveTo(p: Posture, source: Source = Source.BUTTON) = startMove(p, Mode.AUTO, source)

    /** Press-and-hold on a card: moves while held, stops on release or arrival. */
    fun hold(p: Posture, down: Boolean) {
        if (down) { startMove(p, Mode.HOLD, Source.BUTTON); return }
        val m = mover ?: return
        if (m.target != p || m.mode != Mode.HOLD) return
        apply(m.fingerUp(now()))
        afterStep()
    }

    private fun startMove(p: Posture, mode: Mode, source: Source) {
        if (!allowed(Op.MOVE)) return
        val s = conn.status ?: return
        clearNotes()
        mover?.let { if (it.active) apply(it.cancel(now())) }
        mover = null
        if (cancelPreparation()) report("已取消全身按摩準備", speak = false)
        // 沙發（待機）means no massage: stop it, then go back to the sofa preset.
        if (p == Posture.SOFA && s.massageRunning && mode == Mode.AUTO) {
            conn.enqueue(listOf(OsimProtocol.stopMassage()))
            report("停止按摩並回到沙發位置", speak = source == Source.VOICE)
        }
        val m = MoveToTarget(p, mode)
        val fx = m.begin(s, now())
        if (m.result == Result.ALREADY_THERE) {
            report("已在${Names.short(p)}位置")
        } else {
            mover = m
            if (mode == Mode.AUTO) report("${Names.moving(p)}，到位自動停止", speak = source == Source.VOICE)
            else report("${Names.moving(p)}，放開即停", speak = false)
            apply(fx)
        }
        afterStep()
    }

    private fun onMoveFinished(m: MoveToTarget) {
        val target = Names.short(m.target)
        conn.log("MOVE $target ${m.mode} ${m.result} ${"%.1f".format(m.elapsedMs / 1000.0)} s")
        when (m.result) {
            Result.REACHED -> report(Names.reached(m.target))
            Result.RELEASED -> report(
                if (m.elapsedMs < SHORT_PRESS_MS) "按住不放才會移動；點一下會自動調整到$target"
                else "已停止，尚未到${target}位置", speak = false)
            Result.TIMED_OUT -> report("尚未到${target}位置，已停止（超過 ${MoveToTarget.CAP_MS / 1000} 秒）", problem = true)
            Result.FAULT -> report("沙發回報錯誤 ${m.faultCode}，已停止移動", problem = true)
            Result.STALE -> report("沒有收到沙發回報，已停止移動", problem = true)
            Result.ALREADY_THERE, Result.CANCELLED, null -> {}
        }
    }

    /**
     * Drop a full-body session that has not started yet. Once it has asked for the duration toggle
     * or the start key, that command may still be queued or on its way — so the queue is dropped
     * and a stop is sent, or the program would start after all. True if there was one.
     */
    private fun cancelPreparation(): Boolean {
        val f = fullBody ?: return false
        if (!f.preparing) return false
        val commandSent = f.stage == FullBodySession.Stage.SETTING_DURATION || f.stage == FullBodySession.Stage.STARTING
        apply(f.cancel(now()))
        if (commandSent) conn.stopNow()
        return true
    }

    // ================================================================== massage

    /** 全身按摩 with the chosen (or spoken) duration and the AutoLevel3 settings. */
    fun startFullBody(minutes: Int = if (want30) 30 else 15, source: Source = Source.BUTTON) {
        if (!allowed(Op.START_MASSAGE)) return
        val f = fullBody
        if (f != null && f.active) {
            report(if (f.preparing) "全身按摩準備中" else "全身按摩進行中", speak = source == Source.VOICE)
            return
        }
        mover?.let { if (it.active) apply(it.cancel(now())) }
        mover = null
        clearNotes()
        want30 = minutes == 30
        val s = FullBodySession(minutes)
        fullBody = s
        apply(s.begin(conn.status, now()))
        afterStep()
    }

    fun startManual(source: Source = Source.BUTTON) {
        if (!allowed(Op.START_MASSAGE)) return
        mover?.let { if (it.active) apply(it.cancel(now())) }
        mover = null
        cancelPreparation()
        clearNotes()
        conn.enqueue(OsimProtocol.startManual(manual))
        report("正在開始手動按摩", speak = source == Source.VOICE)
        expect("手動按摩已開始", "沙發沒有回報手動按摩已開始") { it.programNo == OsimProtocol.MANUAL_PROGRAM_NO }
        afterStep()
    }

    /** A manual-card change: sent at once while manual massage runs, otherwise kept for the next start. */
    fun changeManual(next: ManualSettings) {
        val prev = manual
        if (next == prev) return
        if (conn.status?.programNo == OsimProtocol.MANUAL_PROGRAM_NO) {
            if (!allowed(Op.ADJUST)) return
            manual = next
            conn.enqueue(OsimProtocol.manualChanges(prev, next))
            report("已送出手動按摩調整", speak = false)
        } else {
            manual = next
        }
    }

    fun pauseOrResume(source: Source = Source.BUTTON) {
        if (conn.status?.paused == true) resume(source) else pause(source)
    }

    fun pause(source: Source = Source.BUTTON) {
        if (!allowed(Op.PAUSE)) return
        val s = conn.status ?: return
        when {
            !s.massageRunning -> report("目前沒有進行中的按摩", speak = source == Source.VOICE)
            s.paused -> report("按摩已經暫停", speak = source == Source.VOICE)
            else -> {
                conn.enqueue(listOf(OsimProtocol.pause()))
                expect("已暫停按摩", "沙發沒有回報暫停") { it.paused }
            }
        }
        afterStep()
    }

    fun resume(source: Source = Source.BUTTON) {
        if (!allowed(Op.RESUME)) return
        val s = conn.status ?: return
        if (!s.paused) { report("沒有暫停中的按摩", speak = source == Source.VOICE); return }
        conn.enqueue(listOf(OsimProtocol.resume()))
        expect("已繼續按摩", "沙發沒有回報繼續") { !it.paused }
        afterStep()
    }

    /** Bumped by every Stop. A voice recognition that began before the latest Stop is not run —
     *  e.g. one still listening while 停止 is pressed in the notification. */
    var stopEpoch = 0; private set

    /** 停止 — screen, voice or notification: cancel every flow and stop movement and massage now. */
    fun stopAll(source: Source = Source.BUTTON) {
        val t0 = SystemClock.uptimeMillis()
        stopEpoch++
        val wasReturning = back?.active == true
        // The flows' own releases are not needed: stopNow sends release + stopMassage first.
        mover?.cancel(now()); mover = null
        fullBody?.cancel(now())
        back?.cancel(now(), "已按停止，沒有回到沙發位置")
        expects.clear()
        clearNotes()
        conn.stopNow()
        conn.log("STOP 來源 $source，${SystemClock.uptimeMillis() - t0} ms 內寫出" + if (wasReturning) "（中止回到沙發）" else "")
        if (conn.operable) report("已停止")
        else report("沙發未連線，無法確認沙發是否已停止，請查看沙發", problem = true)
        afterStep()
    }

    // ================================================================== voice

    /** Run a recognised voice command (the screen already showed what was understood). With no
     *  screen to look at, every reply to it — including the later confirmation — is spoken. */
    fun execute(cmd: VoiceCommand) {
        fromVoice = true
        try {
            when (cmd) {
                is VoiceCommand.MoveTo -> moveTo(cmd.posture, Source.VOICE)
                is VoiceCommand.StartFullBody -> startFullBody(cmd.minutes, Source.VOICE)
                VoiceCommand.StartManual -> startManual(Source.VOICE)
                VoiceCommand.Pause -> pause(Source.VOICE)
                VoiceCommand.Resume -> resume(Source.VOICE)
                VoiceCommand.StopAll -> stopAll(Source.VOICE)
                is VoiceCommand.Intensity -> intensity(cmd.delta, cmd.areas.ifEmpty { Area.entries.toSet() })
                is VoiceCommand.Speed -> speed(cmd.faster)
                is VoiceCommand.SetFunction -> function(cmd.function, cmd.level)
            }
        } finally { fromVoice = false }
    }
    private var fromVoice = false

    private enum class Running { NONE, MANUAL, AUTO }

    private fun running(s: SofaStatus): Running = when {
        s.programNo == OsimProtocol.MANUAL_PROGRAM_NO -> Running.MANUAL
        AutoProgram.byNumber(s.programNo) != null -> Running.AUTO
        else -> Running.NONE
    }

    private fun intensity(delta: Int, areas: Set<Area>) {
        if (!allowed(Op.ADJUST)) return
        val s = conn.status ?: return
        val r = running(s)
        // An auto program is adjusted from what the sofa reports; manual and "next time" from our settings.
        val base: (Area) -> Int = if (r == Running.AUTO) { a -> s.intensity(a) } else { a -> manual.intensity(a) }
        val target = areas.associateWith { (base(it) + delta).coerceIn(OsimProtocol.INTENSITY) }
        val where = if (areas.size == Area.entries.size) "肩、背、腰" else areas.joinToString("、") { Names.area(it) }
        if (target.all { (a, v) -> v == base(a) }) {
            report(if (delta > 0) "${where}力度已是最強（5）" else "${where}力度已是最弱（1）"); return
        }
        val levels = target.values.distinct().joinToString("／")
        when (r) {
            Running.AUTO -> {
                conn.enqueue(target.map { (a, v) -> OsimProtocol.intensity(a, v) })
                report("已送出：${where}力度 $levels", speak = false)
                expect("${where}力度已調為 $levels", "沙發沒有回報力度變更") { st -> target.all { (a, v) -> st.intensity(a) == v } }
            }
            Running.MANUAL -> {
                val next = target.entries.fold(manual) { m, (a, v) -> m.withIntensity(a, v) }
                conn.enqueue(OsimProtocol.manualChanges(manual, next))
                manual = next
                report("已送出：${where}力度 $levels", speak = false)
                expect("${where}力度已調為 $levels", "沙發沒有回報力度變更") { st -> target.all { (a, v) -> st.intensity(a) == v } }
            }
            Running.NONE -> {
                manual = target.entries.fold(manual) { m, (a, v) -> m.withIntensity(a, v) }
                report("目前沒有按摩：已把下次手動按摩的${where}力度設為 $levels")
            }
        }
    }

    private fun speed(faster: Boolean) {
        if (!allowed(Op.ADJUST)) return
        val s = conn.status ?: return
        val next = manual.copy(speed = if (faster) BackSpeed.HIGH else BackSpeed.LOW)
        when (running(s)) {
            Running.AUTO -> report("全身按摩中不能調整速度")
            Running.MANUAL -> {
                conn.enqueue(OsimProtocol.manualChanges(manual, next)); manual = next
                report("已送出：背部速度${next.speed.zh}", speak = false)
                expect("背部速度已調為${next.speed.zh}", "沙發沒有回報速度變更") { it.backSpeed == next.speed }
            }
            Running.NONE -> { manual = next; report("目前沒有按摩：已把下次手動按摩的速度設為${next.speed.zh}") }
        }
    }

    private fun function(f: FootSeat, level: Level) {
        if (!allowed(Op.ADJUST)) return
        val s = conn.status ?: return
        val name = Names.function(f)
        when (running(s)) {
            Running.AUTO -> {
                // In an auto program OSIM only switches these on (AUTO) or off.
                if (level == Level.LOW || level == Level.HIGH) { report("全身按摩中${name}只能開啟或關閉"); return }
                conn.enqueue(listOf(OsimProtocol.footSeat(f, level)))
                report("已送出：$name${level.zh}", speak = false)
                expect("$name 已${if (level == Level.OFF) "關閉" else "開啟"}", "沙發沒有回報${name}變更") {
                    (it.level(f) == Level.OFF) == (level == Level.OFF)
                }
            }
            Running.MANUAL -> {
                val next = manual.withLevel(f, level)
                conn.enqueue(OsimProtocol.manualChanges(manual, next)); manual = next
                report("已送出：$name${level.zh}", speak = false)
            }
            Running.NONE -> { manual = manual.withLevel(f, level); report("目前沒有按摩：已把下次手動按摩的${name}設為${level.zh}") }
        }
    }

    // ================================================================== app lifecycle

    /** The app is no longer visible (Home, another app, screen off): stop → back to the sofa → defaults. */
    fun onAppBackground() {
        inBackground = true
        main.removeCallbacks(linger)
        when (conn.phase) {
            SofaConnection.Phase.READY -> {}
            SofaConnection.Phase.SCANNING, SofaConnection.Phase.CONNECTING,
            SofaConnection.Phase.DISCOVERING, SofaConnection.Phase.VERIFYING -> {
                conn.cancel(); reconnectOnForeground = true; return
            }
            else -> return
        }
        if (back?.active == true) return
        // Drop whatever is still queued or on its way (a start key, manual settings, a stream) and
        // stop first — a command sent before the app left must not start a massage afterwards.
        mover?.cancel(now()); mover = null
        fullBody?.cancel(now())
        expects.clear()
        conn.stopNow()
        val s = conn.status
        val fresh = (conn.statusAgeMs() ?: Long.MAX_VALUE) <= ControlPolicy.STALE_MS
        val f = ReturnToSofaFlow()
        back = f
        recordReturn(RETURN_PENDING, null)
        conn.log("BACKGROUND App 已縮小，開始回到沙發流程")
        apply(f.begin(s, fresh, now()))
        afterStep()
    }

    fun onAppForeground() {
        inBackground = false
        main.removeCallbacks(linger)
        if (reconnectOnForeground && conn.phase in setOf(SofaConnection.Phase.IDLE, SofaConnection.Phase.FAILED) && conn.hasPermissions()) {
            reconnectOnForeground = false
            conn.log("回到前景，重新連線沙發")
            conn.startAutoConnect()
        }
    }

    fun dismissReturnProblem() {
        returnProblem = null
        store.edit().remove(KEY_RETURN).remove(KEY_RETURN_REASON).apply()
    }

    private fun recordReturn(state: String, reason: String?) {
        store.edit().putString(KEY_RETURN, state).putString(KEY_RETURN_REASON, reason).apply()
    }

    /** After the return, stay connected a little (the user may come right back), then let the sofa go. */
    private fun lingerDisconnect() {
        if (!inBackground || back?.active == true) return
        conn.log("BACKGROUND 回位流程結束，${LINGER_MS / 1000} 秒後中斷連線")
        reconnectOnForeground = true
        conn.disconnect()
    }

    // ================================================================== status / clock

    private fun onStatus(s: SofaStatus) {
        val t = now()
        if (s.errorCode != lastFault) {
            if (s.fault) report("沙發回報錯誤 ${s.errorCode}：${s.errorText}", problem = true)
            else if (lastFault != 0) report("沙發錯誤已排除")
            lastFault = s.errorCode
        }
        mover?.let { apply(it.onStatus(s, t)) }
        fullBody?.let { apply(it.onStatus(s, t)) }
        back?.let { apply(it.onStatus(s, t)) }
        val iter = expects.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (e.check(s)) { iter.remove(); e.ok?.let { report(it, speak = e.speak) } }
        }
        afterStep()
    }

    private fun tick() {
        tickPending = false
        val t = now()
        val last = conn.statusAt
        mover?.let { apply(it.onTick(t, last)) }
        fullBody?.let { apply(it.onTick(t, last)) }
        back?.let { apply(it.onTick(t, last)) }
        val iter = expects.iterator()
        while (iter.hasNext()) {
            val e = iter.next()
            if (t > e.until) { iter.remove(); report(e.fail, problem = true) }
        }
        afterStep()
    }

    /** A new connection reached the sofa: start the fault announcements afresh. */
    fun onOperable() {
        lastFault = 0
        expects.clear()
    }

    /** The link to an operable sofa ended. [why] is null when the user disconnected on purpose. */
    private fun onLinkLost(why: String?) {
        mover?.cancel(now()); mover = null
        fullBody?.cancel(now())
        back?.cancel(now(), "連線中斷，無法確認是否已回到沙發位置，請查看沙發")
        expects.clear()
        lastFault = 0
        if (why != null) report(why, problem = true)
        afterStep()
    }

    /** Hold the CPU awake while the sofa moves with the screen off (the return after minimising):
     *  a sleeping CPU would pause the stream and resume it later. Set by [SofaApplication]. */
    var keepAwake: ((Boolean) -> Unit)? = null
    private var awake = false

    /** Settle finished flows, refresh what the screen shows, keep the clock and the poll rate right. */
    private fun afterStep() {
        mover?.let { m -> if (m.result != null) { onMoveFinished(m); mover = null } }
        fullBody?.let { f ->
            // A failure stays on the card until the next action; an end or a cancel just clears it.
            session = when (f.stage) {
                FullBodySession.Stage.ENDED, FullBodySession.Stage.CANCELLED, null -> null
                else -> SessionView(f.minutes, f.stage!!, f.failure, f.settingsConfirmed)
            }
            if (f.finished) fullBody = null
        }
        back?.let { f ->
            if (f.finished) {
                back = null
                // Done: the spoken / shown event says so. Not done: a banner stays until dismissed.
                if (f.stage == ReturnToSofaFlow.Stage.DONE) { recordReturn(RETURN_DONE, null); returnProblem = null }
                else { recordReturn(RETURN_ABORTED, f.abortReason); returnProblem = f.abortReason }
                if (inBackground) main.postDelayed(linger, LINGER_MS)
            }
        }
        returning = back?.let { ReturnView(it.stage!!, it.abortReason) }
        moving = mover?.let { MoveView(it.target, it.mode) }
            ?: fullBody?.move?.takeIf { it.active }?.let { MoveView(it.target, Mode.AUTO) }
            ?: back?.move?.takeIf { it.active }?.let { MoveView(it.target, Mode.AUTO) }
        // A running program only needs statuses; everything else also needs the clock.
        val busy = mover != null || fullBody?.let { it.stage != FullBodySession.Stage.RUNNING } == true ||
            back != null || expects.isNotEmpty()
        conn.pollFast = busy || fullBody != null || conn.status?.massageRunning == true || conn.status?.fault == true
        // A fixed-rate clock: statuses arrive every ~100 ms while a preset streams, so re-arming the
        // tick on each of them would postpone the watchdog forever.
        if (busy && !tickPending) { tickPending = true; main.postDelayed(ticker, TICK_MS) }
        if (!busy && tickPending) { tickPending = false; main.removeCallbacks(ticker) }
        val wantAwake = inBackground && (back != null || mover != null)
        if (wantAwake != awake) { awake = wantAwake; keepAwake?.invoke(wantAwake) }
    }
    private var tickPending = false

    /** A new action replaces the last full-body failure note on the card. */
    private fun clearNotes() {
        if (fullBody == null) session = null
    }

    private fun expect(ok: String?, fail: String, check: (SofaStatus) -> Boolean) {
        expects += Expect(now() + EXPECT_MS, ok, fail, speak = fromVoice, check = check)
    }

    private fun allowed(op: Op): Boolean {
        val v = ControlPolicy.check(op, ControlPolicy.Context(
            operable = conn.operable, status = conn.status, statusAgeMs = conn.statusAgeMs(),
            inBackground = inBackground, returning = back?.active == true))
        if (v is ControlPolicy.Verdict.Block) {
            conn.log("BLOCK $op：${v.reason}")
            report(v.reason, problem = true)
            return false
        }
        return true
    }

    private fun apply(fx: List<Effect>) {
        for (e in fx) when (e) {
            is Effect.Hold -> conn.hold(e.posture)
            Effect.Release -> conn.release()
            is Effect.Send -> conn.enqueue(e.cmds)
            is Effect.Report -> report(e.text, e.speak, e.problem)
            Effect.ResetDefaults -> { want30 = false; manual = ManualSettings.DEFAULTS; conn.log("RESET 下次使用預設：15 分鐘、${OsimProtocol.AutoProfile.LEVEL3.zh}") }
        }
    }

    private fun report(text: String, speak: Boolean = true, problem: Boolean = false) {
        val e = UserEvent(text, speak || fromVoice, problem, SystemClock.uptimeMillis())
        lastEvent = e
        conn.log((if (problem) "SAY! " else "SAY ") + text)
        onEvent?.invoke(e)
    }

    private fun now() = SystemClock.uptimeMillis()

    companion object {
        const val TICK_MS = 250L
        const val EXPECT_MS = 6_000L
        const val SHORT_PRESS_MS = 600L
        const val LINGER_MS = 30_000L
        private const val KEY_RETURN = "return_state"
        private const val KEY_RETURN_REASON = "return_reason"
        private const val RETURN_PENDING = "pending"
        private const val RETURN_DONE = "done"
        private const val RETURN_ABORTED = "aborted"
    }
}
