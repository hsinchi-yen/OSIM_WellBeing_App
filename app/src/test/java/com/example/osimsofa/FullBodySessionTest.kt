package com.example.osimsofa

import com.example.osimsofa.FullBodySession.Stage
import com.example.osimsofa.OsimProtocol.Key
import com.example.osimsofa.OsimProtocol.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** improve-plan §5.2: duration, AutoLevel3, no start unless 躺平 is reported, every wait bounded. */
class FullBodySessionTest {

    private val atSofa = SofaStatus(sofaPosition = true, footBackLimit = true)
    private val recline15 = SofaStatus(reclinePosition = true, is30min = false, shoulderIntensity = 2, backIntensity = 2, lumbarIntensity = 2)
    private val recline30 = recline15.copy(is30min = true)
    private fun program16(s: SofaStatus, level: Int = 2) =
        s.copy(programNo = 16, shoulderIntensity = level, backIntensity = level, lumbarIntensity = level)

    private val sent = ArrayList<OsimProtocol.Cmd>()
    private fun List<Effect>.track(): List<Effect> = also { fx -> fx.filterIsInstance<Effect.Send>().forEach { sent += it.cmds } }
    private fun List<Effect>.reports() = filterIsInstance<Effect.Report>()
    private fun keys() = sent.map { it.key }

    @Test fun fifteenMinutesFromTheSofaPosition() {
        val s = FullBodySession(15)
        val begin = s.begin(atSofa, 0).track()
        assertTrue(begin.contains(Effect.Hold(Posture.RECLINE)))
        assertTrue(begin.reports().single().text.let { it.contains("15 分鐘") && it.contains("力度 3") })
        assertEquals(Stage.MOVING, s.stage)

        assertTrue(s.onStatus(SofaStatus(), 3_000).isEmpty())
        val arrive = s.onStatus(recline15, 15_000).track()
        assertEquals(Effect.Release, arrive.first())                 // stop the stream first …
        assertEquals(listOf(AutoProgramStart), keys())               // … 15 already set: just the start key
        assertEquals(Stage.STARTING, s.stage)

        s.onStatus(program16(recline15), 15_500).track()
        assertEquals(Stage.APPLYING, s.stage)
        val settings = sent.drop(1)
        assertEquals(listOf(Key.SHOULDER_INTENSITY, Key.BACK_INTENSITY, Key.LUMBAR_INTENSITY,
            Key.FOOT_VIBRATION, Key.SEAT_VIBRATION, Key.FOOT_KNEADING), settings.map { it.key })
        assertEquals(listOf(3, 3, 3, 2, 2, 2), settings.map { it.values[1] })   // AutoLevel3: 3 and AUTO (2)

        val done = s.onStatus(program16(recline15, level = 3), 16_000)
        assertEquals(Stage.RUNNING, s.stage)
        assertEquals(true, s.settingsConfirmed)
        assertTrue(done.reports().single().text.contains("已開始 15 分鐘全身按摩"))
    }

    @Test fun thirtyMinutesTogglesOnceAndWaitsForTheSofaToConfirm() {
        val s = FullBodySession(30)
        s.begin(recline15, 0).track()                                // already at 躺平: no movement
        assertEquals(listOf(Key.TOGGLE_DURATION), keys())
        assertFalse(sent.single().retry)                             // never re-sent on a late ack
        assertEquals(Stage.SETTING_DURATION, s.stage)

        assertTrue(s.onStatus(recline15, 300).track().isEmpty())     // still 15: wait, don't toggle again
        s.onStatus(recline30, 700).track()
        assertEquals(listOf(Key.TOGGLE_DURATION, AutoProgramStart), keys())
        assertEquals(Stage.STARTING, s.stage)
    }

    @Test fun anUnconfirmedDurationDoesNotStart() {
        val s = FullBodySession(30)
        s.begin(recline15, 0).track()
        val fx = s.onTick(3_100, lastStatusAt = 3_000)
        assertEquals(Stage.FAILED, s.stage)
        assertTrue(fx.reports().single().problem)
        assertFalse(keys().contains(AutoProgramStart))
    }

    @Test fun noStartWithoutReachingRecline() {
        val s = FullBodySession(15)
        s.begin(atSofa, 0).track()
        val fx = s.onTick(MoveToTarget.CAP_MS, MoveToTarget.CAP_MS - 20)
        assertEquals(Effect.Release, fx.first())
        assertEquals(Stage.FAILED, s.stage)
        assertFalse(keys().contains(AutoProgramStart))
    }

    @Test fun aFaultWhileMovingStopsAndDoesNotStart() {
        val s = FullBodySession(15)
        s.begin(atSofa, 0).track()
        val fx = s.onStatus(SofaStatus(errorCode = 18), 2_000).track()
        assertEquals(Effect.Release, fx.first())
        assertEquals(Stage.FAILED, s.stage)
        assertFalse(keys().contains(AutoProgramStart))
    }

    @Test fun aStartTheSofaNeverReportsFails() {
        val s = FullBodySession(15)
        s.begin(recline15, 0).track()
        assertEquals(Stage.STARTING, s.stage)
        s.onTick(6_100, lastStatusAt = 6_050)
        assertEquals(Stage.FAILED, s.stage)
    }

    @Test fun aSilentSofaEndsTheWaitWithoutAnyNewStatus() {
        val s = FullBodySession(15)
        s.begin(recline15, 0).track()
        s.onTick(3_100, lastStatusAt = 0)                            // independent timer, no status needed
        assertEquals(Stage.FAILED, s.stage)
    }

    @Test fun unreportedSettingsStillRunButSaySo() {
        val s = FullBodySession(15)
        s.begin(recline15, 0)
        s.onStatus(program16(recline15), 500)
        val fx = s.onTick(4_600, lastStatusAt = 4_500)
        assertEquals(Stage.RUNNING, s.stage)
        assertEquals(false, s.settingsConfirmed)
        assertTrue(fx.reports().single().text.contains("尚未回報"))
    }

    @Test fun runningEndsWhenTheSofaReportsNoProgram() {
        val s = FullBodySession(15)
        s.begin(recline15, 0)
        s.onStatus(program16(recline15), 500)
        s.onStatus(program16(recline15, 3), 900)
        s.onStatus(recline15, 900_000)
        assertEquals(Stage.ENDED, s.stage)
    }

    @Test fun alreadyRunningIsJustReported() {
        val s = FullBodySession(15)
        val fx = s.begin(program16(recline15), 0)
        assertEquals(Stage.RUNNING, s.stage)
        assertTrue(fx.none { it is Effect.Send || it is Effect.Hold })
    }

    @Test fun cancellingWhileMovingReleasesAndStopsListening() {
        val s = FullBodySession(15)
        s.begin(atSofa, 0)
        assertEquals(listOf(Effect.Release), s.cancel(1_000))
        assertEquals(Stage.CANCELLED, s.stage)
        assertTrue(s.onStatus(recline15, 2_000).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun onlyFifteenOrThirtyMinutes() { FullBodySession(20) }

    private companion object { const val AutoProgramStart = 0x33 }
}
