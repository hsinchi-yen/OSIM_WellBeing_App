package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Cmd
import com.example.osimsofa.OsimProtocol.Posture

/**
 * The mainboard write discipline, after OSIM's _writeWithAckToMb: one frame in flight, answered by
 * the sofa's reply carrying the same sequence number within 500 ms, re-sent up to 10 times. A held
 * preset streams every ~100 ms (the sofa moves only while the stream lasts); with nothing else to
 * do a keyOff heartbeat polls the status.
 *
 * Stop pre-empts everything (improve-plan M03): it drops the queue and the frame in flight — whose
 * retries end there and whose late reply no longer matches anything — and writes release + stop at
 * once instead of after the old frame's ack window.
 *
 * Pure: [Io] writes and keeps the single timer, so tests run it on a fake clock. Not thread-safe —
 * SofaConnection calls it on the main thread only.
 */
class MbLink(private val io: Io) {

    interface Io {
        fun now(): Long
        /** Hand one frame to the BLE stack; false if it refused (busy). */
        fun write(frame: ByteArray): Boolean
        /** Call [onWake] at [at]; a newer request replaces the pending one. */
        fun wakeAt(at: Long)
        fun log(line: String)
    }

    private class Frame(val cmd: Cmd, val retry: Boolean, val quiet: Boolean, val holdFrame: Boolean = false) {
        val seq = OsimProtocol.nextSeq()
        val bytes = cmd.frame(seq)
        var attempts = 0
        var deadline = 0L
    }

    /** A command the sofa never acknowledged (after every retry). */
    var onFail: ((Cmd) -> Unit)? = null

    /** Heartbeat every [HEARTBEAT_FAST_MS] instead of [HEARTBEAT_IDLE_MS] (massage or flow running). */
    var pollFast = false
        set(v) { if (field != v) { field = v; pump() } }

    /** No valid status on this connection yet: poll quickly until one arrives. */
    var awaitingStatus = true
        set(v) { if (field != v) { field = v; pump() } }

    var writable = false; private set
    val holding: Posture? get() = held
    val pending: Int get() = queue.size + (if (inFlight != null) 1 else 0)

    private val queue = ArrayDeque<Cmd>()
    private var inFlight: Frame? = null
    private var held: Posture? = null
    private var heldSince = 0L
    private var heldFrames = 0
    private var lastSendAt = Long.MIN_VALUE / 2

    /** The link can be written to (notifications are on). */
    fun open() {
        writable = true
        awaitingStatus = true
        pump()
    }

    /** The link is gone: nothing more is sent; whatever was pending is dropped. */
    fun close() {
        writable = false
        queue.clear(); inFlight = null; held = null
    }

    fun enqueue(cmds: List<Cmd>, front: Boolean = false) {
        if (!writable) { io.log("未連線，略過：${cmds.joinToString { it.label }}"); return }
        if (front) cmds.asReversed().forEach { queue.addFirst(it) } else queue.addAll(cmds)
        pump()
    }

    /** Stream [p] until [release] / [stopNow]; switching presets just changes the stream. */
    fun hold(p: Posture) {
        if (!writable) return
        if (held == p) return
        held = p; heldSince = io.now(); heldFrames = 0
        io.log("HOLD ${Names.short(p)} 開始")
        pump()
    }

    /** End the stream: keyOff goes out first so the sofa stops at once. */
    fun release() {
        val p = held ?: return
        held = null
        io.log("HOLD ${Names.short(p)} 結束（串流 $heldFrames 幀，${io.now() - heldSince} ms）")
        if (!writable) return
        // A stream frame is never re-sent, so there is nothing to wait for.
        if (inFlight?.holdFrame == true) inFlight = null
        queue.addFirst(OsimProtocol.release())
        pump()
    }

    /** 停止: drop all pending work and stop movement and massage right now. */
    fun stopNow() {
        held = null
        val dropped = pending
        queue.clear()
        inFlight = null
        if (!writable) return
        queue.addLast(OsimProtocol.release())
        queue.addLast(OsimProtocol.stopMassage())
        io.log("STOP 立即送出" + if (dropped > 0) "（捨棄 $dropped 個待送指令）" else "")
        pump()
    }

