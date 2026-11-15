package com.bleural.croissant.ui.beam

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.bleural.croissant.Phase
import com.bleural.croissant.VoiceState
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import kotlinx.coroutines.delay

/*
 * The cards your words are on: a hold's card while you talk and as its words go, and your words
 * sent, a card in the hue. An agent's conversation and its new chat page draw them (AgentChat.kt).
 * Page pixels; the content from y 66.
 */

/** The cards' left and width, and the words in them: from x 36, 400 across. */
private const val CARD_X = 16f
private const val IN_START = 20f
private const val IN_END = 28f

/** What a hold on a page that takes its words (an agent's session, AgentChat.kt) is doing: heard (still talking, or settling), sending, refused where it went, or failed before it got there. */
internal sealed interface Held {
    val words: String
    data class Heard(override val words: String, val talking: Boolean, val editSoon: Boolean = false) : Held
    data class Sending(override val words: String) : Held
    data class Unsent(override val words: String) : Held
    data class Failed(override val words: String) : Held
    /** Heard and settled, waiting out the grace window before it is sent: cancel drops it, a tap edits it. */
    data class Pending(override val words: String) : Held
    /** Being typed over, the window stopped: send or cancel. */
    data class Editing(override val words: String) : Held

    companion object {
        fun of(voice: VoiceState, sending: String?, unsent: String?, pending: String? = null, editing: Boolean = false, editSoon: Boolean = false, cancelSoon: Boolean = false): Held? = when {
            voice.dictate && voice.phase == Phase.Error -> Failed(voice.error ?: "the hold did not get through")
            // Cancelled while settling: the card goes now, its words dropped as they land.
            voice.dictate && voice.phase != Phase.Idle && cancelSoon -> null
            voice.dictate && voice.phase != Phase.Idle -> Heard(listOfNotNull(pending, voice.transcript.ifBlank { null }).joinToString(" "), voice.phase == Phase.Listening, editSoon)
            pending != null && editing -> Editing(pending)
            pending != null -> Pending(pending)
            sending != null -> Sending(sending)
            unsent != null -> Unsent(unsent)
            else -> null
        }
    }
}

/**
 * A hold's card: heard, black ringed in [hue], the words in white with the caret in the hue and the
 * level under them; sending, filled with [hue], the words in black and three dots in it under them;
 * unsent, in danger, [away] (who did not answer) and retry; failed, in danger with why.
 */
