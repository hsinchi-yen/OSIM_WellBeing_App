package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Key
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.ReturnToSofaFlow.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** improve-plan §7: stop → back to the sofa → reset; never a start, never a move on a fault or stale status. */
class ReturnToSofaFlowTest {

    private val atSofa = SofaStatus(sofaPosition = true, footBackLimit = true)
    private val massageAtRecline = SofaStatus(reclinePosition = true, programNo = 16)
    private val stoppedAtRecline = SofaStatus(reclinePosition = true)

    private val effects = ArrayList<Effect>()
    private fun List<Effect>.track() = also { effects += it }
    private fun sentKeys() = effects.filterIsInstance<Effect.Send>().flatMap { it.cmds }.map { it.key }

    @Test fun stopsTheMassageThenReturnsThenResets() {
        val f = ReturnToSofaFlow()
        f.begin(massageAtRecline, fresh = true, now = 0).track()
        assertEquals(listOf(Key.KEY_OFF, Key.STOP_MASSAGE), sentKeys())
        assertEquals(Stage.STOPPING, f.stage)

        assertTrue(f.onStatus(massageAtRecline, 200).track().isEmpty())       // still running: wait
        assertTrue(f.onStatus(stoppedAtRecline, 600).track().contains(Effect.Hold(Posture.SOFA)))
        assertEquals(Stage.MOVING, f.stage)

        assertTrue(f.onStatus(SofaStatus(sofaPosition = true), 5_000).track().isEmpty())   // footrest not home yet
        val done = f.onStatus(atSofa, 9_000).track()
        assertEquals(listOf(Effect.Release, Effect.ResetDefaults), done.take(2))
        assertEquals(Stage.DONE, f.stage)
        assertNoStart()
    }

    @Test fun alreadyInPlaceOnlyResetsQuietly() {
        val f = ReturnToSofaFlow()
        val fx = f.begin(atSofa, fresh = true, now = 0)
        assertEquals(Stage.DONE, f.stage)
        assertTrue(fx.contains(Effect.ResetDefaults))
        assertTrue(fx.none { it is Effect.Hold || it is Effect.Send })
        assertTrue(fx.filterIsInstance<Effect.Report>().none { it.speak })
    }

    @Test fun aFaultStopsTheMassageButDoesNotMove() {
        val f = ReturnToSofaFlow()
        f.begin(massageAtRecline.copy(errorCode = 18), fresh = true, now = 0).track()
        assertEquals(Stage.ABORTED, f.stage)
        assertEquals(listOf(Key.KEY_OFF, Key.STOP_MASSAGE), sentKeys())
        assertTrue(effects.none { it is Effect.Hold })
        assertTrue(f.abortReason!!.contains("18"))
    }

    @Test fun anUnknownOrStaleStatusStopsButDoesNotMove() {
        for ((status, fresh) in listOf(null to true, massageAtRecline to false)) {
            effects.clear()
            val f = ReturnToSofaFlow()
            f.begin(status, fresh, 0).track()
            assertEquals(Stage.ABORTED, f.stage)
            assertTrue(sentKeys().contains(Key.STOP_MASSAGE))
            assertTrue(effects.none { it is Effect.Hold })
        }
    }

    @Test fun aStopThatIsNeverConfirmedAborts() {
        val f = ReturnToSofaFlow()
        f.begin(massageAtRecline, fresh = true, now = 0).track()
        f.onTick(5_100, lastStatusAt = 5_000).track()
        assertEquals(Stage.ABORTED, f.stage)
        assertTrue(effects.none { it is Effect.Hold })
    }

    @Test fun theWatchdogEndsAReturnThatNeverArrives() {
        val f = ReturnToSofaFlow()
        f.begin(stoppedAtRecline, fresh = true, now = 0).track()
        assertEquals(Stage.MOVING, f.stage)
        val fx = f.onTick(MoveToTarget.CAP_MS, MoveToTarget.CAP_MS - 50)
        assertEquals(Effect.Release, fx.first())
        assertEquals(Stage.ABORTED, f.stage)
    }

    @Test fun stopCancelsTheReturn() {
        val f = ReturnToSofaFlow()
        f.begin(stoppedAtRecline, fresh = true, now = 0)
        assertEquals(listOf(Effect.Release), f.cancel(1_000, "已按停止"))
        assertEquals(Stage.ABORTED, f.stage)
        assertEquals("已按停止", f.abortReason)
        assertTrue(f.onStatus(atSofa, 2_000).isEmpty())                       // no late "done"
    }

    @Test fun aMassageThatStartsAnywayIsStoppedBeforeMovingOn() {
        val f = ReturnToSofaFlow()
        f.begin(stoppedAtRecline, fresh = true, now = 0).track()      // nothing running: straight to MOVING
        assertEquals(Stage.MOVING, f.stage)
        effects.clear()
        val fx = f.onStatus(massageAtRecline, 400).track()             // a start key that was already on its way
        assertEquals(Effect.Release, fx.first())
        assertEquals(listOf(Key.STOP_MASSAGE), sentKeys())
        assertEquals(Stage.STOPPING, f.stage)
        assertTrue(f.onStatus(stoppedAtRecline, 900).track().contains(Effect.Hold(Posture.SOFA)))   // stopped: move again
        assertEquals(Stage.MOVING, f.stage)
    }

    @Test fun aFaultWhileReturningAborts() {
        val f = ReturnToSofaFlow()
        f.begin(stoppedAtRecline, fresh = true, now = 0)
        assertEquals(Effect.Release, f.onStatus(SofaStatus(errorCode = 9), 3_000).first())
        assertEquals(Stage.ABORTED, f.stage)
        assertFalse(f.active)
    }

    private fun assertNoStart() {
        val starts = OsimProtocol.AutoProgram.entries.map { it.startKey } + listOf(0x3B, Key.KNEAD_TAP, Key.ROLLING)
        assertTrue("the return must never start a massage", sentKeys().none { it in starts })
    }
}
