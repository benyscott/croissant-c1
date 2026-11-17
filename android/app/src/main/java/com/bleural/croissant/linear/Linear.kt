package com.bleural.croissant.linear

import com.bleural.croissant.ask.Http
import com.bleural.croissant.ask.KeysStore
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** An issue as a list shows it. */
data class IssueRow(
    val id: String,
    val identifier: String,
    val title: String,
    val state: String,
    /** The workflow state's type: triage, backlog, unstarted, started, completed, canceled. */
    val stateType: String,
    /** 0 none, 1 urgent, 2 high, 3 medium, 4 low. */
    val priority: Int,
    val milestoneId: String? = null,
)

data class ProjectRow(
    val id: String,
    val name: String,
    /** The project's status: Backlog, Planned, In Progress, Completed... */
    val status: String,
    /** Its status's type: backlog, planned, started, paused, completed, canceled. */
    val statusType: String,
    /** 0 to 1. */
    val progress: Double,
    val targetDate: String?,
)

data class Milestone(val id: String, val name: String, val progress: Double, val targetDate: String?)

/** A project opened: its phases (milestones) in order, and its issues. */
data class ProjectFull(
    val row: ProjectRow,
    val description: String,
    val lead: String?,
    val milestones: List<Milestone>,
    val issues: List<IssueRow>,
)

/** An issue opened. */
data class IssueFull(
    val id: String,
    val identifier: String,
    val title: String,
    val description: String,
    val state: String,
    val stateType: String,
    val priority: String,
    val assignee: String?,
    val project: String?,
    val projectId: String?,
    val milestone: String?,
    val cycle: String?,
    val labels: List<String>,
    val updatedAt: String,
)

/** What is loaded, or loading, or why not. A page reads only the part it needs. */
data class LinearUi(
    val hasKey: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val viewer: String? = null,
    val mine: List<IssueRow> = emptyList(),
    val projects: List<ProjectRow> = emptyList(),
    val project: ProjectFull? = null,
    val issue: IssueFull? = null,
)

/**
 * Linear, read only: the issues assigned to the key's owner, the projects, a project's phases and
 * issues, an issue in full. Straight to Linear's GraphQL API from the C1, the personal API key kept
 * sealed in [keys] (as Ask's provider keys are) and sent only in the Authorization header.
 */
class Linear(private val scope: CoroutineScope, private val keys: KeysStore) {
    companion object {
        private const val URL = "https://api.linear.app/graphql"
        private const val KEY = "linear"

        private const val ROW = "id identifier title priority state { name type }"

        private val HOME = """
            query {
              viewer {
                name
                assignedIssues(first: 50, orderBy: updatedAt, filter: { state: { type: { nin: ["completed", "canceled"] } } }) { nodes { $ROW } }
              }
              projects(first: 50, orderBy: updatedAt) { nodes { id name progress targetDate status { name type } } }
            }
        """.trimIndent()

        private val PROJECT = """
            query(${'$'}id: String!) {
              project(id: ${'$'}id) {
                id name description progress targetDate status { name type } lead { name }
                projectMilestones(first: 50) { nodes { id name sortOrder progress targetDate } }
                issues(first: 150, orderBy: updatedAt) { nodes { $ROW projectMilestone { id } } }
              }
            }
        """.trimIndent()

        private val ISSUE = """
            query(${'$'}id: String!) {
              issue(id: ${'$'}id) {
                id identifier title description priorityLabel updatedAt
                state { name type } assignee { name } project { id name } projectMilestone { name }
                cycle { number name } labels { nodes { name } }
              }
            }
        """.trimIndent()

        /** Workflow state types in the order a list shows them. */
        val STATE_ORDER = listOf("started", "unstarted", "triage", "backlog", "completed", "canceled")
    }

    private val _state = MutableStateFlow(LinearUi(hasKey = keys.has(KEY)))
    val state: StateFlow<LinearUi> = _state.asStateFlow()

    /** The key's last four characters, for the key page. */
    val keyHint: String? get() = keys.hint(KEY)

    /** Try [key] against Linear, and keep it only if Linear takes it: null when kept, else why not. */
    suspend fun saveKey(key: String): String? {
        val k = key.trim()
        KeysStore.refused(k)?.let { return it }
        val tried = runCatching { call(k, "query { viewer { name } }", null) }
        tried.exceptionOrNull()?.let { if (it is CancellationException) throw it; return it.message ?: "Linear refused it" }
        keys.save(KEY, k)?.let { return it }
        _state.update { LinearUi(hasKey = true) }
        refresh()
        return null
    }

    fun forgetKey() {
        keys.remove(KEY)
        _state.value = LinearUi(hasKey = false)
    }

