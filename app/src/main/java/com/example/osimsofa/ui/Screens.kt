package com.example.osimsofa.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AirlineSeatReclineExtra
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.osimsofa.ControlPolicy
import com.example.osimsofa.FullBodySession.Stage
import com.example.osimsofa.MoveToTarget
import com.example.osimsofa.Names
import com.example.osimsofa.OsimProtocol
import com.example.osimsofa.OsimProtocol.AutoProfile
import com.example.osimsofa.OsimProtocol.AutoProgram
import com.example.osimsofa.OsimProtocol.BackSpeed
import com.example.osimsofa.OsimProtocol.KneadTap
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.ManualSettings
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.OsimProtocol.RollerWidth
import com.example.osimsofa.OsimProtocol.Rolling
import com.example.osimsofa.SofaConnection
import com.example.osimsofa.SofaConnection.Phase
import com.example.osimsofa.SofaController
import com.example.osimsofa.SofaStatus
import com.example.osimsofa.VoiceInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SofaActions(
    val tapPosture: (Posture) -> Unit,
    val holdPosture: (Posture, Boolean) -> Unit,   // press-and-hold: true on long press, false on release
    val startFullBody: () -> Unit,
    val setWant30: (Boolean) -> Unit,
    val startManual: () -> Unit,
    val changeManual: (ManualSettings) -> Unit,
    val pauseOrResume: () -> Unit,
    val stop: () -> Unit,
    val scan: () -> Unit,
    val cancelConnect: () -> Unit,
    val pick: (String) -> Unit,
    val disconnect: () -> Unit,
    val voice: () -> Unit,
    val voiceCancel: () -> Unit,
    val spotify: () -> Unit,
    val enableBluetooth: () -> Unit,
    val grantPermissions: () -> Unit,
    val openAppSettings: () -> Unit,
    val dismissReturnProblem: () -> Unit,
    val copyDiagnostics: () -> Unit,
    val setTranscripts: (Boolean) -> Unit,
    /** Debug builds only: write raw bytes. */
    val sendRaw: ((String) -> Unit)?,
)

/** What the screen needs to know about the phone, refreshed by the Activity on resume. */
data class UiEnv(
    val hasNfc: Boolean = false,
    val nfcOn: Boolean = false,
    val permissionsOk: Boolean = true,
    val bluetoothOn: Boolean = true,
    val ttsProblem: String? = null,
    val transcripts: Boolean = false,
    val debug: Boolean = false,
    val version: String = "",
)

private enum class Page { MAIN, GUIDE, DIAGNOSTICS }

@Composable
fun SofaApp(conn: SofaConnection, ctrl: SofaController, voice: VoiceInput, env: UiEnv, actions: SofaActions) {
    SofaTheme {
        var page by rememberSaveable { mutableStateOf(Page.MAIN) }
        BackHandler(enabled = page != Page.MAIN) { page = Page.MAIN }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // While connected, Stop is on every page — the sofa may be moving while you read the guide.
            val stopBar: @Composable () -> Unit = {
                if (conn.phase == Phase.READY) StopBar(actions) else Spacer(Modifier.navigationBarsPadding())
            }
            when (page) {
                Page.GUIDE -> GuideScreen(stopBar) { page = Page.MAIN }
                Page.DIAGNOSTICS -> DiagnosticsScreen(conn, env, actions, stopBar) { page = Page.MAIN }
                Page.MAIN ->
                    if (conn.phase == Phase.READY) ControlScreen(conn, ctrl, voice, env, actions, onPage = { page = it })
                    else ConnectScreen(conn, ctrl, env, actions, onPage = { page = it })
            }
        }
    }
}

// ================================================================ connect screen

