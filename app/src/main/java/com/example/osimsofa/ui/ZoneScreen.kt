package com.example.osimsofa.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.osimsofa.Names
import com.example.osimsofa.OsimProtocol
import com.example.osimsofa.OsimProtocol.Area
import com.example.osimsofa.OsimProtocol.AutoProgram
import com.example.osimsofa.OsimProtocol.BackSpeed
import com.example.osimsofa.OsimProtocol.FootSeat
import com.example.osimsofa.OsimProtocol.KneadTap
import com.example.osimsofa.OsimProtocol.Level
import com.example.osimsofa.OsimProtocol.ManualSettings
import com.example.osimsofa.OsimProtocol.Posture
import com.example.osimsofa.OsimProtocol.RollerWidth
import com.example.osimsofa.OsimProtocol.Rolling
import com.example.osimsofa.SofaController
import com.example.osimsofa.SofaStatus
import com.example.osimsofa.Zone
import com.example.osimsofa.ZoneFocus
import com.example.osimsofa.anyOn
import com.example.osimsofa.zoneOn

/**
 * What the zone screen shows: an auto program from the sofa's report (the settings of a next manual
 * massage mean nothing then), everything else from the manual settings.
 */
private class ZoneView(val s: SofaStatus?, val m: ManualSettings) {
    val auto = s != null && AutoProgram.byNumber(s.programNo) != null
    val manualRunning = s?.programNo == OsimProtocol.MANUAL_PROGRAM_NO
    /** Any program at all, including one the app has no name for. */
    val otherProgram = s?.massageRunning == true && !auto && !manualRunning

    fun intensity(a: Area): Int = if (auto && s != null) s.intensity(a) else m.intensity(a)
    fun level(f: FootSeat): Level = if (auto && s != null) s.level(f) else m.level(f)

    fun on(z: Zone): Boolean = when {
        !auto -> m.zoneOn(z)
        z.onRollers -> true
        z == Zone.LEGS -> level(FootSeat.FOOT_KNEADING) != Level.OFF || level(FootSeat.FOOT_VIBRATION) != Level.OFF
        else -> level(FootSeat.SEAT_VIBRATION) != Level.OFF
    }

    /** 0–1, how bright the zone is on the body map. */
    fun strength(z: Zone): Float {
        if (!on(z)) return 0f
        z.area?.let { return intensity(it) / OsimProtocol.INTENSITY.last.toFloat() }
        val fs = if (z == Zone.LEGS) listOf(FootSeat.FOOT_KNEADING, FootSeat.FOOT_VIBRATION) else listOf(FootSeat.SEAT_VIBRATION)
        return if (fs.any { level(it) == Level.HIGH }) 1f else if (fs.any { level(it) == Level.LOW }) 0.45f else 0.7f
    }

    fun value(z: Zone): String = when (z) {
        Zone.SHOULDER, Zone.BACK, Zone.LUMBAR -> if (on(z)) "力度 ${intensity(z.area!!)}" else "關"
        Zone.LEGS -> {
            val knead = level(FootSeat.FOOT_KNEADING)
            val vibrate = level(FootSeat.FOOT_VIBRATION)
            when {
                !on(z) -> "關"
                knead == vibrate -> "揉捏、振動${knead.zh}"     // one line on a narrow phone
                else -> "揉捏${knead.zh}・振動${vibrate.zh}"
            }
        }
        Zone.SEAT -> if (on(z)) "振動${level(FootSeat.SEAT_VIBRATION).zh}" else "關"
    }
}

/** The 部位按摩 tab: pick a part of the body, adjust that part, start it. */
@Composable
internal fun ZoneTab(s: SofaStatus?, age: Long?, ctrl: SofaController, a: SofaActions, zone: Zone, onZone: (Zone) -> Unit, padding: PaddingValues) {
    val view = ZoneView(s, ctrl.manual)
    LazyColumn(
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "banners") { Banners(s, age) }
        item(key = "map") { ZoneMapCard(view, zone, onZone) }
        item(key = "panel") { ZonePanel(view, zone, a) }
        item(key = "manual") { ManualFooter(view, zone, a) }
    }
}

