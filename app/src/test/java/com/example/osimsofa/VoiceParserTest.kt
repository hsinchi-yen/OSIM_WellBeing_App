package com.example.osimsofa

import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.FootSeat
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.VoiceCommand.Intensity
import com.example.osimsofa.VoiceCommand.MoveTo
import com.example.osimsofa.VoiceCommand.SetFunction
import com.example.osimsofa.VoiceCommand.Speed
import com.example.osimsofa.VoiceCommand.StartFullBody
import com.ibm.icu.text.Transliterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses ICU4J's Han-Latin — the same transliterator the app gets from android.icu on the phone. */
class VoiceParserTest {

    private val icu = Transliterator.getInstance("Han-Latin; Latin-ASCII")
    private val voice = VoiceParser { c -> icu.transliterate(c.toString()) }
    private val exactOnly = VoiceParser()

    private fun cmd(s: String): VoiceCommand? = (voice.parse(s) as? VoiceParse.Command)?.cmd
    private fun runs(s: String, expected: VoiceCommand) = assertEquals("「$s」", expected, cmd(s))
    private fun noAction(s: String) {
        val p = voice.parse(s)
        assertFalse("「$s」 must not run anything, got $p", p is VoiceParse.Command)
    }
    private fun asks(s: String) = assertTrue("「$s」 should ask back, got ${voice.parse(s)}", voice.parse(s) is VoiceParse.Clarify)

    // ---------------------------------------------------------------- names (improve-plan §4.1)

    @Test fun newAndOldPostureNames() {
        listOf("坐式", "坐直", "調成坐姿", "坐起來", "切換到坐式").forEach { runs(it, MoveTo(Posture.UPRIGHT)) }
        listOf("躺平", "放平", "我想躺平").forEach { runs(it, MoveTo(Posture.RECLINE)) }
        listOf("回到沙發", "沙發模式", "回到待機", "沙發", "沙發吧", "調成沙發").forEach { runs(it, MoveTo(Posture.SOFA)) }
    }

    @Test fun aBareSofaInsideASentenceIsNotACommand() {
        noAction("請問沙發怎麼使用")
        noAction("這張沙發很舒服")
        asks("沙發加強")                     // which area? — and never a move to the sofa preset
        assertFalse(cmd("沙發加強") is MoveTo)
    }

    // ---------------------------------------------------------------- full body with a duration

    @Test fun fullBodyWithFifteenOrThirtyMinutes() {
        runs("開始全身按摩 15 分鐘", StartFullBody(15, stated = true))
        runs("開始全身按摩十五分鐘", StartFullBody(15, stated = true))
        runs("開始全身按摩 30 分鐘", StartFullBody(30, stated = true))
        runs("開始全身按摩三十分鐘", StartFullBody(30, stated = true))
        runs("全身按摩 30 分鐘", StartFullBody(30, stated = true))
        runs("開始全身按摩半小時", StartFullBody(30, stated = true))
        runs("開始全身按摩１５分鐘", StartFullBody(15, stated = true))   // full-width digits
    }

    @Test fun fullBodyWithoutADurationUsesFifteenAndSaysSo() {
        runs("開始全身按摩", StartFullBody(15, stated = false))
        runs("開始按摩", StartFullBody(15, stated = false))
        assertTrue(VoiceParser.describe(StartFullBody(15, stated = false)).contains("15 分鐘"))
    }

    @Test fun anUnsupportedOrAmbiguousDurationIsAskedBack() {
        asks("開始全身按摩 20 分鐘")
        asks("開始全身按摩 15 或 30 分鐘")
        asks("開始全身按摩十五還是三十分鐘")
    }

    // ---------------------------------------------------------------- refusals (improve-plan §2 table)

    @Test fun negationsNeverMoveOrStart() {
        listOf("不要躺平", "不要開始全身按摩 30 分鐘", "別坐式", "先不要按摩", "不用回到沙發", "我不想全身按摩").forEach { noAction(it) }
        assertEquals(VoiceParse.Why.NEGATED, (voice.parse("不要躺平") as VoiceParse.Refuse).why)
    }