@Composable
private fun ConnectScreen(conn: SofaConnection, ctrl: SofaController, env: UiEnv, a: SofaActions, onPage: (Page) -> Unit) {
    val busy = conn.phase in listOf(Phase.SCANNING, Phase.CONNECTING, Phase.DISCOVERING, Phase.VERIFYING)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text("uDiva 沙發", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(if (env.hasNfc) "靠近感應，即刻掌控" else "搜尋沙發，即刻掌控", style = MaterialTheme.typography.bodyLarge, color = SofaColors.Muted)
        ctrl.returnProblem?.let { Spacer(Modifier.height(16.dp)); ReturnProblemCard(it, a) }
        Spacer(Modifier.height(28.dp))

        when {
            !env.permissionsOk -> ProblemCard(
                "需要藍牙權限", "App 要用藍牙尋找並控制沙發，不會取得你的位置。",
                "允許藍牙權限", a.grantPermissions, secondary = "開啟系統設定" to a.openAppSettings)
            !env.bluetoothOn -> ProblemCard("藍牙未開啟", "開啟藍牙後才能連線沙發。", "開啟藍牙", a.enableBluetooth)
            else -> {
                if (env.hasNfc) NfcHero(active = busy) else SearchHero(active = busy)
                Spacer(Modifier.height(24.dp))
                Text(
                    when (conn.phase) {
                        Phase.IDLE -> if (env.hasNfc) "將手機靠近沙發的感應區，或搜尋沙發" else "點下方按鈕搜尋沙發"
                        Phase.SCANNING -> "正在尋找沙發…"
                        Phase.CONNECTING -> "正在連線…"
                        Phase.DISCOVERING -> "正在辨識沙發…"
                        Phase.VERIFYING -> "正在接收沙發狀態…"
                        Phase.FAILED -> "連線未成功"
                        Phase.READY -> "已連線"
                    },
                    style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
                )
                conn.message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = SofaColors.Muted, textAlign = TextAlign.Center)
                }
                conn.serial?.let { Spacer(Modifier.height(8.dp)); Tag("已感應序號 ${SofaConnection.maskSerial(it)}") }
                Spacer(Modifier.height(24.dp))
                if (busy) {
                    CircularProgressIndicator(color = SofaColors.Champagne)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = a.cancelConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("取消") }
                } else {
                    Button(onClick = a.scan, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Icon(Icons.Filled.Bluetooth, null); Spacer(Modifier.width(8.dp))
                        Text(if (conn.phase == Phase.FAILED) "重新搜尋沙發" else "搜尋沙發")
                    }
                }
                if (env.hasNfc && !env.nfcOn) {
                    Spacer(Modifier.height(10.dp))
                    Text("（可選）開啟手機的 NFC，之後靠近沙發感應區即可連線", style = MaterialTheme.typography.bodyMedium,
                        color = SofaColors.Muted, textAlign = TextAlign.Center)
                }
                if (conn.candidates.isNotEmpty() && !busy) {
                    Spacer(Modifier.height(24.dp)); SectionTitle("或選擇裝置")
                    conn.candidates.take(6).forEach { c ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { a.pick(c.address) },
                            colors = CardDefaults.cardColors(containerColor = if (c.likely) SofaColors.SurfaceHigh else SofaColors.Surface)) {
                            Row(Modifier.padding(14.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                                    Text(SofaConnection.mask(c.address), style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
                                }
                                if (c.likely) Tag("可能是沙發")
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("連線前請確認", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp))
                Bullet("沙發已開機，指示燈亮起")
                Bullet("原廠 OSIM App 已關閉或中斷與沙發的連線")
                Bullet(if (env.hasNfc) "手機已開啟藍牙（NFC 可選）" else "手機已開啟藍牙")
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onPage(Page.GUIDE) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("使用說明") }
            TextButton(onClick = { onPage(Page.DIAGNOSTICS) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("診斷資訊") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProblemCard(title: String, body: String, button: String, onClick: () -> Unit, secondary: Pair<String, () -> Unit>? = null) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigh)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = SofaColors.Champagne); Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            Text(body, style = MaterialTheme.typography.bodyLarge, color = SofaColors.Muted)
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(button) }
            secondary?.let { (label, act) -> TextButton(onClick = act, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) } }
        }
    }
}

@Composable
private fun NfcHero(active: Boolean) = Pulse(active) { c, base, _ ->
    drawCircle(SofaColors.Champagne, radius = base * 0.16f, center = c)
    drawCircle(SofaColors.Ink, radius = base * 0.07f, center = c)
}

@Composable
private fun SearchHero(active: Boolean) = Pulse(active) { c, base, _ ->
    drawCircle(SofaColors.Champagne, radius = base * 0.12f, center = c)
}

@Composable
private fun Pulse(active: Boolean, core: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.geometry.Offset, Float, Float) -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center; val base = size.minDimension / 2f
            drawCircle(SofaColors.SurfaceHigh, radius = base * 0.78f, center = c)
            for (i in 0..2) {
                val f = ((phase + i / 3f) % 1f)
                val alpha = if (active) (1f - f) * 0.9f else 0.35f * (1f - i * 0.25f)
                drawCircle(SofaColors.Champagne.copy(alpha = alpha.coerceIn(0f, 1f)),
                    radius = if (active) base * (0.55f + 0.45f * f) else base * (0.6f + i * 0.14f),
                    center = c, style = Stroke(width = 3.dp.toPx()))
            }
            core(c, base, phase)
        }
    }
}

