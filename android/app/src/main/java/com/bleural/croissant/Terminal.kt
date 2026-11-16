package com.bleural.croissant

import com.bleural.croissant.net.AcpBridgeClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * The Terminal: a shell on the paired bridge's computer, acp-bridge's "shell" agent
 * (acp-bridge/acp_bridge/shell.py), one session for as long as the app runs, started in ~.
 * Each command is a prompt; its output streams back as the agent's words. Its own socket,
 * open while the page is up ([page]); the bridge keeps what was said while it is closed.
 */
class Terminal(private val scope: CoroutineScope, private val bridge: () -> Prefs.AcpBridge) {
    companion object {
        const val AGENT = "shell"
        /** The most lines kept: beyond, the oldest go. */
        private const val LINES_MAX = 400
        /** The shell's notes at the end of a command (shell.py): [now in ~/x] moves the prompt, [done] says nothing. */
        private val MOVED = Regex("""\s*\[now in ([^\]]+)]""")
        private val DONE = Regex("""\s*\[done]""")
    }

    private val client = AcpBridgeClient(bridge).also { c ->
        c.onEvent = ::heard
        c.onDrop = { if (up) reopen() }
    }

    private val _state = MutableStateFlow(TerminalUi())
    val state: StateFlow<TerminalUi> = _state.asStateFlow()

    @Volatile private var up = false
    @Volatile private var session: String? = null
    @Volatile private var seq = 0
    @Volatile private var epoch: String? = null
    private var opening: Job? = null

    /** The page came up (the session is watched again) or went (the socket closes). */
    fun page(on: Boolean) {
        if (on == up) return
        up = on
        if (on) reopen() else { opening?.cancel(); client.close() }
    }

    /** Run [command] in the shell; nothing while one runs. */
    fun run(command: String) {
        val text = command.trim()
        if (text.isEmpty() || _state.value.running) return
        if (text == "clear") return clear()
        _state.update { it.copy(running = true, error = null) }
        scope.launch {
            try {
                client.prompt(AGENT, ensure(), text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(running = false, error = e.message ?: "the bridge is away") }
            }
        }
    }

    /** Stop the command running (^C). */
    fun cancel() {
        val s = session ?: return
        scope.launch { runCatching { client.cancel(AGENT, s) } }
    }

    /** Clear the screen; the shell and its folder stay. */
    fun clear() = _state.update { it.copy(lines = emptyList(), error = null) }

    /** The session, made in ~ the first time. */
    private suspend fun ensure(): String {
        session?.let { return it }
        if (!bridge().set) throw IOException("pair a computer in Agents first")
        return client.new(AGENT, "~").also { session = it; seq = 0 }
    }

    /** Watch the session again from the last update heard (the socket went, or the page came back). */
    private fun reopen() {
        val s = session ?: return
        opening?.cancel()
        opening = scope.launch {
            try {
                val opened = client.open(AGENT, s, _state.value.cwd, seq, epoch)
                epoch = opened.epoch
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.update { it.copy(running = false, error = e.message ?: "the bridge is away") }
            }
        }
    }

    private fun heard(e: JSONObject) {
        if (e.optString("sessionId") != session) return
        when (e.optString("event")) {
            // The bridge restarted: its log of this session is gone, ours stays.
            "reset" -> { seq = 0; epoch = e.optString("epoch").ifEmpty { null }; return }
            "update" -> Unit
            else -> return
        }
        val n = e.optInt("seq")
        if (n <= seq) return
        seq = n
        val u = e.optJSONObject("update") ?: return
        when (u.optString("sessionUpdate")) {
            "user_message_chunk" -> text(u)?.let { t -> add { it.copy(lines = it.lines + TermLine.Command(it.cwd, t)) } }
            "agent_message_chunk" -> text(u)?.let(::output)
            "bridge_turn" -> {
                val running = u.optBoolean("running")
                val error = u.optString("error").takeIf { u.has("error") }
                _state.update { it.copy(running = running) }
                if (error != null) output("error: $error")
            }
        }
    }

    private fun text(u: JSONObject): String? =
        u.optJSONObject("content")?.takeIf { it.optString("type") == "text" }?.optString("text")

    private fun output(raw: String) {
        var t = raw
        MOVED.find(t)?.let { m -> val to = m.groupValues[1]; _state.update { it.copy(cwd = to) }; t = t.removeRange(m.range) }
        t = DONE.replace(t, "")
        if (t.isEmpty()) return
        add { s ->
            val last = s.lines.lastOrNull()
            if (last is TermLine.Output) s.copy(lines = s.lines.dropLast(1) + TermLine.Output(last.text + t))
            else s.copy(lines = s.lines + TermLine.Output(t))
        }
    }

    private fun add(f: (TerminalUi) -> TerminalUi) = _state.update { s -> f(s).let { n -> if (n.lines.size > LINES_MAX) n.copy(lines = n.lines.takeLast(LINES_MAX)) else n } }
}
