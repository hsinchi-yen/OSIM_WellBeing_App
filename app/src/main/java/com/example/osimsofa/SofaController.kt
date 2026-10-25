package com.example.osimsofa

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.osimsofa.FullBodyFlow.Effect
import com.example.osimsofa.FullBodyFlow.Stage
import com.example.osimsofa.OsimProtocol.ManualSettings
import com.example.osimsofa.OsimProtocol.Posture

/** What the buttons and voice do, on top of the mainboard link. State is Compose-observable. */
class SofaController(private val conn: SofaConnection, private val speak: (String) -> Unit) {

    private val flow = FullBodyFlow()
    var fullBodyStage by mutableStateOf(Stage.IDLE); private set
    var want30 by mutableStateOf(false)
    var manual by mutableStateOf(ManualSettings()); private set
    var heldPosture by mutableStateOf<Posture?>(null); private set

    init {
        conn.onStatus = { s ->
            if (s.fault && flow.stage == Stage.MOVING) {
                conn.log("BLOCK 移動中沙發回報錯誤 ${s.errorCode}，停止移動")
                apply(flow.release())
            } else apply(flow.onStatus(s, want30, now()))
        }
    }

    /** Posture button: the sofa moves only while it is held (voice passes a short [maxMs] instead). */
    fun posture(p: Posture, pressed: Boolean, maxMs: Long = SofaConnection.HOLD_CAP_MS) {
        if (!pressed) { heldPosture = null; conn.stopHold(); return }
        apply(flow.release())                       // a posture press cancels a pending full-body move
        heldPosture = p
        conn.startHold(p, maxMs) { heldPosture = null }
    }

    /** While the sofa reports a fault, nothing moves or starts by itself (OSIM: power-cycle first).
     *  Held posture buttons stay usable — they only move while pressed. */
    private fun blockedByFault(): Boolean {
        val s = conn.status ?: return false
        if (!s.fault) return false
        conn.log("BLOCK 沙發回報錯誤 ${s.errorCode}，不自動移動／啟動")
        speak("沙發回報錯誤，請先排除")
        return true
    }

    /** 全身按摩 button: hold to move into position; the program starts by itself on arrival. */
    fun fullBody(pressed: Boolean) {
        if (!pressed) { apply(flow.release()); return }
        if (!blockedByFault()) apply(flow.press(conn.status, want30, now()))
    }

    /** Voice has no finger to lift: the same flow, ended by arrival or by the hold's time cap. */
    fun fullBodyByVoice() { if (!blockedByFault()) apply(flow.press(conn.status, want30, now())) }

    fun fullBodyStartAnyway() { if (!blockedByFault()) apply(flow.startAnyway(conn.status, want30, now())) }

    fun startManual() {
        if (blockedByFault()) return
        apply(flow.release())
        flow.reset(); sync()
        conn.enqueue(OsimProtocol.startManual(manual))
    }

    /** Settings are remembered; while manual massage runs, a change is sent right away. */
    fun changeManual(next: ManualSettings) {
        val prev = manual
        manual = next
        if (conn.status?.programNo == OsimProtocol.MANUAL_PROGRAM_NO) conn.enqueue(OsimProtocol.manualChanges(prev, next))
    }

    fun pause() { if (conn.status?.paused != true) conn.enqueue(listOf(OsimProtocol.pause())) }
    fun resume() = conn.enqueue(listOf(OsimProtocol.resume()))

    fun stopAll() {
        heldPosture = null
        flow.reset(); sync()
        conn.stopAll()
    }

    private fun apply(effects: List<Effect>) {
        for (e in effects) when (e) {
            is Effect.Hold -> {
                heldPosture = e.posture
                conn.startHold(e.posture) { heldPosture = null; apply(flow.release()) }
            }
            Effect.Release -> { heldPosture = null; conn.stopHold() }
            is Effect.Send -> conn.enqueue(e.cmds)
        }
        sync()
    }

    private fun sync() {
        val before = fullBodyStage
        fullBodyStage = flow.stage
        if (before == fullBodyStage) return
        conn.log("FLOW 全身按摩 $before → $fullBodyStage")
        when (fullBodyStage) {
            Stage.MOVING -> speak("正在移到躺平位置")
            Stage.STARTING -> speak("開始全身按摩")
            Stage.RUNNING -> speak("全身按摩已啟動")
            Stage.UNCONFIRMED -> speak("沙發沒有回報啟動")
            Stage.IDLE, Stage.NOT_REACHED -> {}
        }
    }

    private fun now() = SystemClock.uptimeMillis()
}