@Composable
internal fun HeldCard(
    held: Held, hue: Color, level: () -> Float, onRetry: () -> Unit, away: String,
    windowMs: Long = 2_000L, onCancel: () -> Unit = {}, onEdit: () -> Unit = {}, onSendEdited: (String) -> Unit = {},
    onEditSoon: () -> Unit = {}, onCancelSoon: () -> Unit = {},
) {
    if (held is Held.Pending) return PendingCard(held.words, hue, windowMs, onCancel, onEdit)
    if (held is Held.Editing) return EditingCard(held.words, hue, onCancel, onSendEdited)
    val fill = when (held) {
        is Held.Heard -> GROUND
        is Held.Sending -> hue
        is Held.Unsent, is Held.Failed -> DANGER
        is Held.Pending, is Held.Editing -> GROUND
    }
    val ink = if (held is Held.Heard) INK else GROUND
    val dots = remember(hue) { ramp(hue) }
    // Heard to sending, the card closes to the message card's height rather than jumping to it.
    var card = Modifier.padding(start = CARD_X.px, end = CARD_X.px).fillMaxWidth()
        .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 450f))
        .clip(RoundedCornerShape(16.px)).background(fill)
    if (held is Held.Heard) card = card.border(3.px, hue, RoundedCornerShape(16.px))
    Box(card) {
        Column(Modifier.fillMaxWidth()) {
            val words = Modifier.padding(start = IN_START.px, end = IN_END.px).wordsAt(50f)
            when (held) {
                // Let go and settling: a tap on the words already asks for the editor (the caret shows it), cancel already drops them.
                is Held.Heard -> if (held.talking || held.editSoon) CaretWords(held.words, ink, hue, words)
                    else Text(held.words, style = BeamType.Body, color = ink, modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {
                        Cues.play(Sound.FieldFocus); onEditSoon()
                    }.then(words))
                is Held.Failed -> Text(held.words, style = BeamType.Body, color = ink, modifier = words)
                else -> Text(held.words, style = BeamType.Body, color = ink, modifier = words)
            }
            when (held) {
                is Held.Heard -> Box(Modifier.fillMaxWidth().height(98.px)) {
                    if (held.talking) CardLevel(level, hue, Modifier.padding(top = 26.px))
                    else Pill("cancel", DANGER, GROUND, Modifier.align(Alignment.TopEnd).padding(top = 30.px, end = 20.px)) {
                        Cues.play(Sound.Destroy); onCancelSoon()
                    }
                }
                // Sending is the message card it is about to become, the dots where the step
                // line's are: when the answer comes only the line beside them changes.
                is Held.Sending -> Spacer(Modifier.height(22.px))
                is Held.Unsent -> Box(Modifier.fillMaxWidth().height(138.px)) {
                    Text(away, style = BeamType.Value, color = GROUND,
                        modifier = Modifier.padding(start = IN_START.px).wordsAt(52f))
                    Pill("retry", GROUND, INK, Modifier.align(Alignment.TopEnd).padding(top = 74.px, end = 20.px)) {
                        Cues.play(Sound.Commit); onRetry()
                    }
                }
                is Held.Failed, is Held.Pending, is Held.Editing -> Spacer(Modifier.height(22.px))
            }
        }
    }
    if (held is Held.Sending) Box(Modifier.fillMaxWidth().height(138.px)) {
        ThinkingDots(dots, Modifier.padding(start = 26.px, top = 90.px))
    }
}

/** The level in a card while you hold: 18 bars 12 wide, 22 apart, from x 41, 3 to 48 tall, centred on 24. */
@Composable
private fun CardLevel(level: () -> Float, hue: Color, modifier: Modifier) {
    val bars = remember { FloatArray(18) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(70)
            System.arraycopy(bars, 1, bars, 0, bars.size - 1)
            bars[bars.size - 1] = level().coerceIn(0f, 1f)
            tick++
        }
    }
    Canvas(modifier.fillMaxWidth().height(48.px)) {
        if (tick < 0) return@Canvas
        pageScale {
            for (i in bars.indices) {
                val h = 3f + 45f * bars[i]
                rect(25f + i * 22f, 24f - h / 2f, 12f, h, hue, 6f)
            }
        }
    }
}

/** One of your messages: your words in Body black on [fill] from x 36, 400 across, first baseline 48 down, 22 under the last. */
@Composable
internal fun Message(words: String, fill: Color) {
    Box(Modifier.padding(start = CARD_X.px, end = CARD_X.px).fillMaxWidth().clip(RoundedCornerShape(16.px)).background(fill)) {
        Column(Modifier.fillMaxWidth()) {
            Text(words.ifBlank { "…" }, style = BeamType.Body, color = GROUND, modifier = Modifier.padding(start = IN_START.px, end = IN_END.px).wordsAt(48f))
            Spacer(Modifier.height(22.px))
        }
    }
}

/**
 * A hold's words in their grace window: ringed in [hue] as while heard, the words in white, a line
 * under them draining over [windowMs] until they are sent, and cancel at the right. A tap on the
 * words stops the window to edit them ([onEdit]); cancel drops them ([onCancel]).
 */