    /** My issues and the projects, again. */
    fun refresh() = load({ copy(loading = true, error = null) }) { k ->
        val d = call(k, HOME, null)
        val v = d.getJSONObject("viewer")
        val mine = rows(v.getJSONObject("assignedIssues").getJSONArray("nodes")).sortedWith(byState)
        val projects = d.getJSONObject("projects").getJSONArray("nodes").objects().map(::projectRow)
        _state.update { it.copy(loading = false, viewer = v.optString("name").ifEmpty { null }, mine = mine, projects = projects) }
    }

    fun openProject(id: String) = load({ copy(loading = true, error = null, project = project?.takeIf { it.row.id == id }) }) { k ->
        val p = call(k, PROJECT, JSONObject().put("id", id)).getJSONObject("project")
        val ms = p.getJSONObject("projectMilestones").getJSONArray("nodes").objects()
            .sortedBy { it.optDouble("sortOrder") }
            .map { Milestone(it.getString("id"), it.getString("name"), it.optDouble("progress", 0.0), it.str("targetDate")) }
        val issues = p.getJSONObject("issues").getJSONArray("nodes").objects().map { o ->
            row(o).copy(milestoneId = o.optJSONObject("projectMilestone")?.optString("id"))
        }.sortedWith(byState)
        val full = ProjectFull(projectRow(p), p.optString("description"), p.optJSONObject("lead")?.str("name"), ms, issues)
        _state.update { it.copy(loading = false, project = full) }
    }

    fun openIssue(id: String) = load({ copy(loading = true, error = null, issue = issue?.takeIf { it.id == id || it.identifier == id }) }) { k ->
        val o = call(k, ISSUE, JSONObject().put("id", id)).getJSONObject("issue")
        val state = o.getJSONObject("state")
        val cycle = o.optJSONObject("cycle")?.let { c -> c.str("name") ?: "cycle ${c.optInt("number")}" }
        val full = IssueFull(
            id = o.getString("id"), identifier = o.getString("identifier"), title = o.getString("title"),
            description = o.optString("description").takeUnless { o.isNull("description") }.orEmpty(),
            state = state.getString("name"), stateType = state.getString("type"),
            priority = o.optString("priorityLabel"), assignee = o.optJSONObject("assignee")?.str("name"),
            project = o.optJSONObject("project")?.str("name"), projectId = o.optJSONObject("project")?.str("id"),
            milestone = o.optJSONObject("projectMilestone")?.str("name"), cycle = cycle,
            labels = o.optJSONObject("labels")?.optJSONArray("nodes")?.objects()?.map { it.getString("name") }.orEmpty(),
            updatedAt = o.optString("updatedAt"),
        )
        _state.update { it.copy(loading = false, issue = full) }
    }

    private fun load(start: LinearUi.() -> LinearUi, work: suspend (String) -> Unit) {
        _state.update { it.start() }
        scope.launch {
            try {
                val k = withContext(Dispatchers.IO) { keys.key(KEY) } ?: throw IOException("no Linear key")
                withContext(Dispatchers.IO) { work(k) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Linear did not answer") }
            }
        }
    }

    /** One GraphQL call: its `data`, or an [IOException] saying why not, never carrying the key. */
    private suspend fun call(key: String, query: String, vars: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().put("query", query).apply { if (vars != null) put("variables", vars) }
        val req = Request.Builder().url(URL)
            .header("Authorization", key)
            .post(Http.json(body))
            .build()
        val text = try {
            Http.client.newCall(req).execute().use { r ->
                val t = r.body.string()
                if (r.code == 401 || r.code == 403) throw IOException("Linear refused the key")
                if (!r.isSuccessful && !t.trimStart().startsWith("{")) throw IOException("Linear answered ${r.code}")
                t
            }
        } catch (e: IOException) {
            if (e.message?.startsWith("Linear") == true) throw e
            throw IOException("Linear cannot be reached")
        }
        val o = JSONObject(text)
        o.optJSONArray("errors")?.optJSONObject(0)?.let { err ->
            val ext = err.optJSONObject("extensions")
            val said = ext?.str("userPresentableMessage") ?: err.optString("message")
            if (ext?.optString("code") == "AUTHENTICATION_ERROR") throw IOException("Linear refused the key")
            throw IOException(said.ifEmpty { "Linear refused the request" })
        }
        o.getJSONObject("data")
    }

    private val byState = compareBy<IssueRow>({ STATE_ORDER.indexOf(it.stateType).let { i -> if (i < 0) STATE_ORDER.size else i } },
        { if (it.priority == 0) 5 else it.priority })

    private fun rows(a: JSONArray) = a.objects().map(::row)

    private fun row(o: JSONObject): IssueRow {
        val s = o.getJSONObject("state")
        return IssueRow(o.getString("id"), o.getString("identifier"), o.getString("title"), s.getString("name"), s.getString("type"), o.optInt("priority"))
    }

    private fun projectRow(o: JSONObject): ProjectRow {
        val s = o.optJSONObject("status")
        return ProjectRow(o.getString("id"), o.getString("name"), s?.str("name") ?: "", s?.str("type") ?: "", o.optDouble("progress", 0.0), o.str("targetDate"))
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).ifEmpty { null }