// ================================================================ control screen

@Composable
private fun ControlScreen(conn: SofaConnection, ctrl: SofaController, voice: VoiceInput, env: UiEnv, a: SofaActions, onPage: (Page) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val status = conn.status
    val age by produceState(conn.statusAgeMs(), conn.status) {
        while (true) { value = conn.statusAgeMs(); delay(1_000) }
    }
    Scaffold(
        containerColor = SofaColors.Ink,
        topBar = { DeviceBar(conn, a, onPage) },
        bottomBar = {
            Column(Modifier.background(SofaColors.Surface).navigationBarsPadding()) {
                AnimatedVisibility(visible = voice.state != VoiceInput.State.IDLE) { VoicePanel(voice, env, a) }
                EventLine(ctrl.lastEvent)
                QuickBar(ctrl, a, onFullBodyBusy = { scope.launch { list.animateScrollToItem(FULL_BODY_ITEM) } })
            }
        },
    ) { padding ->
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "banners") { Banners(ctrl, status, age, a) }
            item(key = "status") { StatusCard(status, ctrl, a) }
            item(key = "postures") { PostureSection(status, ctrl, a) }
            item(key = "fullbody") { FullBodyCard(status, ctrl, a) }
            item(key = "manual") { ManualCard(status, ctrl, a) }
            item(key = "more") { MoreRow(a, onPage) }
        }
    }
}

private const val FULL_BODY_ITEM = 3

/** Not a TopAppBar: its fixed height clips the two lines at the largest font sizes. */
@Composable
private fun DeviceBar(conn: SofaConnection, a: SofaActions, onPage: (Page) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(SofaColors.Ink).statusBarsPadding().padding(start = 18.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("uDiva 沙發", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(if (conn.demo) "示範模式：沒有連線，不會送出任何指令" else "${conn.deviceName ?: ""} · 已連線",
                style = MaterialTheme.typography.labelMedium, color = if (conn.demo) SofaColors.Champagne else SofaColors.Sage)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("使用說明") }, onClick = { menu = false; onPage(Page.GUIDE) })
                DropdownMenuItem(text = { Text("診斷資訊") }, onClick = { menu = false; onPage(Page.DIAGNOSTICS) })
                DropdownMenuItem(text = { Text("中斷連線") }, onClick = { menu = false; a.disconnect() })
            }
        }
    }
}

/** 語音控制／全身按摩／停止 — always on screen above the system navigation (improve-plan §4.3). */
@Composable
private fun QuickBar(ctrl: SofaController, a: SofaActions, onFullBodyBusy: () -> Unit) {
    val fullBodyBusy = ctrl.session?.let { it.stage != Stage.FAILED } == true
    // Large font: no icons and two lines allowed, so 「語音控制」 is never cut to 「語音」.
    val big = LocalDensity.current.fontScale > 1.3f
    @Composable fun Label(icon: ImageVector, text: String, bold: Boolean = false) {
        if (!big) { Icon(icon, null); Spacer(Modifier.width(4.dp)) }
        Text(text, maxLines = 2, textAlign = TextAlign.Center, fontWeight = if (bold) FontWeight.SemiBold else null)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // At large sizes the two-line labels break between the words, not inside them.
        val br = if (big) "\n" else ""
        FilledTonalButton(onClick = a.voice, modifier = Modifier.weight(1f).heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
            Label(Icons.Filled.Mic, "語音${br}控制")
        }
        FilledTonalButton(
            onClick = { if (fullBodyBusy) onFullBodyBusy() else a.startFullBody() },
            modifier = Modifier.weight(1f).heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 6.dp),
        ) { Label(Icons.Filled.Spa, if (fullBodyBusy) "按摩${br}進度" else "全身${br}按摩") }
        Button(
            onClick = a.stop, modifier = Modifier.weight(1f).heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 6.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SofaColors.Coral, contentColor = SofaColors.Ink),
        ) { Label(Icons.Filled.Stop, "停止", bold = true) }
    }
}

/** The latest outcome (what was also spoken), kept visible for a while above the bar. */
@Composable
private fun EventLine(e: SofaController.UserEvent?) {
    var visible by remember(e) { mutableStateOf(e != null) }
    LaunchedEffect(e) { if (e != null) { delay(12_000); visible = false } }
    AnimatedVisibility(visible = visible && e != null) {
        val ev = e ?: return@AnimatedVisibility
        Row(Modifier.fillMaxWidth().background(if (ev.problem) SofaColors.CoralDeep else SofaColors.SurfaceHigh)
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ev.problem) Icons.Filled.Warning else Icons.Filled.Info, null,
                tint = if (ev.problem) SofaColors.Coral else SofaColors.Sage, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(ev.text, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Text)
        }
    }
}

