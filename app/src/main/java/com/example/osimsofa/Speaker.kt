package com.example.osimsofa

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Spoken replies in zh-TW (improve-plan V04): the language is checked, not assumed — a phone whose
 * TTS has no Chinese voice says so on screen instead of failing silently.
 */
class Speaker(context: Context, private val log: (String) -> Unit) : TextToSpeech.OnInitListener {

    /** Why replies are not spoken, for the screen; null when they are. */
    var problem by mutableStateOf<String?>(null); private set
    private var ready = false
    private var n = 0
    private val early = ArrayList<String>()
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) { problem = "文字轉語音無法啟動，語音回覆已關閉"; log("TTS 初始化失敗 $status"); return }
        val usable = CANDIDATES.firstOrNull { loc ->
            val r = tts.setLanguage(loc)
            r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (usable == null) { problem = "此手機的文字轉語音沒有中文語音，語音回覆已關閉"; log("TTS 沒有中文語音"); return }
        if (usable != CANDIDATES.first()) log("TTS 沒有 zh-TW，改用 $usable")
        ready = true
        early.forEach { say(it) }
        early.clear()
    }

    /** [interrupt]: replace what is being said (stop, faults) instead of queueing after it. */
    fun say(text: String, interrupt: Boolean = false) {
        if (!ready) { if (problem == null && early.size < 2) early += text; return }
        tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "say${++n}")
    }

    /** Before listening, so the phone does not hear itself. */
    fun stop() { if (ready) tts.stop() }

    fun shutdown() = tts.shutdown()

    private companion object {
        val CANDIDATES = listOf(Locale("zh", "TW"), Locale.forLanguageTag("zh-Hant"), Locale.CHINESE)
    }
}
