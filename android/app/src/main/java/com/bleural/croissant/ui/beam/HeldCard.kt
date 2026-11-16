package com.bleural.croissant.ui.beam

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.bleural.croissant.ui.OwnTextField
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
        fun of(voice: VoiceState, sending: String?, unsent: String?, pending: String? = null, editing: Boolean = false, editSoon: Boolean = false, cancelSoon: Boolean = false, typing: Boolean = false): Held? = when {
            // Being edited: the editor stays up through a hold, whose words go in at its cursor.
            pending != null && editing -> Editing(pending)
            voice.dictate && voice.phase == Phase.Error -> Failed(voice.error ?: "the hold did not get through")
            // The text box open: no card, the box stays up and its words go in at its cursor.
            typing && pending == null && voice.dictate && voice.phase != Phase.Idle -> null
            // Cancelled while settling: the card goes now, its words dropped as they land.
            voice.dictate && voice.phase != Phase.Idle && cancelSoon -> null
            voice.dictate && voice.phase != Phase.Idle -> Heard(listOfNotNull(pending, voice.transcript.ifBlank { null }).joinToString(" "), voice.phase == Phase.Listening, editSoon)
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
    inserts: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(), live: String = "",
    windowMs: Long = 2_000L, onCancel: () -> Unit = {}, onEdit: () -> Unit = {}, onSendEdited: (String) -> Unit = {},
    onEditSoon: () -> Unit = {}, onCancelSoon: () -> Unit = {}, onSendNow: () -> Unit = {},
) {
    if (held is Held.Pending) return PendingCard(held.words, hue, windowMs, onCancel, onEdit, onSendNow)
    if (held is Held.Editing) return EditingCard(held.words, hue, inserts, live, onCancel, onSendEdited)
    val fill = when (held) {
        is Held.Heard -> GROUND
        is Held.Sending -> hue
        is Held.Unsent, is Held.Failed -> DANGER
        is Held.Pending, is Held.Editing -> GROUND
    }
    val ink = if (held is Held.Heard) INK else GROUND
    val dots = remember(hue) { ramp(hue) }
    val unsentCopied = rememberCopied()
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
                // Words that did not get there: a long press copies them, to paste in the text box.
                is Held.Unsent -> Text(held.words, style = BeamType.Body, color = ink, modifier = Modifier.copyOnHold(held.words, unsentCopied).then(words))
                else -> Text(held.words, style = BeamType.Body, color = ink, modifier = words)
            }
            if (held is Held.Unsent && unsentCopied.value) CopiedLine(true, GROUND)
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

/** One of your messages: your words in Body black on [fill] from x 36, 400 across, first baseline 48 down, 22 under the last. A long press copies them. */
@Composable
internal fun Message(words: String, fill: Color) {
    val copied = rememberCopied()
    Box(Modifier.padding(start = CARD_X.px, end = CARD_X.px).fillMaxWidth().clip(RoundedCornerShape(16.px)).background(fill)
        .copyOnHold(words, copied)) {
        Column(Modifier.fillMaxWidth()) {
            Text(words.ifBlank { "…" }, style = BeamType.Body, color = GROUND, modifier = Modifier.padding(start = IN_START.px, end = IN_END.px).wordsAt(48f))
            CopiedLine(copied.value, GROUND)
        }
    }
}

/** Whether words were just copied: true for a moment after a long press. */
@Composable
private fun rememberCopied(): MutableState<Boolean> {
    val copied = remember { mutableStateOf(false) }
    LaunchedEffect(copied.value) { if (copied.value) { delay(1_500); copied.value = false } }
    return copied
}

/** A long press puts [words] on the clipboard, to paste in the text box ([TypeBox]) or anywhere. */
@Composable
private fun Modifier.copyOnHold(words: String, copied: MutableState<Boolean>): Modifier {
    val context = LocalContext.current
    return combinedClickable(remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = {
        Cues.play(Sound.LongPress)
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("words", words))
        copied.value = true
    })
}

/** The card's foot: 22 high, or "copied" at the right for a moment after a long press. */
@Composable
private fun CopiedLine(copied: Boolean, ink: Color) {
    if (!copied) return Spacer(Modifier.height(22.px))
    Box(Modifier.fillMaxWidth().height(58.px)) {
        Text("copied", style = BeamType.Value, color = ink, modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.px, end = IN_END.px))
    }
}