@Composable
private fun VoicePanel(voice: VoiceInput, env: UiEnv, a: SofaActions) {
    Column(Modifier.fillMaxWidth().background(SofaColors.SurfaceHigher).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val listening = voice.state == VoiceInput.State.LISTENING
            Box(Modifier.size(40.dp).background(if (listening) SofaColors.Champagne else SofaColors.SurfaceHigh, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, null, tint = if (listening) SofaColors.OnChampagne else SofaColors.Champagne)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                when (voice.state) {
                    VoiceInput.State.PERMISSION -> "等待麥克風權限…"
                    VoiceInput.State.LISTENING -> "聆聽中"
                    VoiceInput.State.PROCESSING -> "辨識中…"
                    VoiceInput.State.DONE -> "語音指令"
                    VoiceInput.State.FAILED -> "沒有執行"
                    VoiceInput.State.IDLE -> ""
                },
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = a.voiceCancel) { Icon(Icons.Filled.Close, contentDescription = "關閉語音") }
        }
        voice.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = SofaColors.Text) }
        voice.partial?.let { Text("「$it」", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted) }
        if (voice.state == VoiceInput.State.LISTENING || voice.state == VoiceInput.State.PERMISSION) {
            Text("可以說：「坐式」「躺平」「回到沙發」「開始全身按摩 15 分鐘」「暫停」「繼續」「停止」",
                style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
        }
        env.ttsProblem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne) }
        if (voice.state == VoiceInput.State.DONE || voice.state == VoiceInput.State.FAILED) {
            OutlinedButton(onClick = a.voice, modifier = Modifier.heightIn(min = 48.dp)) { Text("再說一次") }
        }
    }
}

@Composable
private fun Banners(ctrl: SofaController, s: SofaStatus?, age: Long?, a: SofaActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ctrl.returning?.let {
            Card(colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigher), border = BorderStroke(1.dp, SofaColors.Champagne)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = SofaColors.Champagne, strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("正在回到沙發位置", style = MaterialTheme.typography.titleMedium)
                        Text("App 縮小後會停止按摩並回到沙發位置；按「停止」可中止", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                    }
                }
            }
        }
        ctrl.returnProblem?.let { ReturnProblemCard(it, a) }
        if (s != null && s.fault) {
            Card(colors = CardDefaults.cardColors(containerColor = SofaColors.CoralDeep), border = BorderStroke(1.dp, SofaColors.Coral)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = SofaColors.Coral); Spacer(Modifier.width(8.dp))
                        Text("錯誤（代碼 ${s.errorCode}）", style = MaterialTheme.typography.titleLarge, color = SofaColors.Coral)
                    }
                    Text(s.errorText ?: "", style = MaterialTheme.typography.bodyLarge)
                    Text(SofaStatus.FAULT_ADVICE, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                    Text("排除前 App 不會移動沙發或開始按摩；「停止」仍可使用。", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                }
            }
        }
        if (age != null && age > ControlPolicy.STALE_MS) {
            Card(colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigh)) {
                Text("沙發狀態已 ${age / 1000} 秒沒有更新，畫面可能不是最新；暫停新的動作。",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne)
            }
        }
    }
}

@Composable
private fun ReturnProblemCard(text: String, a: SofaActions) {
    Card(colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigh), border = BorderStroke(1.dp, SofaColors.Champagne)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("上次回到沙發位置沒有完成", style = MaterialTheme.typography.titleMedium, color = SofaColors.Champagne)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = a.dismissReturnProblem, modifier = Modifier.heightIn(min = 48.dp)) { Text("知道了") }
        }
    }
}

/** Current posture + massage, with the single 暫停／繼續 button while a massage runs. */
@Composable
private fun StatusCard(s: SofaStatus?, ctrl: SofaController, a: SofaActions) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigh)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusLine("目前姿勢", ctrl.moving?.let { "移動中 → ${Names.short(it.target)}" } ?: postureText(s))
            StatusLine("按摩", massageText(s))
            if (s != null && (s.footFrontLimit || s.ottoman || !s.footBackLimit)) {
                StatusLine("腳托", when {
                    s.footFrontLimit -> "伸出到底"
                    s.footBackLimit -> "已收回"
                    else -> "介於中間"
                } + if (s.ottoman) "（足部按摩架為腳凳模式，腳托無法收回）" else "")
            }
            if (s != null && s.massageRunning) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(onClick = a.pauseOrResume, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null); Spacer(Modifier.width(8.dp))
                    Text(if (s.paused) "繼續按摩" else "暫停按摩")
                }
            }
        }
    }
}

