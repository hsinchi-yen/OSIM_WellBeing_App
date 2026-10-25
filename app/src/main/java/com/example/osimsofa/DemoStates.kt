package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Level

/**
 * Debug builds only: sofa states to show the control screen without a sofa (layout checks and
 * screenshots on an emulator). The link stays closed in this mode, so nothing can be sent.
 *
 *     adb shell am start -n com.example.osimsofa/.MainActivity --es demo massage
 */
object DemoStates {
    fun of(name: String): SofaStatus? = when (name) {
        "sofa" -> SofaStatus(sofaPosition = true, footBackLimit = true, backrestLimit = true, rollersHome = true,
            shoulderIntensity = 3, backIntensity = 3, lumbarIntensity = 3)
        "massage" -> SofaStatus(reclinePosition = true, footFrontLimit = true, programNo = 16, elapsedMin = 3,
            shoulderIntensity = 3, backIntensity = 3, lumbarIntensity = 3, kneading = true,
            footVibration = Level.AUTO, footKneading = Level.AUTO, seatVibration = Level.AUTO)
        "manual" -> SofaStatus(uprightPosition = true, programNo = OsimProtocol.MANUAL_PROGRAM_NO, elapsedMin = 1,
            shoulderIntensity = 3, backIntensity = 4, lumbarIntensity = 3, kneading = true)
        "fault" -> SofaStatus(errorCode = 18, footFrontLimit = true, uprightPosition = true,
            shoulderIntensity = 2, backIntensity = 2, lumbarIntensity = 2)
        else -> null
    }
}
