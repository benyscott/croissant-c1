package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextOverflow
import com.bleural.croissant.atelier.InstalledApp
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.ui.Place
import com.bleural.croissant.ui.WheelTarget
import com.bleural.croissant.ui.rememberAppGlyph
import com.bleural.croissant.ui.tappable
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The switcher (2026-11-16): the swipe up from the bottom edge, on any screen, as the control
 * centre is the pull down from the top. It brings up the places last opened (Core's recents, as
 * many as it keeps, newest first, not the one in front), and a tap on one goes straight to it, to
 * the page it was left on. The recents were the three discs above Ask on the wheel until now; they
 * live here instead. A swipe down on it or a tap on the dark puts it away, and the wheel does
 * nothing while it is up. ~~A short swipe went Home~~ (2026-11-18): it fought Home's own swipe up.
 * On Home after a lock (BeamUi.kt, LockFace) the same swipe goes back to where the lock left off.
 */

/**
 * On (2026-11-18). False puts the recents back above Ask on the wheel and stops listening for the swipe.
 */
internal const val SWITCHER_ON = true

/** How far up from the bottom a finger may land and still start the swipe, page px: an eighth of the screen. */
private const val BOTTOM_EDGE = 80

/** The swipe's measure, page px: let go past a third of it and the recents stay up. */
private const val REACH = 260f

private const val SWITCH_ROW = 84f
private const val CANVAS_H = 640f

class SwitcherState(private val scope: CoroutineScope) {
    /** 0 under the canvas, 1 fully up. */
    val shown = Animatable(0f)
    var open by mutableStateOf(false)
        private set

    internal fun drag(dy: Float, reach: Float) { scope.launch { shown.snapTo((shown.value - dy / reach).coerceIn(0f, 1f)) } }

    fun open() {
        if (!open) Cues.play(Sound.PanelIn)
        open = true
        scope.launch { shown.animateTo(1f, SPEC) }
    }

    /** [sound] is null when something else speaks: the place a tap opens, or Home. */
    fun close(sound: Sound? = Sound.PanelOut) {
        if (open) sound?.let(Cues::play)
        open = false
        scope.launch { shown.animateTo(0f, SPEC) }
    }

    private companion object {
        val SPEC = tween<Float>(120, easing = LinearEasing)
    }
}

/**
 * The swipe from the bottom edge, on the whole canvas: a finger landing in the bottom
 * [BOTTOM_EDGE] px and going up brings the switcher up with it; let go past a third of [REACH]
 * and the recents stay up, else they go back down. Watched in the Initial pass, like the control
 * centre's edge, and taken at half the slop, so Home's own swipe up and a scrolling page never
 * see it; a tap there, or a drag that sets off sideways or down, is the page's. Off while
 * [enabled] says so (the keys up). While [locked], the panel stays down and the same swipe,
 * past the same third, is [onUnlock].
 */
fun Modifier.switcherEdge(state: SwitcherState, enabled: () -> Boolean, locked: () -> Boolean, onUnlock: () -> Unit): Modifier = pointerInput(state) {
    val edge = BOTTOM_EDGE.px.toPx()
    val reach = REACH.px.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled() || down.position.y < size.height - edge || state.shown.value > 0f) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop / 2
        val unlocking = locked()
        var swiping = false
        var rise = 0f
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            rise = down.position.y - c.position.y
            if (swiping) { if (!unlocking) state.drag(c.positionChange().y, reach); c.consume(); continue }
            val d = c.position - down.position
            if (-d.y > slop && -d.y > abs(d.x)) { swiping = true; if (!unlocking) state.drag(d.y + slop, reach); c.consume() }
            else if (d.getDistance() > slop) return@awaitEachGesture
        }
        if (!swiping) return@awaitEachGesture
        when {
            unlocking -> if (rise >= reach / 3) onUnlock()
            state.shown.value >= 1f / 3 -> state.open()
            else -> state.close(null)
        }
    }
}

/**
 * The swipe down that puts the recents away, anywhere on them while their list is at its top:
 * the panel follows the finger, and let go a third of the way down it goes; else it comes back.
 * A swipe up is the list's, to scroll.
 */
private fun Modifier.swipeDownCloses(state: SwitcherState, atTop: () -> Boolean): Modifier = pointerInput(state) {
    val reach = REACH.px.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!state.open) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        var closing = false
        while (true) {
            val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
            if (!c.pressed) break
            if (closing) { state.drag(c.positionChange().y, reach); c.consume(); continue }
            val d = c.position - down.position
            if (d.y > slop && d.y > abs(d.x) && atTop()) { closing = true; state.drag(d.y - slop, reach); c.consume() }
            else if (d.getDistance() > slop) return@awaitEachGesture
        }
        if (closing) { if (state.shown.value > 2f / 3) state.open() else state.close() }
    }
}

/**
 * The recents over the dimmed page, rising from the bottom: a row each, its glyph in its hue and
 * its name in white, the newest at the top. With none, a word says so.
 */
@Composable
internal fun BeamSwitcher(state: SwitcherState, places: List<Place>, apps: List<InstalledApp>, r: Ramp, onOpen: (Place) -> Unit) {
    val showing by remember(state) { derivedStateOf { state.shown.value > 0f } }
    if (!showing) return
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize().swipeDownCloses(state) { scroll.value == 0 }) {
        Box(Modifier.fillMaxSize().drawBehind { drawRect(r.ground, alpha = 0.9f * state.shown.value) }.tappable { state.close() })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = (CANVAS_H - 40).px)
                .graphicsLayer { translationY = size.height * (1f - state.shown.value); alpha = state.shown.value }
                .verticalScroll(scroll)
                .padding(horizontal = 32.px, vertical = 24.px),
            verticalArrangement = Arrangement.Bottom,
        ) {
            if (places.isEmpty()) Text("nothing opened yet", style = BeamType.Value, color = SUB, modifier = Modifier.padding(vertical = 24.px))
            places.forEach { p -> SwitcherRow(p, apps) { onOpen(p) } }
        }
    }
    if (state.open) WheelTarget { false }
}

@Composable
private fun SwitcherRow(p: Place, apps: List<InstalledApp>, onClick: () -> Unit) {
    val (glyph, hue, name) = lookOf(p, apps)
    Row(Modifier.fillMaxWidth().height(SWITCH_ROW.px).tappable(onClick), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(60.px).clip(CircleShape).background(hue), contentAlignment = Alignment.Center) {
            Icon(glyph, null, Modifier.size(34.px), GROUND)
        }
        Spacer(Modifier.width(24.px))
        Text(name, style = BeamType.Row, color = INK, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A place's glyph, hue and name, as the side wheel draws it. */
@Composable
private fun lookOf(p: Place, apps: List<InstalledApp>): Triple<ImageVector, Color, String> = when (p) {
    is Place.Of -> Triple(p.feature.glyph, rampOf(p.feature).hue, p.feature.word.lowercase(Locale.UK))
    is Place.App -> {
        val app = apps.firstOrNull { it.id == p.id }
        Triple(rememberAppGlyph(app?.manifest?.glyph) ?: NO_GLYPH, rampOf(p, apps).hue, (app?.manifest?.name ?: p.id).lowercase(Locale.UK))
    }
    is Place.Agent -> agentLook(p.id).let { Triple(it.glyph, it.hue, p.id.lowercase(Locale.UK)) }
}