private fun postureText(s: SofaStatus?): String = when {
    s == null -> "讀取中…"
    MoveToTarget.reached(Posture.SOFA, s) -> if (s.massageRunning) "沙發" else "沙發（待機）"
    s.sofaPosition -> "沙發（腳托未收回）"
    s.uprightPosition -> "坐式"
    s.reclinePosition -> "躺平"
    else -> "不在預設位置"
}

private fun massageText(s: SofaStatus?): String {
    if (s == null) return "—"
    val what = when {
        s.programNo == 0 -> return "沒有按摩"
        s.programNo == OsimProtocol.MANUAL_PROGRAM_NO -> "手動按摩"
        s.programNo == OsimProtocol.VGRIP_PROGRAM_NO -> "V-Grip"
        else -> AutoProgram.byNumber(s.programNo)?.zh ?: "程式 ${s.programNo}"
    }
    return if (s.paused) "$what・暫停中" else "$what 進行中・約剩 ${s.remainingMin} 分鐘"
}

/** Label beside the value; at large font sizes above it, so 「目前姿勢」 never breaks mid-word. */
@Composable private fun StatusLine(label: String, value: String) {
    if (LocalDensity.current.fontScale > 1.3f) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Row(verticalAlignment = Alignment.Top) {
            Text(label, Modifier.width(84.dp).padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ---------------------------------------------------------------- postures

@Composable
private fun PostureSection(s: SofaStatus?, ctrl: SofaController, a: SofaActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("姿勢", "點一下：自動到位　按住：放開即停")
        BoxWithConstraints {
            // Phones: one full-width row per preset (three columns break 「沙發（待機）」 mid-word at
            // 411 dp); three columns only on tablets / landscape.
            val stacked = maxWidth < 600.dp || LocalDensity.current.fontScale > 1.3f
            val cards: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> PostureCard(Posture.UPRIGHT, Icons.Filled.AirlineSeatReclineNormal, s, ctrl, a, stacked, m) },
                { m -> PostureCard(Posture.RECLINE, Icons.Filled.AirlineSeatReclineExtra, s, ctrl, a, stacked, m) },
                { m -> PostureCard(Posture.SOFA, Icons.Filled.Weekend, s, ctrl, a, stacked, m) },
            )
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cards.forEach { it(Modifier.fillMaxWidth()) } }
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { cards.forEach { it(Modifier.weight(1f)) } }
        }
    }
}

/**
 * Tap → move to the preset by itself (tap again or 停止 to stop). Long press → move only while
 * held. A press the list takes over as a scroll counts as a release, so the sofa stops.
 * TalkBack gets the auto move as the click action and 停止 as a custom action — nobody has to
 * keep a finger on a button they cannot see.
 */
@Composable
private fun PostureCard(p: Posture, icon: ImageVector, s: SofaStatus?, ctrl: SofaController, a: SofaActions, wide: Boolean, modifier: Modifier) {
    var holding by remember { mutableStateOf(false) }
    val movingHere = ctrl.moving?.target == p
    // Read inside the gesture: the gesture itself must not restart when a move begins, or the
    // lifted finger of a hold would be lost and the sofa would keep moving.
    val movingNow by rememberUpdatedState(movingHere)
    val current = s != null && MoveToTarget.reached(p, s)
    val sub = when {
        movingHere && ctrl.moving?.mode == MoveToTarget.Mode.HOLD -> "移動中…放開即停"
        movingHere -> "移動中…點一下停止"
        current -> "目前位置"
        else -> Names.hint(p)
    }
    Card(
        modifier
            .heightIn(min = if (wide) 72.dp else 120.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = sub
                onClick(label = if (movingHere) "停止" else "自動調整到${Names.short(p)}") {
                    if (movingHere) a.stop() else a.tapPosture(p); true
                }
                customActions = listOf(CustomAccessibilityAction("停止") { a.stop(); true })
            }
            .pointerInput(p) {
                detectTapGestures(
                    onPress = {
                        // finally: a release, a cancel (the list took the gesture) or a dispose all stop a hold.
                        try { tryAwaitRelease() } finally { if (holding) { holding = false; a.holdPosture(p, false) } }
                    },
                    onLongPress = { holding = true; a.holdPosture(p, true) },
                    onTap = { if (movingNow) a.stop() else a.tapPosture(p) },
                )
            },
        colors = CardDefaults.cardColors(containerColor = if (movingHere) SofaColors.SurfaceHigher else SofaColors.Surface),
        border = BorderStroke(if (movingHere || current) 2.dp else 1.dp,
            when { movingHere -> SofaColors.Champagne; current -> SofaColors.Sage; else -> SofaColors.Line }),
    ) {
        val text: @Composable () -> Unit = {
            Column {
                Text(Names.label(p), style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = if (current && !movingHere) SofaColors.Sage else SofaColors.Muted)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = SofaColors.Champagne, modifier = Modifier.size(30.dp)); Spacer(Modifier.width(14.dp)); text()
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = SofaColors.Champagne, modifier = Modifier.size(30.dp)); text()
            }
        }
    }
}

