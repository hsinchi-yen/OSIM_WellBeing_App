package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.FootSeat
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.Posture

/** What a spoken command asks for, with its parameters (improve-plan §5.1). */
sealed interface VoiceCommand {
    data class MoveTo(val posture: Posture) : VoiceCommand
    /** [stated]: the minutes were said; otherwise the default 15 is used and said back. */
    data class StartFullBody(val minutes: Int, val stated: Boolean) : VoiceCommand
    data object StartManual : VoiceCommand
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object StopAll : VoiceCommand
    /** ±1 on [areas]; empty = shoulder, back and lumbar together. */
    data class Intensity(val delta: Int, val areas: Set<Area> = emptySet()) : VoiceCommand
    data class Speed(val faster: Boolean) : VoiceCommand
    data class SetFunction(val function: FootSeat, val level: Level) : VoiceCommand
}

/** The reading of one utterance. */
sealed interface VoiceParse {
    /** [exact]: matched the written words, not only by sound. [note]: said back with the result. */
    data class Command(val cmd: VoiceCommand, val exact: Boolean, val note: String? = null) : VoiceParse
    /** Understood the topic but not enough to act: ask the user to say it again or choose. */
    data class Clarify(val message: String) : VoiceParse
    /** Recognised, deliberately not executed. */
    data class Refuse(val message: String, val why: Why) : VoiceParse
    data object Unknown : VoiceParse

    enum class Why { NEGATED, QUESTION, UNSUPPORTED }
}

/** What to do with one recognition (all its candidates together). */
sealed interface VoiceDecision {
    val heard: String

    data class Run(val cmd: VoiceCommand, override val heard: String, val exact: Boolean, val note: String? = null) : VoiceDecision
    data class Ask(val message: String, override val heard: String) : VoiceDecision
    data class Refuse(val message: String, override val heard: String) : VoiceDecision
}

/**
 * Turns recognised zh-TW (or English) speech into a [VoiceCommand] — or deliberately into nothing.
 *
 * Words are matched first as written, then — only if that finds nothing — by sound: toneless
 * pinyin with the pairs Taiwanese Mandarin (and recognisers) commonly blur merged (zh/z, ch/c,
 * sh/s, n/l, ang/an, eng/en, ing/in), so 「做直」 still means 坐直 and 「站停」 暫停.
 *
 * Safety comes before recall (improve-plan V01/V02): a negation (「不要躺平」), a question
 * (「請問沙發怎麼使用」), two commands at once, an unsupported duration or a bare 「沙發」 inside a
 * longer sentence never start or move anything. Stop is the exception — stopping is always safe,
 * so 「停止後繼續」 stops (and asks for the next command separately).
 */
class VoiceParser(private val pinyin: Pinyin? = null) {

    /** Toneless pinyin of one Han character, e.g. 坐 → "zuo". */
    fun interface Pinyin { fun of(c: Char): String }

    private enum class K {
        STOP, OFF, PAUSE, RESUME, START, ON, MOVE_VERB, THEN,
        FULL_BODY, MANUAL, MASSAGE, UPRIGHT, RECLINE, SOFA_CMD, SOFA_WORD,
        UP, DOWN, FASTER, SLOWER,
        SHOULDER, BACK, LUMBAR, FOOT, SEAT, KNEAD, VIBRATE,
        NEG, QUESTION, ZERO_G,
        /** Words that merely contain a keyword character (關於 ≠ 關). */
        IGNORE,
    }

    private class Word(val k: K, text: String) {
        val text = text.lowercase()
    }

