package com.example.osimsofa.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirlineSeatReclineExtra
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Weekend
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.osimsofa.FullBodyFlow.Stage
import com.example.osimsofa.OsimProtocol
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

class SofaActions(
    val posture: (Posture, Boolean) -> Unit,   // press-and-hold: pressed=true on down, false on release
    val fullBody: (Boolean) -> Unit,           // same gesture: hold to move into position
    val fullBodyStartAnyway: () -> Unit,
    val setWant30: (Boolean) -> Unit,
    val startManual: () -> Unit,
    val changeManual: (ManualSettings) -> Unit,
    val pause: () -> Unit,
    val resume: () -> Unit,
    val stop: () -> Unit,
    val scan: () -> Unit,
    val pick: (String) -> Unit,
    val disconnect: () -> Unit,
    val voice: () -> Unit,
    val spotify: () -> Unit,
    val sendRaw: (String) -> Unit,
)

@Composable
fun SofaApp(conn: SofaConnection, ctrl: SofaController, voiceHint: String?, actions: SofaActions) {
    SofaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (conn.phase == Phase.READY) ControlScreen(conn, ctrl, voiceHint, actions)
            else ConnectScreen(conn, actions)
        }
    }
}

// ---------------------------------------------------------------- connect screen
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConnectScreen(conn: SofaConnection, a: SofaActions) {
    val busy = conn.phase in listOf(Phase.SCANNING, Phase.CONNECTING, Phase.DISCOVERING, Phase.VERIFYING)
    var diag by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(56.dp))
        Text("uDiva 沙發", style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.combinedClickable(onClick = {}, onLongClick = { diag = !diag }))
        Spacer(Modifier.height(6.dp))
        Text("靠近感應，即刻掌控", style = MaterialTheme.typography.bodyLarge, color = SofaColors.Muted)
        AnimatedVisibility(visible = diag) { DiagnosticsPanel(conn, a) }
        Spacer(Modifier.height(36.dp))
        NfcHero(active = busy)
        Spacer(Modifier.height(28.dp))
        Text(
            when (conn.phase) {
                Phase.IDLE -> "將手機靠近沙發的感應區"
                Phase.SCANNING -> "正在尋找沙發…"
                Phase.CONNECTING -> "正在連線…"
                Phase.DISCOVERING -> "正在辨識沙發…"
                Phase.VERIFYING -> "正在驗證連線…"
                Phase.FAILED -> "連線未成功"
                Phase.READY -> "已連線"
            },
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center
        )
        conn.message?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted, textAlign = TextAlign.Center) }
        conn.serial?.let { Spacer(Modifier.height(8.dp)); Tag("已感應序號 $it") }
        Spacer(Modifier.height(30.dp))
        if (busy) CircularProgressIndicator(color = SofaColors.Champagne)
        else Button(onClick = a.scan, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Icon(Icons.Filled.Bluetooth, null); Spacer(Modifier.width(8.dp))
            Text(if (conn.phase == Phase.FAILED) "重新搜尋沙發" else "手動搜尋沙發")
        }
        if (conn.candidates.isNotEmpty() && conn.phase != Phase.READY) {
            Spacer(Modifier.height(26.dp)); SectionTitle("或選擇裝置")
            conn.candidates.take(6).forEach { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { a.pick(c.address) },
                    colors = CardDefaults.cardColors(containerColor = if (c.likely) SofaColors.SurfaceHigh else SofaColors.Surface)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            Text(c.address, style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
                        }
                        if (c.likely) Tag("可能是沙發"); Spacer(Modifier.width(8.dp))
                        Text("${c.rssi} dBm", style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
                    }
                }
            }
        }
        Spacer(Modifier.height(30.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
            Column(Modifier.padding(16.dp)) {
                Text("使用前請確認", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(6.dp))
                Bullet("沙發已開機，指示燈亮起")
                Bullet("原廠 App 已中斷與沙發的連線")
                Bullet("手機已開啟藍牙與 NFC")
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun NfcHero(active: Boolean) {
    val t = rememberInfiniteTransition(label = "pulse")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center; val base = size.minDimension / 2f
            drawCircle(SofaColors.SurfaceHigh, radius = base * 0.78f, center = c)
            for (i in 0..2) {
                val f = ((phase + i / 3f) % 1f)
                val a = if (active) (1f - f) * 0.9f else 0.35f * (1f - i * 0.25f)
                drawCircle(SofaColors.Champagne.copy(alpha = a.coerceIn(0f, 1f)),
                    radius = if (active) base * (0.55f + 0.45f * f) else base * (0.6f + i * 0.14f),
                    center = c, style = Stroke(width = 3.dp.toPx()))
            }
            drawCircle(SofaColors.Champagne, radius = base * 0.16f, center = c)
            drawCircle(SofaColors.Ink, radius = base * 0.07f, center = c)
        }
    }
}

// ---------------------------------------------------------------- control screen
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ControlScreen(conn: SofaConnection, ctrl: SofaController, voiceHint: String?, a: SofaActions) {
    var tab by remember { mutableStateOf(0) }
    var diag by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column(Modifier.combinedClickable(onClick = {}, onLongClick = { diag = !diag })) {
                    Text("uDiva 沙發", style = MaterialTheme.typography.titleLarge)
                    Text((conn.deviceName ?: "") + "  ·  " + (if (conn.linkReady) "已連線" else "連線中"),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (conn.linkReady) SofaColors.Sage else SofaColors.Champagne)
                }
            },
            actions = { TextButton(onClick = a.disconnect) { Text("中斷", color = SofaColors.Muted) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = SofaColors.Ink)
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Segment("控制", tab == 0) { tab = 0 }
            Segment("使用說明", tab == 1) { tab = 1 }
        }
        AnimatedVisibility(visible = diag) { DiagnosticsPanel(conn, a) }
        Box(Modifier.weight(1f)) { if (tab == 0) ControlBody(conn, ctrl, voiceHint, a) else GuideBody() }

        // sticky bottom: 暫停 / 繼續 / 停止
        Surface(color = SofaColors.Surface, tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = a.pause, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(6.dp)); Text("暫停")
                }
                FilledTonalButton(onClick = a.resume, modifier = Modifier.weight(1f).height(52.dp)) {
                    Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("繼續")
                }
                Button(onClick = a.stop, modifier = Modifier.weight(1.3f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SofaColors.Coral, contentColor = SofaColors.Ink)) {
                    Icon(Icons.Filled.Stop, null); Spacer(Modifier.width(6.dp)); Text("停止", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ControlBody(conn: SofaConnection, ctrl: SofaController, voiceHint: String?, a: SofaActions) {
    val status = conn.status
    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StatusCard(status) }

        item { SectionTitle("沙發調整", "長按移動，放開即停") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PostureCard(Posture.UPRIGHT, "直立坐姿", Icons.Filled.AirlineSeatReclineNormal, status?.posture == Posture.UPRIGHT, Modifier.weight(1f)) { a.posture(Posture.UPRIGHT, it) }
                PostureCard(Posture.RECLINE, "後躺放鬆", Icons.Filled.AirlineSeatReclineExtra, status?.posture == Posture.RECLINE, Modifier.weight(1f)) { a.posture(Posture.RECLINE, it) }
                PostureCard(Posture.SOFA, "一般沙發位置", Icons.Filled.Weekend, status?.posture == Posture.SOFA, Modifier.weight(1f)) { a.posture(Posture.SOFA, it) }
            }
        }

        item { SectionTitle("全身按摩", "按住移到躺平，到位自動開始") }
        item { FullBodyCard(ctrl.fullBodyStage, status, ctrl.want30, a) }

        item { SectionTitle("手動按摩") }
        item { ManualCard(ctrl.manual, status?.programNo == OsimProtocol.MANUAL_PROGRAM_NO, status?.posture == Posture.SOFA, status?.fault == true, a) }

        item { SectionTitle("音樂與語音") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(Modifier.weight(1f).clickable { a.spotify() }, colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Icon(Icons.Filled.MusicNote, null, tint = SofaColors.Sage); Spacer(Modifier.height(8.dp))
                        Text("音樂", style = MaterialTheme.typography.titleMedium)
                        Text("開啟 Spotify，由沙發喇叭播放", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                    }
                }
                Card(Modifier.weight(1f).clickable { a.voice() }, colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
                    Column(Modifier.padding(16.dp)) {
                        Icon(Icons.Filled.Mic, null, tint = SofaColors.Champagne); Spacer(Modifier.height(8.dp))
                        Text("語音控制", style = MaterialTheme.typography.titleMedium)
                        Text("說「坐直」「躺平」「沙發」「開始全身按摩」「停」", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                    }
                }
            }
        }
        voiceHint?.let { item { Tag("語音：$it") } }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun StatusCard(s: SofaStatus?) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigh)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusLine("目前位置", if (s == null) "讀取中…" else s.posture?.zh ?: "移動中／不在預設位置")
            StatusLine("腳托", when {
                s == null -> "—"
                s.footFrontLimit -> "伸出到底"
                s.footBackLimit -> "已收回"
                else -> "介於中間"
            })
            StatusLine("足部按摩架", when {
                s == null -> "—"
                s.ottoman -> "沙發偵測為腳凳模式"
                else -> "沙發偵測為腳部按摩模式"
            })
            if (s != null && s.ottoman) {
                Text("腳凳模式下腳托無法收回。若足部按摩架實際上已是腳部按摩模式，代表沙發的模式偵測與實際不符。",
                    style = MaterialTheme.typography.labelMedium, color = SofaColors.Champagne)
            }
            StatusLine("按摩", massageText(s))
            if (s != null && s.fault) {
                Spacer(Modifier.height(4.dp))
                Text("錯誤（代碼 ${s.errorCode}）：${s.errorText}", style = MaterialTheme.typography.titleMedium, color = SofaColors.Coral)
                Text("${SofaStatus.FAULT_ADVICE} 排除前不會自動移動或啟動按摩。", style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
            }
        }
    }
}