// ---------------------------------------------------------------- full body

private val STEPS = listOf("調整為躺平", "確認姿勢", "啟動按摩", "已開始")

private fun stepOf(stage: Stage?): Int = when (stage) {
    Stage.MOVING -> 0
    Stage.SETTING_DURATION -> 1
    Stage.STARTING, Stage.APPLYING -> 2
    Stage.RUNNING -> 3
    else -> -1
}

@Composable
private fun FullBodyCard(s: SofaStatus?, ctrl: SofaController, a: SofaActions) {
    val session = ctrl.session
    val running = s?.programNo == AutoProgram.SIGNATURE.number
    val busy = running || (session != null && session.stage != Stage.FAILED)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface),
        border = BorderStroke(1.dp, if (busy) SofaColors.Champagne else SofaColors.Line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(SofaColors.SurfaceHigh, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Spa, null, tint = SofaColors.Sage)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Names.FULL_BODY, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("先調整為躺平，再開始・${AutoProfile.LEVEL3.zh}", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                }
            }
            // Duration is chosen apart from the start button (no accidental start from a chip).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("時間", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                Chip("15 分鐘", !ctrl.want30, Modifier.weight(1f), enabled = !busy) { a.setWant30(false) }
                Chip("30 分鐘", ctrl.want30, Modifier.weight(1f), enabled = !busy) { a.setWant30(true) }
            }
            val step = stepOf(session?.stage)
            if (session != null && session.stage != Stage.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    STEPS.forEachIndexed { i, label ->
                        val done = i < step || (i == 3 && step == 3)
                        Text((if (done) "✓ " else "") + label, Modifier.weight(1f), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = when { i == step -> SofaColors.Champagne; done -> SofaColors.Sage; else -> SofaColors.Muted })
                    }
                }
            }
            val note = when {
                session?.stage == Stage.FAILED -> "未開始：${session.failure}"
                running && s != null -> if (s.paused) "全身按摩暫停中" else "進行中・約剩 ${s.remainingMin} 分鐘" +
                    if (session?.confirmed == false) "（力度設定已送出，沙發尚未回報）" else ""
                session?.stage == Stage.MOVING -> "正在調整為躺平…到位後自動開始"
                session?.stage == Stage.SETTING_DURATION -> "設定 ${session.minutes} 分鐘…"
                session?.stage == Stage.STARTING || session?.stage == Stage.APPLYING -> "啟動中…"
                else -> null
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, color = if (session?.stage == Stage.FAILED) SofaColors.Coral else SofaColors.Text)
            }
            Button(onClick = a.startFullBody, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(when {
                    running -> "全身按摩進行中"
                    busy -> "準備中…"
                    else -> "開始全身按摩（${if (ctrl.want30) 30 else 15} 分鐘）"
                })
            }
        }
    }
}

// ---------------------------------------------------------------- manual