@Composable
private fun ZoneMapCard(view: ZoneView, zone: Zone, onZone: (Zone) -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface)) {
        BoxWithConstraints(Modifier.padding(14.dp)) {
            // Hidden only when it would squeeze the list: 360 dp at font scale 1.3 keeps it.
            val noRoom = LocalDensity.current.fontScale > 1.4f || maxWidth < 260.dp
            val rows: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Zone.entries.forEach { z -> ZoneRow(z, view.value(z), view.on(z), z == zone) { onZone(z) } }
                }
            }
            if (noRoom) {
                rows(Modifier.fillMaxWidth())       // the body is a picture of the list: the list is enough
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BodyMap(view, zone, onZone, Modifier.width(100.dp))
                    Spacer(Modifier.width(12.dp))
                    rows(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ZoneRow(z: Zone, value: String, on: Boolean, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(if (selected) SofaColors.SurfaceHigher else SofaColors.SurfaceHigh, MaterialTheme.shapes.small)
            .semantics(mergeDescendants = true) { role = Role.Tab; this.selected = selected }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(if (on) SofaColors.Champagne else SofaColors.Line, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(z.zh, style = MaterialTheme.typography.titleMedium,
                color = if (selected) SofaColors.Champagne else SofaColors.Text)
            Text(value, style = MaterialTheme.typography.labelMedium, color = SofaColors.Muted)
        }
    }
}

/**
 * The body from behind, its zones lit by strength. Decorative for TalkBack: [ZoneRow] says the same
 * and is what it navigates. Tapping picks by height, so a thumb need not hit the shape exactly.
 */
@Composable
private fun BodyMap(view: ZoneView, zone: Zone, onZone: (Zone) -> Unit, modifier: Modifier) {
    val massaging = view.auto || view.manualRunning
    val pulse by rememberInfiniteTransition(label = "zones").animateFloat(
        0.82f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "glow")
    Canvas(
        modifier.aspectRatio(DESIGN_W / DESIGN_H)
            .clearAndSetSemantics {}
            .pointerInput(Unit) {
                detectTapGestures { p -> zoneAt(p.y / size.height * DESIGN_H)?.let(onZone) }
            },
    ) {
        val u = size.height / DESIGN_H
        fun o(x: Float, y: Float) = Offset(x * u, y * u)
        val cx = DESIGN_W / 2f
        val body = bodyPath(u)

        drawCircle(SofaColors.SurfaceHigher, radius = 12f * u, center = o(cx, 13f))
        // Arms: they rest on the sofa, they are not a zone.
        listOf(-1f, 1f).forEach { side ->
            val arm = Path().apply {
                addRoundRect(RoundRect(left = (cx + side * 33f - 5.5f) * u, top = 40f * u,
                    right = (cx + side * 33f + 5.5f) * u, bottom = 118f * u, cornerRadius = CornerRadius(5.5f * u)))
            }
            drawPath(arm, SofaColors.SurfaceHigh)
        }
        clipPath(body) {
            drawRect(SofaColors.SurfaceHigher, size = size)
            ZONE_BANDS.forEach { (z, band) ->
                val glow = view.strength(z) * (if (massaging && view.on(z)) pulse else 1f)
                val color = when {
                    z == zone -> SofaColors.Champagne
                    glow > 0f -> SofaColors.ChampagneDeep.copy(alpha = 0.22f + 0.58f * glow)
                    else -> SofaColors.SurfaceHigher
                }
                drawRect(color, topLeft = o(0f, band.start), size = Size(size.width, (band.endInclusive - band.start) * u))
                drawLine(SofaColors.Surface, o(0f, band.start), o(DESIGN_W, band.start), strokeWidth = 1.5f * u)
            }
        }
    }
}

private const val DESIGN_W = 100f
private const val DESIGN_H = 222f

/** Heights on the body, in design units (top of the head = 0). */
private val ZONE_BANDS: List<Pair<Zone, ClosedFloatingPointRange<Float>>> = listOf(
    Zone.SHOULDER to 28f..54f,
    Zone.BACK to 54f..82f,
    Zone.LUMBAR to 82f..104f,
    Zone.SEAT to 104f..130f,
    Zone.LEGS to 130f..222f,
)

private fun zoneAt(y: Float): Zone? = ZONE_BANDS.firstOrNull { y in it.second }?.first

private fun bodyPath(u: Float): Path = Path().apply {
    fun m(x: Float, y: Float) = moveTo(x * u, y * u)
    fun l(x: Float, y: Float) = lineTo(x * u, y * u)
    fun c(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
        cubicTo(x1 * u, y1 * u, x2 * u, y2 * u, x * u, y * u)
    // Neck, shoulders, waist, hips — then down the outside of a leg, up the inside, and mirrored.
    m(44f, 24f); l(56f, 24f); l(57f, 31f)
    c(66f, 34f, 75f, 36f, 80f, 44f)
    c(77f, 62f, 71f, 76f, 69f, 90f)
    c(70f, 100f, 76f, 110f, 76f, 126f)
    c(76f, 150f, 71f, 170f, 69f, 204f)
    c(71f, 212f, 73f, 218f, 69f, 221f); l(56f, 221f)
    c(56f, 214f, 58f, 210f, 57f, 204f)
    c(56f, 180f, 54f, 150f, 50f, 132f)
    c(46f, 150f, 44f, 180f, 43f, 204f)
    c(42f, 210f, 44f, 214f, 44f, 221f); l(31f, 221f)
    c(27f, 218f, 29f, 212f, 31f, 204f)
    c(29f, 170f, 24f, 150f, 24f, 126f)
    c(24f, 110f, 30f, 100f, 31f, 90f)
    c(29f, 76f, 23f, 62f, 20f, 44f)
    c(25f, 36f, 34f, 34f, 43f, 31f)
    close()
}

// ---------------------------------------------------------------- the zone itself

@Composable
private fun ZonePanel(view: ZoneView, zone: Zone, a: SofaActions) {
    val m = view.m
    val s = view.s
    val rollersLocked = view.auto || view.otherProgram
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.Surface),
        border = BorderStroke(1.dp, if (view.on(zone)) SofaColors.ChampagneDeep else SofaColors.Line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(zone.zh, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            when (zone) {
                Zone.SHOULDER, Zone.BACK, Zone.LUMBAR -> {
                    val area = zone.area!!
                    LevelPicker("${zone.zh}力度", view.intensity(area)) { a.setIntensity(area, it) }
                    if (zone == Zone.BACK) {
                        val editable = !rollersLocked
                        ChoiceRow("揉捏／敲打", KneadTap.entries, m.kneadTap, { it.zh }, enabled = editable) { a.changeManual(m.copy(kneadTap = it)) }
                        ChoiceRow("滾動", Rolling.entries, m.rolling, { it.zh }, enabled = editable) { a.changeManual(m.copy(rolling = it)) }
                        ChoiceRow("背部速度", BackSpeed.entries, m.speed, { it.zh }, enabled = editable) { a.changeManual(m.copy(speed = it)) }
                        ChoiceRow("滾輪寬度", RollerWidth.entries, m.width, { it.zh }, enabled = editable) { a.changeManual(m.copy(width = it)) }
                        if (rollersLocked) Note("按摩程式進行中，手法由程式決定；力度仍可調整")
                    }
                    Note("肩、背、腰共用同一組滾輪：力度可以分開調，開關只能一起。")
                }
                Zone.LEGS -> {
                    FunctionRow(FootSeat.FOOT_KNEADING, view, a)
                    FunctionRow(FootSeat.FOOT_VIBRATION, view, a)
                }
                Zone.SEAT -> FunctionRow(FootSeat.SEAT_VIBRATION, view, a)
            }
            FocusAction(view, zone, s, a)
        }
    }
}

/** An auto program only takes these on or off (OSIM's own screen does the same). */
@Composable
private fun FunctionRow(f: FootSeat, view: ZoneView, a: SofaActions) {
    val options = if (view.auto) listOf(Level.OFF, Level.AUTO) else Level.entries
    val current = view.level(f).let { if (view.auto && it != Level.OFF) Level.AUTO else it }
    ChoiceRow(Names.function(f), options, current, { it.zh }) { a.setFunction(f, it) }
}

@Composable
private fun FocusAction(view: ZoneView, zone: Zone, s: SofaStatus?, a: SofaActions) {
    when {
        view.auto || view.otherProgram -> {}
        view.manualRunning ->
            OutlinedButton(onClick = { a.focusZone(zone) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("加強${zone.zh}")
            }
        else -> {
            val blocked = s?.let { ZoneFocus.blocked(zone, it) }
            blocked?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne) }
            Button(onClick = { a.focusZone(zone) }, enabled = blocked == null, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("只按摩${zone.zh}")
            }
        }
    }
}