/**
 * The text box at a conversation's foot: a field as tall as the folder key (84), left of it
 * when [besideKey], one line. Tapped, it opens wider and taller over the foot, the keys under
 * it; a long press in it brings the system's bubble (paste, copy, cut, select all). Its send
 * sends the words as the next prompt, only while [canSend] (the session open, no turn
 * running); the keys' done only puts them away. [state] is the page's, so the words stay.
 */
@Composable
internal fun BoxScope.TypeBox(
    state: TextFieldState, hue: Color, canSend: Boolean, besideKey: Boolean, onSend: (String) -> Unit,
    inserts: kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.emptyFlow(), live: String = "", onTyping: (Boolean) -> Unit = {},
) {
    val keys = LocalSoftwareKeyboardController.current
    val r = remember(hue) { ramp(hue) }
    var open by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val ready = canSend && state.text.isNotBlank()
    // Open, a hold writes here: its words at the cursor (over the selection), nothing sent.
    LaunchedEffect(open) { if (open) inserts.collect { putAtCursor(state, it) } }
    DisposableEffect(Unit) { onDispose { onTyping(false) } }
    fun send() {
        if (!ready) return
        Cues.play(Sound.Commit)
        onSend(state.text.toString())
        state.clearText()
    }
    val end = if (open || !besideKey) 16f else 24f + 84f + 12f
    Column(Modifier.align(Alignment.BottomStart).padding(start = 16.px, end = end.px, bottom = (if (open) 16f else 24f).px).fillMaxWidth()
        .animateContentSize(spring(dampingRatio = 0.85f, stiffness = 450f))
        .clip(RoundedCornerShape(if (open) 16.px else 42.px)).background(GROUND)
        .border(3.px, if (open) hue else SUB, RoundedCornerShape(if (open) 16.px else 42.px))
        .clickable(remember { MutableInteractionSource() }, indication = null) { requester.requestFocus() }) {
        Box(Modifier.fillMaxWidth().then(if (open) Modifier.padding(top = 18.px) else Modifier.height(84.px))
            .padding(start = 28.px, end = 28.px), contentAlignment = if (open) Alignment.TopStart else Alignment.CenterStart) {
            if (state.text.isEmpty()) Text("write", style = BeamType.Body, color = SUB, maxLines = 1)
            HueSelection(r) {
                OwnTextField(
                    state,
                    textStyle = BeamType.Body.copy(color = INK),
                    cursorBrush = SolidColor(hue),
                    lineLimits = if (open) TextFieldLineLimits.MultiLine(3, 6) else TextFieldLineLimits.MultiLine(1, 1),
                    inputTransformation = InputTransformation.maxLength(20_000),
                    // The keys' action is done, not send: a missed full stop never sends. Done puts the keys away, the box stays open with its send.
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keys?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
                        if (it.isFocused && !open) Cues.play(Sound.FieldFocus)
                        open = it.isFocused
                        onTyping(it.isFocused)
                    },
                    outputTransformation = liveAtCursor(live),
                )
            }
        }
        if (open) Box(Modifier.fillMaxWidth().height(84.px)) {
            Pill("send", if (ready) hue else SUB, GROUND, Modifier.align(Alignment.TopEnd).padding(top = 18.px, end = 20.px)) { send() }
        }
    }
}

/**
 * A hold's words in their grace window: ringed in [hue] as while heard, the words in white, a line
 * under them filling left to right over [windowMs] until they are sent, cancel at the left and
 * send at the right. A tap on the words stops the window to edit them ([onEdit]); cancel drops
 * them ([onCancel]); send sends them now ([onSend]).
 */