    /** A reply arrived. [keyReply]: it answers a key operation (D0 C5); C2/C6 queries answer otherwise. */
    fun onReply(seq: Int, keyReply: Boolean) {
        val f = inFlight ?: return
        val matches = if (f.cmd.isKeyOp) keyReply && seq == f.seq else !keyReply
        if (!matches) return                       // late reply to a frame we already gave up on
        inFlight = null
        if (!f.quiet) io.log("ACK ${f.cmd.label}")
        pump()
    }

    fun onWake() {
        val now = io.now()
        val f = inFlight
        if (f != null && now >= f.deadline) {
            if (f.retry && f.attempts < MAX_ATTEMPTS) transmit(f)
            else {
                inFlight = null
                if (f.retry) { io.log("FAIL ${f.cmd.label}：沙發 $MAX_ATTEMPTS 次都沒有回應"); onFail?.invoke(f.cmd) }
            }
        }
        // Defence in depth: the flows release long before this (MoveToTarget.CAP_MS).
        val p = held
        if (p != null && now - heldSince >= HOLD_HARD_CAP_MS) {
            io.log("HOLD ${Names.short(p)} 超過安全上限 ${HOLD_HARD_CAP_MS / 1000} 秒，自動放開")
            release()
        }
        pump()
    }

    private fun pump() {
        if (!writable) return
        val f = inFlight
        if (f != null) { io.wakeAt(f.deadline); return }
        queue.removeFirstOrNull()?.let { transmit(Frame(it, retry = it.retry, quiet = false)); return }
        val now = io.now()
        val p = held
        if (p != null) {
            // The first frame of a stream goes at once; then one every HOLD_PERIOD_MS.
            val due = if (heldFrames == 0) now else lastSendAt + HOLD_PERIOD_MS
            if (now < due) { io.wakeAt(due); return }
            heldFrames++
            transmit(Frame(OsimProtocol.hold(p), retry = false, quiet = heldFrames > 1, holdFrame = true))
            return
        }
        val every = when {
            awaitingStatus -> HEARTBEAT_FIRST_MS
            pollFast -> HEARTBEAT_FAST_MS
            else -> HEARTBEAT_IDLE_MS
        }
        val due = lastSendAt + every
        if (now < due) { io.wakeAt(due); return }
        transmit(Frame(OsimProtocol.heartbeat(), retry = false, quiet = true))
    }

    private fun transmit(f: Frame) {
        inFlight = f
        f.attempts++
        lastSendAt = io.now()
        val ok = io.write(f.bytes)
        if (!f.quiet) {
            val again = if (f.attempts > 1) "（第 ${f.attempts} 次）" else ""
            io.log("TX ${f.cmd.label} ${OsimProtocol.hex(f.bytes)}$again" + (if (ok) "" else " 寫入被拒"))
        }
        f.deadline = lastSendAt + if (ok) ACK_TIMEOUT_MS else WRITE_RETRY_MS
        io.wakeAt(f.deadline)
    }

    companion object {
        const val ACK_TIMEOUT_MS = 500L          // OSIM _writeWithAckToMb: wait ≤500 ms …
        const val MAX_ATTEMPTS = 10              // … and send up to 10 times
        const val WRITE_RETRY_MS = 60L           // the BLE stack refused the write; try again shortly
        const val HOLD_PERIOD_MS = 100L          // OSIM re-sends a held preset every ~100 ms
        const val HOLD_HARD_CAP_MS = 60_000L     // never stream longer, whatever the flows do
        const val HEARTBEAT_FIRST_MS = 300L      // until the first status of a connection
        const val HEARTBEAT_FAST_MS = 1_000L
        const val HEARTBEAT_IDLE_MS = 3_000L     // OSIM polls ~5 s when idle; a bit faster for the UI
    }
}
