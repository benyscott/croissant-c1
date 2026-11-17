package com.bleural.croissant.ui.beam

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Velocity
import android.text.format.DateFormat
import java.time.OffsetDateTime
import com.bleural.croissant.AgentAlert
import com.bleural.croissant.AgentDone
import com.bleural.croissant.AskOption
import com.bleural.croissant.DeviceStatus
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Links
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.ControlState
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.Ink
import com.bleural.croissant.ui.LocalSheet
import com.bleural.croissant.ui.PowerAction
import com.bleural.croissant.ui.SheetAction
import com.bleural.croissant.ui.SheetState
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.clock
import com.bleural.croissant.ui.rememberMinuteTick
import com.bleural.croissant.ui.held
import com.bleural.croissant.ui.tappable
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * What hangs over every Beam screen from the shell: the control centre and the sheet any screen
 * asks for through [LocalSheet]. Nothing is drawn round either: the page under them goes dark and
 * the controls sit on it. Their words and keys are white, grey and the danger red, the same over
 * any feature; [Ramp] only carries those.
 */

/** What slides down with the control centre, page pixels from the top of the canvas: everything it draws, and the drag's measure. */
private const val PANEL = 420f

/**
 * The two levels' left edges and the six keys' centres, 32 clear of either side: two columns 110
 * apart, three rows 110 apart, the middle row on the levels' middle (227).
 */
private const val VOLUME_X = 32
private const val DISPLAY_X = 116
private const val KEYS_LEFT = 296f
private const val KEYS_RIGHT = 406f
// Two rows, 110 apart, on the levels' middle (227).
private const val KEYS_TOP = 172f
private const val KEYS_BOTTOM = 282f
/** The top line's middle: the time, the signal and the battery, over the levels at 70. */
private const val TOP_MID = 39f
/** The battery: a 50 x 28 body and a 3 x 10 nub, its right 32 clear of the edge; the level in it at 22. */
private const val BATT_W = 50f
private const val BATT_H = 28f
private const val NUB_W = 3f
private const val NUB_H = 10f
private val BATT_TYPE = BeamType.Value.copy(fontSize = 22f.pxSp, fontWeight = FontWeight.Medium, letterSpacing = 0f.pxSp)
private const val BATT_X = 480f - 32f - BATT_W - NUB_W
/** The signal bars' gap to the battery. */
private const val BARS_GAP = 16f

/** A level's segments: eight, 56 x 22, 30 apart, the top one at y 86, the bottom one ending at 318. */
private const val SEGMENTS = 8
private const val SEG_TOP = 86f
private const val SEG_W = 56f
private const val SEG_H = 22f
private const val SEG_PITCH = 30f
private const val SEG_SPAN = SEG_PITCH * (SEGMENTS - 1) + SEG_H
/** A level's glyph's top, 18 under its last segment. */
private const val LEVEL_GLYPH = 336f

/**
 * The control centre, with the room's pull and state ([ControlState]): the page
 * under it dimmed to 10% (black at 90%) and what it holds sliding down from the top. Sound and
 * Display as two levels of eight stacked segments (56 x 22, 8 apart, lit white from the bottom,
 * from y 86 to 318) with their glyphs under them at 336; four round keys of 84 to their right,
 * 110 apart both ways, centred on 172 and 282: Wi-Fi and Bluetooth, Sound and Power. Wi-Fi,
 * Bluetooth and Sound are white with a black glyph when on and a grey ring when off; Power a red
 * ring. Over them a top line: the time at the left, the signal and the battery at the right
 * ([BatteryMark]). Everything 32 px clear of the sides. The tailnet has no key: it stays on
 * (2026-11-17; Settings > Tailscale still turns it). A tap or a drag on a level sets it (a muted
 * volume's segments are grey); a tap on Wi-Fi or Bluetooth flips it and a long press opens its
 * page; Sound mutes; the battery opens Battery; Power offers Sleep, Restart and Power off through the sheet. A tap on the dark
 * closes it, a drag on what it holds pushes it back. The wheel does nothing while it is open.
 *
 * It was 34 px lower, with four keys and a top bar of its own over the dark, until the top bar
 * went (2026-10-01): the tailnet and the battery it showed are keys here now.
 */
