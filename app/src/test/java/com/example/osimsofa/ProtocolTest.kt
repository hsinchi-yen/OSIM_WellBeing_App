package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.AutoProgram
import com.example.osimsofa.OsimProtocol.Key
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.ManualSettings
import com.example.osimsofa.OsimProtocol.Posture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Encoder and decoder against the real OSIM ↔ uDiva SOFA capture (resources/capture_mb.txt). */
class ProtocolTest {

    private val capture: List<Pair<String, ByteArray>> =
        javaClass.getResourceAsStream("/capture_mb.txt")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.substringBefore(' ') to OsimProtocol.parseHex(line.substringAfter(' '))!! }

    private fun frames(kind: String) = capture.filter { it.first == kind }.map { it.second }
    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun seqOf(f: ByteArray) = u(f, 2) or (u(f, 3) shl 8)

    @Test fun uprightFrameIsTheCapturedOne() {
        assertEquals("aa 55 a8 00 06 00 c5 00 1d 00 00 89", OsimProtocol.hex(OsimProtocol.keyFrame(Key.UPRIGHT, seq = 0xA8)))
    }

    @Test fun encoderReproducesEveryCapturedKeyFrame() {
        val tx = frames("TX")
        assertEquals(251, tx.size)
        for (f in tx) assertArrayEquals(OsimProtocol.hex(f), f, OsimProtocol.keyFrame(u(f, 8), u(f, 9), seqOf(f)))
    }

    @Test fun initQueriesAreTheCapturedOnes() {
        val init = frames("INIT")
        val cmds = OsimProtocol.initMb()
        assertEquals(init.size, cmds.size)
        init.zip(cmds).forEach { (f, c) -> assertArrayEquals(OsimProtocol.hex(f), f, c.frame(seqOf(f))) }
    }

    @Test fun signatureStartFrameMatchesAHandComputedChecksum() {
        // AA^55^05^00^06^00^C5^00^33^00^00 = 0x0A
        val start = OsimProtocol.startProgram(AutoProgram.SIGNATURE, is30min = false, want30 = false).single()
        assertEquals("aa 55 05 00 06 00 c5 00 33 00 00 0a", OsimProtocol.hex(start.frame(seq = 5)))
    }

    @Test fun sequenceIsLittleEndianAndWrapsLikeOsim() {
        val f = OsimProtocol.keyFrame(Key.KEY_OFF, seq = 0x1FE)
        assertEquals(0xFE, u(f, 2)); assertEquals(0x01, u(f, 3))
        assertEquals(0, OsimProtocol.seqAfter(510))
        assertEquals(7, OsimProtocol.seqAfter(6))
    }

    @Test fun everyCapturedStatusPacketDecodes() {
        val rx = frames("RX")
        assertEquals(261, rx.size)
        for (p in rx) assertNotNull(OsimProtocol.hex(p), SofaStatus.parse(p))
        // C5 replies acknowledge our writes; unsolicited CA reports must not.
        val reports = rx.filter { u(it, 8) == 0xCA }
        assertTrue(reports.isNotEmpty())
        assertTrue(reports.none { OsimProtocol.isReply(it) })
        assertTrue(rx.filter { u(it, 8) == 0xC5 }.all { OsimProtocol.isReply(it) })
    }

    @Test fun idleStatusIsTheSofaPosition() {
        val s = SofaStatus.parse(frames("RX").first())!!
        assertEquals(Posture.SOFA, s.posture)
        assertTrue(s.backrestLimit); assertTrue(s.footBackLimit)
        assertEquals(1, s.shoulderIntensity); assertEquals(1, s.backIntensity); assertEquals(1, s.lumbarIntensity)
        assertEquals(0, s.programNo); assertFalse(s.paused); assertFalse(s.is30min); assertFalse(s.report)
        assertEquals(0xA0, s.seq)
    }

    @Test fun holdingUprightLeavesTheSofaPosition() {
        val statuses = frames("RX").mapNotNull { SofaStatus.parse(it) }
        assertEquals(Posture.SOFA, statuses.first().posture)
        assertTrue(statuses.any { it.posture == null })
    }

    @Test fun truncatedPacketsAreStillClassifiedByTheirHeader() {
        // Seen on the real sofa with the default MTU: only the first 20 bytes arrive.
        val reply = OsimProtocol.parseHex("55 aa 03 00 15 00 06 d0 c5 00 08 12 00 20 01 04 88 22 38 48")!!
        val report = OsimProtocol.parseHex("55 aa 03 00 15 00 06 d0 ca 00 08 12 00 20 01 04 88 22 38 48")!!
        assertTrue(OsimProtocol.isReply(reply))
        assertFalse(OsimProtocol.isReply(report))
        assertNull(SofaStatus.parse(reply))                 // never half-decoded
    }

    @Test fun ottomanIsByte15Bit0x04() {
        val p = frames("RX").first().copyOf()
        assertFalse(SofaStatus.parse(p)!!.ottoman)          // the normal state of 10/23
        p[15] = (p[15].toInt() or 0x04).toByte()
        p[26] = OsimProtocol.xor(p, 26).toByte()
        assertTrue(SofaStatus.parse(p)!!.ottoman)
    }

    @Test fun faultCode18IsTheFootSafetySensor() {
        val s = SofaStatus(errorCode = 0x12)
        assertTrue(s.fault)
        assertEquals("腳托與椅背馬達：腳部安全感應器被觸發", s.errorText)
        assertNull(SofaStatus().errorText)
    }

    @Test fun corruptOrForeignPacketsAreRejected() {
        val good = frames("RX").first()
        assertNull(SofaStatus.parse(good.copyOf().also { it[12] = (it[12].toInt() xor 0x20).toByte() }))
        assertNull(SofaStatus.parse(good.copyOf(26)))
        assertNull(SofaStatus.parse(frames("TX").first()))
    }

    @Test fun autoProgramKeysMatchTheDecompile() {
        assertEquals(16, AutoProgram.SIGNATURE.number)
        assertEquals(0x33, AutoProgram.SIGNATURE.startKey)
        assertEquals(Posture.RECLINE, AutoProgram.SIGNATURE.prepare)
        assertEquals((0x33..0x38).toList(), AutoProgram.entries.map { it.startKey })
        assertEquals(AutoProgram.SIGNATURE, AutoProgram.byNumber(16))
    }

    @Test fun startProgramTogglesDurationOnlyWhenNeeded() {
        fun keys(is30: Boolean?, want30: Boolean) = OsimProtocol.startProgram(AutoProgram.SIGNATURE, is30, want30).map { it.key }
        assertEquals(listOf(0x33), keys(false, false))
        assertEquals(listOf(0x49, 0x33), keys(false, true))
        assertEquals(listOf(0x49, 0x33), keys(true, false))
        assertEquals(listOf(0x33), keys(null, true))
    }

    @Test fun manualStartPressesAndReleasesEverySetting() {
        val cmds = OsimProtocol.startManual(ManualSettings())
        assertEquals(Key.KEY_OFF, cmds.first().key)
        val pairs = cmds.drop(1).chunked(2)
        assertEquals(10, pairs.size)
        pairs.forEach { assertEquals(Key.KEY_OFF, it[1].key) }
        val sent = pairs.associate { it[0].key to it[0].values[1] }
        assertEquals(2, sent[Key.KNEAD_TAP])          // kneading
        assertEquals(1, sent[Key.ROLLING])            // full
        assertEquals(1, sent[Key.BACK_SPEED])         // auto
        assertEquals(4, sent[Key.ROLLER_WIDTH])       // auto
        assertEquals(3, sent[Key.BACK_INTENSITY])
        assertEquals(2, sent[Key.FOOT_VIBRATION])     // AutoLevel3: foot / seat on AUTO
        assertEquals(2, sent[Key.SEAT_VIBRATION])
        assertEquals(2, sent[Key.FOOT_KNEADING])
    }

    @Test fun everyReplyEchoesTheSequenceOfTheFrameItAnswers() {
        var lastTx: Int? = null
        var matched = 0
        for ((kind, f) in capture) {
            if (kind == "TX" || kind == "INIT") { lastTx = seqOf(f); continue }
            val seq = OsimProtocol.replySeq(f)
            if (u(f, 8) == 0xCA) { assertNull(seq); continue }        // a report answers nothing
            assertEquals(OsimProtocol.hex(f), lastTx, seq)
            assertTrue(OsimProtocol.isKeyReply(f))
            matched++
        }
        assertEquals(249, matched)
    }

    @Test fun autoProgramSettingsAreOsimsPostStartList() {
        val cmds = OsimProtocol.autoProgramSettings(OsimProtocol.AutoProfile.LEVEL3)
        assertEquals(listOf(Key.SHOULDER_INTENSITY, Key.BACK_INTENSITY, Key.LUMBAR_INTENSITY,
            Key.FOOT_VIBRATION, Key.SEAT_VIBRATION, Key.FOOT_KNEADING), cmds.map { it.key })
        assertEquals(listOf(3, 3, 3, 2, 2, 2), cmds.map { it.values[1] })
        assertTrue(cmds.none { it.key == Key.KEY_OFF })                // sent back to back, like OSIM
        // AA^55^07^00^06^00^C5^00^44^03^00 = 0x7C
        assertEquals("aa 55 07 00 06 00 c5 00 44 03 00 7c", OsimProtocol.hex(cmds.first().frame(seq = 7)))
    }

    @Test fun theDurationToggleIsTheOnlyCommandNeverRetried() {
        assertFalse(OsimProtocol.toggleDuration().retry)
        assertTrue(OsimProtocol.startProgram(AutoProgram.SIGNATURE).retry)
        assertTrue(OsimProtocol.stopMassage().retry)
    }

    @Test fun footAndSeatLevelsDecode() {
        val p = frames("RX").first().copyOf()
        p[16] = 0x24                                                    // foot kneading auto · foot vibration high
        p[18] = ((p[18].toInt() and 0xF0) or 0x01).toByte()            // seat vibration low
        p[26] = OsimProtocol.xor(p, 26).toByte()
        val s = SofaStatus.parse(p)!!
        assertEquals(Level.AUTO, s.footKneading)
        assertEquals(Level.HIGH, s.footVibration)
        assertEquals(Level.LOW, s.seatVibration)
    }

    @Test fun theAppNamesThePresetsAsConfirmed() {
        assertEquals("坐式", Names.label(Posture.UPRIGHT))
        assertEquals("躺平", Names.label(Posture.RECLINE))
        assertEquals("沙發（待機）", Names.label(Posture.SOFA))
        assertEquals("全身按摩 30 分鐘・力度 3・其餘自動", Names.fullBody(30))
    }

    @Test fun manualChangeSendsOnlyWhatChanged() {
        val a = ManualSettings()
        val b = a.copy(back = 4, footVibration = Level.HIGH)
        val cmds = OsimProtocol.manualChanges(a, b)
        assertEquals(listOf(Key.BACK_INTENSITY, Key.KEY_OFF, Key.FOOT_VIBRATION, Key.KEY_OFF), cmds.map { it.key })
        assertEquals(listOf(4, 0, 4, 0), cmds.map { it.values[1] })
        assertTrue(OsimProtocol.manualChanges(a, a).isEmpty())
    }
}