/** Five stops, each its own target: faster than − ＋ for someone lying down, and read out by TalkBack. */
@Composable
private fun LevelPicker(label: String, value: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
            Text("$value", style = MaterialTheme.typography.titleLarge, color = SofaColors.Champagne, fontWeight = FontWeight.SemiBold)
            Text(" / ${OsimProtocol.INTENSITY.last}", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OsimProtocol.INTENSITY.forEach { n -> Chip("$n", n == value, Modifier.weight(1f)) { onPick(n) } }
        }
    }
}

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)

@Composable
private fun ManualFooter(view: ZoneView, zone: Zone, a: SofaActions) {
    val s = view.s
    val m = view.m
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = SofaColors.SurfaceHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Names.MANUAL, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            // The settings of a next manual massage say nothing about a program that is running.
            if (!view.auto && !view.otherProgram) {
                Text("${m.kneadTap.zh}・${m.rolling.zh}滾動・力度 ${m.shoulder}/${m.back}/${m.lumbar}", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
            }
            if (view.manualRunning && s != null) {
                Text("沙發回報：肩 ${s.shoulderIntensity}・背 ${s.backIntensity}・腰 ${s.lumbarIntensity}・速度${s.backSpeed.zh}",
                    style = MaterialTheme.typography.bodyMedium, color = SofaColors.Sage)
                Text("手動按摩進行中：調整會立即送出", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Sage)
            } else if (!view.auto && !view.otherProgram) {
                Text("這些設定在開始時套用", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Muted)
                // The panel above already says so when it is about the zone on screen.
                if (s?.posture == Posture.SOFA && ZoneFocus.blocked(zone, s) == null) {
                    Text("背部按摩需要先調整為「坐式」或「躺平」", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne)
                }
                if (!m.anyOn()) Text("所有部位都是關閉的", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Champagne)
                Button(onClick = a.startManual, enabled = m.anyOn(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("開始手動按摩", textAlign = TextAlign.Center)
                }
            } else {
                Text("${Names.massage(s)}", style = MaterialTheme.typography.bodyMedium, color = SofaColors.Sage)
            }
        }
    }
}