    @Test fun questionsNeverMoveOrStart() {
        listOf("請問沙發怎麼使用", "沙發怎麼用？", "可以躺平嗎", "為什麼不能坐式", "全身按摩是什麼").forEach { noAction(it) }
        val r = voice.parse("可以躺平嗎") as VoiceParse.Refuse
        assertEquals(VoiceParse.Why.QUESTION, r.why)
        assertTrue(r.message.contains("躺平"))         // tells what to say instead
    }

    @Test fun twoCommandsAtOnceAreAskedBack() {
        asks("坐式然後躺平")
        asks("開始全身按摩然後坐式")
        asks("暫停然後坐式")
    }

    @Test fun zeroGravityDoesNotExistOnThisSofa() {
        val r = voice.parse("零重力")
        assertTrue(r is VoiceParse.Refuse && r.why == VoiceParse.Why.UNSUPPORTED)
    }

    // ---------------------------------------------------------------- stop / pause / resume

    @Test fun stopAlwaysWins() {
        listOf("停止", "停", "取消", "停止全身按摩", "不要動", "別動", "關掉", "結束按摩", "stop").forEach {
            runs(it, VoiceCommand.StopAll)
        }
        assertNull((voice.parse("停止全身按摩") as VoiceParse.Command).note)   // the massage is what is stopped
    }

    @Test fun stopThenSomethingElseOnlyStops() {
        val p = voice.parse("停止後繼續") as VoiceParse.Command
        assertEquals(VoiceCommand.StopAll, p.cmd)
        assertNotNull(p.note)                         // asks for the next command separately
    }

    @Test fun aNegatedStopDoesNothing() = noAction("不要停")

    @Test fun pauseAndResume() {
        listOf("暫停", "先停一下", "暫停全身按摩", "等一下").forEach { runs(it, VoiceCommand.Pause) }
        listOf("繼續", "繼續按摩", "恢復").forEach { runs(it, VoiceCommand.Resume) }
    }

    @Test fun manualStart() {
        runs("開始手動按摩", VoiceCommand.StartManual)
        runs("手動按摩", VoiceCommand.StartManual)
    }

    // ---------------------------------------------------------------- adjustments

    @Test fun footAndSeatFunctionsAreTheirOwnCommands() {
        runs("關閉足部振動", SetFunction(FootSeat.FOOT_VIBRATION, Level.OFF))   // not 停止
        runs("開啟足部揉捏", SetFunction(FootSeat.FOOT_KNEADING, Level.AUTO))
        runs("關閉臀部振動", SetFunction(FootSeat.SEAT_VIBRATION, Level.OFF))
        runs("足部振動加強", SetFunction(FootSeat.FOOT_VIBRATION, Level.HIGH))
        asks("關閉足部")                                                      // kneading or vibration?
    }

    @Test fun intensityAndSpeed() {
        runs("加強", Intensity(+1))
        runs("輕一點", Intensity(-1))
        runs("肩部加強", Intensity(+1, setOf(Area.SHOULDER)))
        runs("背部減弱", Intensity(-1, setOf(Area.BACK)))
        runs("加速", Speed(faster = true))
        runs("慢一點", Speed(faster = false))
    }

    // ---------------------------------------------------------------- by sound

    @Test fun homophonesFromTheRecogniserStillMatch() {
        listOf("做直", "座直", "做式", "座式", "坐是").forEach { runs(it, MoveTo(Posture.UPRIGHT)) }
        runs("湯平", MoveTo(Posture.RECLINE))
        runs("殺發", MoveTo(Posture.SOFA))
        runs("泉身按摩", StartFullBody(15, stated = false))
        runs("站停", VoiceCommand.Pause)
        runs("記續", VoiceCommand.Resume)
        runs("首動按摩", VoiceCommand.StartManual)
        runs("家強", Intensity(+1))
        runs("簡速", Speed(faster = false))
    }

    @Test fun taiwaneseFuzzySoundsMatch() {
        runs("坐子", MoveTo(Posture.UPRIGHT))     // zhi → zi
        runs("薩發", MoveTo(Posture.SOFA))        // sha → sa
        runs("談品", MoveTo(Posture.RECLINE))     // tang ping → tan pin
    }

