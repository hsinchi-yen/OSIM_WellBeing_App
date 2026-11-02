package com.example.osimsofa.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
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
import androidx.compose.ui.semantics.selected
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
import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.AutoProfile
import com.example.osimsofa.OsimProtocol.AutoProgram
import com.example.osimsofa.OsimProtocol.FootSeat
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.ManualSettings
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.SofaConnection
import com.example.osimsofa.SofaConnection.Phase
import com.example.osimsofa.SofaController
import com.example.osimsofa.SofaStatus
import com.example.osimsofa.VoiceInput
import com.example.osimsofa.Zone
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SofaActions(
    val tapPosture: (Posture) -> Unit,
    val holdPosture: (Posture, Boolean) -> Unit,   // press-and-hold: true on long press, false on release
    val stopMove: () -> Unit,                      // the moving card tapped again: movement only
    val continueMove: () -> Unit,                  // after an interrupted move: on to its target …
    val returnToOrigin: () -> Unit,                // … or back to where it started
    val dismissInterrupted: () -> Unit,
    val startFullBody: () -> Unit,
    val setWant30: (Boolean) -> Unit,
    val startManual: () -> Unit,
    val changeManual: (ManualSettings) -> Unit,
    val setIntensity: (Area, Int) -> Unit,
    val setFunction: (FootSeat, Level) -> Unit,
    val focusZone: (Zone) -> Unit,
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
        // Kept here, not in ControlScreen: that leaves the composition for the guide and for a
        // reconnect, and rememberSaveable state goes with it — the tab would reset, and a voice
        // request already shown would count as new and jump to it again.
        var tab by rememberSaveable { mutableStateOf(ControlTab.HOME) }
        var zone by rememberSaveable { mutableStateOf(Zone.BACK) }
        var spotlightSeen by rememberSaveable { mutableIntStateOf(0) }
        // What was just said about a part of the body is shown: the screen follows the voice.
        LaunchedEffect(ctrl.spotlight) {
            ctrl.spotlight?.takeIf { it.nonce != spotlightSeen }?.let {
                spotlightSeen = it.nonce; zone = it.zone; tab = ControlTab.ZONES
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // While connected, Stop is on every page — the sofa may be moving while you read the guide.
            val stopBar: @Composable () -> Unit = {
                if (conn.phase == Phase.READY) StopBar(actions) else Spacer(Modifier.navigationBarsPadding())
            }
            when (page) {
                Page.GUIDE -> GuideScreen(stopBar) { page = Page.MAIN }
                Page.DIAGNOSTICS -> DiagnosticsScreen(conn, env, actions, stopBar) { page = Page.MAIN }
                Page.MAIN ->
                    if (conn.phase == Phase.READY) ControlScreen(conn, ctrl, voice, env, actions, tab, { tab = it }, zone, { zone = it }, onPage = { page = it })
                    else ConnectScreen(conn, env, actions, onPage = { page = it })
            }
        }
    }
}

// ================================================================ connect screen