    private val words: List<Word> = buildList {
        fun add(k: K, vararg w: String) = w.forEach { add(Word(k, it)) }
        add(K.STOP, "停止", "停下來", "停下", "停住", "停", "取消", "結束", "別動", "不要動", "不要再動", "stop", "cancel")
        add(K.OFF, "關閉", "關掉", "關")
        add(K.PAUSE, "暫停", "停一下", "等一下", "稍等", "pause")
        add(K.RESUME, "繼續", "恢復", "resume", "continue")
        add(K.START, "開始", "啟動", "執行", "來個", "我要", "幫我", "start")
        add(K.ON, "開啟", "打開", "開")
        add(K.MOVE_VERB, "調成", "調到", "調整到", "調整成", "切換到", "切換成", "切到", "換成", "換到", "改成", "改為", "變成", "回到")
        add(K.THEN, "然後", "之後", "接著", "再來", "再", "後")
        add(K.IGNORE, "關於", "相關", "有關", "開心", "離開", "公開", "開關")
        add(K.FULL_BODY, "全身按摩", "全身", "fullbody", "wholebody")
        add(K.MANUAL, "手動按摩", "手動", "manual")
        add(K.MASSAGE, "按摩", "massage")
        add(K.UPRIGHT, "坐式", "坐直", "坐姿", "坐正", "坐好", "坐起來", "直立", "豎直", "upright")
        add(K.RECLINE, "躺平", "放平", "躺下", "後躺", "平躺", "躺著", "recline", "flat")
        add(K.SOFA_CMD, "回到沙發", "回沙發", "沙發模式", "沙發位置", "回到待機", "待機模式", "待機", "回原位", "回到原位", "恢復原位", "standby")
        add(K.SOFA_WORD, "沙發", "sofa")
        add(K.UP, "加強", "大力", "用力", "強一點", "重一點", "大力一點", "加大", "stronger", "harder")
        add(K.DOWN, "減弱", "小力", "輕一點", "弱一點", "輕點", "小力一點", "減小", "weaker", "softer", "gentler")
        add(K.FASTER, "加速", "快一點", "快點", "快一些", "faster")
        add(K.SLOWER, "減速", "慢一點", "慢點", "慢一些", "slower")
        add(K.SHOULDER, "肩部", "肩膀", "頸肩", "肩")
        add(K.BACK, "背部", "後背", "背")
        add(K.LUMBAR, "腰部", "腰")
        add(K.FOOT, "足部", "腳部", "腳底", "足底", "腳", "足")
        add(K.SEAT, "臀部", "屁股", "座墊", "座椅", "臀")
        add(K.KNEAD, "揉捏", "揉")
        add(K.VIBRATE, "振動", "震動", "振", "震")
        add(K.NEG, "不要", "不用", "不想", "先不要", "不必", "不需要", "沒有要", "免了", "別", "甭", "don't", "dont", "donot")
        add(K.QUESTION, "怎麼", "怎樣", "如何", "什麼", "為什麼", "為何", "請問", "哪裡", "哪個", "哪", "是不是", "能不能",
            "可不可以", "有沒有", "是否", "how", "what", "why")
        add(K.ZERO_G, "零重力", "無重力", "zerogravity")
    }

    private data class Hit(val k: K, val start: Int, val end: Int)

    /**
     * One utterance. A refusal from the written words is final. Matching by sound is tried when the
     * written words found nothing or only "ask back" (「泉身按摩」: the exact 「按摩」 alone would only
     * ask), and for a stop that rests on the single character 停 (「站停」 is 暫停 misheard — the
     * only way a stop can turn into anything else, and only into a pause).
     */
    fun parse(utterance: String): VoiceParse {
        val text = normalize(utterance)
        if (text.isEmpty()) return VoiceParse.Unknown
        val exactHits = hits(text, byChar(text), exact = true)
        val exact = interpret(text, exactHits, exact = true)
        if (pinyin == null || exact is VoiceParse.Refuse) return exact
        if (exact is VoiceParse.Command) {
            val loneStop = exact.cmd == VoiceCommand.StopAll &&
                exactHits.filter { it.k == K.STOP || it.k == K.OFF }.all { it.end - it.start == 1 }
            if (!loneStop) return exact
            val sound = bySoundIfCovered(text)
            return if (sound is VoiceParse.Command && sound.cmd == VoiceCommand.Pause) sound else exact
        }
        val sound = bySoundIfCovered(text)
        return if (sound is VoiceParse.Command || sound is VoiceParse.Refuse || exact == VoiceParse.Unknown) sound else exact
    }

    /** The reading by sound — a command only if its words cover at least half of what was said
     *  (fillers like 我、請、一下 aside), so a sentence that merely contains a similar sound
     *  (「我想聽音樂」 ≈ 先停) is not taken for a command. */
    private fun bySoundIfCovered(text: String): VoiceParse {
        val hits = hits(text, bySound(text), exact = false)
        val result = interpret(text, hits, exact = false)
        if (result !is VoiceParse.Command) return result
        val counted = text.indices.filter { isHan(text[it]) && text[it] !in FILLERS }
        if (counted.isEmpty()) return result
        val covered = counted.count { i -> hits.any { i >= it.start && i < it.end } }
        return if (covered * 2 >= counted.size) result else VoiceParse.Unknown
    }

