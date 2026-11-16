package com.bleural.croissant

import com.bleural.croissant.net.AcpBridgeClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One line of the Terminal: a command as typed at its folder's prompt, or what came back. */
sealed interface TermLine {
    data class Command(val cwd: String, val text: String) : TermLine
    data class Output(val text: String) : TermLine
}

data class TerminalUi(
    val lines: List<TermLine> = emptyList(),
    /** The folder the next command runs in, as the shell names it (~ for home). */
    val cwd: String = "~",
    val running: Boolean = false,
    /** Why the last command did not go (no bridge paired, the bridge away). */
    val error: String? = null,
)

/**
 * The Terminal: commands run on the paired bridge's computer (acp-bridge's term op,
 * acp-bridge/acp_bridge/terminal.py), starting in ~; the folder a command ends in is where the
 * next one runs. Its own socket, open while the page is up ([page]); a command still running
 * when the socket goes (the R1 slept, the page left) is stopped by the bridge.
 */
class Terminal(private val scope: CoroutineScope, private val bridge: () -> Prefs.AcpBridge) {
    companion object {
        /** The most lines kept: beyond, the oldest go. */
        private const val LINES_MAX = 400
    }

    private val client = AcpBridgeClient(bridge).also { c ->
        c.onEvent = ::heard
        c.onDrop = { ended("[connection lost]\n") }
    }

    private val _state = MutableStateFlow(TerminalUi())
    val state: StateFlow<TerminalUi> = _state.asStateFlow()

    @Volatile private var up = false

    /** The page came up or went (the socket closes, stopping a command still running). */
    fun page(on: Boolean) {
        if (on == up) return
        up = on
        if (!on) { client.close(); ended(null) }
    }

    /** Run [command] at the prompt; nothing while one runs. `clear` clears the screen here. */
    fun run(command: String) {
        val text = command.trim()
        if (text.isEmpty() || _state.value.running) return
        if (text == "clear") return clear()
        val cwd = _state.value.cwd
        add { it.copy(lines = it.lines + TermLine.Command(cwd, text), running = true, error = null) }
        scope.launch {
            try {
                if (!bridge().set) throw IOException("pair a computer in Agents first")
                client.ask("term", JSONObject().put("text", text).put("cwd", cwd))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(running = false, error = e.message ?: "the bridge is away") }
            }
        }
    }

    /** Stop the command running (^C). */
    fun cancel() {
        scope.launch { runCatching { client.ask("term_cancel") } }
    }

    /** Clear the screen; the folder stays. */
    fun clear() = _state.update { it.copy(lines = emptyList(), error = null) }

    private fun heard(e: JSONObject) {
        if (e.optString("event") != "term") return
        if (e.has("out")) output(e.optString("out"))
        if (!e.optBoolean("end")) return
        val code = e.optInt("code")
        if (code != 0 && !e.optBoolean("stopped")) output("[exit $code]\n")
        _state.update { it.copy(running = false, cwd = e.optString("cwd").ifEmpty { it.cwd }) }
    }

    /** A command running is gone with the socket: [note] says so. */
    private fun ended(note: String?) {
        if (!_state.value.running) return
        note?.let(::output)
        _state.update { it.copy(running = false) }
    }

    private fun output(t: String) {
        if (t.isEmpty()) return
        add { s ->
            val last = s.lines.lastOrNull()
            if (last is TermLine.Output) s.copy(lines = s.lines.dropLast(1) + TermLine.Output(last.text + t))
            else s.copy(lines = s.lines + TermLine.Output(t))
        }
    }

    private fun add(f: (TerminalUi) -> TerminalUi) = _state.update { s -> f(s).let { n -> if (n.lines.size > LINES_MAX) n.copy(lines = n.lines.takeLast(LINES_MAX)) else n } }
}
