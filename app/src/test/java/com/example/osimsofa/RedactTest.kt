package com.example.osimsofa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** improve-plan D02: what leaves the phone has no addresses, serials or (by default) speech. */
class RedactTest {

    @Test fun bluetoothAddressesKeepOnlyTheirLastTwoBytes() {
        assertEquals("嘗試已配對項目 **:**:**:**:EE:FF", Redact.line("嘗試已配對項目 AA:BB:CC:DD:EE:FF"))
    }

    @Test fun serialsKeepOnlyTheirFirstFourDigits() {
        assertEquals("找到上次連線的沙發：uDivaSOFA_1234****", Redact.line("找到上次連線的沙發：uDivaSOFA_12345678"))
        assertEquals("NFC https://prod.services.osim-cloud.com/otonfc/****", Redact.line("NFC https://prod.services.osim-cloud.com/otonfc/0000X00000000"))
    }

    @Test fun framesAndTimesAreLeftAlone() {
        val tx = "09:12:44.120  TX 放開 aa 55 05 00 06 00 c5 00 00 00 00 39"
        assertEquals(tx, Redact.line(tx))
    }

    @Test fun whatWasSaidIsLeftOutUnlessAskedFor() {
        val lines = listOf("VOICE 聽到 躺平／湯平 → Run", "STATUS pos=沙發")
        val out = Redact.export(lines, keepTranscripts = false, header = "h")
        assertFalse(out.contains("躺平"))
        assertTrue(out.contains("STATUS"))
        assertTrue(Redact.export(lines, keepTranscripts = true, header = "h").contains("躺平"))
    }
}
