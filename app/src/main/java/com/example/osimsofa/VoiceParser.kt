package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.FootSeat
import com.example.osimsofa.OsimProtocol.KneadTap
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.OsimProtocol.Rolling

/** What a spoken command asks for, with its parameters (improve-plan §5.1). */
sealed interface VoiceCommand {
    data class MoveTo(val posture: Posture) : VoiceCommand
    /** [stated]: the minutes were said; otherwise the default 15 is used and said back. */
    data class StartFullBody(val minutes: Int, val stated: Boolean) : VoiceCommand
    data object StartManual : VoiceCommand
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object StopAll : VoiceCommand
    /** ±1 on [areas]; empty = shoulder, back and lumbar together. [again]: 「再強一點」 with no
     *  area said — it means the part adjusted last ([VoiceMemory]). */
    data class Intensity(val delta: Int, val areas: Set<Area> = emptySet(), val again: Boolean = false) : VoiceCommand
    /** 「腰部力度調到 4」: an absolute level 1–5; empty [areas] as above. */
    data class SetIntensity(val level: Int, val areas: Set<Area> = emptySet()) : VoiceCommand
    data class Speed(val faster: Boolean) : VoiceCommand
    data class SetFunction(val function: FootSeat, val level: Level) : VoiceCommand
    /** 「腰部按摩」: a massage aimed at one zone. */
    data class Focus(val zone: Zone) : VoiceCommand
    /** Foot kneading and foot vibration together. */
    data class LegMassage(val level: Level) : VoiceCommand
    /** 「關閉背部」: the rollers off, the other zones carry on. */
    data object BackOff : VoiceCommand
    data class Technique(val kneadTap: KneadTap) : VoiceCommand
    data class Roll(val rolling: Rolling) : VoiceCommand
    data class Width(val wider: Boolean) : VoiceCommand
    /** Answered from the sofa's status; sends nothing to the sofa. */
    data class Query(val topic: Topic) : VoiceCommand

    enum class Topic { REMAINING, STATE, INTENSITY, HELP }
}

/**
 * The part adjusted last, so 「背部加強」 then 「再強一點」 means the back. Only ever narrows an
 * adjustment: it cannot turn anything into a movement or a start, and it fades after [WINDOW_MS].
 */
class VoiceMemory {
    private var last: VoiceCommand? = null
    private var at = 0L

    /** [cmd] with its 「再…」 filled in. Forgotten or nothing to go on: all of 肩、背、腰. */
    fun resolve(cmd: VoiceCommand, now: Long): VoiceCommand {
        if (cmd !is VoiceCommand.Intensity || !cmd.again) return cmd
        val level = if (cmd.delta > 0) Level.HIGH else Level.LOW
        val plain = cmd.copy(again = false)
        if (now - at > WINDOW_MS) return plain
        return when (val l = last) {
            is VoiceCommand.Intensity -> plain.copy(areas = l.areas)
            is VoiceCommand.SetIntensity -> plain.copy(areas = l.areas)
            is VoiceCommand.SetFunction -> l.copy(level = level)
            is VoiceCommand.LegMassage -> l.copy(level = level)
            is VoiceCommand.Focus -> when {
                l.zone.area != null -> plain.copy(areas = setOf(l.zone.area))
                l.zone == Zone.LEGS -> VoiceCommand.LegMassage(level)
                else -> VoiceCommand.SetFunction(FootSeat.SEAT_VIBRATION, level)
            }
            else -> plain
        }
    }

    /** Call with what actually ran. A stop forgets: the next 「再強一點」 starts from nothing. */
    fun remember(cmd: VoiceCommand, now: Long) {
        when (cmd) {
            VoiceCommand.StopAll -> last = null
            is VoiceCommand.Intensity, is VoiceCommand.SetIntensity, is VoiceCommand.SetFunction,
            is VoiceCommand.LegMassage, is VoiceCommand.Focus -> { last = cmd; at = now }
            else -> {}
        }
    }

