package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.AutoProgram
import com.example.osimsofa.OsimProtocol.Cmd
import com.example.osimsofa.OsimProtocol.Posture

/**
 * 全身按摩 "hold to prepare": while the button is held the sofa moves to the program's preparation
 * position (recline for Signature, as OSIM does); when the status reports it arrived, the program
 * starts. Letting go before that stops the sofa, like every posture button. The start counts as
 * confirmed only once the sofa reports the program's number (16 for Signature).
 *
 * Pure logic: callers execute the returned [Effect]s, so this is unit-tested without Bluetooth.
 */
class FullBodyFlow(val program: AutoProgram = AutoProgram.SIGNATURE) {

    enum class Stage { IDLE, MOVING, STARTING, RUNNING, NOT_REACHED, UNCONFIRMED }

    sealed interface Effect {
        data class Hold(val posture: Posture) : Effect
        object Release : Effect
        data class Send(val cmds: List<Cmd>) : Effect
    }

    var stage = Stage.IDLE
        private set
    private var startedAt = 0L

    fun press(status: SofaStatus?, want30: Boolean, now: Long): List<Effect> = when {
        stage == Stage.MOVING || stage == Stage.STARTING || stage == Stage.RUNNING -> emptyList()
        status?.posture == program.prepare -> start(status, want30, now)
        else -> { stage = Stage.MOVING; listOf(Effect.Hold(program.prepare)) }
    }

    /** Finger lifted, or the hold's safety cap fired. */
    fun release(): List<Effect> =
        if (stage == Stage.MOVING) { stage = Stage.NOT_REACHED; listOf(Effect.Release) } else emptyList()

    /** The user chose to start although the sofa is not in position (after NOT_REACHED / UNCONFIRMED). */
    fun startAnyway(status: SofaStatus?, want30: Boolean, now: Long): List<Effect> =
        if (stage == Stage.NOT_REACHED || stage == Stage.UNCONFIRMED || stage == Stage.IDLE) start(status, want30, now)
        else emptyList()

    fun onStatus(s: SofaStatus, want30: Boolean, now: Long): List<Effect> {
        when (stage) {
            Stage.MOVING -> if (s.posture == program.prepare) return listOf(Effect.Release) + start(s, want30, now)
            Stage.STARTING -> when {
                s.programNo == program.number -> stage = Stage.RUNNING
                now - startedAt > CONFIRM_MS -> stage = Stage.UNCONFIRMED
            }
            Stage.UNCONFIRMED -> if (s.programNo == program.number) stage = Stage.RUNNING
            Stage.RUNNING -> if (s.programNo == 0) stage = Stage.IDLE
            Stage.IDLE, Stage.NOT_REACHED -> {}
        }
        return emptyList()
    }

    /** Stop pressed, or another function took over. */
    fun reset() { stage = Stage.IDLE }

    private fun start(s: SofaStatus?, want30: Boolean, now: Long): List<Effect> {
        stage = Stage.STARTING
        startedAt = now
        return listOf(Effect.Send(OsimProtocol.startProgram(program, s?.is30min, want30)))
    }

    companion object { const val CONFIRM_MS = 6_000L }
}
