package com.example.osimsofa

import com.example.osimsofa.MoveToTarget.Mode
import com.example.osimsofa.MoveToTarget.Result
import com.example.osimsofa.OsimProtocol.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** improve-plan §6: a move ends on the sofa's "reached" report, the timer is only a watchdog. */
class MoveToTargetTest {

    private val atSofa = SofaStatus(sofaPosition = true, footBackLimit = true)
    private val between = SofaStatus()
    private val atUpright = SofaStatus(uprightPosition = true)

    @Test fun theStreamStopsWhenTheSofaReportsThePreset() {
        val m = MoveToTarget(Posture.UPRIGHT, Mode.AUTO)
        assertEquals(listOf(Effect.Hold(Posture.UPRIGHT)), m.begin(atSofa, 0))
        assertTrue(m.onStatus(between, 500).isEmpty())
        assertEquals(listOf(Effect.Release), m.onStatus(atUpright, 12_000))
        assertEquals(Result.REACHED, m.result)
        assertEquals(12_000, m.elapsedMs)
        assertTrue(m.onStatus(between, 12_100).isEmpty())          // finished: nothing more
    }

    @Test fun aLongTravelIsNotCutShortAtEightSeconds() {
        val m = MoveToTarget(Posture.RECLINE, Mode.AUTO)
        m.begin(atSofa, 0)
        for (t in 100L..20_000L step 100) {                        // statuses keep arriving every 100 ms
            assertTrue(m.onTick(t, lastStatusAt = t - 50).isEmpty())
        }
        assertTrue(m.active)
    }

    @Test fun theWatchdogReleasesAndReportsNotReached() {
        val m = MoveToTarget(Posture.RECLINE, Mode.AUTO)
        m.begin(atSofa, 0)
        assertEquals(listOf(Effect.Release), m.onTick(MoveToTarget.CAP_MS, MoveToTarget.CAP_MS - 50))
        assertEquals(Result.TIMED_OUT, m.result)
    }

    @Test fun alreadyThereDoesNotMove() {
        val m = MoveToTarget(Posture.UPRIGHT, Mode.AUTO)
        assertTrue(m.begin(atUpright, 0).isEmpty())
        assertEquals(Result.ALREADY_THERE, m.result)
        assertFalse(m.active)
    }

    @Test fun aFaultStopsTheMove() {
        val m = MoveToTarget(Posture.SOFA, Mode.AUTO)
        m.begin(atUpright, 0)
        assertEquals(listOf(Effect.Release), m.onStatus(SofaStatus(errorCode = 18), 1_000))
        assertEquals(Result.FAULT, m.result)
        assertEquals(18, m.faultCode)
    }

    @Test fun losingSightOfTheSofaStopsTheMove() {
        val m = MoveToTarget(Posture.UPRIGHT, Mode.AUTO)
        m.begin(atSofa, 0)
        assertTrue(m.onTick(1_000, lastStatusAt = 0).isEmpty())
        assertEquals(listOf(Effect.Release), m.onTick(2_000, lastStatusAt = 0))
        assertEquals(Result.STALE, m.result)
    }

    @Test fun liftingTheFingerOnlyEndsAHold() {
        val hold = MoveToTarget(Posture.UPRIGHT, Mode.HOLD)
        hold.begin(atSofa, 0)
        assertEquals(listOf(Effect.Release), hold.fingerUp(800))
        assertEquals(Result.RELEASED, hold.result)

        val auto = MoveToTarget(Posture.UPRIGHT, Mode.AUTO)
        auto.begin(atSofa, 0)
        assertTrue(auto.fingerUp(800).isEmpty())
        assertTrue(auto.active)
    }

    @Test fun aHoldAlsoStopsOnArrival() {
        val m = MoveToTarget(Posture.UPRIGHT, Mode.HOLD)
        m.begin(atSofa, 0)
        assertEquals(listOf(Effect.Release), m.onStatus(atUpright, 9_000))   // no pushing at the preset
        assertTrue(m.fingerUp(9_500).isEmpty())
    }

    @Test fun cancelReleasesOnce() {
        val m = MoveToTarget(Posture.RECLINE, Mode.AUTO)
        m.begin(atSofa, 0)
        assertEquals(listOf(Effect.Release), m.cancel(300))
        assertEquals(Result.CANCELLED, m.result)
        assertTrue(m.cancel(400).isEmpty())
    }

    @Test fun backToTheSofaNeedsTheFootrestHome() {
        assertFalse(MoveToTarget.reached(Posture.SOFA, SofaStatus(sofaPosition = true)))
        assertTrue(MoveToTarget.reached(Posture.SOFA, atSofa))
    }
}
