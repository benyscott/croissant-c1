package com.bleural.croissant.ui.beam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bleural.croissant.Core
import com.bleural.croissant.audio.Cues
import com.bleural.croissant.audio.Sound
import com.bleural.croissant.linear.IssueRow
import com.bleural.croissant.linear.ProjectRow
import com.bleural.croissant.net.DocBlock
import com.bleural.croissant.ui.Feature
import com.bleural.croissant.ui.Glyphs
import com.bleural.croissant.ui.NavState
import com.bleural.croissant.ui.PanelDeps
import com.bleural.croissant.ui.Screen
import com.bleural.croissant.ui.elasticPull
import com.bleural.croissant.ui.tappable
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * Linear in Beam, read only: my open issues and the projects; a project's phases with their issues;
 * an issue in full. Words on black, Linear's indigo where it matters.
 */

private val LINEAR: Ramp get() = rampOf(Feature.Linear)

/** One line of a list: a group's label, an issue, a project, or a line of words. */
private sealed interface LinLine {
    data class Label(val text: String, val count: Int?, val first: Boolean, val end: String? = null) : LinLine
    data class Issue(val row: IssueRow) : LinLine
    data class Project(val row: ProjectRow) : LinLine
    data class Words(val text: String, val danger: Boolean = false) : LinLine
}

private val DONE = setOf("completed", "canceled")

private val PROJECT_ORDER = listOf("started", "planned", "paused", "backlog", "completed", "canceled")

private fun pct(p: Double) = "${(p * 100).roundToInt()}%"

/** Linear opened: a key to add, or my issues and the projects. */
@Composable
internal fun LinearRoute(d: PanelDeps, core: Core) {
    val ui by core.linear.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.hasKey) { if (ui.hasKey) core.linear.refresh() }
    if (!ui.hasKey) return NoKeyPage(onKey = { d.nav.push(Screen.LinearKey) }, onBack = d::close)
    val lines = remember(ui.mine, ui.projects, ui.loading, ui.error) {
        buildList {
            val error = ui.error
            if (error != null) add(LinLine.Words(error, danger = true))
            else if (ui.loading && ui.mine.isEmpty() && ui.projects.isEmpty()) add(LinLine.Words("loading"))
            add(LinLine.Label("my issues", ui.mine.size, first = isEmpty()))
            ui.mine.forEach { add(LinLine.Issue(it)) }
            val projects = ui.projects.sortedBy { PROJECT_ORDER.indexOf(it.statusType).let { i -> if (i < 0) PROJECT_ORDER.size else i } }
            add(LinLine.Label("projects", projects.size, first = false))
            projects.forEach { add(LinLine.Project(it)) }
        }
    }
    ListPage("linear", lines, onIssue = { d.nav.push(Screen.LinearIssue(it)) }, onProject = { d.nav.push(Screen.LinearProject(it)) }, onBack = d::close) {
        KnobKey(Glyphs.Restart, LINEAR, onClick = { core.linear.refresh(); Cues.play(Sound.Commit) }, enabled = !ui.loading)
        QuietKey(Glyphs.Key, LINEAR, onClick = { d.nav.push(Screen.LinearKey) }, slot = 1)
    }
}

/** No key yet: where to make one, and the key to type it. */
@Composable
private fun NoKeyPage(onKey: () -> Unit, onBack: () -> Unit) {
    val r = LINEAR
    val (scroll, pull) = rememberWordsScroll(onBack)
    PageTop("linear", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            Text("add a personal API key: in Linear, Settings, Security & access, Personal API keys.", style = BeamType.Body, color = r.ink,
                modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = 50.px))
            Spacer(Modifier.height(112.px))
        }
        KnobKey(Glyphs.Key, r, onClick = onKey)
    }
}

