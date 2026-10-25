package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.AutoProfile
import com.example.osimsofa.OsimProtocol.Posture

/**
 * The words the app uses for the sofa — one place for the screen, the spoken replies, the
 * notification and the guide (improve-plan §4.1). The phrases voice accepts are in [VoiceParser];
 * they also take the old names (坐直、坐姿…), but every reply uses the names below.
 */
object Names {
    /** Main label of a preset on the screen. */
    fun label(p: Posture): String = when (p) {
        Posture.UPRIGHT -> "坐式"
        Posture.RECLINE -> "躺平"
        Posture.SOFA -> "沙發（待機）"
    }

    /** The preset inside a sentence: 「正在調整為坐式」「回到沙發」. */
    fun short(p: Posture): String = when (p) {
        Posture.UPRIGHT -> "坐式"
        Posture.RECLINE -> "躺平"
        Posture.SOFA -> "沙發"
    }

    /** Second line on the preset card. */
    fun hint(p: Posture): String = when (p) {
        Posture.UPRIGHT -> "調整至坐式位置"
        Posture.RECLINE -> "調整至躺平位置"
        Posture.SOFA -> "回到一般沙發使用狀態"
    }

    fun moving(p: Posture): String =
        if (p == Posture.SOFA) "正在回到沙發位置" else "正在調整為${short(p)}"

    fun reached(p: Posture): String =
        if (p == Posture.SOFA) "已回到沙發位置" else "已到${short(p)}位置"

    const val FULL_BODY = "全身按摩"
    const val MANUAL = "手動按摩"

    /** 「全身按摩 15 分鐘・力度 3・其餘自動」 */
    fun fullBody(minutes: Int, profile: AutoProfile = AutoProfile.LEVEL3): String =
        "$FULL_BODY $minutes 分鐘・${profile.zh}"

    fun area(a: OsimProtocol.Area): String = when (a) {
        OsimProtocol.Area.SHOULDER -> "肩部"
        OsimProtocol.Area.BACK -> "背部"
        OsimProtocol.Area.LUMBAR -> "腰部"
    }

    fun function(f: OsimProtocol.FootSeat): String = when (f) {
        OsimProtocol.FootSeat.FOOT_KNEADING -> "足部揉捏"
        OsimProtocol.FootSeat.FOOT_VIBRATION -> "足部振動"
        OsimProtocol.FootSeat.SEAT_VIBRATION -> "臀部振動"
    }

    /** Where the sofa is, from its own report. 沙發 needs the footrest home too; 待機 = no massage. */
    fun posture(s: SofaStatus?): String = when {
        s == null -> "讀取中…"
        MoveToTarget.reached(Posture.SOFA, s) -> if (s.massageRunning) "沙發" else "沙發（待機）"
        s.sofaPosition -> "沙發（腳托未收回）"
        s.uprightPosition -> "坐式"
        s.reclinePosition -> "躺平"
        else -> "不在預設位置"
    }

    /** What the sofa is doing, e.g. 「全身按摩 進行中・約剩 12 分鐘」. */
    fun massage(s: SofaStatus?): String {
        if (s == null) return "—"
        val what = when {
            s.programNo == 0 -> return "沒有按摩"
            s.programNo == OsimProtocol.MANUAL_PROGRAM_NO -> MANUAL
            s.programNo == OsimProtocol.VGRIP_PROGRAM_NO -> "V-Grip"
            else -> OsimProtocol.AutoProgram.byNumber(s.programNo)?.zh ?: "程式 ${s.programNo}"
        }
        return if (s.paused) "$what・暫停中" else "$what 進行中・約剩 ${s.remainingMin} 分鐘"
    }
}