@Composable
internal fun BeamControlCenter(
    state: ControlState,
    device: DeviceStatus,
    volume: Int,
    brightness: Int,
    muted: Boolean,
    r: Ramp,
    onVolume: (Int) -> Unit,
    onBrightness: (Int) -> Unit,
    onMute: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit,
    onBluetooth: (Boolean) -> Unit,
    onTailnet: (Boolean) -> Unit,
    onOpenWifi: () -> Unit,
    onOpenBluetooth: () -> Unit,
    onOpenTailnet: () -> Unit,
    onOpenBattery: () -> Unit,
    onPower: (PowerAction) -> Unit,
    alerts: List<AgentAlert> = emptyList(),
    onOpenAlert: (AgentAlert) -> Unit = {},
    onPickAlert: (AgentAlert, AskOption?) -> Unit = { _, _ -> },
    done: List<AgentDone> = emptyList(),
    onOpenDone: (AgentDone) -> Unit = {},
    onDismissDone: (AgentDone) -> Unit = {},
) {
    val sheet = LocalSheet.current
    // Whether it is on screen at all; the number itself is read in draw and layer blocks only.
    val showing by remember(state) { derivedStateOf { state.shown.value > 0f } }
    val twentyFour = DateFormat.is24HourFormat(LocalContext.current)
    val minute by rememberMinuteTick()
    val time = remember(minute, twentyFour) { clock(OffsetDateTime.now(), twentyFour).lowercase() }
    // The controls and the notices scroll as one (2026-11-17): a drag scrolls them, and what is
    // left of an upward drag once they are at their end pushes the whole thing back, as a drag on
    // the controls alone did before. Put away, it opens again at the top.
    val scroll = rememberScrollState()
    LaunchedEffect(state.open) { if (!state.open) scroll.scrollTo(0) }
    val push = remember(state) { pushBack(state) }
    Box(Modifier.fillMaxSize()) {
        if (showing) Box(Modifier.fillMaxSize().drawBehind { drawRect(r.ground, alpha = 0.9f * state.shown.value) }.tappable { state.close() })
        Column(
            Modifier.fillMaxWidth().heightIn(max = SCREEN_H.px)
                .graphicsLayer { translationY = size.height * (state.shown.value - 1f) }
                .onSizeChanged { state.height = it.height }
                .nestedScroll(push)
                .verticalScroll(scroll),
        ) {
        Box(Modifier.fillMaxWidth().height(PANEL.px)) {
            // The top line: the time at the left; the signal and the battery at the right.
            Box(Modifier.offset(x = VOLUME_X.px, y = (TOP_MID - 20f).px).height(40.px), contentAlignment = Alignment.CenterStart) {
                Text(time, style = BeamType.Value, color = r.ink, maxLines = 1)
            }
            // The signal left of the battery; nothing without a SIM in use.
            device.cell?.let { cell ->
                Canvas(Modifier.offset(x = (BATT_X - BARS_GAP - BARS_W).px, y = (TOP_MID - BARS_H / 2).px).size(BARS_W.px, BARS_H.px)) {
                    cellBars(cell, 0f, 0f, size.width / BARS_W, r.ink, r.sub, r.deep)
                }
            }
            BatteryMark(device, r, onClick = onOpenBattery)
            LevelKey(VOLUME_X, if (muted) Glyphs.SoundOff else Glyphs.Sound, volume, r, faded = muted) { if (muted) onMute(false); onVolume(it) }
            LevelKey(DISPLAY_X, Glyphs.Display, brightness, r, faded = false, onChange = onBrightness)
            // Four keys (2026-11-17): the tailnet stays on, so its key went; the battery is the top line's.
            RoundKey(KEYS_LEFT, KEYS_TOP, Glyphs.Wifi, if (device.wifiEnabled) r.ink else null, r.sub, r, onClick = {
                Links.touched(); onWifi(!device.wifiEnabled); Cues.play(if (device.wifiEnabled) Sound.SwitchOff else Sound.SwitchOn)
            }, onLongClick = onOpenWifi)
            RoundKey(KEYS_RIGHT, KEYS_TOP, Glyphs.Bluetooth, if (device.bluetoothOn) r.ink else null, r.sub, r, onClick = {
                onBluetooth(!device.bluetoothOn); Cues.play(if (device.bluetoothOn) Sound.SwitchOff else Sound.SwitchOn)
            }, onLongClick = onOpenBluetooth)
            // Muting sounds its switch before the gain goes, unmuting after it is back: both are heard.
            RoundKey(KEYS_LEFT, KEYS_BOTTOM, if (muted) Glyphs.SoundOff else Glyphs.Sound, if (muted) null else r.ink, r.sub, r, onClick = {
                if (muted) { onMute(false); Cues.play(Sound.SwitchOn) } else { Cues.play(Sound.SwitchOff); onMute(true) }
            })
            RoundKey(KEYS_RIGHT, KEYS_BOTTOM, Glyphs.Power, null, r.danger, r, onClick = {
                sheet.show(listOf(
                    SheetAction("sleep", r.ink, Glyphs.Moon) { state.close(null); onPower(PowerAction.Sleep) },
                    SheetAction("restart", r.ink, Glyphs.Restart) { Cues.play(Sound.Commit); onPower(PowerAction.Reboot) },
                    SheetAction("power off", DANGER, Glyphs.Power) { Cues.play(Sound.Destroy); onPower(PowerAction.PowerOff) },
                ))
            })
        }
        // What waits on you, under the keys, scrolling with them: a permission of any session, then turns that ended.
        if (showing) {
            alerts.forEach { a -> AlertCard(a, onOpen = { onOpenAlert(a) }, onPick = { o -> onPickAlert(a, o) }) }
            done.forEach { d -> DoneCard(d, onOpen = { onOpenDone(d) }, onDismiss = { onDismissDone(d) }) }
        }
        }
        // Closed, a dot at the top right says something waits, in the hue of its agent: pull down to see it.
        val first = alerts.firstOrNull()?.agent ?: done.firstOrNull()?.agent
        if (first != null && !state.open) Canvas(Modifier.align(Alignment.TopEnd).offset(x = (-16).px, y = 16.px).size(14.px)) { drawCircle(agentLook(first).hue) }
    }
    if (state.open) WheelTarget { false }
}