@Composable
private fun ConnectScreen(conn: SofaConnection, env: UiEnv, a: SofaActions, onPage: (Page) -> Unit) {
    val busy = conn.phase in listOf(Phase.SCANNING, Phase.CONNECTING, Phase.DISCOVERING, Phase.VERIFYING)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text("uDiva 沙發", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(if (env.hasNfc) "靠近感應，即刻掌控" else "搜尋沙發，即刻掌控", style = MaterialTheme.typography.bodyLarge, color = SofaColors.Muted)
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

private enum class ControlTab(val zh: String) { HOME("總覽"), ZONES("部位按摩") }

@Composable
private fun ControlScreen(
    conn: SofaConnection, ctrl: SofaController, voice: VoiceInput, env: UiEnv, a: SofaActions,
    tab: ControlTab, onTab: (ControlTab) -> Unit, zone: Zone, onZone: (Zone) -> Unit, onPage: (Page) -> Unit,
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val status = conn.status
    val age by produceState(conn.statusAgeMs(), conn.status) {
        while (true) { value = conn.statusAgeMs(); delay(1_000) }
    }
    BackHandler(enabled = tab != ControlTab.HOME) { onTab(ControlTab.HOME) }
    Scaffold(
        containerColor = SofaColors.Ink,
        topBar = {
            Column(Modifier.background(SofaColors.Ink)) {
                DeviceBar(conn, a, onPage)
                TabSwitcher(tab, onTab)
            }
        },
        bottomBar = {
            Column(Modifier.background(SofaColors.Surface).navigationBarsPadding()) {
                AnimatedVisibility(visible = voice.state != VoiceInput.State.IDLE) { VoicePanel(voice, env, a) }
                EventLine(ctrl.lastEvent)
                QuickBar(ctrl, a, onFullBodyBusy = {
                    onTab(ControlTab.HOME)
                    scope.launch { list.animateScrollToItem(FULL_BODY_ITEM) }
                })
            }
        },
    ) { padding ->
        when (tab) {
            ControlTab.HOME -> LazyColumn(
                state = list,
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "banners") { Banners(status, age) }
                item(key = "status") { StatusCard(status, ctrl, a) }
                item(key = "postures") { PostureSection(status, ctrl, a) }
                item(key = "fullbody") { FullBodyCard(status, ctrl, a) }
                item(key = "zones") { ZoneShortcuts { onZone(it); onTab(ControlTab.ZONES) } }
                item(key = "more") { MoreRow(a, onPage) }
            }
            ControlTab.ZONES -> ZoneTab(status, age, ctrl, a, zone, onZone, padding)
        }
    }
}

@Composable
private fun TabSwitcher(tab: ControlTab, onPick: (ControlTab) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
        .background(SofaColors.Surface, CircleShape).padding(4.dp)) {
        ControlTab.entries.forEach { t ->
            val on = t == tab
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .background(if (on) SofaColors.Champagne else SofaColors.Surface, CircleShape)
                    .semantics { role = Role.Tab; selected = on }
                    .clickable { onPick(t) },
                contentAlignment = Alignment.Center,
            ) {
                Text(t.zh, style = MaterialTheme.typography.labelLarge, color = if (on) SofaColors.OnChampagne else SofaColors.Muted)
            }
        }
    }
}

/** Straight from the overview to one part of the body. */
@Composable
private fun ZoneShortcuts(onZone: (Zone) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("部位按摩", "只按想放鬆的地方")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Zone.entries.forEach { z ->
                // Goes somewhere rather than choosing something, so not a Chip (TalkBack: 「未選」).
                Surface(onClick = { onZone(z) }, Modifier.weight(1f).heightIn(min = 48.dp), shape = CircleShape,
                    color = SofaColors.SurfaceHigher, contentColor = SofaColors.Text) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(z.zh, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
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
    // Large font: no icons and two lines allowed, so 「語音控制」 is never cut to 「語音」 or broken
    // mid-word. From 1.25: a 360 dp phone at font scale 1.3 already has no room for the icon.
    val big = LocalDensity.current.fontScale >= 1.25f
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
        val listening = voice.state == VoiceInput.State.LISTENING
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicIndicator(listening, voice.level)
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
        voice.partial?.let { Text("「$it」", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne) }
        if (listening || voice.state == VoiceInput.State.PERMISSION) {
            VOICE_HINTS.forEach { (topic, examples) -> VoiceHint(topic, examples) }
        }
        env.ttsProblem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne) }
        if (voice.state == VoiceInput.State.DONE || voice.state == VoiceInput.State.FAILED) {
            OutlinedButton(onClick = a.voice, modifier = Modifier.heightIn(min = 48.dp)) { Text("再說一次") }
        }
    }
}

