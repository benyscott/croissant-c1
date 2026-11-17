package com.bleural.croissant.ui.beam

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.bleural.croissant.Phase
import com.bleural.croissant.VoiceState
import com.bleural.croissant.ui.Hue

// Where the side button is, in page px on the 480 x 640 canvas: the right edge, a third of the
// way down. Tune on the device (2026-11-17).
private const val BUTTON_X = 480f
private const val BUTTON_Y = 213f
/** The glow's reach at rest and at the top of its breath, and the rings' reach as they go. */
private const val GLOW_MIN = 56f
private const val GLOW_MAX = 80f
private const val RING_REACH = 150f
private const val BREATH_MS = 900
private const val RING_MS = 1400

/**
 * That the mic is taking words for the page (dictation into a field, an agent's chat, an Atelier
 * app), over everything: a soft orange half-glow breathing at the side button, the edge it is on,
 * and blurred rings going out from it. Ask is left out: its croissant's ears say so already, and
 * an Ask hold is never [VoiceState.dictate]. It fades in and out in 150 ms; it takes no touches.
 */
@Composable
internal fun ListeningGlow(voice: VoiceState) {
    val on = voice.dictate && voice.phase == Phase.Listening
    val shown by animateFloatAsState(if (on) 1f else 0f, tween(150), label = "listening")
    if (shown == 0f) return
    val t = rememberInfiniteTransition(label = "listening")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(BREATH_MS, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(RING_MS, easing = LinearEasing)), label = "ring")
    Canvas(Modifier.fillMaxSize()) {
        pageScale {
            val c = Offset(BUTTON_X, BUTTON_Y)
            // Two rings half a cycle apart, so one is always going out.
            glowRing(c, ring, shown)
            glowRing(c, (ring + 0.5f) % 1f, shown)
            val reach = GLOW_MIN + (GLOW_MAX - GLOW_MIN) * breath
            drawCircle(
                Brush.radialGradient(
                    0f to Hue.Ask.copy(alpha = 0.85f), 0.45f to Hue.Ask.copy(alpha = 0.45f), 1f to Color.Transparent,
                    center = c, radius = reach,
                ),
                reach, c, alpha = shown,
            )
        }
    }
}

/** A ring [f] of the way out: a soft band of orange, fading as it goes. */
private fun DrawScope.glowRing(c: Offset, f: Float, shown: Float) {
    val reach = GLOW_MIN + (RING_REACH - GLOW_MIN) * f
    val band = 0.18f
    drawCircle(
        Brush.radialGradient(
            0f to Color.Transparent, (1f - 2 * band) to Color.Transparent,
            (1f - band) to Hue.Ask.copy(alpha = 0.5f * (1f - f)), 1f to Color.Transparent,
            center = c, radius = reach,
        ),
        reach, c, alpha = shown,
    )
}