private fun massageText(s: SofaStatus?): String {
    if (s == null) return "—"
    val program = AutoProgram.byNumber(s.programNo)
    val what = when {
        s.programNo == 0 -> return "待機（${if (s.is30min) 30 else 15} 分鐘）"
        s.programNo == OsimProtocol.MANUAL_PROGRAM_NO -> "手動按摩"
        s.programNo == OsimProtocol.VGRIP_PROGRAM_NO -> "V-Grip"
        program != null -> program.zh
        else -> "程式 ${s.programNo}"
    }
    return if (s.paused) "$what · 暫停中" else "$what 進行中 · 剩餘約 ${s.remainingMin} 分"
}

@Composable private fun StatusLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(76.dp), style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun FullBodyCard(stage: Stage, status: SofaStatus?, want30: Boolean, a: SofaActions) {
    var pressed by remember { mutableStateOf(false) }
    val atPosition = status?.posture == AutoProgram.SIGNATURE.prepare
    val sub = if (status?.fault == true && stage != Stage.RUNNING) "沙發回報錯誤，排除前無法使用" else when (stage) {
        Stage.MOVING -> "移到躺平中…放開即停"
        Stage.STARTING -> "已到位，啟動中…"
        Stage.RUNNING -> "進行中 · 剩餘約 ${status?.remainingMin ?: "–"} 分"
        Stage.NOT_REACHED -> "還沒到躺平位置。再按住即可繼續移動"
        Stage.UNCONFIRMED -> "已送出，但沙發沒有回報啟動"
        Stage.IDLE -> if (atPosition) "已在躺平位置，按一下即開始" else "按住：移到躺平後自動開始"
    }
    Card(
        Modifier.fillMaxWidth().pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressed = true; a.fullBody(true)
                try { awaitRelease() } finally { pressed = false; a.fullBody(false) }
            })
        },
        colors = CardDefaults.cardColors(containerColor = if (pressed) SofaColors.SurfaceHigher else SofaColors.Surface),
        border = BorderStroke(if (pressed) 2.dp else 1.dp, if (pressed || stage == Stage.RUNNING) SofaColors.Champagne else SofaColors.Line)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(SofaColors.SurfaceHigh, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Spa, null, tint = SofaColors.Sage)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("全身按摩", style = MaterialTheme.typography.titleMedium)
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("時間", style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
                Chip("15 分", !want30, Modifier.width(72.dp)) { a.setWant30(false) }
                Chip("30 分", want30, Modifier.width(72.dp)) { a.setWant30(true) }
                Spacer(Modifier.weight(1f))
                if (stage == Stage.NOT_REACHED || stage == Stage.UNCONFIRMED) {
                    TextButton(onClick = a.fullBodyStartAnyway) { Text(if (stage == Stage.UNCONFIRMED) "重試" else "直接開始") }
                }
            }
        }
    }
}

