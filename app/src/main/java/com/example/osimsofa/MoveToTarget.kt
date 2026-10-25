package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Posture

/**
 * One move to a preset (坐式／躺平／沙發): stream the preset key until the sofa reports that preset,
 * then release (improve-plan §6). Normal completion is the sofa's own "reached" bit, never a timer;
 * [capMs] is only a watchdog that releases and reports "not reached". Used by the posture cards,
 * voice, the full-body preparation and the return to the sofa.
 *
 * [Mode.HOLD]: a finger is on the button — lifting it stops the move.
 * [Mode.AUTO]: tap / voice / flows — the move ends by arrival, Stop, a fault, a stale status or the cap.
 *
 * Pure: the caller runs the returned [Effect]s and feeds statuses and clock ticks.
 */
class MoveToTarget(val target: Posture, val mode: Mode, private val capMs: Long = CAP_MS) {

    enum class Mode { HOLD, AUTO }

    enum class Result { REACHED, ALREADY_THERE, RELEASED, TIMED_OUT, FAULT, STALE, CANCELLED }

    var result: Result? = null; private set
    var startedAt = 0L; private set
    var endedAt = 0L; private set
    /** The fault code that ended the move ([Result.FAULT]). */
    var faultCode = 0; private set
    private var begun = false

    val active: Boolean get() = begun && result == null
    val elapsedMs: Long get() = endedAt - startedAt

    fun begin(status: SofaStatus?, now: Long): List<Effect> {
        check(!begun) { "a move is begun once" }
        begun = true
        startedAt = now
        if (status != null && reached(target, status)) { finish(Result.ALREADY_THERE, now); return emptyList() }
        return listOf(Effect.Hold(target))
    }

    fun onStatus(s: SofaStatus, now: Long): List<Effect> {
        if (!active) return emptyList()
        return when {
            s.fault -> { faultCode = s.errorCode; finish(Result.FAULT, now) }
            reached(target, s) -> finish(Result.REACHED, now)
            else -> emptyList()
        }
    }

    /** [lastStatusAt]: when the last valid status arrived. While the key streams every frame is
     *  answered with a status, so a silent link for [STALE_MS] means we can no longer see the sofa. */
    fun onTick(now: Long, lastStatusAt: Long): List<Effect> {
        if (!active) return emptyList()
        return when {
            now - startedAt >= capMs -> finish(Result.TIMED_OUT, now)
            now - maxOf(lastStatusAt, startedAt) > STALE_MS -> finish(Result.STALE, now)
            else -> emptyList()
        }
    }

    /** The finger left the button (only [Mode.HOLD] listens). */
    fun fingerUp(now: Long): List<Effect> =
        if (active && mode == Mode.HOLD) finish(Result.RELEASED, now) else emptyList()

    /** Stop, another command, the app going to the background, or the link going down. */
    fun cancel(now: Long): List<Effect> = if (active) finish(Result.CANCELLED, now) else emptyList()

    private fun finish(r: Result, now: Long): List<Effect> {
        result = r
        endedAt = now
        return if (r == Result.ALREADY_THERE) emptyList() else listOf(Effect.Release)
    }

    companion object {
        /** Provisional watchdog: the longest normal travel is not measured yet (the OSIM capture held
         *  坐式 6 s and 沙發 9 s without arriving), so leave room; the log records every travel time. */
        const val CAP_MS = 40_000L
        const val STALE_MS = 1_500L

        /** The sofa's own arrival report. For the sofa preset the footrest must be home too — the
         *  backrest alone is not "back to the sofa" (improve-plan §6). */
        fun reached(target: Posture, s: SofaStatus): Boolean = when (target) {
            Posture.UPRIGHT -> s.uprightPosition
            Posture.RECLINE -> s.reclinePosition
            Posture.SOFA -> s.sofaPosition && s.footBackLimit
        }
    }
}