    /**
     * One recognition: the recogniser's candidates, best first, and its confidence scores (often
     * only for the first; ≤ 0 means unknown). Exact readings beat readings by sound; readings by
     * sound are only taken from the best candidate; candidates that disagree are asked back.
     */
    fun decide(candidates: List<String>, confidence: FloatArray? = null): VoiceDecision {
        val heard = candidates.filter { it.isNotBlank() }.take(MAX_CANDIDATES)
        if (heard.isEmpty()) return VoiceDecision.Refuse("沒有聽到內容，請再說一次", "")
        val parsed = heard.map { it to parse(it) }
        val (topText, top) = parsed.first()

        // Stop is always safe: an exact 停止 anywhere, or one heard as the best candidate, wins.
        parsed.firstOrNull { (_, p) -> p is VoiceParse.Command && p.cmd == VoiceCommand.StopAll && (p.exact || p === top) }
            ?.let { (t, p) -> return VoiceDecision.Run(VoiceCommand.StopAll, t, (p as VoiceParse.Command).exact, p.note) }

        if (top is VoiceParse.Refuse) return VoiceDecision.Refuse(top.message, topText)
        // e.g. 「開始全身按摩 20 分鐘」: never fall back to a lower candidate that lost the number.
        if (top is VoiceParse.Clarify) return VoiceDecision.Ask(top.message, topText)
        if (parsed.any { (_, p) -> p is VoiceParse.Refuse && p.why == VoiceParse.Why.NEGATED }) {
            return VoiceDecision.Refuse("沒有執行：辨識結果裡有否定的說法（${parsed.first { it.second is VoiceParse.Refuse }.first}）", topText)
        }

        val exact = parsed.filter { (_, p) -> p is VoiceParse.Command && p.exact }
        val pool = exact.ifEmpty { if (top is VoiceParse.Command) listOf(parsed.first()) else emptyList() }
        if (pool.isEmpty()) {
            parsed.firstOrNull { it.second is VoiceParse.Clarify }
                ?.let { (t, p) -> return VoiceDecision.Ask((p as VoiceParse.Clarify).message, t) }
            return VoiceDecision.Refuse("聽到「$topText」，但沒有對應的指令", topText)
        }

        val cmds = pool.map { (it.second as VoiceParse.Command).cmd }
        val merged = merge(cmds) ?: return VoiceDecision.Ask(conflict(cmds), topText)
        if (exact.isEmpty() && confidence != null && confidence.isNotEmpty() && confidence[0] > 0f && confidence[0] < LOW_CONFIDENCE) {
            return VoiceDecision.Ask("沒聽清楚（聽到「$topText」），請再說一次", topText)
        }
        val (chosenText, chosen) = pool.firstOrNull { (it.second as VoiceParse.Command).cmd == merged } ?: pool.first()
        return VoiceDecision.Run(merged, chosenText, exact.isNotEmpty(), (chosen as VoiceParse.Command).note)
    }

    // ------------------------------------------------------------------ matching

    /** Every keyword occurrence, then the longest non-overlapping ones (暫停 over 停, 全身按摩 over 按摩). */
    private fun hits(text: String, units: List<String>, exact: Boolean): List<Hit> {
        val hanCount = text.count { isHan(it) }
        val all = ArrayList<Hit>()
        for (w in words) {
            val wu = if (exact) byChar(w.text) else bySound(w.text)
            if (wu.isEmpty() || wu.size > units.size) continue
            // By sound a single syllable is too easy to hit by accident (聽 = 停); only a lone 停 counts.
            if (!exact && wu.size == 1 && !(w.k == K.STOP && hanCount <= 2)) continue
            for (i in 0..units.size - wu.size) {
                if (wu.indices.all { units[i + it] == wu[it] }) all += Hit(w.k, i, i + wu.size)
            }
        }
        val chosen = ArrayList<Hit>()
        for (h in all.sortedWith(compareByDescending<Hit> { it.end - it.start }.thenBy { it.start })) {
            if (chosen.none { h.start < it.end && it.start < h.end }) chosen += h
        }
        return chosen.sortedBy { it.start }
    }