@Composable
private fun ManualCard(s: ManualSettings, running: Boolean, atSofa: Boolean, fault: Boolean, a: SofaActions) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceRow("揉捏／敲打", KneadTap.entries, s.kneadTap, { it.zh }) { a.changeManual(s.copy(kneadTap = it)) }
            ChoiceRow("滾動", Rolling.entries, s.rolling, { it.zh }) { a.changeManual(s.copy(rolling = it)) }
            ChoiceRow("背部速度", BackSpeed.entries, s.speed, { it.zh }) { a.changeManual(s.copy(speed = it)) }
            ChoiceRow("滾輪寬度", RollerWidth.entries, s.width, { it.zh }) { a.changeManual(s.copy(width = it)) }
            LevelStepper("肩部力度", s.shoulder) { a.changeManual(s.copy(shoulder = it)) }
            LevelStepper("背部力度", s.back) { a.changeManual(s.copy(back = it)) }
            LevelStepper("腰部力度", s.lumbar) { a.changeManual(s.copy(lumbar = it)) }
            ChoiceRow("足部揉捏", Level.entries, s.footKneading, { it.zh }) { a.changeManual(s.copy(footKneading = it)) }
            ChoiceRow("足部振動", Level.entries, s.footVibration, { it.zh }) { a.changeManual(s.copy(footVibration = it)) }
            ChoiceRow("臀部振動", Level.entries, s.seatVibration, { it.zh }) { a.changeManual(s.copy(seatVibration = it)) }
            if (running) {
                Text("手動按摩進行中，調整會立即生效", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Sage)
            } else if (fault) {
                Text("沙發回報錯誤，排除前無法開始手動按摩", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Coral)
            } else {
                if (atSofa) Text("背部按摩需要先「坐直」或「躺平」", style = MaterialTheme.typography.labelMedium, color = SofaColors.Champagne)
                Button(onClick = a.startManual, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("開始手動按摩") }
            }
        }
    }
}

