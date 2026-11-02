package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.FootSeat
import com.example.osimsofa.OsimProtocol.KneadTap
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.ManualSettings
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.OsimProtocol.Rolling

/**
 * The parts of the body the sofa can massage separately. A zone is a view over the manual-massage
 * settings the sofa already has — it adds no key code of its own.
 *
 * It is about the massage only. The backrest and the footrest do not move on their own here: their
 * independent keys (0x0D/0x0E/0x1F/0x15/0x16) are decompile-only, never seen in the capture, so the
 * app does not send them (CLAUDE.md safety rules). Moving is always one of the three presets.
 *
 * 肩、背、腰 share one set of rollers: each has its own intensity, but only the rollers as a whole
 * can be switched off. 腿足 and 臀部 are separate mechanisms.
 */
enum class Zone(val zh: String, val area: Area?) {
    SHOULDER("肩部", Area.SHOULDER),
    BACK("背部", Area.BACK),
    LUMBAR("腰部", Area.LUMBAR),
    LEGS("腿足", null),
    SEAT("臀部", null);

    val onRollers: Boolean get() = area != null

    companion object {
        fun of(a: Area): Zone = entries.first { it.area == a }
    }
}

/** At least one function is on: a manual massage only runs once something is switched on. */
fun ManualSettings.anyOn(): Boolean =
    rollersOn() || FootSeat.entries.any { level(it) != Level.OFF }

fun ManualSettings.rollersOn(): Boolean = kneadTap != KneadTap.OFF || rolling != Rolling.OFF

fun ManualSettings.zoneOn(z: Zone): Boolean = when (z) {
    Zone.SHOULDER, Zone.BACK, Zone.LUMBAR -> rollersOn()
    Zone.LEGS -> footKneading != Level.OFF || footVibration != Level.OFF
    Zone.SEAT -> seatVibration != Level.OFF
}

/** 「腰部按摩」: what a massage aimed at one zone means, in plain manual settings. */
object ZoneFocus {
    /** The zone asked for is at least this strong, the neighbours on the rollers at most [QUIET]. */
    const val STRONG = 4
    const val QUIET = 2

    /** Started from idle: only this zone runs. */
    fun solo(base: ManualSettings, z: Zone): ManualSettings {
        val on = emphasise(base, z)
        val off = on.copy(footKneading = Level.OFF, footVibration = Level.OFF, seatVibration = Level.OFF)
        val noRollers = on.copy(kneadTap = KneadTap.OFF, rolling = Rolling.OFF)
        return when (z) {
            Zone.SHOULDER, Zone.BACK, Zone.LUMBAR -> off
            Zone.LEGS -> noRollers.copy(seatVibration = Level.OFF)
            Zone.SEAT -> noRollers.copy(footKneading = Level.OFF, footVibration = Level.OFF)
        }
    }

    /** While manual massage runs: bring this zone up, leave the other mechanisms as they are. */
    fun emphasise(base: ManualSettings, z: Zone): ManualSettings = when (z) {
        Zone.SHOULDER, Zone.BACK, Zone.LUMBAR -> Area.entries.fold(rollers(base)) { m, a ->
            val now = m.intensity(a)
            m.withIntensity(a, if (a == z.area) maxOf(now, STRONG) else minOf(now, QUIET))
        }
        Zone.LEGS -> base.copy(footKneading = on(base.footKneading), footVibration = on(base.footVibration))
        Zone.SEAT -> base.copy(seatVibration = on(base.seatVibration))
    }

    /** Why this zone cannot run where the sofa is now, or null if it can. The app does not move the
     *  sofa for it: starting a massage on one body part should not surprise anyone with a recline. */
    fun blocked(z: Zone, s: SofaStatus): String? = when {
        z == Zone.SEAT -> null
        s.posture == Posture.SOFA ->
            "${z.zh}按摩需要先調整姿勢；請先說「坐式」或「躺平」"
        z == Zone.LEGS && s.ottoman ->
            "足部按摩架目前是腳凳模式；請先把它翻到足部按摩模式"
        else -> null
    }

    private fun rollers(m: ManualSettings) = m.copy(
        kneadTap = if (m.kneadTap == KneadTap.OFF) KneadTap.KNEADING else m.kneadTap,
        rolling = if (m.rolling == Rolling.OFF) Rolling.FULL else m.rolling,
    )

    private fun on(l: Level) = if (l == Level.OFF) Level.AUTO else l
}
