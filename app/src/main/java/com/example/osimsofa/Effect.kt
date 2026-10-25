package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Cmd
import com.example.osimsofa.OsimProtocol.Posture

/**
 * What a control flow ([MoveToTarget], [FullBodySession], [ReturnToSofaFlow]) asks for. The flows
 * are pure state machines; [SofaController] carries the effects out on the Bluetooth link, so the
 * flows are unit-tested without a sofa.
 */
sealed interface Effect {
    /** Stream a preset key until [Release] (the sofa only moves while the stream lasts). */
    data class Hold(val posture: Posture) : Effect

    /** End the stream; the link sends keyOff so the sofa stops at once. */
    data object Release : Effect

    data class Send(val cmds: List<Cmd>) : Effect

    /** Something the user should hear / see; [problem] marks a failure or a refusal. */
    data class Report(val text: String, val speak: Boolean = true, val problem: Boolean = false) : Effect

    /** Back to the defaults for the next session (15 min, AutoLevel3) — nothing is sent to the sofa. */
    data object ResetDefaults : Effect
}
