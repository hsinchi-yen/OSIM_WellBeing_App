package com.example.osimsofa

import com.example.osimsofa.FullBodyFlow.Effect
import com.example.osimsofa.FullBodyFlow.Stage
import com.example.osimsofa.OsimProtocol.Posture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBodyFlowTest {

    private val atRecline = SofaStatus(reclinePosition = true)
    private val atSofa = SofaStatus(sofaPosition = true)
    private val moving = SofaStatus()

    private fun sentKeys(effects: List<Effect>) = effects.filterIsInstance<Effect.Send>().flatMap { it.cmds }.map { it.key }

    @Test fun pressAtReclineStartsSignatureAtOnce() {
        val f = FullBodyFlow()
        assertEquals(listOf(0x33), sentKeys(f.press(atRecline, want30 = false, now = 0)))
        assertEquals(Stage.STARTING, f.stage)
    }

    @Test fun holdMovesToReclineAndStartsOnArrival() {
        val f = FullBodyFlow()
        assertEquals(listOf(Effect.Hold(Posture.RECLINE)), f.press(atSofa, false, 0))
        assertEquals(Stage.MOVING, f.stage)
        assertTrue(f.onStatus(moving, false, 500).isEmpty())
        val arrive = f.onStatus(atRecline, false, 9_000)
        assertEquals(Effect.Release, arrive.first())           // stop the stream first …
        assertEquals(listOf(0x33), sentKeys(arrive))           // … then start the program
        assertEquals(Stage.STARTING, f.stage)
        assertTrue(f.release().isEmpty())                      // lifting the finger now changes nothing
        f.onStatus(atRecline.copy(programNo = 16), false, 10_000)
        assertEquals(Stage.RUNNING, f.stage)
    }

    @Test fun releasingBeforeArrivalStopsAndOffersStartAnyway() {
        val f = FullBodyFlow()
        f.press(atSofa, false, 0)
        assertEquals(listOf(Effect.Release), f.release())
        assertEquals(Stage.NOT_REACHED, f.stage)
        assertEquals(listOf(0x33), sentKeys(f.startAnyway(moving, false, 1_000)))
        assertEquals(Stage.STARTING, f.stage)
    }

    @Test fun aStartTheSofaNeverReportsIsUnconfirmed() {
        val f = FullBodyFlow()
        f.press(atRecline, false, 0)
        f.onStatus(atRecline, false, 3_000); assertEquals(Stage.STARTING, f.stage)
        f.onStatus(atRecline, false, 7_000); assertEquals(Stage.UNCONFIRMED, f.stage)
        f.onStatus(atRecline.copy(programNo = 16), false, 8_000); assertEquals(Stage.RUNNING, f.stage)
    }

    @Test fun runningEndsWhenTheSofaReportsNoProgram() {
        val f = FullBodyFlow()
        f.press(atRecline, false, 0)
        f.onStatus(atRecline.copy(programNo = 16), false, 1_000)
        f.onStatus(atRecline, false, 900_000)
        assertEquals(Stage.IDLE, f.stage)
    }

    @Test fun thirtyMinutesIsToggledBeforeTheStart() {
        val f = FullBodyFlow()
        assertEquals(listOf(0x49, 0x33), sentKeys(f.press(atRecline.copy(is30min = false), want30 = true, now = 0)))
    }

    @Test fun pressingAgainWhileMovingDoesNothing() {
        val f = FullBodyFlow()
        f.press(atSofa, false, 0)
        assertTrue(f.press(atSofa, false, 10).isEmpty())
        assertEquals(Stage.MOVING, f.stage)
    }
}