    private fun interpret(text: String, hits: List<Hit>, exact: Boolean): VoiceParse {
        fun has(k: K) = hits.any { it.k == k }
        fun cmd(c: VoiceCommand, note: String? = null) = VoiceParse.Command(c, exact, note)

        if (has(K.ZERO_G)) return VoiceParse.Refuse("uDiva 沙發沒有零重力姿勢；可以說「躺平」或「坐式」", VoiceParse.Why.UNSUPPORTED)

        val function: FootSeat? = when {
            has(K.SEAT) && !has(K.FOOT) -> FootSeat.SEAT_VIBRATION       // the seat has only vibration
            has(K.FOOT) && has(K.KNEAD) && !has(K.VIBRATE) -> FootSeat.FOOT_KNEADING
            has(K.FOOT) && has(K.VIBRATE) && !has(K.KNEAD) -> FootSeat.FOOT_VIBRATION
            else -> null
        }
        val footOrSeat = has(K.FOOT) || has(K.SEAT)

        // 1. Stop wins, unless it is negated right before it (「不要停」). What it stops (按摩、躺平…)
        //    is its object; another verb or 「然後」 means a second command, which is not run.
        val stop = hits.firstOrNull { it.k == K.STOP } ?: hits.firstOrNull { it.k == K.OFF && !footOrSeat }
        if (stop != null) {
            val negated = hits.any { it.k == K.NEG && it.end <= stop.start && stop.start - it.end <= 1 }
            if (negated) return VoiceParse.Refuse("沒有執行：聽到「不要停」之類的說法", VoiceParse.Why.NEGATED)
            val more = listOf(K.THEN, K.RESUME, K.PAUSE, K.START, K.ON, K.MOVE_VERB, K.UP, K.DOWN, K.FASTER, K.SLOWER).any { has(it) }
            return cmd(VoiceCommand.StopAll, if (more) "已停止；下一個指令請分開再說一次" else null)
        }
        if (has(K.NEG)) return VoiceParse.Refuse("沒有執行：聽到否定的說法", VoiceParse.Why.NEGATED)

        val intents = intents(text, hits)
        val question = has(K.QUESTION) || text.endsWith("?") || text.endsWith("嗎") || text.endsWith("呢") || text.endsWith("吗")
        if (question) {
            val hint = intents.singleOrNull()?.let { "；要${it.zh}請直接說「${it.say}」" } ?: "；要操作請直接說指令，例如「躺平」"
            return VoiceParse.Refuse("聽起來像是問題，沒有執行$hint", VoiceParse.Why.QUESTION)
        }

        // 2. Foot / seat functions.
        if (footOrSeat && intents.none { it.primary }) {
            if (function == null) return VoiceParse.Clarify("要調整「足部揉捏」還是「足部振動」？")
            val level = when {
                has(K.OFF) -> Level.OFF
                has(K.ON) || has(K.START) -> Level.AUTO
                has(K.UP) && !has(K.DOWN) -> Level.HIGH
                has(K.DOWN) && !has(K.UP) -> Level.LOW
                else -> return VoiceParse.Clarify("要開啟還是關閉${Names.function(function)}？")
            }
            return cmd(VoiceCommand.SetFunction(function, level))
        }

        if (intents.size > 1) {
            return VoiceParse.Clarify("聽到${intents.joinToString("和") { "「${it.zh}」" }}，請一次說一個指令")
        }
        val intent = intents.singleOrNull() ?: return when {
            has(K.MASSAGE) -> VoiceParse.Clarify("要開始全身按摩嗎？請說「開始全身按摩」")
            has(K.START) || has(K.ON) -> VoiceParse.Clarify("要開始什麼？可以說「開始全身按摩」或「開始手動按摩」")
            else -> VoiceParse.Unknown
        }

        return when (intent) {
            Intent.PAUSE -> cmd(VoiceCommand.Pause)
            Intent.RESUME -> cmd(VoiceCommand.Resume)
            Intent.MANUAL -> cmd(VoiceCommand.StartManual)
            Intent.UPRIGHT -> cmd(VoiceCommand.MoveTo(Posture.UPRIGHT))
            Intent.RECLINE -> cmd(VoiceCommand.MoveTo(Posture.RECLINE))
            Intent.SOFA -> cmd(VoiceCommand.MoveTo(Posture.SOFA))
            Intent.FULL_BODY -> {
                val m = durations(text)
                when {
                    m.isEmpty() -> cmd(VoiceCommand.StartFullBody(DEFAULT_MINUTES, stated = false))
                    m.size > 1 -> VoiceParse.Clarify("請選一個時間：15 或 30 分鐘")
                    m[0] in SUPPORTED_MINUTES -> cmd(VoiceCommand.StartFullBody(m[0], stated = true))
                    else -> VoiceParse.Clarify("全身按摩只能選 15 或 30 分鐘（聽到 ${m[0]} 分鐘）")
                }
            }
            Intent.INTENSITY -> when {
                has(K.UP) && has(K.DOWN) -> VoiceParse.Clarify("要加強還是減弱？")
                has(K.SOFA_WORD) -> VoiceParse.Clarify("要調整哪個部位？可以說「肩部加強」「背部加強」「腰部加強」，或只說「加強」")
                else -> {
                    val areas = buildSet {
                        if (has(K.SHOULDER)) add(Area.SHOULDER)
                        if (has(K.BACK)) add(Area.BACK)
                        if (has(K.LUMBAR)) add(Area.LUMBAR)
                    }
                    cmd(VoiceCommand.Intensity(if (has(K.UP)) +1 else -1, areas))
                }
            }
            Intent.SPEED ->
                if (has(K.FASTER) && has(K.SLOWER)) VoiceParse.Clarify("要加快還是放慢？")
                else cmd(VoiceCommand.Speed(faster = has(K.FASTER)))
        }
    }