@Composable
private fun PendingCard(words: String, hue: Color, windowMs: Long, onCancel: () -> Unit, onEdit: () -> Unit, onSend: () -> Unit) {
    val left = remember(words) { Animatable(1f) }
    LaunchedEffect(words) { left.animateTo(0f, tween(windowMs.toInt().coerceAtLeast(1), easing = LinearEasing)) }
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
    // Under the card, never over it: the bar filling until they go, then cancel and send.
    Column(Modifier.padding(start = CARD_X.px + 4.px, end = CARD_X.px + 4.px, top = 18.px).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(8.px).clip(RoundedCornerShape(4.px)).background(hue.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(1f - left.value).height(8.px).clip(RoundedCornerShape(4.px)).background(hue))
        }
        Spacer(Modifier.height(20.px))
        Row(Modifier.fillMaxWidth()) {
            Pill("cancel", GROUND, DANGER, Modifier.border(2.px, DANGER, RoundedCornerShape(23.px))) { Cues.play(Sound.Destroy); onCancel() }
            Spacer(Modifier.weight(1f))
            Pill("send", hue, GROUND) { Cues.play(Sound.Commit); onSend() }
        }
    }
}

/** A hold's words typed over on the keys, the grace window stopped: send them as they are now, or cancel. */
@Composable
private fun EditingCard(words: String, hue: Color, inserts: kotlinx.coroutines.flow.Flow<String>, live: String, onCancel: () -> Unit, onSend: (String) -> Unit) {
    val keys = LocalSoftwareKeyboardController.current
    val state = remember { TextFieldState(words) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    // A hold while editing: its words go in where the cursor is (over the selection), spaced from
    // what is either side. The editor stays up: send or cancel are the user's.
    LaunchedEffect(Unit) {
        inserts.collect { said ->
            putAtCursor(state, said)
        }
    }
    val r = remember(hue) { ramp(hue) }
    Column(Modifier.padding(start = CARD_X.px, end = CARD_X.px).fillMaxWidth()
        .clip(RoundedCornerShape(16.px)).background(GROUND).border(3.px, hue, RoundedCornerShape(16.px))) {
        Spacer(Modifier.height(16.px))
        // The words at reading size, as on the card before the tap: Body, not the big title.
        HueSelection(r) {
            OwnTextField(
                state,
                textStyle = BeamType.Body.copy(color = INK),
                cursorBrush = SolidColor(hue),
                inputTransformation = InputTransformation.maxLength(2_000),
                outputTransformation = liveAtCursor(live),
                // Done, not send, as in the text box: the keys go away, send is the card's own.
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keys?.hide() }),
                modifier = Modifier.focusRequester(requester).fillMaxWidth().padding(start = IN_START.px, end = IN_END.px),
            )
        }
        Box(Modifier.fillMaxWidth().height(92.px)) {
            Row(Modifier.align(Alignment.TopEnd).padding(top = 26.px, end = 20.px)) {
                Pill("cancel", DANGER, GROUND) { Cues.play(Sound.Destroy); onCancel() }
                Spacer(Modifier.padding(start = 12.px))
                Pill("send", hue, GROUND) { Cues.play(Sound.Commit); onSend(state.text.toString()) }
            }
        }
    }
}

/** A hold's words as they come, while it is held or settling; empty otherwise. */
internal fun liveWords(voice: VoiceState): String =
    if (voice.dictate && voice.phase != Phase.Idle && voice.phase != Phase.Error) voice.transcript.trim() else ""

/** [said] put in at [state]'s cursor (over its selection), spaced from what is either side; the cursor after it. */
private fun putAtCursor(state: TextFieldState, said: String) {
    state.edit {
        val (at, end, put) = spaced(asCharSequence(), selection.min, selection.max, said)
        replace(at, end, put)
        selection = androidx.compose.ui.text.TextRange(at + put.trimEnd().length)
    }
}

/** Where [said] goes in [text] over [at]..[end], with a space either side where the words touch. */
private fun spaced(text: CharSequence, at: Int, end: Int, said: String): Triple<Int, Int, String> {
    val before = if (at > 0 && !text[at - 1].isWhitespace()) " " else ""
    val after = if (end < text.length && !text[end].isWhitespace()) " " else ""
    return Triple(at, end, before + said.trim() + after)
}

/** A hold's [live] words shown at the cursor as they come, not yet in the text: they go in when they settle. */
@Composable
private fun liveAtCursor(live: String): androidx.compose.foundation.text.input.OutputTransformation? {
    if (live.isEmpty()) return null
    return remember(live) {
        androidx.compose.foundation.text.input.OutputTransformation {
            val (at, end, put) = spaced(asCharSequence(), selection.min, selection.max, live)
            replace(at, end, put)
        }
    }
}