@Composable
private fun GuideBody() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GuideCard("連線沙發", listOf(
            "開啟 App 後，將手機靠近沙發感應區，會自動連線並進入控制畫面。",
            "若沒有反應，點「手動搜尋沙發」，或從清單選擇。",
        ))
        GuideCard("沙發調整（三種）", listOf(
            "坐直、躺平、沙發：長按移動，放開就停。",
            "上方「目前位置」會顯示沙發已到哪個位置。",
        ))
        GuideCard("全身按摩", listOf(
            "按住「全身按摩」，沙發會移到躺平位置，到位後自動開始；中途放開就停。",
            "已在躺平位置時，按一下就開始。可先選 15 或 30 分鐘。",
        ))
        GuideCard("手動按摩", listOf(
            "選好揉捏／敲打、滾動、速度、寬度、力度與足部、臀部，按「開始手動按摩」。",
            "進行中調整會立即生效。背部按摩需要先坐直或躺平。",
        ))
        GuideCard("語音控制（中文）", listOf(
            "點「語音控制」後說：「坐直」「躺平」「沙發」「開始全身按摩」「開始手動按摩」",
            "「加強」「減弱」「加速」「減速」「暫停」「繼續」「停」。",
            "同音或說法相近也聽得懂，例如辨識成「做直」也會當成「坐直」。",
            "語音移動最多 8 秒；全身按摩到位後自動開始，說「停」即可停止。",
        ))
        GuideCard("音樂", listOf("點「音樂」開啟 Spotify；在手機藍牙把沙發設為音訊輸出即可由沙發喇叭播放。"))
        GuideCard("安全提醒", listOf("沙發移動時請確認周圍淨空；異常時立即按「停止」或關閉沙發電源。"))
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- diagnostics (hidden)
@Composable
private fun DiagnosticsPanel(conn: SofaConnection, a: SofaActions) {
    var hex by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigher)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("診斷", style = MaterialTheme.typography.titleMedium, color = SofaColors.Sky)
            Text("變體：${conn.variant ?: "-"}　MTU：${conn.mtu}　狀態：${conn.status?.summary() ?: "-"}", style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
            Text("最近回應：${conn.lastRx ?: "-"}", style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hex, { hex = it }, Modifier.weight(1f), placeholder = { Text("raw hex") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii), singleLine = true)
                FilledTonalButton(onClick = { a.sendRaw(hex) }) { Text("送出") }
            }
            Box(Modifier.fillMaxWidth().height(170.dp).background(SofaColors.Ink, MaterialTheme.shapes.small).padding(8.dp)) {
                LazyColumn { items(conn.logLines.asReversed()) { Text(it, style = MaterialTheme.typography.labelMedium, color = SofaColors.Text) } }
            }
        }
    }
}

