package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.AutoProgram
import com.example.osimsofa.OsimProtocol.Key
import com.example.osimsofa.OsimProtocol.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** improve-plan M03 / §9.1 "BLE 排程": the write discipline on a fake clock. */
class MbLinkTest {

    private class FakeIo : MbLink.Io {
        var t = 0L
        var wake = Long.MAX_VALUE
        val written = ArrayList<Pair<Long, ByteArray>>()
        override fun now() = t
        override fun write(frame: ByteArray): Boolean { written += t to frame; return true }
        override fun wakeAt(at: Long) { wake = at }
        override fun log(line: String) {}
    }

    private val io = FakeIo()
    private val link = MbLink(io)
    private var failed = 0

    private fun key(f: ByteArray) = f[8].toInt() and 0xFF
    private fun seq(f: ByteArray) = (f[2].toInt() and 0xFF) or ((f[3].toInt() and 0xFF) shl 8)
    private fun last() = io.written.last().second
    private fun keys() = io.written.map { key(it.second) }
    private fun ack(f: ByteArray = last()) = link.onReply(seq(f), keyReply = true)

    /** Move the clock to [to], firing every wake-up on the way; [autoAck] answers each new frame. */
    private fun advance(to: Long, autoAck: Boolean = false) {
        var seen = io.written.size
        while (true) {
            if (autoAck && io.written.size > seen) { seen = io.written.size; ack(); continue }
            if (io.wake > to) break
            io.t = io.wake; io.wake = Long.MAX_VALUE
            link.onWake()
        }
        io.t = to
    }

    @Before fun open() {
        link.onFail = { failed++ }
        link.open()
        link.awaitingStatus = false
        ack()                                    // the first heartbeat goes out at once
        io.written.clear()
    }

    @Test fun oneFrameInFlightAndEachWaitsForItsAck() {
        link.enqueue(listOf(OsimProtocol.pause(), OsimProtocol.resume()))
        assertEquals(listOf(Key.PAUSE), keys())
        ack()
        assertEquals(listOf(Key.PAUSE, Key.RESUME), keys())
    }

    @Test fun aLateReplyToAnAbandonedFrameDoesNotAdvanceTheNewWork() {
        link.enqueue(listOf(OsimProtocol.intensity(Area.SHOULDER, 4)))
        val old = last()
        link.stopNow()
        val release = last()
        assertEquals(Key.KEY_OFF, key(release))
        ack(old)                                 // the sofa answers the old frame late …
        assertEquals(Key.KEY_OFF, key(last()))   // … which must not count as the release's ack
        ack(release)
        assertEquals(Key.STOP_MASSAGE, key(last()))
        ack()
        advance(10_000, autoAck = true)
        assertEquals(1, keys().count { it == Key.SHOULDER_INTENSITY })   // never re-sent after the stop
    }

    @Test fun stopGoesOutAtOnceEvenWhileAFrameIsBeingRetried() {
        link.enqueue(listOf(OsimProtocol.startProgram(AutoProgram.SIGNATURE)))
        advance(550)                             // first ack window passed: re-sent
        assertEquals(listOf(0x33, 0x33), keys())
        io.t = 600
        link.stopNow()
        assertEquals(600L to Key.KEY_OFF, io.written.last().first to key(last()))
        advance(10_000, autoAck = true)
        assertEquals(2, keys().count { it == 0x33 })                     // its retries ended with the stop
        assertTrue(keys().contains(Key.STOP_MASSAGE))
    }

    @Test fun aHeldPresetStreamsAndReleaseSendsKeyOffAtOnce() {
        link.hold(Posture.UPRIGHT)
        assertEquals(listOf(Key.UPRIGHT), keys())
        ack()
        advance(100)
        assertEquals(listOf(Key.UPRIGHT, Key.UPRIGHT), keys())
        io.t = 130
        link.release()                           // the second frame is still unanswered
        assertEquals(130L to Key.KEY_OFF, io.written.last().first to key(last()))
    }

    @Test fun aStreamNeverOutlivesTheHardCap() {
        link.hold(Posture.RECLINE)
        advance(MbLink.HOLD_HARD_CAP_MS + 2_000, autoAck = true)
        val lastStream = io.written.indexOfLast { key(it.second) == Key.RECLINE }
        assertTrue(io.written[lastStream].first <= MbLink.HOLD_HARD_CAP_MS)
        assertEquals(Key.KEY_OFF, key(io.written[lastStream + 1].second))
    }

    @Test fun theDurationToggleIsNeverResent() {
        link.enqueue(listOf(OsimProtocol.toggleDuration()))
        advance(10_000)                          // no ack at all
        assertEquals(1, keys().count { it == Key.TOGGLE_DURATION })
        assertEquals(0, failed)                  // not a "no answer" failure either: the status decides
    }

    @Test fun otherCommandsAreTriedTenTimesThenReported() {
        link.enqueue(listOf(OsimProtocol.pause()))
        advance(20_000)
        assertEquals(MbLink.MAX_ATTEMPTS, keys().count { it == Key.PAUSE })
        assertEquals(1, failed)
    }

    @Test fun theHeartbeatPollsFastUntilTheFirstStatusThenSlowsDown() {
        io.t = 1_000
        link.awaitingStatus = true                      // a new rate applies at once
        assertEquals(listOf(Key.KEY_OFF), keys()); ack()
        advance(1_000 + MbLink.HEARTBEAT_FIRST_MS)
        assertEquals(2, keys().size); ack()
        link.awaitingStatus = false
        val t1 = 1_000 + MbLink.HEARTBEAT_FIRST_MS
        advance(t1 + MbLink.HEARTBEAT_IDLE_MS - 1)
        assertEquals(2, keys().size)
        advance(t1 + MbLink.HEARTBEAT_IDLE_MS)
        assertEquals(3, keys().size); ack()
        link.pollFast = true
        advance(t1 + MbLink.HEARTBEAT_IDLE_MS + MbLink.HEARTBEAT_FAST_MS)
        assertEquals(4, keys().size)
    }

    @Test fun queriesAreAnsweredWithoutAKeyReply() {
        link.enqueue(OsimProtocol.initMb())
        val c2 = last()
        assertEquals(listOf(0xC2, 0x01), listOf(c2[6].toInt() and 0xFF, c2[7].toInt() and 0xFF))
        link.onReply(seq = 0x7777, keyReply = true)                      // a key reply: not this one's answer
        assertEquals(1, io.written.size)
        link.onReply(seq = 0x7777, keyReply = false)                     // the 06 C2 01 answer
        assertEquals(0xC6, last()[6].toInt() and 0xFF)
    }

    @Test fun aClosedLinkSendsNothing() {
        link.close()
        link.enqueue(listOf(OsimProtocol.pause()))
        link.hold(Posture.SOFA)
        link.stopNow()
        advance(5_000)
        assertTrue(io.written.isEmpty())
    }
}