    private enum class Intent(val zh: String, val say: String, val primary: Boolean = true) {
        PAUSE("暫停", "暫停"), RESUME("繼續", "繼續"), FULL_BODY("全身按摩", "開始全身按摩"),
        MANUAL("手動按摩", "開始手動按摩"), UPRIGHT("坐式", "坐式"), RECLINE("躺平", "躺平"),
        SOFA("沙發（待機）", "回到沙發"), INTENSITY("調整力度", "加強", primary = false), SPEED("調整速度", "加速", primary = false),
    }

    private fun intents(text: String, hits: List<Hit>): List<Intent> {
        fun has(k: K) = hits.any { it.k == k }
        val out = ArrayList<Intent>()
        val pauseOrResume = has(K.PAUSE) || has(K.RESUME)
        if (has(K.PAUSE)) out += Intent.PAUSE
        if (has(K.RESUME)) out += Intent.RESUME
        // 「暫停全身按摩」「繼續按摩」: the massage is the object, not a second command.
        if (!pauseOrResume) {
            if (has(K.MANUAL)) out += Intent.MANUAL
            else if (has(K.FULL_BODY) || (has(K.MASSAGE) && (has(K.START) || has(K.ON) || durations(text).isNotEmpty()))) out += Intent.FULL_BODY
        }
        if (has(K.UPRIGHT)) out += Intent.UPRIGHT
        if (has(K.RECLINE)) out += Intent.RECLINE
        val sofaWord = hits.firstOrNull { it.k == K.SOFA_WORD }
        val sofaAlone = sofaWord != null &&
            text.filterIndexed { i, c -> i !in sofaWord.start until sofaWord.end && c !in PARTICLES }.isEmpty()
        if (has(K.SOFA_CMD) || (sofaWord != null && (sofaAlone || has(K.MOVE_VERB) || (has(K.START) && hits.size == 2)))) out += Intent.SOFA
        if (has(K.UP) || has(K.DOWN)) out += Intent.INTENSITY
        if (has(K.FASTER) || has(K.SLOWER)) out += Intent.SPEED
        return out
    }

    // ------------------------------------------------------------------ helpers

    private fun merge(cmds: List<VoiceCommand>): VoiceCommand? {
        if (cmds.all { it is VoiceCommand.StartFullBody }) {
            val stated = cmds.filterIsInstance<VoiceCommand.StartFullBody>().filter { it.stated }.map { it.minutes }.distinct()
            return when (stated.size) {
                0 -> VoiceCommand.StartFullBody(DEFAULT_MINUTES, stated = false)
                1 -> VoiceCommand.StartFullBody(stated[0], stated = true)
                else -> null
            }
        }
        return cmds.distinct().singleOrNull()
    }

    private fun conflict(cmds: List<VoiceCommand>): String =
        if (cmds.all { it is VoiceCommand.StartFullBody }) "聽到 15 和 30 分鐘，請再說一次要幾分鐘"
        else "聽到${cmds.distinct().joinToString("或") { "「${describe(it)}」" }}，請再說一次"

    private fun byChar(text: String): List<String> = text.map { it.toString() }

    private fun bySound(text: String): List<String> {
        val p = pinyin ?: return byChar(text)
        return text.map { c -> if (isHan(c)) fuzzy(p.of(c)).ifEmpty { c.toString() } else c.toString() }
    }