// ---------------------------------------------------------------- components
@Composable private fun SectionTitle(title: String, hint: String? = null) {
    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (hint != null) { Spacer(Modifier.width(10.dp)); Text(hint, style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted) }
    }
}

@Composable private fun Segment(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(Modifier.clickable(onClick = onClick), shape = CircleShape,
        color = if (selected) SofaColors.Champagne else SofaColors.Surface,
        contentColor = if (selected) SofaColors.OnChampagne else SofaColors.Text) {
        Text(label, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), shape = CircleShape,
        color = if (selected) SofaColors.Champagne else SofaColors.SurfaceHigh,
        contentColor = if (selected) SofaColors.OnChampagne else SofaColors.Text) {
        Text(label, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

@Composable private fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: (T) -> String, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o -> Chip(name(o), o == selected, Modifier.weight(1f)) { onPick(o) } }
        }
    }
}

@Composable private fun LevelStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange(value - 1) }, enabled = value > OsimProtocol.INTENSITY.first,
            shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("$value", Modifier.width(40.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Button(onClick = { onChange(value + 1) }, enabled = value < OsimProtocol.INTENSITY.last,
            shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) { Text("＋", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable private fun Tag(text: String) {
    Surface(shape = CircleShape, color = SofaColors.SurfaceHigh, contentColor = SofaColors.Champagne) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 2.dp)) { Text("•  ", color = SofaColors.Champagne); Text(text, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted) }
}

@Composable private fun PostureCard(posture: Posture, sub: String, icon: ImageVector, current: Boolean, modifier: Modifier, onHold: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Card(modifier.height(128.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                pressed = true; onHold(true)
                try { awaitRelease() } finally { pressed = false; onHold(false) }
            })
        },
        colors = CardDefaults.cardColors(containerColor = if (pressed) SofaColors.SurfaceHigher else SofaColors.Surface),
        border = BorderStroke(if (pressed || current) 2.dp else 1.dp,
            when { pressed -> SofaColors.Champagne; current -> SofaColors.Sage; else -> SofaColors.Line })) {
        Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, null, tint = SofaColors.Champagne, modifier = Modifier.size(30.dp))
            Column {
                Text(posture.zh, style = MaterialTheme.typography.titleMedium)
                Text(when { pressed -> "移動中…"; current -> "目前位置"; else -> sub },
                    style = MaterialTheme.typography.labelMedium, color = if (current && !pressed) SofaColors.Sage else SofaColors.Muted)
            }
        }
    }
}

@Composable private fun GuideCard(title: String, lines: List<String>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SofaColors.Champagne); Spacer(Modifier.height(6.dp))
            lines.forEach { Bullet(it) }
        }
    }
}