private val VOICE_HINTS = listOf(
    "部位" to "腰部按摩・背部加強・關閉足部",
    "力度" to "力度調到 4・腰部最強・再強一點",
    "姿勢" to "坐式・躺平・回到沙發",
    "查詢" to "還剩幾分鐘・力度多少",
)

@Composable
private fun VoiceHint(topic: String, examples: String) {
    Row {
        Text(topic, Modifier.width(44.dp), style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne)
        Text(examples, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
    }
}

/** The ring follows the voice: seeing it move is how you know the phone hears you. */
@Composable
private fun MicIndicator(listening: Boolean, level: Float) {
    val heard by animateFloatAsState(if (listening) level else 0f, tween(120), label = "mic")
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(SofaColors.Champagne.copy(alpha = 0.28f), radius = r * (0.74f + 0.26f * heard))
            drawCircle(if (listening) SofaColors.Champagne else SofaColors.SurfaceHigh, radius = r * 0.74f)
        }
        Icon(Icons.Filled.Mic, null, tint = if (listening) SofaColors.OnChampagne else SofaColors.Champagne)
    }
}

@Composable
internal fun Banners(s: SofaStatus?, age: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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

/** Where the sofa is and what it is doing, first thing on the screen; 暫停／繼續 while a massage runs. */
@Composable
private fun StatusCard(s: SofaStatus?, ctrl: SofaController, a: SofaActions) {
    val running = s != null && s.massageRunning
    val big = LocalDensity.current.fontScale > 1.4f
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface),
        border = BorderStroke(1.dp, if (running) SofaColors.ChampagneDeep else SofaColors.Line)) {
        Column(
            Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(SofaColors.SurfaceHigher, SofaColors.Surface)))
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("目前姿勢", style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
                    Text(ctrl.moving?.let { "移動中 → ${Names.short(it.target)}" } ?: Names.posture(s), style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("按摩", style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
                    Text(Names.massage(s), style = MaterialTheme.typography.titleMedium, color = if (running) SofaColors.Champagne else SofaColors.Text)
                }
                if (running && !big && s != null) {
                    Spacer(Modifier.width(12.dp))
                    TimeRing(s)
                }
            }
            if (s != null && (s.footFrontLimit || s.ottoman || !s.footBackLimit)) {
                StatusLine("腳托", when {
                    s.footFrontLimit -> "伸出到底"
                    s.footBackLimit -> "已收回"
                    else -> "介於中間"
                } + if (s.ottoman) "（足部按摩架為腳凳模式，腳托無法收回）" else "")
            }
            if (s != null && s.massageRunning) {
                FilledTonalButton(onClick = a.pauseOrResume, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null); Spacer(Modifier.width(8.dp))
                    Text(if (s.paused) "繼續按摩" else "暫停按摩")
                }
            }
        }
    }
}

/** Minutes left, as the part of the session still to come. */
@Composable
private fun TimeRing(s: SofaStatus) {
    val total = (s.remainingMin + s.elapsedMin).coerceAtLeast(1)
    val left = s.remainingMin.toFloat() / total
    Box(Modifier.size(84.dp).semantics(mergeDescendants = true) { stateDescription = "約剩 ${s.remainingMin} 分鐘" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 6.dp.toPx()
            drawCircle(SofaColors.Line, radius = size.minDimension / 2f - w / 2f, style = Stroke(w))
            drawArc(if (s.paused) SofaColors.Muted else SofaColors.Champagne, startAngle = -90f, sweepAngle = 360f * left,
                useCenter = false, topLeft = androidx.compose.ui.geometry.Offset(w / 2f, w / 2f),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${s.remainingMin}", style = MaterialTheme.typography.headlineSmall)
            Text("分鐘", style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
        }
    }
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
            // Three tiles side by side, with the short names; large fonts and narrow screens (a
            // tile on a 360 dp phone has about 73 dp of text width) get one full-width row per
            // preset, so nothing breaks mid-word.
            val stacked = maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.25f
            val cards: List<@Composable (Modifier) -> Unit> = listOf(
                { m -> PostureCard(Posture.UPRIGHT, Icons.Filled.AirlineSeatReclineNormal, s, ctrl, a, stacked, m) },
                { m -> PostureCard(Posture.RECLINE, Icons.Filled.AirlineSeatReclineExtra, s, ctrl, a, stacked, m) },
                { m -> PostureCard(Posture.SOFA, Icons.Filled.Weekend, s, ctrl, a, stacked, m) },
            )
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { cards.forEach { it(Modifier.fillMaxWidth()) } }
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { cards.forEach { it(Modifier.weight(1f)) } }
        }
        ctrl.interrupted?.let { InterruptedCard(it, a) }
    }
}