@Composable
private fun ManualCard(s: SofaStatus?, ctrl: SofaController, a: SofaActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val m = ctrl.manual
    val running = s?.programNo == OsimProtocol.MANUAL_PROGRAM_NO
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }
                .semantics { stateDescription = if (open) "已展開" else "已收合" }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Names.MANUAL, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text("${m.kneadTap.zh}・${m.rolling.zh}・力度 ${m.shoulder}/${m.back}/${m.lumbar}・足部${m.footVibration.zh}",
                        style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                }
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            if (running && s != null) {
                Text("沙發回報：肩 ${s.shoulderIntensity}・背 ${s.backIntensity}・腰 ${s.lumbarIntensity}・速度${s.backSpeed.zh}",
                    style = MaterialTheme.typography.bodyMedium, color = SofaColors.Sage)
            }
            if (open) {
                ManualGroup("背部") {
                    ChoiceRow("揉捏／敲打", KneadTap.entries, m.kneadTap, { it.zh }) { a.changeManual(m.copy(kneadTap = it)) }
                    ChoiceRow("滾動", Rolling.entries, m.rolling, { it.zh }) { a.changeManual(m.copy(rolling = it)) }
                    ChoiceRow("背部速度", BackSpeed.entries, m.speed, { it.zh }) { a.changeManual(m.copy(speed = it)) }
                    ChoiceRow("滾輪寬度", RollerWidth.entries, m.width, { it.zh }) { a.changeManual(m.copy(width = it)) }
                    LevelStepper("肩部力度", m.shoulder) { a.changeManual(m.copy(shoulder = it)) }
                    LevelStepper("背部力度", m.back) { a.changeManual(m.copy(back = it)) }
                    LevelStepper("腰部力度", m.lumbar) { a.changeManual(m.copy(lumbar = it)) }
                }
                ManualGroup("足部") {
                    ChoiceRow("足部揉捏", Level.entries, m.footKneading, { it.zh }) { a.changeManual(m.copy(footKneading = it)) }
                    ChoiceRow("足部振動", Level.entries, m.footVibration, { it.zh }) { a.changeManual(m.copy(footVibration = it)) }
                }
                ManualGroup("臀部") {
                    ChoiceRow("臀部振動", Level.entries, m.seatVibration, { it.zh }) { a.changeManual(m.copy(seatVibration = it)) }
                }
            }
            Text(if (running) "手動按摩進行中：調整會立即送出" else "這些設定在按下「開始手動按摩」時套用",
                style = MaterialTheme.typography.bodyMedium, color = if (running) SofaColors.Sage else SofaColors.Muted)
            if (!running) {
                if (s?.posture == Posture.SOFA) Text("背部按摩需要先調整為「坐式」或「躺平」", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne)
                Button(onClick = a.startManual, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("開始手動按摩") }
            }
        }
    }
}

@Composable
private fun ManualGroup(title: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(title == "背部") }
    Column(Modifier.fillMaxWidth().background(SofaColors.SurfaceHigh, MaterialTheme.shapes.small).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = !open }
            .semantics { stateDescription = if (open) "已展開" else "已收合" }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        if (open) content()
    }
}

@Composable
private fun MoreRow(a: SofaActions, onPage: (Page) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = a.spotify, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Filled.MusicNote, null, tint = SofaColors.Sage); Spacer(Modifier.width(6.dp)); Text("音樂", maxLines = 1)
            }
            OutlinedButton(onClick = { onPage(Page.GUIDE) }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Icon(Icons.Filled.Info, null); Spacer(Modifier.width(6.dp)); Text("使用說明", maxLines = 1)
            }
        }
        Text("「音樂」會開啟 Spotify，本 App 隨之進入背景：按摩會停止、沙發回到沙發位置。", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
    }
}

// ================================================================ guide

/** A single 停止 button at the bottom of the secondary pages while a sofa is connected. */
@Composable
private fun StopBar(a: SofaActions) {
    Box(Modifier.fillMaxWidth().background(SofaColors.Surface).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Button(
            onClick = a.stop, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SofaColors.Coral, contentColor = SofaColors.Ink),
        ) { Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("停止", fontWeight = FontWeight.SemiBold) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GuideScreen(stopBar: @Composable () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("使用說明") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = SofaColors.Ink),
        )
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GuideCard("連線沙發", listOf(
                "開啟 App 會自動連線上次的沙發；也可將手機靠近沙發感應區，或點「搜尋沙發」。",
                "感應後優先連線那一張沙發；附近有多個沙發裝置又無法確定時，會列出讓你選。",
            ))
            GuideCard("三種姿勢", listOf(
                "坐式、躺平、沙發（待機）。點一下：自動調整到位，到位就停；移動中再點一下或按「停止」即停。",
                "按住：只在按住時移動，放開就停。",
                "沙發（待機）會先停止按摩，再回到沙發位置。",
                "「目前姿勢」依沙發回報顯示；沒有按摩不代表已回到沙發位置。",
            ))
            GuideCard("全身按摩", listOf(
                "先選 15 或 30 分鐘，按「開始全身按摩」或下方「全身按摩」。",
                "沙發會先調整為躺平，確認到位後才開始；肩、背、腰力度 3，其餘自動。",
                "沒有到位、沒有回報或沙發報錯時不會開始，並說明原因。",
            ))
            GuideCard("暫停與停止", listOf(
                "按摩進行中，上方狀態卡有「暫停按摩／繼續按摩」。",
                "下方「停止」隨時可用：立刻停止移動、按摩與回位。",
            ))
            GuideCard("手動按摩", listOf(
                "展開「手動按摩」，分背部、足部、臀部設定，按「開始手動按摩」。",
                "手動按摩進行中，調整會立即送出；其他時候會在下次開始時套用。",
            ))
            GuideCard("語音控制（中文）", listOf(
                "點下方「語音控制」說一次指令（每次點一下聽一次，不會一直開著麥克風）。",
                "姿勢：「坐式」（坐直、坐姿也可）、「躺平」、「回到沙發」。",
                "全身按摩：「開始全身按摩 15 分鐘」「開始全身按摩 30 分鐘」；沒說時間就是 15 分鐘。",
                "其他：「開始手動按摩」「暫停」「繼續」「停止」「加強」「減弱」「肩部加強」「關閉足部振動」。",
                "說法相近或同音也聽得懂（例如辨識成「做直」也算坐直）。",
                "「不要躺平」、問句（例如「沙發怎麼用？」）或一次說兩個指令都不會執行；說「停止」一定會停。",
            ))
            GuideCard("縮小 App 時", listOf(
                "按主畫面鍵、切到其他 App、關螢幕或開啟 Spotify 時：停止按摩 → 回到沙發位置 → 下次設定恢復為 15 分鐘、力度 3。",
                "通知列會顯示進度並有「停止」；沙發報錯或狀態不明時不會自動移動。",
                "轉動手機或權限視窗不算縮小。",
            ))
            GuideCard("音樂", listOf("點「音樂（Spotify）」開啟 Spotify；在手機藍牙把沙發設為音訊輸出即可由沙發喇叭播放。"))
            GuideCard("安全提醒", listOf("移動前請確認沙發周圍與腳托下方淨空；異常時按「停止」或關閉沙發電源。"))
        }
        stopBar()
    }
}