    companion object { const val WINDOW_MS = 60_000L }
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
        MAX, MIN, AUTO, TAP, ROLL_FULL, ROLL_PARTIAL, WIDER, NARROWER,
        /** The backrest or footrest as a thing to move, and moving it: no independent key exists. */
        REST, LIFT,
        /** Something hurts or is tired: an offer, not a command. */
        ACHE,
        ASK_REMAINING, ASK_STATE, ASK_LEVEL, ASK_HELP,
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
        add(K.IGNORE, "關於", "相關", "有關", "開心", "離開", "公開", "開關", "足夠", "滿足", "背景", "背包", "特別")
        add(K.FULL_BODY, "全身按摩", "全身", "fullbody", "wholebody")
        add(K.MANUAL, "手動按摩", "手動", "manual")
        add(K.MASSAGE, "按摩", "massage")
        add(K.UPRIGHT, "坐式", "坐直", "坐姿", "坐正", "坐好", "坐起來", "直立", "豎直", "upright")
        add(K.RECLINE, "躺平", "放平", "躺下", "後躺", "平躺", "躺著", "recline", "flat")
        add(K.SOFA_CMD, "回到沙發", "回沙發", "沙發模式", "沙發位置", "回到待機", "待機模式", "待機", "回原位", "回到原位", "恢復原位", "standby")
        add(K.SOFA_WORD, "沙發", "sofa")
        // 「太大力了」 complains about the strength: it must not read as the 「大力」 inside it.
        add(K.UP, "加強", "大力", "用力", "強一點", "重一點", "大力一點", "加大", "更強", "更大力", "調高", "提高", "調強", "開強",
            "太輕", "太弱", "太小力", "不夠力", "不夠強", "不夠大力", "stronger", "harder")
        add(K.DOWN, "減弱", "小力", "輕一點", "弱一點", "輕點", "小力一點", "減小", "更輕", "更小力", "調低", "降低", "調弱", "開弱",
            "太強", "太大力", "太用力", "太重", "太痛", "太大", "weaker", "softer", "gentler")
        add(K.FASTER, "加速", "快一點", "快點", "快一些", "太慢", "faster")
        add(K.SLOWER, "減速", "慢一點", "慢點", "慢一些", "太快", "slower")
        add(K.MAX, "最強", "最大力", "最大", "最高", "最重")
        add(K.MIN, "最弱", "最輕", "最小力", "最小", "最低")
        add(K.AUTO, "自動", "auto")
        add(K.SHOULDER, "肩部", "肩膀", "肩頸", "頸肩", "頸部", "脖子", "肩", "頸")
        add(K.BACK, "背部", "後背", "上背", "背脊", "脊椎", "背")
        add(K.LUMBAR, "腰部", "下背", "腰椎", "後腰", "腰")
        add(K.FOOT, "足部", "腳部", "腳底", "足底", "腳掌", "腿部", "小腿", "雙腿", "雙腳", "腿", "腳", "足")
        add(K.SEAT, "臀部", "屁股", "座墊", "座椅", "大腿", "臀")
        add(K.REST, "椅背", "靠背", "腳托", "腳凳", "腳踏")
        add(K.LIFT, "抬高", "抬起", "升高", "升起", "放下", "放低", "往後", "往前", "伸出", "伸長", "收回", "收起")
        add(K.ACHE, "痠痛", "酸痛", "緊繃", "僵硬", "好累", "很累", "不舒服", "痠", "酸", "痛")
        add(K.KNEAD, "揉捏", "揉")
        add(K.TAP, "敲打", "拍打", "捶打", "敲")
        add(K.ROLL_FULL, "全程滾動", "全程")
        add(K.ROLL_PARTIAL, "局部滾動", "局部")
        add(K.WIDER, "寬一點", "加寬", "調寬")
        add(K.NARROWER, "窄一點", "縮窄", "調窄")
        add(K.VIBRATE, "振動", "震動", "振", "震")
        add(K.NEG, "不要", "不用", "不想", "先不要", "不必", "不需要", "沒有要", "免了", "別", "甭", "don't", "dont", "donot")
        add(K.QUESTION, "怎麼", "怎樣", "如何", "什麼", "為什麼", "為何", "請問", "哪裡", "哪個", "哪", "是不是", "能不能",
            "可不可以", "有沒有", "是否", "how", "what", "why")
        add(K.ZERO_G, "零重力", "無重力", "zerogravity")
        add(K.ASK_REMAINING, "還剩", "剩多久", "剩幾分鐘", "剩下幾分鐘", "還有幾分鐘", "還有多久", "還要多久", "多久結束", "幾點結束", "何時結束", "什麼時候結束")
        add(K.ASK_STATE, "什麼姿勢", "哪個姿勢", "什麼狀態", "目前姿勢", "現在姿勢", "目前狀態", "現在狀態")
        add(K.ASK_LEVEL, "力度多少", "力度幾", "強度多少", "強度幾", "目前力度", "幾級")
        add(K.ASK_HELP, "可以說什麼", "能說什麼", "要說什麼", "有哪些指令", "有什麼指令", "語音說明", "指令", "help")
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

