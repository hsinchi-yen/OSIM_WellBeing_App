package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Key
import com.example.osimsofa.OsimProtocol.KneadTap
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.ManualSettings
import com.example.osimsofa.OsimProtocol.Rolling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MassageZonesTest {

    private val defaults = ManualSettings.DEFAULTS

    // ---------------------------------------------------------------- safety: no new key codes

    /** Independent backrest / footrest keys: decompile only, absent from the capture. */
    private val motorKeys = setOf(0x0D, 0x0E, 0x1F, 0x15, 0x16)

    /** Everything a zone may send: the manual-massage keys the app already used before zones. */
    private val manualKeys = setOf(
        Key.KEY_OFF, Key.KNEAD_TAP, Key.ROLLING, Key.BACK_SPEED, Key.ROLLER_WIDTH,
        Key.SHOULDER_INTENSITY, Key.BACK_INTENSITY, Key.LUMBAR_INTENSITY,
        Key.FOOT_KNEADING, Key.FOOT_VIBRATION, Key.SEAT_VIBRATION,
    )

    @Test fun theProtocolHasNoMotorKey() {
        val defined = Key::class.java.declaredFields.filter { it.type == Int::class.javaPrimitiveType }
            .map { it.isAccessible = true; it.getInt(null) }
        assertTrue(defined.isNotEmpty())
        assertEquals(emptySet<Int>(), defined.toSet() intersect motorKeys)
    }

    @Test fun aZoneOnlySendsManualKeys() {
        for (z in Zone.entries) {
            val start = OsimProtocol.startManual(ZoneFocus.solo(defaults, z))
            val change = OsimProtocol.manualChanges(defaults, ZoneFocus.emphasise(defaults, z))
            val keys = (start + change).mapNotNull { it.key }.toSet()
            assertEquals("$z", emptySet<Int>(), keys - manualKeys)
        }
    }

    // ---------------------------------------------------------------- focus

    @Test fun aRollerZoneIsStrongAndItsNeighboursQuiet() {
        val m = ZoneFocus.solo(defaults, Zone.LUMBAR)
        assertEquals(ZoneFocus.STRONG, m.lumbar)
        assertEquals(ZoneFocus.QUIET, m.shoulder)
        assertEquals(ZoneFocus.QUIET, m.back)
    }

    @Test fun aChosenLevelIsNeverLoweredByFocusingOnIt() {
        assertEquals(5, ZoneFocus.solo(defaults.copy(lumbar = 5), Zone.LUMBAR).lumbar)
        assertEquals(1, ZoneFocus.solo(defaults.copy(back = 1), Zone.LUMBAR).back)
    }

    @Test fun soloRunsOnlyThatZone() {
        for (z in Zone.entries) {
            val m = ZoneFocus.solo(defaults, z)
            assertTrue("$z is on", m.zoneOn(z))
            val others = Zone.entries.filter { it != z && !(it.onRollers && z.onRollers) }
            others.forEach { assertFalse("$it is off for $z", m.zoneOn(it)) }
        }
    }

    @Test fun soloSwitchesRollersBackOnIfTheyWereOff() {
        val off = defaults.copy(kneadTap = KneadTap.OFF, rolling = Rolling.OFF)
        assertTrue(ZoneFocus.solo(off, Zone.BACK).rollersOn())
    }

    @Test fun emphasiseLeavesTheOtherMechanismsRunning() {
        val m = ZoneFocus.emphasise(defaults, Zone.BACK)
        assertEquals(defaults.footKneading, m.footKneading)
        assertEquals(defaults.seatVibration, m.seatVibration)
        val legs = ZoneFocus.emphasise(defaults.copy(footKneading = Level.OFF), Zone.LEGS)
        assertEquals(Level.AUTO, legs.footKneading)
        assertTrue(legs.rollersOn())
    }

    @Test fun aChosenLegLevelIsKept() {
        assertEquals(Level.HIGH, ZoneFocus.solo(defaults.copy(footVibration = Level.HIGH), Zone.LEGS).footVibration)
    }

    @Test fun everyFocusStartsSomething() {
        for (z in Zone.entries) assertTrue(ZoneFocus.solo(defaults, z).anyOn())
        val allOff = ZoneFocus.solo(defaults, Zone.SEAT).copy(seatVibration = Level.OFF)
        assertFalse(allOff.anyOn())
    }

    // ---------------------------------------------------------------- where the sofa has to be

    @Test fun onlyTheSeatRunsInTheSofaPosition() {
        val sofa = SofaStatus(sofaPosition = true)
        assertNotNull(ZoneFocus.blocked(Zone.BACK, sofa))
        assertNotNull(ZoneFocus.blocked(Zone.LUMBAR, sofa))
        assertNotNull(ZoneFocus.blocked(Zone.LEGS, sofa))
        assertNull(ZoneFocus.blocked(Zone.SEAT, sofa))
    }

    @Test fun legsNeedTheFootMassagerInFootMode() {
        val s = SofaStatus(uprightPosition = true, ottoman = true)
        assertNotNull(ZoneFocus.blocked(Zone.LEGS, s))
        assertNull(ZoneFocus.blocked(Zone.BACK, s))
        assertNull(ZoneFocus.blocked(Zone.LEGS, s.copy(ottoman = false)))
    }

    @Test fun aRollerZoneTouchesOnlyOneArea() {
        assertEquals(Zone.BACK, Zone.of(OsimProtocol.Area.BACK))
        assertNull(Zone.LEGS.area)
        assertFalse(Zone.SEAT.onRollers)
    }
}