@Composable
private fun PendingCard(words: String, hue: Color, windowMs: Long, onCancel: () -> Unit, onEdit: () -> Unit) {
    val tap = remember { MutableInteractionSource() }
    Box(Modifier.padding(start = CARD_X.px, end = CARD_X.px).fillMaxWidth()
        .clip(RoundedCornerShape(16.px)).background(GROUND).border(3.px, hue, RoundedCornerShape(16.px))) {
        Column(Modifier.fillMaxWidth()) {
            Text(words, style = BeamType.Body, color = INK,
                modifier = Modifier.clickable(tap, indication = null) { Cues.play(Sound.FieldFocus); onEdit() }
                    .padding(start = IN_START.px, end = IN_END.px).wordsAt(50f))
            Spacer(Modifier.height(22.px))
        }
    }
}

/** A hold's words typed over on the keys, the grace window stopped: send them as they are now, or cancel. */
@Composable
private fun EditingCard(words: String, hue: Color, onCancel: () -> Unit, onSend: (String) -> Unit) {
    val state = remember { TextFieldState(words) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val r = remember(hue) { ramp(hue) }
    Column(Modifier.padding(start = CARD_X.px, end = CARD_X.px).fillMaxWidth()
        .clip(RoundedCornerShape(16.px)).background(GROUND).border(3.px, hue, RoundedCornerShape(16.px))) {
        Spacer(Modifier.height(16.px))
        TitleField(state, r, INK, 2_000, Modifier.focusRequester(requester), onFocus = {}, onDone = { Cues.play(Sound.Commit); onSend(state.text.toString()) })
        Box(Modifier.fillMaxWidth().height(92.px)) {
            Row(Modifier.align(Alignment.TopEnd).padding(top = 26.px, end = 20.px)) {
                Pill("cancel", DANGER, GROUND) { Cues.play(Sound.Destroy); onCancel() }
                Spacer(Modifier.padding(start = 12.px))
                Pill("send", hue, GROUND) { Cues.play(Sound.Commit); onSend(state.text.toString()) }
            }
        }
    }
}

/**
 * Over the whole page while a hold's words wait out their window: the page dimmed, and in the
 * middle a card ringed in [hue], "sending", a bar draining over [windowMs], and under it cancel
 * (the big one), edit and send now. The words on the page still take a tap to edit.
 */
@Composable
internal fun SendingSheet(words: String, hue: Color, windowMs: Long, onCancel: () -> Unit, onEdit: () -> Unit, onSend: () -> Unit) {
    val left = remember(words) { Animatable(1f) }
    LaunchedEffect(words) { left.animateTo(0f, tween(windowMs.toInt().coerceAtLeast(1), easing = LinearEasing)) }
    Box(Modifier.fillMaxSize().background(GROUND.copy(alpha = 0.6f)).clickable(remember { MutableInteractionSource() }, indication = null) {}) {
        Column(Modifier.align(Alignment.Center).padding(start = 24.px, end = 24.px).fillMaxWidth()
            .clip(RoundedCornerShape(20.px)).background(GROUND).border(3.px, hue, RoundedCornerShape(20.px))
            .padding(start = 24.px, end = 24.px, bottom = 24.px)) {
            Text("sending", style = BeamType.Body, color = INK, modifier = Modifier.wordsAt(50f))
            Spacer(Modifier.height(20.px))
            Box(Modifier.fillMaxWidth().height(8.px).clip(RoundedCornerShape(4.px)).background(hue.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(left.value).height(8.px).clip(RoundedCornerShape(4.px)).background(hue))
            }
            Spacer(Modifier.height(28.px))
            Box(Modifier.fillMaxWidth().height(64.px).clip(RoundedCornerShape(32.px)).background(DANGER)
                .clickable(remember { MutableInteractionSource() }, indication = null) { Cues.play(Sound.Destroy); onCancel() }) {
                Text("cancel", style = PILL, color = GROUND, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(16.px))
            Row(Modifier.fillMaxWidth()) {
                Pill("edit", GROUND, INK, Modifier.border(2.px, INK, RoundedCornerShape(23.px))) { Cues.play(Sound.FieldFocus); onEdit() }
                Spacer(Modifier.weight(1f))
                Pill("send now", hue, GROUND) { Cues.play(Sound.Commit); onSend() }
            }
        }
    }
}
