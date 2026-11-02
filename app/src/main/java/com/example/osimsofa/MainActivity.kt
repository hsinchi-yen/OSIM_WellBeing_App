package com.example.osimsofa

import android.Manifest
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.osimsofa.ui.SofaActions
import com.example.osimsofa.ui.SofaApp
import com.example.osimsofa.ui.UiEnv

/**
 * Hosts the screen, the permissions, NFC and voice. The sofa session itself lives in
 * [SofaApplication], so this Activity can be recreated (rotation) without touching the sofa.
 */
class MainActivity : ComponentActivity() {

    private val app get() = application as SofaApplication
    private val conn get() = app.conn
    private val controller get() = app.controller
    private val prefs by lazy { getSharedPreferences("sofa", MODE_PRIVATE) }
    private lateinit var voice: VoiceInput
    private val parser by lazy { VoiceParser(platformPinyin()) }
    private val voiceMemory = VoiceMemory()
    private var nfc: NfcAdapter? = null
    private var env by mutableStateOf(UiEnv())

    private var afterBtPermission: (() -> Unit)? = null
    private val btPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshEnv()
        if (conn.hasPermissions()) { afterBtPermission?.invoke(); askNotificationsOnce() }
        else toast("需要藍牙權限才能連線沙發")
        afterBtPermission = null
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) voice.listen()
        else voice.fail(
            if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) "需要麥克風權限才能使用語音控制"
            else "麥克風權限已被拒絕：請到「設定 › 應用程式 › uDiva 沙發 › 權限」開啟")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val bluetoothEnable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshEnv() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        voice = VoiceInput(this, ::onVoiceResult, ::onVoicePartial)
        nfc = NfcAdapter.getDefaultAdapter(this)
        refreshEnv()

        val actions = SofaActions(
            tapPosture = { controller.moveTo(it) },
            holdPosture = { p, down -> controller.hold(p, down) },
            stopMove = { controller.stopMove() },
            continueMove = { controller.continueMove() },
            returnToOrigin = { controller.returnToOrigin() },
            dismissInterrupted = { controller.dismissInterrupted() },
            startFullBody = { controller.startFullBody() },
            setWant30 = { controller.want30 = it },
            startManual = { controller.startManual() },
            changeManual = { controller.changeManual(it) },
            setIntensity = { area, level -> controller.setIntensity(area, level) },
            setFunction = { f, level -> controller.setFunction(f, level) },
            focusZone = { controller.focus(it) },
            pauseOrResume = { controller.pauseOrResume() },
            stop = { voice.cancel(); controller.stopAll() },
            scan = { withBluetooth { conn.startAutoConnect() } },
            cancelConnect = { conn.cancel() },
            pick = { addr -> withBluetooth { conn.connectCandidate(addr) } },
            disconnect = { controller.disconnect() },
            voice = { startVoice() },
            voiceCancel = { voice.cancel() },
            spotify = { launchSpotify() },
            enableBluetooth = { enableBluetooth() },
            grantPermissions = { btPermissions.launch(conn.requiredPermissions()) },
            openAppSettings = { openAppSettings() },
            copyDiagnostics = { copyDiagnostics() },
            setTranscripts = { on -> prefs.edit().putBoolean(KEY_TRANSCRIPTS, on).apply(); refreshEnv() },
            sendRaw = if (BuildConfig.DEBUG) { s -> OsimProtocol.parseHex(s)?.let { conn.sendRaw(it) } ?: toast("十六進位格式錯誤") } else null,
        )
        setContent { SofaApp(conn, controller, voice, env, actions) }

        if (savedInstanceState == null) {
            conn.log("VOICE 同音比對自我測試：做直→${parser.parse("做直")} 站停→${parser.parse("站停")} 殺發→${parser.parse("殺發")}")
            if (BuildConfig.DEBUG) intent.getStringExtra("demo")?.let { DemoStates.of(it) }?.let { conn.debugDemo(it); return }
            // Opening the app does not connect by itself (user decision 2026-10-25): only an NFC tap,
            // the 搜尋 button, or coming back from another app while connected (the system may have
            // ended the app meanwhile — reconnectAfterSwitch covers that cold start).
            val nfcTap = handleNfcIntent(intent)
            if (!nfcTap && controller.reconnectAfterSwitch() && conn.phase == SofaConnection.Phase.IDLE &&
                conn.hasPermissions() && conn.bluetoothOn) {
                conn.log("從其他 App 切回來，重新連線沙發")
                conn.startAutoConnect()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (debugSay(intent)) return
        handleNfcIntent(intent)
    }

    /**
     * Debug builds, demo mode only: run text through the voice path as if it had been heard, to
     * check it on an emulator (no microphone). Demo mode keeps the link closed, so nothing can reach
     * a sofa; never widen this to a real connection — movement is not triggered from a PC.
     *
     *     adb shell am start -n com.example.osimsofa/.MainActivity --es say 腰部按摩
     */
    private fun debugSay(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG || !conn.demo) return false
        val said = intent?.getStringExtra("say") ?: return false
        voiceEpoch = controller.stopEpoch
        onVoiceResult(listOf(said), null)
        return true
    }

    override fun onResume() {
        super.onResume()
        refreshEnv()
        // Catch a tap while the app is open (any NDEF tag), so "靠近感應" works from inside the app too.
        try {
            val pi = PendingIntent.getActivity(this, 0, Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            nfc?.enableForegroundDispatch(this, pi, null, null)
        } catch (e: Exception) {}
    }

    override fun onPause() {
        super.onPause()
        try { nfc?.disableForegroundDispatch(this) } catch (e: Exception) {}
    }

    /** Not visible any more: no microphone in the background (improve-plan §7.2). */
    override fun onStop() {
        super.onStop()
        voice.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        voice.destroy()
    }

    private fun refreshEnv() {
        env = UiEnv(
            hasNfc = nfc != null,
            nfcOn = nfc?.isEnabled == true,
            permissionsOk = conn.hasPermissions(),
            bluetoothOn = conn.bluetoothOn,
            ttsProblem = app.speaker.problem,
            transcripts = prefs.getBoolean(KEY_TRANSCRIPTS, false),
            debug = BuildConfig.DEBUG,
            version = BuildConfig.VERSION_NAME,
        )
    }

    private fun withBluetooth(block: () -> Unit) {
        if (conn.hasPermissions()) block()
        else { afterBtPermission = block; btPermissions.launch(conn.requiredPermissions()) }
    }

    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun enableBluetooth() {
        if (!conn.hasPermissions()) { btPermissions.launch(conn.requiredPermissions()); return }
        try { bluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
        catch (e: Exception) { toast("請從系統設定開啟藍牙") }
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        catch (e: ActivityNotFoundException) { toast("無法開啟設定") }
    }

    // ---------------- NFC → connect to exactly that sofa ----------------
    /** True if [intent] was an NFC tap (and a connection was started). */
    private fun handleNfcIntent(intent: Intent?): Boolean {
        intent ?: return false
        val isNfc = intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TAG_DISCOVERED || intent.action == NfcAdapter.ACTION_TECH_DISCOVERED
        if (!isNfc && intent.data == null) return false
        var serial: String? = null
        intent.data?.let { uri -> serial = OsimProtocol.parseSerialFromNfcUri(uri.toString()) }
        @Suppress("DEPRECATION")
        val raw = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
        raw?.forEach { p ->
            (p as? NdefMessage)?.records?.forEach { rec ->
                if (serial == null) serial = OsimProtocol.parseSerialFromNfcUri(String(rec.payload))
            }
        }
        if (serial != null) { conn.serial = serial; conn.log("感應到沙發序號：${SofaConnection.maskSerial(serial!!)}") }
        else conn.log("感應到標籤，未解析出序號；改為一般搜尋")
        if (conn.operable && serial != null && conn.deviceName?.endsWith(serial!!) == true) return true
        app.speaker.say("已感應到沙發，正在連線")
        withBluetooth { conn.startAutoConnect() }
        return true
    }

    // ---------------- voice (zh-TW) ----------------
    /** The controller's Stop count when this recognition began: a Stop since then (e.g. from the
     *  notification while still listening) cancels what was being said. */
    private var voiceEpoch = -1

    private fun startVoice() {
        if (voice.state == VoiceInput.State.LISTENING || voice.state == VoiceInput.State.PROCESSING) return
        voiceEpoch = controller.stopEpoch
        app.speaker.stop()                     // don't let the phone hear its own reply
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voice.awaitPermission()
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        voice.listen()
    }

    /** 「停止」 in the middle of a sentence: the sofa need not wait for the sentence to end. */
    private fun onVoicePartial(text: String) {
        if (controller.stopEpoch != voiceEpoch || !parser.earlyStop(text)) return
        conn.log("VOICE 聆聽中聽到停止")
        voice.finishEarly("聽到「$text」\n理解為：${VoiceParser.describe(VoiceCommand.StopAll)}")
        controller.execute(VoiceCommand.StopAll)
        voiceMemory.remember(VoiceCommand.StopAll, SystemClock.uptimeMillis())
    }

    private fun onVoiceResult(candidates: List<String>, confidence: FloatArray?) {
        if (controller.stopEpoch != voiceEpoch) {
            conn.log("VOICE 聆聽期間按了停止，這次不執行")
            voice.fail("聆聽時按了「停止」，這次的語音指令不執行")
            return
        }
        val d = parser.decide(candidates, confidence)
        if (prefs.getBoolean(KEY_TRANSCRIPTS, false)) conn.log("VOICE 聽到 ${candidates.joinToString("／")} → $d")
        else conn.log("VOICE 決定：${d::class.simpleName}" +
            if (d is VoiceDecision.Run) " ${VoiceParser.describe(voiceMemory.resolve(d.cmd, SystemClock.uptimeMillis()))}" else "")
        when (d) {
            is VoiceDecision.Run -> {
                val now = SystemClock.uptimeMillis()
                val cmd = voiceMemory.resolve(d.cmd, now)
                voice.done("聽到「${d.heard}」\n理解為：${VoiceParser.describe(cmd)}")
                controller.execute(cmd)
                voiceMemory.remember(cmd, now)
                d.note?.let { app.speaker.say(it) }
            }
            is VoiceDecision.Ask -> { voice.fail("聽到「${d.heard}」\n${d.message}"); app.speaker.say(d.message) }
            is VoiceDecision.Refuse -> {
                voice.fail(if (d.heard.isEmpty()) d.message else "聽到「${d.heard}」\n${d.message}")
                app.speaker.say(d.message)
            }
        }
    }

    /** Toneless pinyin from the platform ICU for homophone matching. android.icu's Transliterator
     *  exists from Android 10 (API 29); below that only the exact words match. */
    private fun platformPinyin(): VoiceParser.Pinyin? {
        if (Build.VERSION.SDK_INT < 29) { conn.log("VOICE Android ${Build.VERSION.RELEASE} 沒有拼音轉換，只比對原字"); return null }
        return try {
            val t = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")
            VoiceParser.Pinyin { c -> t.transliterate(c.toString()) }
        } catch (e: Exception) { conn.log("VOICE 無法載入拼音轉換，只比對原字：$e"); null }
    }

    // ---------------- music ----------------
    /** Open the Spotify app itself; the Play Store only if Spotify really can't be started.
     *  Leaving the app runs the return to the sofa (improve-plan §7.3) — the button says so. */
    private fun launchSpotify() {
        val launch = packageManager.getLaunchIntentForPackage(SPOTIFY)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("spotify:")).setPackage(SPOTIFY)
        try {
            startActivity(launch)
        } catch (e: ActivityNotFoundException) {
            toast("尚未安裝 Spotify")
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SPOTIFY"))) } catch (e: Exception) {}
        }
    }

    // ---------------- diagnostics ----------------
    private fun copyDiagnostics() {
        val text = Redact.export(conn.logLines.toList(), keepTranscripts = env.transcripts,
            header = "uDiva 沙發 ${BuildConfig.VERSION_NAME}（${Build.MODEL}，Android ${Build.VERSION.RELEASE}）")
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("uDiva 診斷紀錄", text))
        toast("已複製診斷紀錄（序號與位址已遮罩）")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private companion object {
        const val SPOTIFY = "com.spotify.music"
        const val KEY_TRANSCRIPTS = "log_transcripts"
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