    /**
     * While still listening: has the user already said a stop? A partial result ends in a word, so
     * only whole stop words count (not a lone 停, which 「停一下」 may be growing into) — the sofa then
     * stops without waiting for the end of the sentence. A negation before it has been heard by then.
     */
    fun earlyStop(partial: String): Boolean {
        val text = normalize(partial)
        val hits = hits(text, byChar(text), exact = true)
        val p = interpret(text, hits, exact = true)
        return p is VoiceParse.Command && p.cmd == VoiceCommand.StopAll &&
            hits.any { it.k == K.STOP && it.end - it.start >= 2 }
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
        val areas = buildSet {
            if (has(K.SHOULDER)) add(Area.SHOULDER)
            if (has(K.BACK)) add(Area.BACK)
            if (has(K.LUMBAR)) add(Area.LUMBAR)
        }
        // 關 + one zone with a switch of its own is about that zone, not about everything.
        val switchesOffZone = footOrSeat || areas == setOf(Area.BACK)

        // 1. Stop wins, unless it is negated right before it (「不要停」). What it stops (按摩、躺平…)
        //    is its object; another verb or 「然後」 means a second command, which is not run.
        val stop = hits.firstOrNull { it.k == K.STOP } ?: hits.firstOrNull { it.k == K.OFF && !switchesOffZone }
        if (stop != null) {
            val negated = hits.any { it.k == K.NEG && it.end <= stop.start && stop.start - it.end <= 1 }
            if (negated) return VoiceParse.Refuse("沒有執行：聽到「不要停」之類的說法", VoiceParse.Why.NEGATED)
            val more = listOf(K.THEN, K.RESUME, K.PAUSE, K.START, K.ON, K.MOVE_VERB, K.UP, K.DOWN, K.FASTER, K.SLOWER).any { has(it) }
            return when {
                more -> cmd(VoiceCommand.StopAll, "已停止；下一個指令請分開再說一次")
                // 「關掉腰部」: rather stop too much than leave running what someone wants off.
                stop.k == K.OFF && areas.isNotEmpty() -> cmd(VoiceCommand.StopAll, "肩、背、腰共用同一組滾輪，無法單獨關閉，已全部停止")
                else -> cmd(VoiceCommand.StopAll)
            }
        }
        if (has(K.NEG)) return VoiceParse.Refuse("沒有執行：聽到否定的說法", VoiceParse.Why.NEGATED)

        val intents = intents(text, hits)

        // 2. Questions about the sofa are answered; they send nothing, so they need no care.
        val topic = when {
            has(K.ASK_HELP) -> VoiceCommand.Topic.HELP
            has(K.ASK_REMAINING) -> VoiceCommand.Topic.REMAINING
            has(K.ASK_LEVEL) -> VoiceCommand.Topic.INTENSITY
            has(K.ASK_STATE) -> VoiceCommand.Topic.STATE
            else -> null
        }
        if (topic != null && intents.isEmpty()) return cmd(VoiceCommand.Query(topic))

        val question = has(K.QUESTION) || text.endsWith("?") || text.endsWith("嗎") || text.endsWith("呢") || text.endsWith("吗")
        if (question) {
            val hint = intents.singleOrNull()?.let { "；要${it.zh}請直接說「${it.say}」" } ?: "；要操作請直接說指令，例如「躺平」"
            return VoiceParse.Refuse("聽起來像是問題，沒有執行$hint", VoiceParse.Why.QUESTION)
        }

        // 3. Moving one part of the sofa does not exist here; it never becomes a preset by accident.
        if (intents.none { it.primary } && (has(K.REST) || has(K.LIFT))) {
            return VoiceParse.Refuse("椅背和腳托無法單獨調整；可以說「坐式」「躺平」或「回到沙發」", VoiceParse.Why.UNSUPPORTED)
        }

        val zoneHit = hits.lastOrNull { it.k in ZONE_WORDS }
        val number = levelIn(text) ?: zoneHit?.let { z -> levelAfterZone(text.substring(z.end)) }
        val strongest = has(K.MAX) && !has(K.MIN)
        val weakest = has(K.MIN) && !has(K.MAX)
        val zones = zonesOf(hits)

        // 4. Foot / seat functions.
        if (footOrSeat && intents.none { it.primary }) {
            if (number != null) return VoiceParse.Clarify("${zones.first().zh}只有「弱」「強」「自動」，沒有 1 到 5 級")
            val level = when {
                has(K.OFF) -> Level.OFF
                strongest -> Level.HIGH
                weakest -> Level.LOW
                has(K.UP) && !has(K.DOWN) -> Level.HIGH
                has(K.DOWN) && !has(K.UP) -> Level.LOW
                has(K.AUTO) || has(K.ON) || has(K.START) -> Level.AUTO
                else -> null
            }
            val named = has(K.KNEAD) || has(K.VIBRATE)
            val massage = has(K.MASSAGE) || has(K.MANUAL)
            if (function != null && (named || level != null && level != Level.AUTO)) {
                level ?: return VoiceParse.Clarify("要開啟還是關閉${Names.function(function)}？")
                return cmd(VoiceCommand.SetFunction(function, level))
            }
            if (has(K.VIBRATE) && has(K.KNEAD)) return VoiceParse.Clarify("請一次說一個：足部揉捏或足部振動")
            if (level == null || level == Level.AUTO) {
                // 「腿部按摩」 asks for a massage; 「開啟足部」 only switches the function on.
                if (!massage && !has(K.START)) {
                    if (level == null) return VoiceParse.Clarify(if (has(K.ACHE)) offer(zones) else zoneChoices(zones.first()))
                    return cmd(function?.let { VoiceCommand.SetFunction(it, Level.AUTO) } ?: VoiceCommand.LegMassage(Level.AUTO))
                }
                if (areas.isNotEmpty() || (has(K.FOOT) && has(K.SEAT))) return VoiceParse.Clarify("請一次說一個部位")
                if (durations(text).isNotEmpty()) return VoiceParse.Clarify(NO_ZONE_MINUTES)
                return cmd(VoiceCommand.Focus(if (has(K.FOOT)) Zone.LEGS else Zone.SEAT))
            }
            return cmd(VoiceCommand.LegMassage(level))
        }

        // 5. An absolute level for the rollers' areas.
        val strength = if (durations(text).isNotEmpty()) null else number ?: when {
            strongest -> OsimProtocol.INTENSITY.last
            weakest -> OsimProtocol.INTENSITY.first
            else -> null
        }
        if (strength != null && intents.none { it.primary }) {
            if (has(K.SOFA_WORD)) return VoiceParse.Clarify("要調整哪個部位？可以說「腰部力度 4」，或只說「力度 4」")
            if (strength !in OsimProtocol.INTENSITY) return VoiceParse.Clarify("力度只有 1 到 5 級（聽到 $strength）")
            return cmd(VoiceCommand.SetIntensity(strength, areas))
        }

        // 6. How the rollers work.
        if (intents.isEmpty()) {
            val fingers = when {
                has(K.KNEAD) && has(K.TAP) -> KneadTap.BOTH
                has(K.TAP) -> KneadTap.TAPPING
                has(K.KNEAD) -> KneadTap.KNEADING
                else -> null
            }
            if (fingers != null) return cmd(VoiceCommand.Technique(fingers))
            if (has(K.ROLL_FULL)) return cmd(VoiceCommand.Roll(Rolling.FULL))
            if (has(K.ROLL_PARTIAL)) return cmd(VoiceCommand.Roll(Rolling.PARTIAL))
            if (has(K.WIDER) != has(K.NARROWER)) return cmd(VoiceCommand.Width(wider = has(K.WIDER)))
            if (has(K.VIBRATE)) return VoiceParse.Clarify("要調整「足部振動」還是「臀部振動」？")
        }

        // 7. The rollers' areas by name.
        if (areas.isNotEmpty() && intents.isEmpty()) {
            if (has(K.OFF)) return cmd(VoiceCommand.BackOff)
            if (has(K.MASSAGE) || has(K.MANUAL) || has(K.START) || has(K.ON)) {
                if (areas.size > 1) return VoiceParse.Clarify("請一次說一個部位")
                if (durations(text).isNotEmpty()) return VoiceParse.Clarify(NO_ZONE_MINUTES)
                return cmd(VoiceCommand.Focus(Zone.of(areas.single())))
            }
            return VoiceParse.Clarify(if (has(K.ACHE)) offer(zones) else zoneChoices(zones.first()))
        }

        if (intents.size > 1) {
            return VoiceParse.Clarify("聽到${intents.joinToString("和") { "「${it.zh}」" }}，請一次說一個指令")
        }
        val intent = intents.singleOrNull() ?: return when {
            has(K.ACHE) -> VoiceParse.Clarify("要減輕請說「輕一點」，要結束請說「停止」")
            has(K.MASSAGE) -> VoiceParse.Clarify("要開始全身按摩嗎？請說「開始全身按摩」")
            has(K.START) || has(K.ON) -> VoiceParse.Clarify("要開始什麼？可以說「開始全身按摩」或「腰部按摩」")
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
                else -> cmd(VoiceCommand.Intensity(if (has(K.UP)) +1 else -1, areas, again = areas.isEmpty() && AGAIN.containsMatchIn(text)))
            }
            Intent.SPEED ->
                if (has(K.FASTER) && has(K.SLOWER)) VoiceParse.Clarify("要加快還是放慢？")
                else cmd(VoiceCommand.Speed(faster = has(K.FASTER)))
        }
    }

    private fun zonesOf(hits: List<Hit>): List<Zone> = hits.mapNotNull { h ->
        when (h.k) {
            K.SHOULDER -> Zone.SHOULDER; K.BACK -> Zone.BACK; K.LUMBAR -> Zone.LUMBAR
            K.FOOT -> Zone.LEGS; K.SEAT -> Zone.SEAT
            else -> null
        }
    }.distinct()

    private fun zoneChoices(z: Zone) = "${z.zh}：可以說「${z.zh}按摩」「${z.zh}加強」或「${z.zh}減弱」"

    private fun offer(zones: List<Zone>) = "要放鬆${zones.first().zh}嗎？請說「${zones.first().zh}按摩」"

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
        // A body part makes 「開始…按摩」 about that part: never a full-body start, which would recline.
        val zoned = hits.any { it.k in ZONE_WORDS }
        if (!pauseOrResume) {
            if (has(K.MANUAL) && !zoned) out += Intent.MANUAL
            else if (has(K.FULL_BODY) || (has(K.MASSAGE) && !zoned && (has(K.START) || has(K.ON) || durations(text).isNotEmpty()))) out += Intent.FULL_BODY
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
        private const val NO_ZONE_MINUTES = "部位按摩不能指定時間；請只說部位，例如「腰部按摩」"
        private val ZONE_WORDS = setOf(K.SHOULDER, K.BACK, K.LUMBAR, K.FOOT, K.SEAT)
        private val AGAIN = Regex("再|更|還要")
        private const val NUMERAL = "\\d{1,2}|[一二兩三四五六七八九十]+"
        private val LEVEL_AFTER_WORD =
            Regex("(?:力度|強度|調到|調成|調整到|調整成|改成|改為|變成|設為|設成|設定為|加強到|減弱到)(?:為|到)?($NUMERAL)(?![下半些點])")
        private val LEVEL_WITH_UNIT = Regex("($NUMERAL)(?:級|段|檔)")
        private val LEVEL_ALONE = Regex("^的?(?:力度|強度)?($NUMERAL)(?:級|段|檔)?[了吧啦喔]*$")

        /** What the recogniser is told to expect (Android 13+), so 「腰部」 is not heard as 「要不」. */
        val BIAS_PHRASES = listOf(
            "腰部按摩", "背部按摩", "肩部按摩", "腿部按摩", "足部按摩", "臀部按摩",
            "腰部加強", "腰部減弱", "背部加強", "背部減弱", "肩部加強", "肩部減弱", "足部加強", "足部減弱",
            "足部揉捏", "足部振動", "臀部振動", "關閉足部", "關閉背部",
            "力度", "再強一點", "再輕一點", "太大力", "揉捏", "敲打", "全程滾動", "局部滾動",
            "坐式", "躺平", "回到沙發", "全身按摩", "手動按摩", "暫停", "繼續", "停止", "還剩幾分鐘",
        )

        /** 「腰部力度調到 4」「第三段」「二級」; null when no level is named. May be out of range. */
        fun levelIn(text: String): Int? =
            (LEVEL_AFTER_WORD.find(text) ?: LEVEL_WITH_UNIT.find(text))?.let { number(it.groupValues[1]) }

        private fun levelAfterZone(rest: String): Int? =
            LEVEL_ALONE.find(rest)?.let { number(it.groupValues[1]) }

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
            is VoiceCommand.SetIntensity -> {
                val where = if (c.areas.isEmpty()) "肩、背、腰" else c.areas.joinToString("、") { Names.area(it) }
                "${where}力度調為 ${c.level}"
            }
            is VoiceCommand.Focus -> "開始${c.zone.zh}按摩"
            is VoiceCommand.LegMassage -> when (c.level) {
                Level.OFF -> "關閉足部按摩"
                Level.AUTO -> "開啟足部按摩（自動）"
                Level.HIGH -> "足部按摩調強"
                Level.LOW -> "足部按摩調弱"
            }
            VoiceCommand.BackOff -> "關閉背部滾輪"
            is VoiceCommand.Technique -> "背部手法：${c.kneadTap.zh}"
            is VoiceCommand.Roll -> "背部${c.rolling.zh}滾動"
            is VoiceCommand.Width -> if (c.wider) "滾輪調寬" else "滾輪調窄"
            is VoiceCommand.Query -> when (c.topic) {
                VoiceCommand.Topic.REMAINING -> "查詢剩餘時間"
                VoiceCommand.Topic.STATE -> "查詢沙發狀態"
                VoiceCommand.Topic.INTENSITY -> "查詢目前力度"
                VoiceCommand.Topic.HELP -> "說明可用的指令"
            }
            is VoiceCommand.SetFunction -> when (c.level) {
                Level.OFF -> "關閉${Names.function(c.function)}"
                Level.AUTO -> "開啟${Names.function(c.function)}（自動）"
                Level.HIGH -> "${Names.function(c.function)}調強"
                Level.LOW -> "${Names.function(c.function)}調弱"
            }
        }
    }
}
