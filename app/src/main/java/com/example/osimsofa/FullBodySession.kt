package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.AutoProfile
import com.example.osimsofa.OsimProtocol.AutoProgram

/**
 * 全身按摩 for a chosen 15 or 30 minutes with the [AutoProfile.LEVEL3] settings (improve-plan §5.2):
 *
 * 1. move to 躺平 unless the sofa already reports it ([MoveToTarget], AUTO);
 * 2. duration: toggle only if the sofa reports the other one, then wait for a status confirming it —
 *    the toggle is never re-sent, so it can't flip back;
 * 3. start Signature (0x33) and wait for the sofa to report program 16;
 * 4. send OSIM's post-start settings (shoulder/back/lumbar 3, foot/seat AUTO) and look for the
 *    intensities in the status;
 * 5. running until the sofa reports another program.
 *
 * Nothing starts unless 躺平 was reported. Every wait has its own deadline checked by [onTick], so a
 * sofa that goes quiet ends the session with a reason instead of leaving it hanging.
 */
class FullBodySession(
    val minutes: Int,
    val profile: AutoProfile = AutoProfile.LEVEL3,
    val program: AutoProgram = AutoProgram.SIGNATURE,
) {
    init { require(minutes == 15 || minutes == 30) { "全身按摩只有 15 或 30 分鐘" } }

    enum class Stage { MOVING, SETTING_DURATION, STARTING, APPLYING, RUNNING, ENDED, FAILED, CANCELLED }

    var stage: Stage? = null; private set
    var failure: String? = null; private set
    /** Did the sofa report the profile's intensities after the start? null until known. */
    var settingsConfirmed: Boolean? = null; private set
    var move: MoveToTarget? = null; private set

    private val want30 = minutes == 30
    private var waitSince = 0L

    /** Before the program runs: a posture change or Stop cancels the whole session. */
    val preparing: Boolean get() = stage == Stage.MOVING || stage == Stage.SETTING_DURATION || stage == Stage.STARTING
    val active: Boolean get() = preparing || stage == Stage.APPLYING || stage == Stage.RUNNING
    val finished: Boolean get() = stage == Stage.ENDED || stage == Stage.FAILED || stage == Stage.CANCELLED

    fun begin(status: SofaStatus?, now: Long): List<Effect> {
        check(stage == null) { "a session is begun once" }
        val intro = Names.fullBody(minutes, profile)
        if (status?.programNo == program.number) {
            stage = Stage.RUNNING
            return listOf(Effect.Report("全身按摩已在進行中"))
        }
        val m = MoveToTarget(program.prepare, MoveToTarget.Mode.AUTO)
        move = m
        stage = Stage.MOVING
        val fx = m.begin(status, now)
        if (m.result == MoveToTarget.Result.ALREADY_THERE && status != null) {
            return listOf(Effect.Report("$intro。已在${Names.short(program.prepare)}位置")) + afterArrival(status, now)
        }
        return listOf(Effect.Report("$intro。${Names.moving(program.prepare)}")) + fx
    }

    fun onStatus(s: SofaStatus, now: Long): List<Effect> = when (stage) {
        Stage.MOVING -> {
            val m = move!!
            val fx = m.onStatus(s, now)
            when (m.result) {
                MoveToTarget.Result.REACHED ->
                    fx + Effect.Report(Names.reached(program.prepare), speak = false) + afterArrival(s, now)
                MoveToTarget.Result.FAULT -> fx + fail("沙發回報錯誤 ${s.errorCode}（${s.errorText}），已停止移動，未開始按摩")
                else -> fx
            }
        }
        Stage.SETTING_DURATION -> when {
            s.fault -> fail("沙發回報錯誤 ${s.errorCode}，未開始按摩")
            s.is30min == want30 -> start(now)
            else -> emptyList()
        }
        Stage.STARTING -> when {
            s.programNo == program.number -> {
                stage = Stage.APPLYING; waitSince = now
                listOf(Effect.Send(OsimProtocol.autoProgramSettings(profile)))
            }
            s.fault -> fail("沙發回報錯誤 ${s.errorCode}，全身按摩沒有啟動")
            else -> emptyList()
        }
        Stage.APPLYING -> when {
            s.programNo != program.number -> end()
            OsimProtocol.Area.entries.all { s.intensity(it) == profile.intensity(it) } -> {
                stage = Stage.RUNNING; settingsConfirmed = true
                listOf(Effect.Report("已開始 $minutes 分鐘全身按摩（${profile.zh}）"))
            }
            else -> emptyList()
        }
        Stage.RUNNING -> if (s.programNo != program.number) end() else emptyList()
        else -> emptyList()
    }

    fun onTick(now: Long, lastStatusAt: Long): List<Effect> {
        if (stage == Stage.MOVING) {
            val m = move!!
            val fx = m.onTick(now, lastStatusAt)
            return when (m.result) {
                MoveToTarget.Result.TIMED_OUT -> fx + fail("尚未到${Names.short(program.prepare)}位置，已停止；未開始按摩")
                MoveToTarget.Result.STALE -> fx + fail("沒有收到沙發回報，已停止移動；未開始按摩")
                else -> fx
            }
        }
        val waiting = stage == Stage.SETTING_DURATION || stage == Stage.STARTING || stage == Stage.APPLYING
        if (waiting && now - maxOf(lastStatusAt, waitSince) > STALE_MS) {
            return if (stage == Stage.APPLYING) running(confirmed = false) else fail("沒有收到沙發回報，未開始按摩")
        }
        return when {
            stage == Stage.SETTING_DURATION && now - waitSince > DURATION_CONFIRM_MS ->
                fail("無法確認按摩時間已改為 $minutes 分鐘，未開始按摩")
            stage == Stage.STARTING && now - waitSince > START_CONFIRM_MS ->
                fail("沙發沒有回報全身按摩已啟動")
            stage == Stage.APPLYING && now - waitSince > APPLY_CONFIRM_MS -> running(confirmed = false)
            else -> emptyList()
        }
    }

    /** Stop, a posture change while preparing, the app going to the background, or the link lost. */
    fun cancel(now: Long): List<Effect> {
        if (finished || stage == null) return emptyList()
        val fx = move?.cancel(now).orEmpty()
        stage = Stage.CANCELLED
        return fx
    }

    private fun afterArrival(s: SofaStatus, now: Long): List<Effect> {
        stage = Stage.SETTING_DURATION
        waitSince = now
        if (s.is30min == want30) return start(now)
        return listOf(Effect.Send(listOf(OsimProtocol.toggleDuration())))
    }

    private fun start(now: Long): List<Effect> {
        stage = Stage.STARTING
        waitSince = now
        return listOf(Effect.Send(listOf(OsimProtocol.startProgram(program))))
    }

    private fun running(confirmed: Boolean): List<Effect> {
        stage = Stage.RUNNING
        settingsConfirmed = confirmed
        val note = if (confirmed) "（${profile.zh}）" else "；力度設定已送出，沙發尚未回報"
        return listOf(Effect.Report("已開始 $minutes 分鐘全身按摩$note"))
    }

    private fun end(): List<Effect> {
        stage = Stage.ENDED
        return listOf(Effect.Report("全身按摩已結束"))
    }

    private fun fail(why: String): List<Effect> {
        stage = Stage.FAILED
        failure = why
        return listOf(Effect.Report(why, problem = true))
    }

    companion object {
        const val DURATION_CONFIRM_MS = 3_000L
        const val START_CONFIRM_MS = 6_000L
        const val APPLY_CONFIRM_MS = 4_000L
        /** While waiting the heartbeat runs every second; this long without a status means trouble. */
        const val STALE_MS = 3_000L
    }
}
