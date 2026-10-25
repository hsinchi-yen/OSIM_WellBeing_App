package com.example.osimsofa

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * One tap = one recognition (improve-plan V03, §5.4). SpeechRecognizer is not meant for always-on
 * listening, so there is no wake word and no endless restart; Stop on screen is always there.
 *
 * Idle → Permission → Listening → Processing → Done | Failed, cancellable at any point. Each
 * recognition has a job number, so a result that arrives after a cancel is dropped.
 */
class VoiceInput(
    private val context: Context,
    private val onResult: (candidates: List<String>, confidence: FloatArray?) -> Unit,
) {
    enum class State { IDLE, PERMISSION, LISTENING, PROCESSING, DONE, FAILED }

    var state by mutableStateOf(State.IDLE); private set
    /** What the panel says: prompt, heard text and outcome, or the reason it failed. */
    var message by mutableStateOf<String?>(null); private set
    var partial by mutableStateOf<String?>(null); private set

    private var recognizer: SpeechRecognizer? = null
    private var job = 0

    val busy: Boolean get() = state == State.PERMISSION || state == State.LISTENING || state == State.PROCESSING

    fun awaitPermission() { state = State.PERMISSION; message = "請允許使用麥克風"; partial = null }

    fun listen() {
        if (state == State.LISTENING || state == State.PROCESSING) return      // never a second job
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { fail("此手機沒有可用的語音辨識服務"); return }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        val my = ++job
        r.setRecognitionListener(listener(my))
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, VoiceParser.MAX_CANDIDATES)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        state = State.LISTENING; message = "準備聆聽…"; partial = null
        r.startListening(i)
    }

    fun cancel() {
        job++
        if (state == State.LISTENING || state == State.PROCESSING) recognizer?.cancel()
        state = State.IDLE; message = null; partial = null
    }

    /** The outcome of the recognition, shown until the panel is closed or the next one starts. */
    fun done(text: String) { state = State.DONE; message = text; partial = null }

    fun fail(text: String) { state = State.FAILED; message = text; partial = null }

    fun destroy() { job++; recognizer?.destroy(); recognizer = null; state = State.IDLE }

    private fun listener(my: Int) = object : RecognitionListener {
        override fun onReadyForSpeech(p: Bundle?) { if (my == job) message = "請說出指令…" }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(v: Float) {}
        override fun onBufferReceived(b: ByteArray?) {}
        override fun onEndOfSpeech() { if (my == job && state == State.LISTENING) { state = State.PROCESSING; message = "辨識中…" } }
        override fun onPartialResults(p: Bundle?) {
            if (my != job) return
            partial = p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
        }
        override fun onResults(r: Bundle?) {
            if (my != job) return
            val heard = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val conf = r?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            state = State.PROCESSING
            onResult(heard, conf)
        }
        override fun onError(e: Int) { if (my == job) fail(errorText(e)) }
        override fun onEvent(t: Int, p: Bundle?) {}
    }

    companion object {
        /** SpeechRecognizer error codes (numbers, so older API levels compile them too). */
        fun errorText(e: Int): String = when (e) {
            1, 2, 4 -> "語音辨識需要網路連線，請確認網路後再試一次"   // NETWORK_TIMEOUT, NETWORK, SERVER
            3 -> "麥克風無法使用，請確認沒有其他 App 正在錄音"         // AUDIO
            5 -> "語音辨識中斷，請再試一次"                           // CLIENT
            6 -> "沒有聽到聲音，請靠近手機再說一次"                   // SPEECH_TIMEOUT
            7 -> "沒有聽清楚，請再說一次"                             // NO_MATCH
            8 -> "語音辨識忙碌中，請稍後再試"                         // RECOGNIZER_BUSY
            9 -> "需要麥克風權限才能使用語音控制"                     // INSUFFICIENT_PERMISSIONS
            10, 11 -> "語音服務暫時無法使用，請稍後再試"               // TOO_MANY_REQUESTS, SERVER_DISCONNECTED
            12, 13 -> "此手機的語音辨識不支援繁體中文（台灣）"         // LANGUAGE_NOT_SUPPORTED, LANGUAGE_UNAVAILABLE
            else -> "語音辨識失敗（代碼 $e），請再試一次"
        }
    }
}
