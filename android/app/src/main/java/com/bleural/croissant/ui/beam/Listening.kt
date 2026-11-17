package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bleural.croissant.Phase
import com.bleural.croissant.VoiceState

// Where the side button is, in page px on the 480 x 640 canvas: the right edge, 243 down
// (213 sat 30 above the button, 2026-11-17).
private const val BUTTON_X = 480f
private const val BUTTON_Y = 243f
/** The half-disc's radius, the rings' reach as they go out, and the outlines, as the keys' 3. */
private const val CORE = 40f
private const val RING_REACH = 130f
private const val LINE = 3f
private const val RING_MS = 1400

/**
 * That the mic is taking words for the page (dictation into a field, an agent's chat, an Atelier
 * app), over everything, drawn as the keys are: a black half-disc at the side button with a 3 px
 * outline in Claude's orange ([agentLook]), and two outline rings going out from it half a beat
 * apart, thinning away as they go. Flat, no blur (2026-11-17). Ask is left out: its croissant's
 * ears say so already, and an Ask hold is never [VoiceState.dictate]. It fades in and out in
 * 150 ms; it takes no touches.
 */
@Composable
internal fun ListeningGlow(voice: VoiceState) {
    val on = voice.dictate && voice.phase == Phase.Listening
    val shown by animateFloatAsState(if (on) 1f else 0f, tween(150), label = "listening")
    if (shown == 0f) return
    val ring by rememberInfiniteTransition(label = "listening")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(RING_MS, easing = LinearEasing)), label = "ring")
    val hue = agentLook("claude").hue
    Canvas(Modifier.fillMaxSize()) {
        pageScale {
            val c = Offset(BUTTON_X, BUTTON_Y)
            outlineRing(c, ring, hue, shown)
            outlineRing(c, (ring + 0.5f) % 1f, hue, shown)
            drawCircle(Color.Black, CORE, c, alpha = shown)
            drawCircle(hue, CORE - LINE / 2, c, alpha = shown, style = Stroke(LINE))
        }
    }
}

/** A ring [f] of the way out from the half-disc: an outline in [hue], fading as it goes. */
private fun DrawScope.outlineRing(c: Offset, f: Float, hue: Color, shown: Float) {
    drawCircle(hue, CORE + (RING_REACH - CORE) * f, c, alpha = shown * (1f - f), style = Stroke(LINE))
}