/** A list of issues and projects under their labels: the wheel steps the rows, a tap opens one. */
@Composable
private fun ListPage(
    word: String,
    lines: List<LinLine>,
    onIssue: (String) -> Unit,
    onProject: (String) -> Unit,
    onBack: () -> Unit,
    keys: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    val r = LINEAR
    val rowAt = remember(lines) { lines.indices.filter { lines[it] is LinLine.Issue || lines[it] is LinLine.Project } }
    val rows = rememberRows(
        rowAt.size, onBack,
        itemOf = { rowAt[it] },
        topOf = { val i = rowAt[it]; if (i > 0 && lines[i - 1] is LinLine.Label) i - 1 else i },
    )
    PageTop(word, r) {
        ShelfList(rows, r) {
            items(lines.size) { i ->
                val at = rowAt.indexOf(i)
                when (val l = lines[i]) {
                    is LinLine.Label -> DotLabel(l.text, r, count = l.count, end = l.end, top = if (l.first) LABEL_FIRST else LABEL_ABOVE)
                    is LinLine.Words -> Text(l.text, style = BeamType.Label, color = if (l.danger) r.danger else r.sub, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = 38.px))
                    is LinLine.Issue -> ShelfRow(
                        l.row.title, r, focused = at == rows.focus,
                        onClick = { rows.focus = at; onIssue(l.row.id) },
                        value = l.row.state.lowercase(), past = l.row.stateType in DONE, wrap = true,
                    )
                    is LinLine.Project -> ShelfRow(
                        l.row.name, r, focused = at == rows.focus,
                        onClick = { rows.focus = at; onProject(l.row.id) },
                        value = if (l.row.statusType in DONE) l.row.status.lowercase() else pct(l.row.progress),
                        past = l.row.statusType in DONE, wrap = true, chevron = true,
                    )
                }
            }
            item { Spacer(Modifier.height(112.px)) }
        }
        keys()
    }
}

/** Every page pushed over Linear. */
@Composable
internal fun LinearPages(screen: Screen, core: Core, nav: NavState) {
    when (screen) {
        Screen.LinearKey -> LinearKeyPage(core, nav)
        is Screen.LinearProject -> LinearProjectPage(screen.id, core, nav)
        is Screen.LinearIssue -> LinearIssuePage(screen.id, core, nav)
        else -> Unit
    }
}

/** A project: what it is at, then each phase (milestone) with its issues, then those in no phase. */
@Composable
private fun LinearProjectPage(id: String, core: Core, nav: NavState) {
    val ui by core.linear.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { core.linear.openProject(id) }
    val p = ui.project?.takeIf { it.row.id == id }
    val name = p?.row?.name ?: ui.projects.firstOrNull { it.id == id }?.name ?: "project"
    val lines = remember(p, ui.loading, ui.error) {
        buildList {
            val error = ui.error
            if (error != null) add(LinLine.Words(error, danger = true))
            if (p == null) { if (error == null) add(LinLine.Words("loading")); return@buildList }
            add(LinLine.Words(listOfNotNull(p.row.status.lowercase().ifEmpty { null }, pct(p.row.progress), p.row.targetDate?.let { "due $it" }, p.lead).joinToString(" · ")))
            for (m in p.milestones) {
                val issues = p.issues.filter { it.milestoneId == m.id }
                add(LinLine.Label(m.name.lowercase(), null, first = false, end = "${issues.size} · ${pct(m.progress)}"))
                issues.forEach { add(LinLine.Issue(it)) }
            }
            val loose = p.issues.filter { it.milestoneId == null || p.milestones.none { m -> m.id == it.milestoneId } }
            if (loose.isNotEmpty()) {
                add(LinLine.Label(if (p.milestones.isEmpty()) "issues" else "no phase", loose.size, first = false))
                loose.forEach { add(LinLine.Issue(it)) }
            }
        }
    }
    ListPage(name, lines, onIssue = { nav.push(Screen.LinearIssue(it)) }, onProject = {}, onBack = nav::pop) {}
}