// ================================================================ diagnostics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(conn: SofaConnection, env: UiEnv, a: SofaActions, stopBar: @Composable () -> Unit, onBack: () -> Unit) {
    var hex by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("診斷資訊") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = SofaColors.Ink),
        )
        Column(Modifier.fillMaxWidth().weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("版本 ${env.version}　狀態 ${conn.phase}　MTU ${conn.mtu}　服務 ${conn.variant ?: "-"}", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
            Text("沙發回報：${conn.status?.summary() ?: "-"}", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("記錄語音逐字稿（預設關閉）", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = env.transcripts, onCheckedChange = a.setTranscripts)
            }
            OutlinedButton(onClick = a.copyDiagnostics, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("複製診斷紀錄（序號、位址已遮罩）")
            }
            a.sendRaw?.let { send ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(hex, { hex = it }, Modifier.weight(1f), placeholder = { Text("raw hex（測試版）") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), singleLine = true)
                    FilledTonalButton(onClick = { send(hex) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("送出") }
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f).background(SofaColors.Surface, MaterialTheme.shapes.small).padding(8.dp)) {
                LazyColumn { items(conn.logLines.asReversed()) { Text(it, style = MaterialTheme.typography.labelMedium, color = SofaColors.Text) } }
            }
        }
        stopBar()
    }
}

// ================================================================ components

@Composable private fun SectionTitle(title: String, hint: String? = null) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
    }
}

@Composable private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        modifier.heightIn(min = 48.dp).semantics { role = Role.RadioButton; stateDescription = if (selected) "已選" else "未選" }
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (selected) SofaColors.Champagne else SofaColors.SurfaceHigher,
        contentColor = if (selected) SofaColors.OnChampagne else if (enabled) SofaColors.Text else SofaColors.Muted,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, Modifier.padding(horizontal = 6.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> Chip(name(o), o == selected, Modifier.weight(1f)) { onPick(o) } }
        }
    }
}

@Composable private fun LevelStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = "$label $value" }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > OsimProtocol.INTENSITY.first,
            shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(52.dp)
                .semantics { this.onClick(label = "$label 減弱") { onChange(value - 1); true } }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("$value", Modifier.width(44.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Button(onClick = { onChange(value + 1) }, enabled = value < OsimProtocol.INTENSITY.last,
            shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(52.dp)
                .semantics { this.onClick(label = "$label 加強") { onChange(value + 1); true } }) { Text("＋", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable private fun Tag(text: String) {
    Surface(shape = CircleShape, color = SofaColors.SurfaceHigh, contentColor = SofaColors.Champagne) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("•  ", color = SofaColors.Champagne)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
    }
}

@Composable private fun GuideCard(title: String, lines: List<String>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SofaColors.Champagne, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            lines.forEach { Bullet(it) }
        }
    }
}
