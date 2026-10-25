package com.example.osimsofa

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.osimsofa.OsimProtocol.BackSpeed
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.ui.SofaActions
import com.example.osimsofa.ui.SofaApp
import java.util.Locale

/**
 * uDiva 沙發 — consumer app host.
 * Flow: NFC tap (or manual scan) → SofaConnection auto-connects → SofaController drives the sofa.
 * Voice (zh-TW) and TTS guidance ride on the same controller.
 */
class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var conn: SofaConnection
    private lateinit var controller: SofaController
    private val main = Handler(Looper.getMainLooper())
    private var nfc: NfcAdapter? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var voiceHint by mutableStateOf<String?>(null)
    private var pendingAutoConnect = false
    private val voice by lazy { VoiceGrammar(platformPinyin()) }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = conn.requiredPermissions().all { res[it] == true || checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (ok && pendingAutoConnect) { pendingAutoConnect = false; conn.startAutoConnect() }
        else if (!ok) toast("需要藍牙權限才能連線沙發")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        conn = SofaConnection(applicationContext)
        controller = SofaController(conn) { speak(it) }
        conn.log("VOICE 同音比對自我測試：做直→${voice.match("做直")} 站停→${voice.match("站停")} 殺發→${voice.match("殺發")}")
        nfc = NfcAdapter.getDefaultAdapter(this)
        tts = TextToSpeech(this, this)

        val actions = SofaActions(
            posture = { p, pressed ->
                if (!conn.linkReady) { if (pressed) toast("尚未連線沙發") }
                else { if (pressed) speak(p.zh); controller.posture(p, pressed) }
            },
            fullBody = { pressed -> if (conn.linkReady) controller.fullBody(pressed) else if (pressed) toast("尚未連線沙發") },
            fullBodyStartAnyway = { controller.fullBodyStartAnyway() },
            setWant30 = { controller.want30 = it },
            startManual = { controller.startManual(); speak("開始手動按摩") },
            changeManual = { controller.changeManual(it) },
            pause = { controller.pause(); speak("暫停") },
            resume = { controller.resume(); speak("繼續") },
            stop = { controller.stopAll(); speak("停止") },
            scan = { ensurePermsThen { conn.startAutoConnect() } },
            pick = { addr -> ensurePermsThen { conn.connectCandidate(addr) } },
            disconnect = { conn.disconnect() },
            voice = { startVoice() },
            spotify = { launchSpotify() },
            sendRaw = { s -> OsimProtocol.parseHex(s)?.let { conn.sendRaw(it) } ?: toast("十六進位格式錯誤") },
        )
        setContent { SofaApp(conn, controller, voiceHint, actions) }

        handleNfcIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Catch a tap while the app is open (any NDEF tag), so "靠近感應" works from inside the app too.
        try {
            val pi = PendingIntent.getActivity(
                this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            )
            nfc?.enableForegroundDispatch(this, pi, null, null)
        } catch (e: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { nfc?.disableForegroundDispatch(this) } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        conn.disconnect(silent = true)
        recognizer?.destroy(); tts?.shutdown()
    }

    private fun ensurePermsThen(block: () -> Unit) {
        if (conn.hasPermissions()) block()
        else { pendingAutoConnect = true; permLauncher.launch(conn.requiredPermissions()) }
    }

    // ---------------- NFC → auto connect ----------------
    private fun handleNfcIntent(intent: Intent?) {
        intent ?: return
        val isNfc = intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED || intent.action == NfcAdapter.ACTION_TECH_DISCOVERED
        if (!isNfc && intent.data == null) return
        var serial: String? = null
        intent.data?.let { uri -> conn.log("NFC URL: $uri"); serial = OsimProtocol.parseSerialFromNfcUri(uri.toString()) }
        @Suppress("DEPRECATION")
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        raw?.forEach { p ->
            (p as? NdefMessage)?.records?.forEach { rec ->
                val payload = String(rec.payload)
                conn.log("NDEF: $payload")
                if (serial == null) serial = OsimProtocol.parseSerialFromNfcUri(payload)
            }
        }
        if (serial != null) { conn.serial = serial; conn.log("感應到沙發序號：$serial") }
        else conn.log("感應到標籤，未解析出序號；仍嘗試自動連線")
        speak("已感應到沙發，正在連線")
        ensurePermsThen { conn.startAutoConnect() }
    }

    // ---------------- voice (zh-TW) ----------------
    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale.TRADITIONAL_CHINESE
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sofa")
    }

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { toast("此手機不支援語音辨識"); return }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) { voiceHint = "請說出指令…" }
                    override fun onResults(r: Bundle?) {
                        val heard = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        // The recogniser's alternatives often hold the right homophone: take the first that matches.
                        val hit = heard.firstNotNullOfOrNull { t -> voice.match(t)?.let { t to it } }
                        val text = hit?.first ?: heard.firstOrNull() ?: ""
                        conn.log("VOICE 聽到 ${heard.joinToString("／")} → ${hit?.second ?: "無對應"}")
                        if (hit == null) { voiceHint = "聽到「$text」，但沒有對應的指令"; speak("沒有對應的指令") }
                        else { voiceHint = "聽到「$text」→ ${zh(hit.second)}"; speak(zh(hit.second)); onVoice(hit.second) }
                    }
                    override fun onError(e: Int) { voiceHint = "語音辨識失敗（$e），請再試一次" }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(p: Bundle?) {}
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
            }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-TW")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        voiceHint = "準備聆聽…"
        recognizer?.startListening(i)
    }

    /** Toneless pinyin from the platform ICU (android.icu, API 24+) for homophone matching. */
    private fun platformPinyin(): VoiceGrammar.Pinyin? = try {
        val t = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")
        VoiceGrammar.Pinyin { c -> t.transliterate(c.toString()) }
    } catch (e: Exception) { conn.log("VOICE 無法載入拼音轉換，只比對原字：$e"); null }

    private fun onVoice(action: VoiceAction) {
        if (!conn.linkReady) { toast("尚未連線沙發"); return }
        val m = controller.manual
        fun intensity(d: Int) = m.copy(
            shoulder = (m.shoulder + d).coerceIn(OsimProtocol.INTENSITY),
            back = (m.back + d).coerceIn(OsimProtocol.INTENSITY),
            lumbar = (m.lumbar + d).coerceIn(OsimProtocol.INTENSITY),
        )
        when (action) {
            // No finger to lift: a voice move is bounded to 8 s.
            VoiceAction.UPRIGHT -> controller.posture(Posture.UPRIGHT, true, VOICE_MOVE_MS)
            VoiceAction.RECLINE -> controller.posture(Posture.RECLINE, true, VOICE_MOVE_MS)
            VoiceAction.SOFA -> controller.posture(Posture.SOFA, true, VOICE_MOVE_MS)
            VoiceAction.FULL_BODY -> controller.fullBodyByVoice()
            VoiceAction.MANUAL_START -> controller.startManual()
            VoiceAction.INTENSITY_UP -> controller.changeManual(intensity(+1))
            VoiceAction.INTENSITY_DOWN -> controller.changeManual(intensity(-1))
            VoiceAction.SPEED_UP -> controller.changeManual(m.copy(speed = BackSpeed.HIGH))
            VoiceAction.SPEED_DOWN -> controller.changeManual(m.copy(speed = BackSpeed.LOW))
            VoiceAction.PAUSE -> controller.pause()
            VoiceAction.RESUME -> controller.resume()
            VoiceAction.STOP -> controller.stopAll()
        }
    }

    /** Short zh-TW confirmation per action, spoken back and shown. */
    private fun zh(a: VoiceAction): String = when (a) {
        VoiceAction.UPRIGHT -> "坐直"; VoiceAction.RECLINE -> "躺平"; VoiceAction.SOFA -> "沙發"
        VoiceAction.FULL_BODY -> "開始全身按摩"; VoiceAction.MANUAL_START -> "開始手動按摩"
        VoiceAction.INTENSITY_UP -> "力度加強"; VoiceAction.INTENSITY_DOWN -> "力度減弱"
        VoiceAction.SPEED_UP -> "加速"; VoiceAction.SPEED_DOWN -> "減速"
        VoiceAction.PAUSE -> "暫停"; VoiceAction.RESUME -> "繼續"; VoiceAction.STOP -> "停止"
    }

    // ---------------- music ----------------
    /** Open the Spotify app itself; the Play Store only if Spotify really can't be started. */
    private fun launchSpotify() {
        val launch = packageManager.getLaunchIntentForPackage(SPOTIFY)
            ?: Intent(Intent.ACTION_VIEW, android.net.Uri.parse("spotify:")).setPackage(SPOTIFY)
        try {
            startActivity(launch)
            toast("請在手機藍牙中將沙發設為音訊輸出")
        } catch (e: ActivityNotFoundException) {
            toast("尚未安裝 Spotify")
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$SPOTIFY"))) } catch (e: Exception) {}
        }
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private companion object {
        const val VOICE_MOVE_MS = 8_000L
        const val SPOTIFY = "com.spotify.music"
    }
}
