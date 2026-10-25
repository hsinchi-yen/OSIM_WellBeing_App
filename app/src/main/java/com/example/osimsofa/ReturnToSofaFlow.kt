package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Posture

/**
 * The app left the screen (improve-plan §7): stop the massage → see it stopped → move to the sofa
 * preset (backrest at sofa and footrest home) → reset the next-session defaults → standby.
 *
 * The stop is always sent — stopping is safe whatever the state. The move only happens on a fresh,
 * fault-free status; a fault, a stale or missing status, Stop, the link going down or a timeout
 * aborts and keeps the sofa's real state. Nothing is retried and no massage is ever started.
 */
class ReturnToSofaFlow {

    enum class Stage { STOPPING, MOVING, DONE, ABORTED }

    var stage: Stage? = null; private set
    var abortReason: String? = null; private set
    var move: MoveToTarget? = null; private set
    private var waitSince = 0L

    val active: Boolean get() = stage == Stage.STOPPING || stage == Stage.MOVING
    val finished: Boolean get() = stage == Stage.DONE || stage == Stage.ABORTED

    /** [fresh]: [status] arrived recently on the current connection. */
    fun begin(status: SofaStatus?, fresh: Boolean, now: Long): List<Effect> {
        check(stage == null) { "a return is begun once" }
        stage = Stage.STOPPING
        waitSince = now
        val stop = Effect.Send(listOf(OsimProtocol.release(), OsimProtocol.stopMassage()))
        if (status == null || !fresh) return listOf(stop) + abort("沙發狀態不明：已送出停止，但不自動回到沙發位置")
        if (status.fault) return listOf(stop) + abort("沙發回報錯誤 ${status.errorCode}（${status.errorText}）：已停止按摩，未自動回到沙發位置")
        if (!status.massageRunning && MoveToTarget.reached(Posture.SOFA, status)) {
            // Nothing to do but the reset — no announcement every time Home is pressed.
            stage = Stage.DONE
            return listOf(Effect.ResetDefaults, Effect.Report("已在沙發位置，進入待機", speak = false))
        }
        val intro = Effect.Report("App 已縮小：停止按摩並回到沙發位置")
        if (status.massageRunning) return listOf(intro, stop)
        return listOf(intro) + toSofa(status, now)
    }

    fun onStatus(s: SofaStatus, now: Long): List<Effect> = when (stage) {
        Stage.STOPPING -> when {
            s.fault -> abort("沙發回報錯誤 ${s.errorCode}：已停止按摩，未自動回到沙發位置")
            !s.massageRunning -> toSofa(s, now)
            else -> emptyList()
        }
        Stage.MOVING -> {
            val m = move!!
            if (s.massageRunning && !s.fault) {
                // A start that was already on its way when the app left: stop it again, wait, then move.
                val fx = m.cancel(now)
                stage = Stage.STOPPING
                waitSince = now
                fx + Effect.Send(listOf(OsimProtocol.stopMassage()))
            } else {
                val fx = m.onStatus(s, now)
                when (m.result) {
                    MoveToTarget.Result.REACHED -> fx + done()
                    MoveToTarget.Result.FAULT -> fx + abort("移動中沙發回報錯誤 ${s.errorCode}，已停止回位")
                    else -> fx
                }
            }
        }
        else -> emptyList()
    }

    fun onTick(now: Long, lastStatusAt: Long): List<Effect> = when (stage) {
        Stage.STOPPING -> when {
            now - waitSince > STOP_CONFIRM_MS -> abort("沒有收到按摩已停止的回報，未自動回到沙發位置")
            else -> emptyList()
        }
        Stage.MOVING -> {
            val m = move!!
            val fx = m.onTick(now, lastStatusAt)
            when (m.result) {
                MoveToTarget.Result.TIMED_OUT -> fx + abort("尚未回到沙發位置，已停止")
                MoveToTarget.Result.STALE -> fx + abort("沒有收到沙發回報，已停止回到沙發位置")
                else -> fx
            }
        }
        else -> emptyList()
    }

    /** Stop pressed (screen or notification) or the link went down. */
    fun cancel(now: Long, reason: String): List<Effect> {
        if (!active) return emptyList()
        val fx = move?.cancel(now).orEmpty()
        stage = Stage.ABORTED
        abortReason = reason
        return fx
    }

    private fun toSofa(s: SofaStatus, now: Long): List<Effect> {
        val m = MoveToTarget(Posture.SOFA, MoveToTarget.Mode.AUTO)
        move = m
        stage = Stage.MOVING
        val fx = m.begin(s, now)
        if (m.result == MoveToTarget.Result.ALREADY_THERE) return done()
        return listOf(Effect.Report(Names.moving(Posture.SOFA), speak = false)) + fx
    }

    private fun done(): List<Effect> {
        stage = Stage.DONE
        return listOf(Effect.ResetDefaults, Effect.Report("已回到沙發位置，進入待機"))
    }

    private fun abort(why: String): List<Effect> {
        stage = Stage.ABORTED
        abortReason = why
        return listOf(Effect.Report(why, problem = true))
    }

    companion object {
        const val STOP_CONFIRM_MS = 5_000L
    }
}