/** The screen's height in page px: the most the controls and the notices under them take. */
private const val SCREEN_H = 640f

/**
 * The control centre's drag, now that the controls scroll with the notices: what the scroll
 * leaves of an upward drag (it is at its end, or there is nothing to scroll) pushes the panel
 * back; while it is partly back, a downward drag brings it down before anything scrolls; the
 * finger lifting settles it as [ControlState.release] does.
 */
private fun pushBack(state: ControlState) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f || state.shown.value >= 1f) return Offset.Zero
        state.drag(available.y)
        return Offset(0f, available.y)
    }
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
        state.drag(available.y)
        return Offset(0f, available.y)
    }
    override suspend fun onPreFling(available: Velocity): Velocity {
        if (state.shown.value >= 1f) return Velocity.Zero
        state.release()
        return available
    }
}

/**
 * Who a notice is from, over it: the folder and the agent in grey, the conversation's title in
 * the agent's hue; a tap opens the conversation.
 */
@Composable
private fun NoticeHead(folder: String, agent: String, title: String, r: Ramp, onOpen: () -> Unit) {
    Column(Modifier.fillMaxWidth().tappable(onOpen).padding(start = 20.px, end = 20.px, top = 16.px)) {
        Text("$folder · $agent", style = BeamType.Value, color = SUB, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (title.isNotBlank()) Text(title, style = BeamType.Value, color = r.hue, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A permission waiting in a session, in the control centre: the chat's own question card
 * ([Question], in the agent's hue) with who it is from over it. A tap on that opens the
 * session's chat; an option answers it from here.
 */
@Composable
private fun AlertCard(a: AgentAlert, onOpen: () -> Unit, onPick: (AskOption?) -> Unit) {
    val r = agentLook(a.agent).ramp
    Question(a.asking.title, null, a.asking.options, r, head = { NoticeHead(a.folder, a.agent, a.title, r, onOpen) }, after = 8f) { o ->
        Cues.play(if (o == null || o.kind.startsWith("reject")) Sound.Destroy else Sound.Commit)
        onPick(o)
    }
}

/**
 * A turn that ended while its chat was not on screen: framed as the question card, the start of
 * what the agent said (in danger when the turn failed), then open (in the hue) and dismiss.
 */
@Composable
private fun DoneCard(d: AgentDone, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val r = agentLook(d.agent).ramp
    Box(Modifier.padding(start = 16.px, end = 16.px, top = 8.px).fillMaxWidth().border(3.px, r.hue, RoundedCornerShape(16.px))) {
        Column(Modifier.fillMaxWidth()) {
            NoticeHead(d.folder, d.agent, d.title, r, onOpen)
            Text(d.error ?: d.words.ifBlank { "done" }, style = BeamType.Body, color = if (d.error != null) r.danger else INK,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().tappable(onOpen).padding(start = 20.px, end = 20.px, top = 8.px))
            Row(Modifier.padding(start = 20.px, end = 20.px, top = 24.px, bottom = 22.px), horizontalArrangement = Arrangement.spacedBy(12.px)) {
                Pill("open", r.hue, GROUND) { Cues.play(Sound.Commit); onOpen() }
                Pill("dismiss", SUB, GROUND) { Cues.play(Sound.Commit); onDismiss() }
            }
        }
    }
    Spacer(Modifier.height(8.px))
}

/**
 * A level of the control centre at x [x]: eight segments of 56 x 22 from y 86, 30 apart, lit
 * white from the bottom (grey when [faded]), the rest faint; its glyph 32 in white (grey when
 * faded) under them at y 336. A tap or a drag on it, or 16 px past either end, sets it; a
 * segment tapped lights up to itself.
 */
@Composable
private fun LevelKey(x: Int, glyph: ImageVector, value: Int, r: Ramp, faded: Boolean, onChange: (Int) -> Unit) {
    val latest = rememberUpdatedState(onChange)
    val at = { y: Float, u: Float -> (100 * (1 - (y / u - 16f) / SEG_SPAN)).roundToInt().coerceIn(0, 100) }
    val lit = ceil(value.coerceIn(0, 100) * SEGMENTS / 100f - 0.001f).toInt()
    val on = if (faded) r.sub else r.ink
    Box(
        Modifier.offset(x = x.px, y = (SEG_TOP - 16f).px).size(SEG_W.px, (SEG_SPAN + 32f).px)
            .pointerInput(Unit) { detectTapGestures { latest.value(at(it.y, 1.px.toPx())); Cues.play(Sound.Commit) } }
            .pointerInput(Unit) {
                detectVerticalDragGestures(onDragEnd = { Cues.play(Sound.Commit) }) { c, _ -> c.consume(); latest.value(at(c.position.y, 1.px.toPx())) }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / SEG_W
            for (i in 0 until SEGMENTS) {
                // i counts from the bottom.
                val top = 16f + (SEGMENTS - 1 - i) * SEG_PITCH
                drawRoundRect(if (i < lit) on else r.deep, Offset(0f, top * u), Size(SEG_W * u, SEG_H * u), CornerRadius(9f * u))
            }
        }
    }
    Icon(glyph, null, Modifier.offset(x = (x + 12).px, y = LEVEL_GLYPH.px).size(32.px), on)
}

/**
 * A round key of the control centre, 84 across, centred on ([cx], [cy]): filled in [fill]
 * with a black glyph 40 when given (a switch that is on), else a 3 px ring and the glyph in
 * [ring] (a switch that is off, grey; Power, red).
 */
@Composable
private fun RoundKey(cx: Float, cy: Float, glyph: ImageVector, fill: Color?, ring: Color, r: Ramp, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Box(
        Modifier.offset(x = (cx - 42f).px, y = (cy - 42f).px).size(84.px).clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill) else Modifier.border(3.px, ring, CircleShape))
            .held(onClick, onLongClick),
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, null, Modifier.size(40.px), fill?.let { r.ground } ?: ring) }
}

/**
 * The battery at the top line's right, as a phone draws it (2026-11-18): a rounded body outlined
 * in grey with a nub at the right, filled from the left to the level, the level's number in its
 * middle without a percent sign, black over the fill and white past it. The fill white; green
 * while it charges; the danger red at 15% and under off the charger. Not read yet, the outline
 * alone. A tap opens Battery. The number is 22, under the kit's 26, as it has to fit the body.
 */
@Composable
private fun BatteryMark(device: DeviceStatus, r: Ramp, onClick: () -> Unit) {
    val pct = device.batteryPct
    val fill = when {
        device.charging -> Ink.Ok
        pct in 0..15 -> r.danger
        else -> r.ink
    }
    val text = rememberTextMeasurer()
    // The tap reaches 12 round it.
    Box(
        Modifier.offset(x = (BATT_X - 12f).px, y = (TOP_MID - BATT_H / 2 - 12f).px)
            .size((BATT_W + NUB_W + 24f).px, (BATT_H + 24f).px).held(onClick, null),
    ) {
        Canvas(Modifier.offset(12.px, 12.px).size((BATT_W + NUB_W).px, BATT_H.px)) {
            val u = size.height / BATT_H
            val line = 2f * u
            val bw = BATT_W * u
            drawRoundRect(r.sub, Offset(line / 2, line / 2), Size(bw - line, size.height - line), CornerRadius(7f * u), style = Stroke(line))
            drawRoundRect(r.sub, Offset(bw, (BATT_H - NUB_H) / 2 * u), Size(NUB_W * u, NUB_H * u), CornerRadius(1.5f * u))
            if (pct < 0) return@Canvas
            val inner = 4f * u
            val edge = inner + (bw - 2 * inner) * pct.coerceIn(0, 100) / 100f
            drawRoundRect(fill, Offset(inner, inner), Size(edge - inner, size.height - 2 * inner), CornerRadius(3f * u))
            val laid = text.measure("$pct", BATT_TYPE)
            val at = Offset((bw - laid.size.width) / 2, (size.height - laid.size.height) / 2)
            clipRect(right = edge) { drawText(laid, r.ground, at) }
            clipRect(left = edge) { drawText(laid, r.ink, at) }
        }
    }
}

/**
 * The sheet any screen asks for through [LocalSheet] (a borrowed page, the control centre's
 * Power), drawn as the kit's [Sheet] in [r]: its words lowercase, an action in [DANGER] in
 * danger, the rest in the hue. What it asks, when it says
 * ([SheetState.show]'s question and line), the kit sets over the words. It goes when what it
 * asked about goes ([under]).
 */
@Composable
internal fun BeamSheetHost(state: SheetState, under: Any?, r: Ramp) {
    LaunchedEffect(under) { state.close() }
    val spec = state.current ?: return
    val bands = spec.actions.map { a ->
        SheetBand(a.word.lowercase(), if (a.hue == DANGER) DANGER else null) { state.act(a) }
    }
    Sheet(r, bands, onCancel = state::dismiss, question = spec.question, line = spec.line)
}