    companion object {
        const val DEFAULT_MINUTES = 15
        val SUPPORTED_MINUTES = setOf(15, 30)
        const val MAX_CANDIDATES = 5
        const val LOW_CONFIDENCE = 0.5f
        private const val PARTICLES = "請吧啦喔哦了呀啊嘛的唷囉耶"
        /** Words around a command that carry no meaning of their own (not counted for coverage). */
        private const val FILLERS = "我你請幫要想讓把給一下吧了的啊呀喔哦啦嘛呢唷囉耶好"

        /** Lowercase; drop spaces and punctuation; full-width digits and ？ to ASCII. */
        fun normalize(s: String): String = buildString {
            for (ch in s.lowercase()) {
                val c = when (ch) { in '０'..'９' -> '0' + (ch - '０'); '？' -> '?'; else -> ch }
                if (c.isWhitespace() || c in "，。！、,.!；;：:「」『』\"()（）…~～-") continue
                append(c)
            }
        }

        /** Minutes mentioned: 15、十五、三十分、半小時、「15 或 30 分鐘」… */
        fun durations(text: String): List<Int> {
            val out = ArrayList<Int>()
            val unit = "(分鐘|分钟|分|min)"
            Regex("(\\d{1,3})$unit").findAll(text).forEach { out += it.groupValues[1].toInt() }
            Regex("([零〇一二兩三四五六七八九十]+)$unit").findAll(text).forEach { m -> chineseNumber(m.groupValues[1])?.let { out += it } }
            Regex("(\\d{1,3}|[零〇一二兩三四五六七八九十]+)(或是|或者|或|還是|跟|和)(\\d{1,3}|[零〇一二兩三四五六七八九十]+)$unit")
                .findAll(text).forEach { m -> number(m.groupValues[1])?.let { out += it } }
            if (Regex("半(個)?(小時|鐘頭)").containsMatchIn(text)) out += 30
            if (text.contains("一刻鐘")) out += 15
            if (Regex("(^|[^半])(一|1|兩|2)(個)?(小時|鐘頭)").containsMatchIn(text)) out += 60
            return out.distinct()
        }

        private fun number(s: String): Int? = s.toIntOrNull() ?: chineseNumber(s)

        /** 五 → 5, 十五 → 15, 三十 → 30, 四十五 → 45 (below 100 is all a duration needs). */
        fun chineseNumber(s: String): Int? {
            val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '兩' to 2, '三' to 3, '四' to 4,
                '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
            if (s.isEmpty()) return null
            val ten = s.indexOf('十')
            if (ten < 0) return if (s.length == 1) digit[s[0]] else null
            if (ten > 1 || s.length > ten + 2) return null
            val tens = if (ten == 0) 1 else digit[s[0]] ?: return null
            val ones = if (ten == s.length - 1) 0 else digit[s[ten + 1]] ?: return null
            return tens * 10 + ones
        }

        private fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

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

        /** What the command does, in the words the app uses — shown and spoken as 「理解為…」. */
        fun describe(c: VoiceCommand): String = when (c) {
            is VoiceCommand.MoveTo -> if (c.posture == Posture.SOFA) "回到沙發（待機）" else "調整為${Names.short(c.posture)}"
            is VoiceCommand.StartFullBody -> "開始全身按摩 ${c.minutes} 分鐘" + if (c.stated) "" else "（未說時間，使用 15 分鐘）"
            VoiceCommand.StartManual -> "開始手動按摩"
            VoiceCommand.Pause -> "暫停按摩"
            VoiceCommand.Resume -> "繼續按摩"
            VoiceCommand.StopAll -> "停止"
            is VoiceCommand.Intensity -> {
                val where = if (c.areas.isEmpty()) "肩、背、腰" else c.areas.joinToString("、") { Names.area(it) }
                "${where}力度${if (c.delta > 0) "加強" else "減弱"}"
            }
            is VoiceCommand.Speed -> if (c.faster) "速度加快" else "速度放慢"
            is VoiceCommand.SetFunction -> when (c.level) {
                Level.OFF -> "關閉${Names.function(c.function)}"
                Level.AUTO -> "開啟${Names.function(c.function)}（自動）"
                Level.HIGH -> "${Names.function(c.function)}調強"
                Level.LOW -> "${Names.function(c.function)}調弱"
            }
        }
    }
}
