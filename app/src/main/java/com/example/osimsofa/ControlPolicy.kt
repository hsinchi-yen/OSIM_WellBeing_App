package com.example.osimsofa

/**
 * May this operation run now? One answer for the buttons and voice (improve-plan M04 / S01): the
 * link must be up with a valid status on it, the status must be fresh, the sofa must not report a
 * fault, and nothing new starts while the app is in the background (there, only the notification's
 * Stop is available). Stop is always allowed.
 */
object ControlPolicy {

    enum class Op { MOVE, START_MASSAGE, ADJUST, PAUSE, RESUME, STOP }

    data class Context(
        /** BLE link up and at least one valid status received on it (SofaConnection.Phase.READY). */
        val operable: Boolean,
        val status: SofaStatus?,
        /** Time since the last valid status, null if none yet. */
        val statusAgeMs: Long?,
        val inBackground: Boolean = false,
    )

    sealed interface Verdict {
        data object Allow : Verdict
        data class Block(val reason: String) : Verdict
    }

    /** Statuses normally arrive at least every 3 s (heartbeat), every 100 ms while a key streams. */
    const val STALE_MS = 6_000L

    fun check(op: Op, c: Context): Verdict {
        if (op == Op.STOP) return Verdict.Allow
        if (!c.operable) return Verdict.Block("尚未連線沙發")
        val s = c.status ?: return Verdict.Block("尚未收到沙發狀態")
        val age = c.statusAgeMs ?: return Verdict.Block("尚未收到沙發狀態")
        if (age > STALE_MS) return Verdict.Block("沙發狀態已 ${age / 1000} 秒沒有更新，請稍候或重新連線")
        if (s.fault && op != Op.PAUSE) {
            return Verdict.Block("沙發回報錯誤 ${s.errorCode}（${s.errorText}），為安全起見暫停移動與按摩")
        }
        if (c.inBackground) return Verdict.Block("App 在背景，不執行新的動作")
        return Verdict.Allow
    }
}