    @Test fun matchesBySoundAreMarkedAsSuch() {
        assertTrue((voice.parse("坐直") as VoiceParse.Command).exact)
        assertFalse((voice.parse("做直") as VoiceParse.Command).exact)
    }

    @Test fun aOneSyllableWordNeedsAShortUtterance() {
        runs("亭", VoiceCommand.StopAll)          // a lone 「停」 misheard
        noAction("我想聽音樂")                     // 聽 (ting) must not mean 停
    }

    @Test fun withoutPinyinOnlyExactWordsMatch() {
        assertEquals(MoveTo(Posture.UPRIGHT), (exactOnly.parse("坐直") as VoiceParse.Command).cmd)
        assertEquals(VoiceParse.Unknown, exactOnly.parse("做直"))
    }

    // ---------------------------------------------------------------- several candidates (V02)

    @Test fun candidatesThatDisagreeOnTheDurationAreAskedBack() {
        assertTrue(voice.decide(listOf("開始全身按摩15分鐘", "開始全身按摩30分鐘")) is VoiceDecision.Ask)
    }

    @Test fun aStatedDurationFillsInForACandidateWithout() {
        val d = voice.decide(listOf("開始全身按摩", "開始全身按摩30分鐘")) as VoiceDecision.Run
        assertEquals(StartFullBody(30, stated = true), d.cmd)
    }

    @Test fun aBadDurationIsNotRescuedByACandidateThatLostIt() {
        assertTrue(voice.decide(listOf("開始全身按摩20分鐘", "開始全身按摩")) is VoiceDecision.Ask)
    }

    @Test fun aNegatedCandidateBlocksTheCommand() {
        assertTrue(voice.decide(listOf("躺平", "不要躺平")) is VoiceDecision.Refuse)
    }

    @Test fun anExactCandidateBeatsASoundMatch() {
        val d = voice.decide(listOf("做直", "坐直")) as VoiceDecision.Run
        assertEquals(MoveTo(Posture.UPRIGHT), d.cmd)
        assertTrue(d.exact)
        assertEquals("坐直", d.heard)
    }

    @Test fun soundMatchesAreOnlyTakenFromTheBestCandidate() {
        assertTrue(voice.decide(listOf("今天天氣很好", "做直")) is VoiceDecision.Refuse)
    }

    @Test fun stopInAnyCandidateWins() {
        assertEquals(VoiceCommand.StopAll, (voice.decide(listOf("躺平", "停")) as VoiceDecision.Run).cmd)
    }

    @Test fun aLowConfidenceSoundMatchIsAskedBack() {
        assertTrue(voice.decide(listOf("做直"), floatArrayOf(0.2f)) is VoiceDecision.Ask)
        assertTrue(voice.decide(listOf("做直"), floatArrayOf(0.9f)) is VoiceDecision.Run)
        assertTrue(voice.decide(listOf("坐直"), floatArrayOf(0.2f)) is VoiceDecision.Run)   // exact words don't need it
    }

    @Test fun nothingHeard() {
        assertTrue(voice.decide(emptyList()) is VoiceDecision.Refuse)
        assertTrue(voice.decide(listOf(" ")) is VoiceDecision.Refuse)
    }

    // ---------------------------------------------------------------- helpers

    @Test fun chineseNumbers() {
        assertEquals(15, VoiceParser.chineseNumber("十五"))
        assertEquals(30, VoiceParser.chineseNumber("三十"))
        assertEquals(5, VoiceParser.chineseNumber("五"))
        assertEquals(25, VoiceParser.chineseNumber("二十五"))
        assertEquals(10, VoiceParser.chineseNumber("十"))
        assertNull(VoiceParser.chineseNumber("一百"))
    }

    @Test fun fuzzyMergesTheUsualPairs() {
        assertEquals("zi", VoiceParser.fuzzy("zhi"))
        assertEquals("sa", VoiceParser.fuzzy("sha"))
        assertEquals("ci", VoiceParser.fuzzy("chi"))
        assertEquals("tin", VoiceParser.fuzzy("ting"))
        assertEquals("tan", VoiceParser.fuzzy("tang"))
        assertEquals("sen", VoiceParser.fuzzy("sheng"))
        assertEquals("lu", VoiceParser.fuzzy("nu"))
    }
}
