package com.bleural.croissant

import android.util.Log
import com.bleural.croissant.net.AcpBridgeClient
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** A permission waiting on the bridge, in any session: what the control centre shows. */
data class AgentAlert(val agent: String, val session: String, val cwd: String, val asking: Asking, val title: String = "") {
    val key get() = "$agent/$session/${asking.request}"
    /** The folder's last path part, the session's name here. */
    val folder get() = cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }
}

/** What a session shows on the Agents pages, most pressing first. */
enum class SessionMark { Asking, Done, Running, None }

/** A turn that ended in a session not on screen: the conversation's [title], the start of what the agent said ([words]). */
data class AgentDone(val agent: String, val session: String, val cwd: String, val title: String, val words: String, val error: String?) {
    val key get() = "$agent/$session"
    val folder get() = cwd.trimEnd('/').substringAfterLast('/').ifEmpty { cwd }
}

/**
 * Every permission the paired bridge waits on, and every turn that ended unseen ([done]), kept on a socket of its own that stays open
 * whatever page is up (the Agents page's closes when it goes): the bridge's `alerts` op gives
 * those waiting now and then tells each one that comes or goes. Dropped (the R1 slept, the
 * bridge restarted), it is opened again, sooner then less often. [heard] is told a new one.
 */
class AgentAlerts(
    private val scope: CoroutineScope,
    private val bridge: () -> Prefs.AcpBridge,
    private val heard: () -> Unit = {},
    /** The session on screen ("agent/session"), if one is: its ended turn is seen already. */
    private val onScreen: () -> String? = { null },
) {
    private companion object {
        const val TAG = "AgentAlerts"
        const val FIRST_MS = 2_000L
        const val MOST_MS = 15_000L
    }

    private val _list = MutableStateFlow<List<AgentAlert>>(emptyList())
    val list: StateFlow<List<AgentAlert>> = _list.asStateFlow()

    private val _running = MutableStateFlow<Map<String, String>>(emptyMap())
    /** The sessions ("agent/session") a turn of which runs on the bridge now, each with what it is doing ("running npm test"). */
    val running: StateFlow<Map<String, String>> = _running.asStateFlow()

    /** What a session has to show: asking you, a reply not seen, a turn running, or nothing. */
    fun markOf(agent: String, session: String): SessionMark {
        val key = "$agent/$session"
        return when {
            _list.value.any { it.agent == agent && it.session == session } -> SessionMark.Asking
            _done.value.any { it.key == key } -> SessionMark.Done
            key in _running.value.keys -> SessionMark.Running
            else -> SessionMark.None
        }
    }

    private val _done = MutableStateFlow<List<AgentDone>>(emptyList())
    val done: StateFlow<List<AgentDone>> = _done.asStateFlow()

    private val client = AcpBridgeClient(bridge).also { c ->
        c.onEvent = ::event
        c.onDrop = { start() }
    }
    private var job: Job? = null

    /** Open the socket and ask for alerts, again until it answers; a running attempt is started over. */
    fun start() {
        job?.cancel()
        if (!bridge().set) { _list.value = emptyList(); _done.value = emptyList(); return }
        job = scope.launch {
            var wait = FIRST_MS
            while (true) {
                try {
                    val o = client.ask("alerts") as JSONObject
                    val a = o.optJSONArray("asking") ?: JSONArray()
                    val now = (0 until a.length()).mapNotNull { alert(a.getJSONObject(it)) }
                    val d = o.optJSONArray("done") ?: JSONArray()
                    val ended = (0 until d.length()).map { done(d.getJSONObject(it)) }.filter { !shown(it) }
                    val before = _list.value.map { it.key }.toSet() + _done.value.map { it.key }
                    val r = o.optJSONArray("running") ?: JSONArray()
                    _running.value = (0 until r.length()).associate { r.getJSONObject(it).let { x -> "${x.optString("agent")}/${x.optString("sessionId")}" to x.optString("doing") } }
                    _list.value = now
                    _done.value = ended
                    if (now.any { it.key !in before } || ended.any { it.key !in before }) heard()
                    return@launch
                } catch (e: AcpBridgeClient.Refused) {
                    return@launch
                } catch (e: IOException) {
                    Log.i(TAG, "bridge away: ${e.message}")
                } catch (e: TimeoutCancellationException) {
                    // The socket's own timeouts (no Wi-Fi yet after a sleep): asked again, not given up on.
                    Log.i(TAG, "bridge slow: ${e.message}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "alerts not read", e)
                }
                delay(wait)
                wait = (wait * 2).coerceAtMost(MOST_MS)
            }
        }
    }

    /** Paired again or unpaired: forget, and listen to the bridge now in use. */
    fun relinked() {
        job?.cancel()
        client.close()
        _list.value = emptyList()
        _done.value = emptyList()
        _running.value = emptyMap()
        start()
    }

    /** Answer [a] with [option] (an option's id), or cancel it (null). Gone from the list at once, back if it did not get there. */
    fun permit(a: AgentAlert, option: String?) {
        _list.update { l -> l.filter { it.key != a.key } }
        scope.launch {
            try {
                client.permit(a.agent, a.session, a.asking.request, option)
            } catch (e: IOException) {
                Log.i(TAG, "permit failed: ${e.message}")
                if (e.message != "nothing waits for that") _list.update { l -> if (l.any { it.key == a.key }) l else l + a }
            }
        }
    }

    /** The ended turn of [d] put away, here and on the bridge. */
    fun seen(d: AgentDone) {
        _done.update { l -> l.filter { it.key != d.key } }
        scope.launch { runCatching { client.ask("seen", JSONObject().put("agent", d.agent).put("sessionId", d.session)) } }
    }

    /** Seen when on screen: the bridge is told, nothing shows. */
    private fun shown(d: AgentDone): Boolean = (onScreen() == d.key).also { if (it) seen(d) }

    /** Taken off the list here (answered in its chat): the bridge's "answered" follows. */
    fun drop(agent: String, session: String, request: String) =
        _list.update { l -> l.filter { !(it.agent == agent && it.session == session && it.asking.request == request) } }

    private fun event(e: JSONObject) {
        when (e.optString("event")) {
            "asking" -> {
                val a = alert(e) ?: return
                var added = false
                _list.update { l -> if (l.any { it.key == a.key }) l else { added = true; l + a } }
                if (added) heard()
            }
            "answered" -> drop(e.optString("agent"), e.optString("sessionId"), e.optString("request"))
            "done" -> {
                val d = done(e)
                if (shown(d)) return
                _done.update { l -> l.filter { it.key != d.key } + d }
                heard()
            }
            "turn" -> {
                val key = "${e.optString("agent")}/${e.optString("sessionId")}"
                _running.update { if (e.optBoolean("running")) it + (key to e.optString("doing")) else it - key }
            }
            "seen" -> _done.update { l -> l.filter { it.key != "${e.optString("agent")}/${e.optString("sessionId")}" } }
        }
    }

    private fun done(o: JSONObject) = AgentDone(o.optString("agent"), o.optString("sessionId"), o.optString("cwd"),
        o.optString("title"), o.optString("words"), o.optString("error").ifEmpty { null })

    private fun alert(o: JSONObject): AgentAlert? {
        val request = o.optString("request").ifEmpty { return null }
        val opts = o.optJSONArray("options") ?: JSONArray()
        val options = (0 until opts.length()).map { i ->
            opts.getJSONObject(i).let { AskOption(it.optString("optionId"), it.optString("name"), it.optString("kind")) }
        }
        val title = o.optJSONObject("toolCall")?.optString("title").orEmpty()
        return AgentAlert(o.optString("agent"), o.optString("sessionId"), o.optString("cwd"), Asking(request, title, options), o.optString("title"))
    }
}
