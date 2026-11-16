package com.bleural.croissant.ui.beam

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.TermLine
import com.bleural.croissant.TerminalUi
import com.bleural.croissant.ui.Grotesk
import com.bleural.croissant.ui.OwnTextField
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.tappable

/* The Terminal: a shell on the paired computer, green on black, a prompt at the folder it is in. */

private val TERM_GREEN = Color(0xFF00FF41)
private val TERM_DIM = Color(0xFF00B830)
private val TERM_ERR = Color(0xFFFF4D4D)
private val TERM_MONO = Grotesk.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 20.sp)

@Composable
internal fun TerminalRoute(d: PanelDeps, core: Core) {
    val ui by core.terminal.state.collectAsStateWithLifecycle()
    TerminalPage(ui, onRun = core.terminal::run, onCancel = core.terminal::cancel, onBack = d::close)
}

/**
 * Every command at its prompt (`~/Code $ ls`) in bright green, its output under it dimmer, and
 * the prompt last, typed on the keys (Uri's, for - and /). A tap anywhere brings the keys back;
 * while a command runs, ^C stops it. Wheel up at the top, or a pull, goes back.
 */
@Composable
private fun TerminalPage(ui: TerminalUi, onRun: (String) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    val (scroll, pull) = rememberWordsScroll(onBack)
    val field = rememberTextFieldState()
    val requester = remember { FocusRequester() }
    // Always at the newest line, as a terminal is.
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    LaunchedEffect(Unit) { requester.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().elasticPull(pull, TERM_GREEN, Color.Black).verticalScroll(scroll)
                .tappable { requester.requestFocus() }.padding(horizontal = 10.dp, vertical = 12.dp),
        ) {
            for (line in ui.lines) when (line) {
                is TermLine.Command -> Text("${line.cwd} $ ${line.text}", style = TERM_MONO, color = TERM_GREEN)
                is TermLine.Output -> Text(line.text.trimEnd('\n'), style = TERM_MONO, color = TERM_DIM)
            }
            ui.error?.let { Text(it, style = TERM_MONO, color = TERM_ERR) }
            if (ui.running) Text("^C", style = TERM_MONO, color = TERM_GREEN, modifier = Modifier.padding(vertical = 4.dp).tappable(onCancel))
            Row(Modifier.fillMaxWidth()) {
                Text("${ui.cwd} $ ", style = TERM_MONO, color = TERM_GREEN)
                OwnTextField(
                    field,
                    modifier = Modifier.weight(1f).focusRequester(requester),
                    textStyle = TERM_MONO.copy(color = TERM_GREEN),
                    cursorBrush = SolidColor(TERM_GREEN),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = {
                        if (!ui.running) { onRun(field.text.toString()); field.clearText() }
                    }),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