/** An issue in full: its title and state, the facts, its description as markdown. */
@Composable
private fun LinearIssuePage(id: String, core: Core, nav: NavState) {
    val r = LINEAR
    val ui by core.linear.state.collectAsStateWithLifecycle()
    LaunchedEffect(id) { core.linear.openIssue(id) }
    val i = ui.issue?.takeIf { it.id == id }
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    val work = rememberWorkMeasurer()
    val draw = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 32)
    val unit = with(LocalDensity.current) { 1.px.toPx() }
    val blocks = remember(i?.description) { i?.description?.takeIf { it.isNotBlank() }?.let { mdBlocks(DocBlock.markdown(it), work, unit, r.hue) }.orEmpty() }
    PageTop(i?.identifier ?: "issue", r) {
        Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
            if (i == null) {
                Text(ui.error ?: "loading", style = BeamType.Body, color = if (ui.error != null) r.danger else r.sub,
                    modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = 50.px))
                return@Column
            }
            ItemTitle(i.title, r, details = i.state.lowercase())
            Fact("priority", i.priority.lowercase().ifEmpty { null }, r)
            Fact("assignee", i.assignee, r)
            Fact("project", i.project, r, onClick = i.projectId?.let { pid -> { nav.push(Screen.LinearProject(pid)) } })
            Fact("phase", i.milestone, r)
            Fact("cycle", i.cycle, r)
            Fact("labels", i.labels.joinToString(", ").ifEmpty { null }, r)
            if (blocks.isNotEmpty()) {
                DotLabel("description", r, top = LABEL_UNDER_TITLE)
                CompositionLocalProvider(LocalFileHue provides r.hue) { blocks.forEach { MdBlockView(it, draw) } }
            }
            Spacer(Modifier.height(48.px))
        }
    }
}

/** A fact of an issue on one line, its name in sub and what it is in white (the hue when a tap opens it); nothing when it has none. */
@Composable
private fun Fact(name: String, value: String?, r: Ramp, onClick: (() -> Unit)? = null) {
    if (value == null) return
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = r.sub)) { append("$name  ") }
            withStyle(SpanStyle(color = if (onClick != null) r.hue else r.ink)) { append(value) }
        },
        style = BeamType.Body, maxLines = 2, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().tappable(onClick).padding(horizontal = 24.px).paddingFromBaseline(top = 44.px),
    )
}

/**
 * Linear's key, as a provider's is typed: shown while typed, tried against Linear and kept only if
 * Linear takes it, then back to the list. With one kept, its last four and the X to forget it.
 */
@Composable
private fun LinearKeyPage(core: Core, nav: NavState) {
    val r = LINEAR
    val ui by core.linear.state.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var said by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requester.requestFocus() }
    val scope = rememberCoroutineScope()
    fun go() {
        val k = key.trim()
        if (k.isEmpty() || testing) return
        testing = true
        said = null
        scope.launch {
            val why = core.linear.saveKey(k)
            testing = false
            if (why == null) nav.pop(Sound.Commit) else { said = why; Cues.play(Sound.JoinFailed) }
        }
    }
    val (scroll, pull) = rememberWordsScroll(nav::pop)
    PageTop("linear", r) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().elasticPull(pull, r.ink, r.ground).verticalScroll(scroll)) {
                DotLabel("key", r, top = LABEL_FIRST, end = if (ui.hasKey) core.linear.keyHint?.let { "kept …$it" } ?: "kept" else null)
                Field(
                    key, { key = it; said = null }, r, Modifier.focusRequester(requester),
                    placeholder = "lin_api_…",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }), refused = said != null,
                )
                val line = if (testing) "testing" else said
                if (line != null) Text(line, style = BeamType.Label, color = if (testing) r.sub else r.danger, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.px).paddingFromBaseline(top = 38.px, bottom = 22.px))
                Spacer(Modifier.height(112.px))
            }
            KnobKey(Glyphs.Check, r, onClick = { go() }, enabled = key.isNotBlank() && !testing)
            if (ui.hasKey) DangerKey(Glyphs.Close, r, onClick = { asking = true; Cues.play(Sound.SheetIn) }, slot = 1)
            if (asking) Sheet(
                r, "forget", colour = r.danger,
                onCancel = { asking = false; Cues.play(Sound.SheetDismiss) },
                onAct = { asking = false; core.linear.forgetKey(); nav.pop(Sound.Destroy) },
            )
        }
    }
}
