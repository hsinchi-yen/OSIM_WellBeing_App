package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.BackSpeed
import com.example.osimsofa.OsimProtocol.FootSeat
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.OsimProtocol.RollerWidth

/**
 * One 27-byte uDiva4 status packet: `55 AA | seq echo | 15 00 | 06 D0 C5|CA | 17 status bytes | xor`.
 * The sofa sends one in reply to every key operation (C5) and on its own as a report (CA).
 * Bit layout from OSIM's UDiva4ChairStatus (package:udiva4_command) — see findings/PROTOCOL.md.
 */
data class SofaStatus(
    val report: Boolean = false,          // CA = unsolicited report, C5 = reply to our key operation
    val seq: Int = 0,
    val kneading: Boolean = false,
    val tapping: Boolean = false,
    val rollerFull: Boolean = false,
    val rollerPartial: Boolean = false,
    val paused: Boolean = false,
    val backrestLimit: Boolean = false,
    val errorCode: Int = 0,
    val rollersHome: Boolean = false,
    val sofaPosition: Boolean = false,
    val uprightPosition: Boolean = false,
    val reclinePosition: Boolean = false,
    val footFrontLimit: Boolean = false,
    val footBackLimit: Boolean = false,
    val backIntensity: Int = 0,
    val shoulderIntensity: Int = 0,
    val lumbarIntensity: Int = 0,
    val is30min: Boolean = false,
    /** The foot massager frame is flipped to 腳凳 (ottoman) mode; OSIM disables the footrest then
     *  (「將足部按摩架由腳凳轉換為腳部按摩模式」 — it can only retract in foot massage mode). */
    val ottoman: Boolean = false,
    val speedLow: Boolean = false,
    val speedHigh: Boolean = false,
    /** Foot kneading / foot vibration / seat vibration as reported ([16], [18]; decode is [static]). */
    val footKneading: Level = Level.OFF,
    val footVibration: Level = Level.OFF,
    val seatVibration: Level = Level.OFF,
    /** Roller width bits of [10]; null when none is set (auto or not reported). */
    val rollerWidth: RollerWidth? = null,
    val programNo: Int = 0,
    val elapsedMin: Int = 0,
) {
    /** The preset the sofa reports it is at, or null while moving / in between. */
    val posture: Posture?
        get() = when {
            sofaPosition -> Posture.SOFA
            uprightPosition -> Posture.UPRIGHT
            reclinePosition -> Posture.RECLINE
            else -> null
        }

    val massageRunning: Boolean get() = programNo != 0

    /** The sofa reports a fault (OSIM: hasFault → 「錯誤（代碼N）」). */
    val fault: Boolean get() = errorCode != 0

    /** What the fault means, from OSIM's UDiva4ErrorCode table (its UI only shows the number). */
    val errorText: String? get() = if (errorCode == 0) null else ERRORS[errorCode] ?: "未知的錯誤"

    /** Same as OSIM's massageRemainingTime: (15 or 30) minus elapsed minutes. */
    val remainingMin: Int get() = ((if (is30min) 30 else 15) - elapsedMin).coerceAtLeast(0)

    val backSpeed: BackSpeed get() = when { speedHigh -> BackSpeed.HIGH; speedLow -> BackSpeed.LOW; else -> BackSpeed.AUTO }

    fun intensity(a: Area): Int = when (a) {
        Area.SHOULDER -> shoulderIntensity; Area.BACK -> backIntensity; Area.LUMBAR -> lumbarIntensity
    }

    fun level(f: FootSeat): Level = when (f) {
        FootSeat.FOOT_KNEADING -> footKneading; FootSeat.FOOT_VIBRATION -> footVibration; FootSeat.SEAT_VIBRATION -> seatVibration
    }

    /** The fields worth logging/showing; equal summaries mean nothing the user cares about changed. */
    fun summary(): String =
        "pos=${posture?.let { Names.short(it) } ?: "—"} foot=${if (footFrontLimit) "front" else if (footBackLimit) "back" else "mid"} ottoman=$ottoman " +
            "prog=$programNo paused=$paused 30min=$is30min " +
            "int=$shoulderIntensity/$backIntensity/$lumbarIntensity knead=$kneading tap=$tapping " +
            "fk=${footKneading.name} fv=${footVibration.name} sv=${seatVibration.name} err=$errorCode"

    companion object {
        const val LENGTH = 27

        /** OSIM's advice for every fault (its 「錯誤（代碼N）」 screen). */
        const val FAULT_ADVICE = "關閉沙發總開關後重新啟動；若問題持續存在，請聯繫 OSIM 服務中心。"

        /** UDiva4ErrorCode, keyed by code (findByCode compares the code, not the enum index). */
        private val ERRORS = mapOf(
            1 to "背部機芯上下移動：極限開關異常",
            2 to "背部機芯上下移動：反轉方向異常",
            3 to "背部機芯上下移動：移動感測器未偵測到移動",
            4 to "背部機芯上下移動：肩部偵測未感測到移動",
            5 to "背部揉捏：反轉方向異常",
            9 to "腳托與椅背馬達：極限開關異常",
            10 to "腳托與椅背馬達：移動感測器未偵測到移動",
            11 to "腳托與椅背馬達：背部機芯安全蓋被打開",
            12 to "腳托與椅背馬達：極限開關異常",
            13 to "腳托與椅背馬達：移動感測器未偵測到移動",
            15 to "足部機構：反轉方向異常",
            16 to "背部馬達過電流／過電壓",
            17 to "背部馬達過電流／過電壓",
            18 to "腳托與椅背馬達：腳部安全感應器被觸發",
            19 to "24V 主機板電源異常",
            20 to "3D 機芯：極限開關異常",
            21 to "3D 機芯：移動逾時",
            22 to "控制面板異常",
        )

        /** low / high / auto / auto-by-program bits of one foot or seat function. */
        private fun level(bits: Int, low: Int, high: Int, auto: Int): Level = when {
            bits and high != 0 -> Level.HIGH
            bits and low != 0 -> Level.LOW
            bits and auto != 0 -> Level.AUTO
            else -> Level.OFF
        }

        fun parse(b: ByteArray): SofaStatus? {
            if (b.size != LENGTH) return null
            val u = IntArray(b.size) { b[it].toInt() and 0xFF }
            if (u[0] != 0x55 || u[1] != 0xAA || u[7] != 0xD0 || (u[8] != 0xC5 && u[8] != 0xCA)) return null
            if (OsimProtocol.xor(b, LENGTH - 1) != u[LENGTH - 1]) return null
            fun bit(i: Int, mask: Int) = (u[i] and mask) != 0
            return SofaStatus(
                report = u[8] == 0xCA,
                seq = u[2] or (u[3] shl 8),
                kneading = bit(9, 0x01), tapping = bit(9, 0x02),
                rollerFull = bit(9, 0x04), rollerPartial = bit(9, 0x08), paused = bit(9, 0x40),
                backrestLimit = bit(10, 0x08),
                rollerWidth = when {
                    bit(10, 0x20) -> RollerWidth.NARROW
                    bit(10, 0x40) -> RollerWidth.MIDDLE
                    bit(10, 0x80) -> RollerWidth.WIDE
                    else -> null
                },
                errorCode = u[11] and 0x1F,
                rollersHome = bit(12, 0x10), sofaPosition = bit(12, 0x20),
                backIntensity = u[13] shr 4,
                is30min = bit(15, 0x08), ottoman = bit(15, 0x04),
                footKneading = level(u[16], low = 0x01, high = 0x02, auto = 0x0C),
                footVibration = level(u[16], low = 0x10, high = 0x20, auto = 0xC0),
                shoulderIntensity = u[17] shr 4, lumbarIntensity = u[17] and 0x0F,
                seatVibration = level(u[18], low = 0x01, high = 0x02, auto = 0x0C),
                footFrontLimit = bit(18, 0x10), speedHigh = bit(18, 0x20), speedLow = bit(18, 0x40),
                footBackLimit = bit(19, 0x10), reclinePosition = bit(19, 0x40), uprightPosition = bit(19, 0x80),
                programNo = u[20],
                elapsedMin = u[21],
            )
        }
    }
}
