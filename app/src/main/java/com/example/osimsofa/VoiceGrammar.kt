package com.example.osimsofa

enum class VoiceAction {
    UPRIGHT, RECLINE, SOFA, FULL_BODY, MANUAL_START,
    INTENSITY_UP, INTENSITY_DOWN, SPEED_UP, SPEED_DOWN, PAUSE, RESUME, STOP
}

/**
 * Maps a recognised zh-TW (or English) utterance to a sofa action (scope: 沙發調整三姿勢、
 * 全身按摩、手動按摩、暫停/繼續/停).
 *
 * Speech recognition often returns a homophone (做直 for 坐直, 站停 for 暫停), so each keyword
 * matches either as the exact words or by sound: toneless pinyin with the usual fuzzy pairs of
 * Taiwanese Mandarin merged (zh/z, ch/c, sh/s, n/l, ang/an, eng/en, ing/in). Without a [pinyin]
 * source only exact words match.
 *
 * Actions are tried in table order, which matters: 「暫停」 contains 「停」 and
 * 「停止全身按摩」 contains 「全身」.
 */
class VoiceGrammar(private val pinyin: Pinyin? = null) {

    /** Toneless pinyin of one Han character, e.g. 坐 → "zuo". */
    fun interface Pinyin { fun of(c: Char): String }

    private val table: List<Pair<VoiceAction, List<String>>> = listOf(
        VoiceAction.PAUSE to listOf("暫停", "停一下", "等一下", "pause"),
        VoiceAction.RESUME to listOf("繼續", "恢復", "resume"),
        VoiceAction.STOP to listOf("停止", "停下", "停", "關閉", "關掉", "結束", "stop"),
        VoiceAction.FULL_BODY to listOf("全身", "開始按摩", "full body", "whole body"),
        VoiceAction.MANUAL_START to listOf("手動", "manual"),
        VoiceAction.RECLINE to listOf("躺平", "放平", "躺下", "後躺", "recline", "flat"),
        VoiceAction.UPRIGHT to listOf("坐直", "坐正", "坐起來", "直立", "豎直", "upright"),
        VoiceAction.SOFA to listOf("沙發", "sofa"),
        VoiceAction.INTENSITY_UP to listOf("加強", "大力", "用力", "強一點", "重一點", "stronger"),
        VoiceAction.INTENSITY_DOWN to listOf("減弱", "小力", "輕一點", "弱一點", "weaker"),
        VoiceAction.SPEED_UP to listOf("加速", "快一點", "快點", "faster"),
        VoiceAction.SPEED_DOWN to listOf("減速", "慢一點", "慢點", "slower"),
    )

    private val wordSounds = HashMap<String, List<String>>()

    fun match(utterance: String): VoiceAction? {
        val u = utterance.lowercase().trim()
        val heard = sounds(u)
        for ((action, words) in table) {
            if (words.any { u.contains(it.lowercase()) || soundsLike(heard, it) }) return action
        }
        return null
    }

    /** The keyword's syllables appear in a row in what was heard. A one-syllable keyword (停) only
     *  counts in a very short utterance, so 「我想聽音樂」 is not taken for 停. */
    private fun soundsLike(heard: List<String>, word: String): Boolean {
        val w = wordSounds.getOrPut(word) { sounds(word) }
        if (w.isEmpty() || heard.size < w.size) return false
        if (w.size == 1 && heard.size > 2) return false
        return (0..heard.size - w.size).any { i -> w.indices.all { heard[i + it] == w[it] } }
    }

    private fun sounds(text: String): List<String> {
        val p = pinyin ?: return emptyList()
        return text.filter { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
            .map { fuzzy(p.of(it)) }
            .filter { it.isNotEmpty() }
    }

    companion object {
        /** Merge the sound pairs Taiwanese Mandarin speakers (and recognisers) commonly blur. */
        fun fuzzy(syllable: String): String {
            var s = syllable.lowercase().filter { it in 'a'..'z' }
            s = when {
                s.startsWith("zh") -> "z" + s.substring(2)
                s.startsWith("ch") -> "c" + s.substring(2)
                s.startsWith("sh") -> "s" + s.substring(2)
                s.startsWith("n") -> "l" + s.substring(1)
                else -> s
            }
            if (s.endsWith("ang") || s.endsWith("eng") || s.endsWith("ing")) s = s.dropLast(1)
            return s
        }
    }
}
