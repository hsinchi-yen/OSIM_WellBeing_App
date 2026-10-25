package com.example.osimsofa

/**
 * The diagnostics log as it may leave the phone (improve-plan D02): Bluetooth addresses and serial
 * numbers are masked, and what was said is left out unless transcript logging was switched on.
 */
object Redact {
    private val mac = Regex("(?i)\\b(?:[0-9a-f]{2}:){4}([0-9a-f]{2}:[0-9a-f]{2})\\b")
    /** Six or more digits in a row (sofa serials such as 12345678, product SNs): keep the first four. */
    private val digits = Regex("\\d{6,}")
    private val nfcPath = Regex("(?i)(otonfc/)[^\\s?/]+")
    private val productSn = Regex("(?i)(productsn=)[^&\\s]+")

    fun line(s: String): String {
        var t = mac.replace(s) { "**:**:**:**:${it.groupValues[1]}" }
        t = nfcPath.replace(t) { "${it.groupValues[1]}****" }
        t = productSn.replace(t) { "${it.groupValues[1]}****" }
        t = digits.replace(t) { it.value.take(4) + "*".repeat(it.value.length - 4) }
        return t
    }

    fun export(lines: List<String>, keepTranscripts: Boolean, header: String): String = buildString {
        appendLine(header)
        for (l in lines) {
            if (!keepTranscripts && l.contains("VOICE 聽到")) continue
            appendLine(line(l))
        }
    }
}
