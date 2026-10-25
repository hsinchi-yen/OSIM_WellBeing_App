package com.example.osimsofa

import com.ibm.icu.text.Transliterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Uses ICU4J's Han-Latin — the same transliterator the app gets from android.icu on the phone. */
class VoiceGrammarTest {

    private val icu = Transliterator.getInstance("Han-Latin; Latin-ASCII")
    private val voice = VoiceGrammar { c -> icu.transliterate(c.toString()) }
    private val exactOnly = VoiceGrammar()

    @Test fun stopWordsWinOverWhatTheyStop() {
        assertEquals(VoiceAction.PAUSE, voice.match("暫停"))
        assertEquals(VoiceAction.PAUSE, voice.match("先停一下"))
        assertEquals(VoiceAction.STOP, voice.match("停"))
        assertEquals(VoiceAction.STOP, voice.match("停止全身按摩"))
    }

    @Test fun programsAndPostures() {
        assertEquals(VoiceAction.FULL_BODY, voice.match("開始全身按摩"))
        assertEquals(VoiceAction.MANUAL_START, voice.match("開始手動按摩"))
        assertEquals(VoiceAction.RECLINE, voice.match("躺平"))
        assertEquals(VoiceAction.UPRIGHT, voice.match("坐直"))
        assertEquals(VoiceAction.SOFA, voice.match("回到沙發"))
    }

    @Test fun homophonesFromTheRecogniserStillMatch() {
        assertEquals(VoiceAction.UPRIGHT, voice.match("做直"))
        assertEquals(VoiceAction.UPRIGHT, voice.match("座直"))
        assertEquals(VoiceAction.RECLINE, voice.match("湯平"))
        assertEquals(VoiceAction.SOFA, voice.match("殺發"))
        assertEquals(VoiceAction.FULL_BODY, voice.match("泉身按摩"))
        assertEquals(VoiceAction.PAUSE, voice.match("站停"))
        assertEquals(VoiceAction.RESUME, voice.match("記續"))
        assertEquals(VoiceAction.MANUAL_START, voice.match("首動按摩"))
        assertEquals(VoiceAction.INTENSITY_UP, voice.match("家強"))
        assertEquals(VoiceAction.SPEED_DOWN, voice.match("簡速"))
    }

    @Test fun taiwaneseFuzzySoundsMatch() {
        assertEquals(VoiceAction.UPRIGHT, voice.match("坐子"))     // zhi → zi
        assertEquals(VoiceAction.SOFA, voice.match("薩發"))       // sha → sa
        assertEquals(VoiceAction.RECLINE, voice.match("談品"))     // tang ping → tan pin
    }

    @Test fun aOneSyllableWordNeedsAShortUtterance() {
        assertEquals(VoiceAction.STOP, voice.match("亭"))         // a lone 「停」 misheard
        assertNull(voice.match("我想聽音樂"))                      // 聽 (ting) must not mean 停
    }

    @Test fun withoutPinyinOnlyExactWordsMatch() {
        assertEquals(VoiceAction.UPRIGHT, exactOnly.match("坐直"))
        assertNull(exactOnly.match("做直"))
    }

    @Test fun zeroGravityIsNotAPosition() {
        assertNull(voice.match("零重力"))
    }

    @Test fun fuzzyMergesTheUsualPairs() {
        assertEquals("zi", VoiceGrammar.fuzzy("zhi"))
        assertEquals("sa", VoiceGrammar.fuzzy("sha"))
        assertEquals("ci", VoiceGrammar.fuzzy("chi"))
        assertEquals("tin", VoiceGrammar.fuzzy("ting"))
        assertEquals("tan", VoiceGrammar.fuzzy("tang"))
        assertEquals("sen", VoiceGrammar.fuzzy("sheng"))
        assertEquals("lu", VoiceGrammar.fuzzy("nu"))
    }
}