/** A tap move stopped on the way (Stop, tapped again, or no arrival in time): the user decides. */
@Composable
private fun InterruptedCard(i: SofaController.Interrupted, a: SofaActions) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigher),
        border = BorderStroke(1.dp, SofaColors.Champagne)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("移動在途中停下，尚未到${Names.short(i.target)}", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = a.continueMove, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Text("繼續到${Names.short(i.target)}", maxLines = 2, textAlign = TextAlign.Center)
                }
                i.origin?.let { o ->
                    OutlinedButton(onClick = a.returnToOrigin, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                        border = BorderStroke(1.dp, SofaColors.Champagne)) {
                        Text("回到${Names.short(o)}", maxLines = 2, textAlign = TextAlign.Center)
                    }
                }
            }
            TextButton(onClick = a.dismissInterrupted, modifier = Modifier.heightIn(min = 48.dp)) { Text("留在這裡") }
        }
    }
}

/**
 * Tap → move to the preset in one go, stopping there by itself; tapping the moving card again
 * stops the movement only (a running massage carries on). Long press → move only while held. A
 * press the list takes over as a scroll counts as a release, so the sofa stops. TalkBack gets the
 * automatic move as the click action and 停止移動 as a custom action — nobody has to keep a finger
 * on a button they cannot see.
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
        movingHere && ctrl.moving?.mode == MoveToTarget.Mode.HOLD -> if (wide) "移動中…放開即停" else "放開即停"
        movingHere -> if (wide) "移動中…點一下停止" else "點一下停止"
        current -> "目前位置"
        wide -> Names.hint(p)
        else -> "點一下調整"
    }
    Card(
        modifier
            .heightIn(min = if (wide) 72.dp else 120.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = sub
                onClick(label = if (movingHere) "停止移動" else "自動調整到${Names.short(p)}") {
                    if (movingHere) a.stopMove() else a.tapPosture(p); true
                }
                customActions = listOf(CustomAccessibilityAction("停止移動") { a.stopMove(); true })
            }
            .pointerInput(p) {
                detectTapGestures(
                    onPress = {
                        // finally: a release, a cancel (the list took the gesture) or a dispose all stop a hold.
                        try { tryAwaitRelease() } finally { if (holding) { holding = false; a.holdPosture(p, false) } }
                    },
                    onLongPress = { holding = true; a.holdPosture(p, true) },
                    onTap = { if (movingNow) a.stopMove() else a.tapPosture(p) },
                )
            },
        colors = CardDefaults.cardColors(containerColor = if (movingHere) SofaColors.SurfaceHigher else SofaColors.Surface),
        border = BorderStroke(if (movingHere || current) 2.dp else 1.dp,
            when { movingHere -> SofaColors.Champagne; current -> SofaColors.Sage; else -> SofaColors.Line }),
    ) {
        val text: @Composable () -> Unit = {
            Column {
                Text(if (wide) Names.label(p) else Names.short(p), style = MaterialTheme.typography.titleMedium)
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
        Text("「音樂」會開啟 Spotify；本 App 在背景時，沙發維持目前的按摩與姿勢，可從通知列停止。", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
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
                "將手機靠近沙發感應區（NFC）即自動連線；或點「搜尋沙發」。開啟 App 本身不會自動連線。",
                "從其他 App 切回來時，會自動重新連上剛才的沙發。",
                "感應後優先連線那一張沙發；附近有多個沙發裝置又無法確定時，會列出讓你選。",
            ))
            GuideCard("三種姿勢", listOf(
                "坐式、躺平、沙發（待機）。點一下：一次調整到位，到位就停。",
                "移動中再點一下同一張卡只停止移動（按摩照常）；下方「停止」會停止全部。",
                "中途停下時會問你：「繼續到…」或「回到原本位置」；也可以說「繼續」。",
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
            GuideCard("部位按摩", listOf(
                "到「部位按摩」分頁，點身體圖或清單選肩部、背部、腰部、腿足、臀部，再調整該部位。",
                "「只按摩…」只開啟這個部位；手動按摩進行中則改為「加強…」，其他部位不變。",
                "肩、背、腰共用同一組滾輪：力度可以分開調，開關只能一起。",
                "背部按摩需要坐式或躺平；App 不會為了部位按摩自動移動沙發。",
                "足部按摩架要在足部按摩模式（不是腳凳模式）。",
                "全身按摩進行中可調力度與足部、臀部開關；背部手法由程式決定。",
                "椅背與腳托不能單獨移動，只有坐式、躺平、沙發三種姿勢。",
            ))
            GuideCard("手動按摩", listOf(
                "在「部位按摩」分頁調好各部位，按「開始手動按摩」。",
                "手動按摩進行中，調整會立即送出；其他時候會在下次開始時套用。",
            ))
            GuideCard("語音控制（中文）", listOf(
                "點下方「語音控制」說一次指令（每次點一下聽一次，不會一直開著麥克風）。",
                "姿勢：「坐式」（坐直、坐姿也可）、「躺平」、「回到沙發」。",
                "全身按摩：「開始全身按摩 15 分鐘」「開始全身按摩 30 分鐘」；沒說時間就是 15 分鐘。",
                "部位：「腰部按摩」「背部按摩」「腿部按摩」「肩部按摩」「臀部按摩」。",
                "力度：「背部加強」「腰部減弱」「腰部力度調到 4」「肩部最輕」「太大力了」；接著說「再強一點」會調同一個部位。",
                "足部與臀部：「足部加強」「關閉足部」「關閉足部振動」「開啟臀部」。",
                "背部手法：「敲打」「揉捏」「局部滾動」「滾輪寬一點」「關閉背部」。",
                "查詢：「還剩幾分鐘」「力度多少」「現在什麼姿勢」「可以說什麼」。",
                "其他：「開始手動按摩」「暫停」「繼續」「停止」「加強」「減弱」。",
                "說法相近或同音也聽得懂（例如辨識成「做直」也算坐直）。",
                "「不要躺平」、其他問句（例如「沙發怎麼用？」）或一次說兩個指令都不會執行；說「停止」一定會停，話還沒說完就會停。",
                "「腳抬高」「椅背往後」不會執行：椅背與腳托不能單獨移動。",
            ))
            GuideCard("縮小 App 時", listOf(
                "按主畫面鍵、切到其他 App、關螢幕或開啟 Spotify：沙發維持目前狀態，進行中的按摩或移動會照常完成。",
                "通知列顯示沙發目前的狀態，並有「停止」。按住姿勢卡的移動會在離開時停止。",
                "沙發沒有進行中的功能一分鐘後，App 會放開連線；切回 App 時自動重新連線，並顯示沙發目前的狀態。",
            ))
            GuideCard("音樂", listOf("點「音樂」開啟 Spotify；在手機藍牙把沙發設為音訊輸出即可由沙發喇叭播放。"))
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

@Composable internal fun SectionTitle(title: String, hint: String? = null) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
    }
}

@Composable internal fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
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

@Composable internal fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: (T) -> String, enabled: Boolean = true, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> Chip(name(o), o == selected, Modifier.weight(1f), enabled) { onPick(o) } }
        }
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
