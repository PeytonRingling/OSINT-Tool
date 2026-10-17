package vip.ringling.recon.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.domain.Exposure
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.RiskScore
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.domain.isRegistryRecord
import vip.ringling.recon.domain.scorable
import vip.ringling.recon.ui.components.LegalNotice
import vip.ringling.recon.ui.components.LegalNoticeBanner
import vip.ringling.recon.ui.components.LocalUserLocation
import vip.ringling.recon.ui.components.ModuleCard
import vip.ringling.recon.ui.components.RegistrySummaryRow
import vip.ringling.recon.ui.components.RemoteImage
import vip.ringling.recon.ui.components.distanceMeters
import vip.ringling.recon.ui.components.icon
import vip.ringling.recon.ui.map.MapPin
import vip.ringling.recon.ui.map.TileMap
import vip.ringling.recon.ui.signal.BracketPanel
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.RiskNumeral
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SignalIconButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.TickBar
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.signal.riskTone
import vip.ringling.recon.ui.signal.textColor
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType
import kotlin.math.ceil

data class ResultsActions(
    val onBack: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onPin: () -> Unit = {},
    val onFinding: (Finding) -> Unit = {},
    val onOpenFinding: (Finding) -> Unit = {},
    /** A map pin was tapped: select it (the preview card appears) rather than opening it. */
    val onSelect: (Finding) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onHandled: (Finding, Boolean) -> Unit = { _, _ -> },
    val onFilter: (FindingFilter) -> Unit = {},
    val onSort: (AreaSort) -> Unit = {},
    val onAckRegistry: () -> Unit = {},
    val onRequestLocation: () -> Unit = {},
    val onShowFindings: () -> Unit = {},
    val onStop: () -> Unit = {},
)

/** Header shared by the result screens: back, "TYPE · done/total modules" label, value, export (and pin). */
@Composable
fun ResultsHeader(state: ResultsUiState, actions: ResultsActions, modifier: Modifier = Modifier, glass: Boolean = false) {
    val sig = ReconTheme.signal
    val total = state.statuses.size
    val done = state.statuses.values.count { it == ModuleStatus.DONE || it == ModuleStatus.FAILED }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SignalIconButton(Icons.Rounded.ArrowBack, "Back to radar", actions.onBack)
        Column(Modifier.weight(1f)) {
            SignalLabel("${state.input?.type?.label ?: ""} · $done/$total modules", small = true)
            Text(state.input?.raw.orEmpty(), color = sig.text, style = ReconType.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SignalIconButton(Icons.Rounded.BookmarkAdd, "Pin to case", actions.onPin)
        if (state.findings.any { vip.ringling.recon.domain.Export.exportable(it) }) SignalIconButton(Icons.Rounded.FileDownload, "Export report", actions.onExport)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** Exposure map for every non-ZIP target: risk numeral, stats, orbit graph, breach timeline and "fix first". */
@Composable
fun ExposureScreen(state: ResultsUiState, actions: ResultsActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(), showFindingsButton: Boolean = true) {
    val sig = ReconTheme.signal
    val input = state.input
    val scored = state.findings.filter { it.scorable }
    val score = RiskScore.compute(state.findings)
    val exposed = scored.count { it.severity == Severity.EXPOSED }; val info = scored.count { it.severity == Severity.INFO }
    val nodes = Exposure.nodes(state.findings, state.statuses.keys)
    val events = Exposure.timeline(state.findings)
    val steps = Exposure.fixFirst(state.findings)
    val tone = riskTone(score)
    var sheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<SheetFor?>(null) }
    sheet?.let { sh ->
        val (title, list, empty) = when (sh) {
            is SheetFor.Module -> Triple(sh.label.lowercase().replaceFirstChar { it.uppercase() }, scored.filter { it.module == sh.module }, "Nothing found by this module.")
            is SheetFor.Step -> Triple(sh.step.title, scored.filter { it.id in sh.step.findingIds }, "No matching findings.")
        }
        androidx.compose.material3.ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = sig.panel) {
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
                if (list.isEmpty()) { SignalLabel(title); Text(empty, color = sig.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp)) }
                else Box(Modifier.heightIn(max = 560.dp)) { androidx.compose.foundation.rememberScrollState().let { sc -> Column(Modifier.verticalScroll(sc)) { FindingGroup(title, "${list.size}", list, state.handled) { f -> sheet = null; actions.onFinding(f) } } } }
            }
        }
    }
    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf(tone.let { if (it == Tone.ALERT) sig.alert else sig.signal }.copy(alpha = 0.10f), Color.Transparent), radius = 800f))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ResultsHeader(state, actions) }
            if (state.running) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = sig.signal, trackColor = sig.ledOff) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        RiskNumeral(score)
                        StatusTag(Exposure.headline(score), tone, Modifier.padding(top = 10.dp))
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                        Stat(scored.size, "findings", sig.text); Stat(exposed, "exposed", sig.alertText); Stat(info, "info", sig.infoText)
                        val handledN = scored.count { vip.ringling.recon.data.HandledRepository.keyOf(it) in state.handled }
                        if (handledN > 0) Stat(handledN, "handled", sig.safeText)
                    }
                }
            }
            item { TickBar(ceil(score / 5.0).toInt().coerceIn(0, 20), tone = tone, modifier = Modifier.padding(top = 2.dp)) }
            item {
                BracketPanel(Modifier.fillMaxWidth().height(262.dp)) {
                    OrbitGraph(input?.type?.icon() ?: ModuleId.BREACH.icon(), input?.raw.orEmpty(), nodes, Modifier.fillMaxSize(), onNode = { sheet = SheetFor.Module(it.module, it.label) })
                    if (showFindingsButton) SignalButton("${state.findings.count { it.module != ModuleId.DORKS && it.scorable }} findings ›", actions.onShowFindings, Modifier.align(Alignment.BottomEnd).padding(10.dp), mono = true)
                }
            }
            if (events.isNotEmpty()) item {
                HairPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { SignalLabel("Breach timeline"); SignalLabel("${events.size} events · tap a dot") }
                        BreachTimeline(events, Modifier.fillMaxWidth().padding(top = 6.dp).height(46.dp), onEvent = { e -> scored.firstOrNull { it.id == e.findingId }?.let(actions.onFinding) })
                    }
                }
            }
            val sources = Exposure.breachSources(state.findings)
            if (sources.isNotEmpty()) item(key = "sources") {
                HairPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { SignalLabel("Breach sources checked"); SignalLabel("${sources.size}") }
                        sources.forEachIndexed { i, sc ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                            val target = sc.findingId?.let { id -> state.findings.firstOrNull { it.id == id } }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).let { m -> if (target == null) m else m.clickable(onClickLabel = "Open ${sc.name} result") { actions.onFinding(target) } }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(sc.name, color = sig.text, style = MaterialTheme.typography.titleSmall)
                                    Text(sc.summary, color = sig.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                when (sc.state) {
                                    vip.ringling.recon.domain.SourceState.FOUND -> StatusTag("Found", Tone.ALERT)
                                    vip.ringling.recon.domain.SourceState.CLEAN -> StatusTag("Clean", Tone.SAFE)
                                    vip.ringling.recon.domain.SourceState.SKIPPED -> StatusTag("Skipped", Tone.MUTED)
                                    vip.ringling.recon.domain.SourceState.FAILED -> StatusTag("Failed", Tone.ALERT)
                                }
                                if (target != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = sig.muted)
                            }
                        }
                    }
                }
            }
            val breaches = scored.filter { it.module == ModuleId.BREACH }.sortedBy { if (it.severity == Severity.EXPOSED) 0 else 1 }
            if (breaches.isNotEmpty()) item(key = "breaches") {
                FindingGroup("Breaches", "${breaches.size} · tap for details", breaches, state.handled, onOpen = actions.onFinding)
            }
            val others = scored.filter { it.module != ModuleId.BREACH }.sortedBy { if (it.severity == Severity.EXPOSED) 0 else 1 }
            if (others.isNotEmpty()) item(key = "others") {
                FindingGroup("Other findings", "${others.size}", others, state.handled, onOpen = actions.onFinding)
            }
            if (steps.isNotEmpty()) item {
                HairPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { SignalLabel("Fix first"); SignalLabel("${steps.size} steps") }
                        steps.forEachIndexed { i, st ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 54.dp).let { m -> if (st.findingIds.isEmpty()) m else m.clickable(onClickLabel = "Show affected findings") { sheet = SheetFor.Step(st) } }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).let { if (st.urgent) it.background(sig.alert) else it.border(1.dp, Color(0xFF3A423A), RoundedCornerShape(6.dp)) },
                                    contentAlignment = Alignment.Center,
                                ) { Text("${i + 1}", color = if (st.urgent) Color(0xFF1A0804) else sig.muted, style = ReconType.value.copy(fontSize = 12.sp)) }
                                Column(Modifier.weight(1f)) {
                                    Text(st.title, color = sig.text, style = MaterialTheme.typography.titleSmall)
                                    Text(st.detail, color = sig.muted, style = MaterialTheme.typography.bodySmall)
                                }
                                if (st.findingIds.isNotEmpty()) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = sig.muted)
                            }
                        }
                    }
                }
            }
            if (!state.running && state.findings.isEmpty()) item {
                Text("Nothing found. Either this target is clean or every source that could check it is switched off in the loadout.", color = sig.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Stat(n: Int, label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$n", color = color, style = ReconType.value.copy(fontSize = 11.sp))
        Text(label.uppercase(), color = ReconTheme.signal.muted, style = ReconType.label.copy(fontSize = 11.sp, letterSpacing = 0.06.sp))
    }
}

/** The full findings list (grouped by module, filterable). Tapping a row opens its detail. */
@Composable
fun FindingsList(state: ResultsUiState, actions: ResultsActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(), onBack: (() -> Unit)? = null, showHeader: Boolean = true, compact: Boolean = false) {
    val sig = ReconTheme.signal
    val shown = state.findings.filter { f ->
        when (state.filter) {
            FindingFilter.ALL -> true
            FindingFilter.EXPOSED -> f.severity == Severity.EXPOSED
            FindingFilter.INFO -> f.severity == Severity.INFO
        }
    }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showHeader && onBack != null) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SignalIconButton(Icons.Rounded.ArrowBack, "Back to exposure map", onBack)
                Column(Modifier.weight(1f)) { SignalLabel("Findings", small = true); Text(state.input?.raw.orEmpty(), color = sig.text, style = ReconType.value, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text("${shown.size}", color = sig.muted, style = ReconType.value)
            }
        }
        item { SegmentedControl(FindingFilter.entries.map { it.label }, state.filter.ordinal, { actions.onFilter(FindingFilter.entries[it]) }) }
        val byModule = shown.groupBy { it.module }
        state.statuses.keys.sortedBy { it.ordinal }.forEach { m ->
            val list = byModule[m].orEmpty()
            if (list.isEmpty() && state.filter != FindingFilter.ALL) return@forEach
            if (compact) { if (list.isNotEmpty()) item(key = "m-$m") { FindingGroup(m.label, "${list.size}", list, state.handled, onOpen = actions.onFinding) }; return@forEach }
            item(key = "m-$m") {
                ModuleCard(m, state.statuses[m] ?: ModuleStatus.DONE, list, state.errors[m], state.selectedId, actions.onFinding)
            }
        }
    }
}

// ---------------------------------------------------------------- area scan (ZIP)

/** Offender-registry area scan: map on top, bottom sheet with the record count, distance controls, notices and the record list. No risk score, no ring. */
@Composable
fun AreaScreen(state: ResultsUiState, actions: ResultsActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(), mapHeight: androidx.compose.ui.unit.Dp = 340.dp, split: Boolean = false) {
    val sig = ReconTheme.signal
    val me = LocalUserLocation.current
    val records = state.findings.filter { it.isRegistryRecord }
    val located = records.filter { it.geo != null }
    val rings = remember(located) { vip.ringling.recon.domain.Geo.scanRings(located.map { it.geo!! }) }
    val mapBox: @Composable (Modifier) -> Unit = { mod ->
        Box(mod) {
            TileMap(
                pins = located.map { MapPin(it.id, it.geo!!, it.severity, it.title, vip.ringling.recon.domain.APPROXIMATE_TAG in it.tags) },
                me = me, selectedId = state.selectedId, onPinClick = { p -> located.firstOrNull { it.id == p.id }?.let(actions.onSelect) },
                modifier = Modifier.fillMaxSize(), clipShape = androidx.compose.ui.graphics.RectangleShape,
                camera = vip.ringling.recon.domain.MapMemory.camera("area"), ringCenter = rings?.first, ringMeters = rings?.second.orEmpty(),
                oneFingerPan = true, controlsPadding = PaddingValues(top = 72.dp + contentPadding.calculateTopPadding()),
            )
            Row(Modifier.padding(top = contentPadding.calculateTopPadding() + 8.dp, start = 16.dp, end = 16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassButton(Icons.Rounded.ArrowBack, "Back to radar", actions.onBack)
                Row(
                    Modifier.height(48.dp).clip(RoundedCornerShape(10.dp)).background(sig.ground.copy(alpha = 0.78f)).border(1.dp, sig.line, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { SignalLabel("ZIP"); Text(state.input?.raw.orEmpty(), color = sig.text, style = ReconType.value.copy(fontSize = 14.sp)) }
                Box(Modifier.weight(1f))
                GlassButton(Icons.Rounded.BookmarkAdd, "Pin to case", actions.onPin)
            }
            if (!split) records.firstOrNull { it.id == state.selectedId }?.let { r ->
                RecordPreview(r, me, onOpen = { actions.onOpenFinding(r) }, onClose = actions.onClearSelection, modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 72.dp, bottom = 12.dp))
            }
        }
    }
    if (split) {
        // Large screens: map fills the centre, a 320dp pane on the right carries the selected record and the list.
        Row(modifier.fillMaxSize().background(sig.ground)) {
            mapBox(Modifier.weight(1f).fillMaxSize())
            Column(Modifier.width(320.dp).fillMaxSize().background(sig.panel).border(1.dp, sig.hairline)) {
                state.findings.firstOrNull { it.id == state.selectedId && it.isRegistryRecord }?.let { r ->
                    SelectedFindingCard(r, vip.ringling.recon.data.HandledRepository.keyOf(r) in state.handled, actions.onOpenFinding, actions.onPin, "Open record", Modifier.padding(start = 12.dp, end = 12.dp, top = contentPadding.calculateTopPadding() + 8.dp), onHandled = null)
                }
                AreaSheetList(state, actions, records, me, Modifier.weight(1f), contentPadding)
            }
        }
        return
    }
    Column(modifier.fillMaxSize().background(sig.ground)) {
        mapBox(Modifier.fillMaxWidth().height(mapHeight + contentPadding.calculateTopPadding()))
        // bottom sheet, overlapping the map by 18dp
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(top = 0.dp).clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Color(0xFF070A08).let { if (sig.ground.red > 0.5f) sig.panel else it })
                .border(1.dp, sig.signal.copy(alpha = 0.35f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(sig.ledOff)) }
            AreaSheetList(state, actions, records, me, Modifier.weight(1f), contentPadding)
        }
    }
}

/** Compact detail card for the selected finding in the right-hand pane of the large-screen layouts. */
@Composable
fun SelectedFindingCard(f: Finding, handled: Boolean, onOpen: (Finding) -> Unit, onPin: () -> Unit, openLabel: String, modifier: Modifier = Modifier, onHandled: ((Boolean) -> Unit)?) {
    val sig = ReconTheme.signal
    BracketPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SignalLabel("Selected", small = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (f.imageUrl != null) RemoteImage(f.imageUrl, "Photo of ${f.title}", Modifier.size(width = 84.dp, height = 104.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(f.title, color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(f.detail.lineSequence().first(), color = sig.muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (f.tags.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                f.tags.take(6).forEach { t -> StatusTag(t.removeSuffix(" registry"), if (t in f.hotTags) Tone.ALERT else Tone.INFO) }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalButton(openLabel, { onOpen(f) }, Modifier.heightIn(min = 48.dp), filled = true, mono = true)
                if (onHandled == null) SignalButton("Pin to case", onPin, Modifier.heightIn(min = 48.dp), mono = true)
                else SignalButton(if (handled) "Handled ✓" else "Mark handled", { onHandled(!handled) }, Modifier.heightIn(min = 48.dp), mono = true)
            }
        }
    }
}

@Composable
private fun GlassButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val sig = ReconTheme.signal
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(sig.ground.copy(alpha = 0.78f)).border(1.dp, sig.line, RoundedCornerShape(10.dp)).clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = sig.text, modifier = Modifier.size(20.dp)) }
}

@Composable
fun AreaSheetList(state: ResultsUiState, actions: ResultsActions, records: List<Finding>, me: GeoPoint?, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val sig = ReconTheme.signal
    val sorted = remember(records, state.sort, me) {
        if (state.sort == AreaSort.NEAREST && me != null) records.sortedBy { r -> r.geo?.let { vip.ringling.recon.domain.Geo.distanceMeters(me, it) } ?: Double.MAX_VALUE }
        else records.sortedBy { it.title.lowercase() }
    }
    val extras = state.findings.filter { it.module == ModuleId.REGISTRY && !it.isRegistryRecord }
    val summary = extras.firstOrNull { it.id == "REGISTRY-summary" }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(records.size.toString(), color = sig.signal, style = ReconType.numeral.copy(fontSize = 72.sp, lineHeight = 58.sp))
                Column(Modifier.padding(bottom = 4.dp)) {
                    SignalLabel(if (records.size == 1) "Registry record" else "Registry records", color = sig.text)
                    Text("IDAHO ADULT + JUVENILE · LIVE LOOKUP", color = sig.muted, style = ReconType.labelSmall.copy(fontSize = 10.5.sp), modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        if (!state.registryAck) {
            item { LegalNotice(actions.onAckRegistry) }
        } else {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (me == null) SignalButton("Show distances", actions.onRequestLocation, Modifier.weight(1f).height(48.dp), filled = true, icon = Icons.Outlined.MyLocation)
                    else Box(Modifier.weight(1f))
                    SignalButton(state.sort.label, { actions.onSort(if (state.sort == AreaSort.NEAREST) AreaSort.AZ else AreaSort.NEAREST) }, Modifier.width(112.dp), mono = true)
                }
                Text("YOUR LOCATION STAYS ON THIS DEVICE", color = sig.muted, style = ReconType.labelSmall.copy(fontSize = 9.5.sp), modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                LegalNoticeBanner(Modifier.padding(horizontal = 0.dp))
                summary?.let { RegistrySummaryRow(it, Modifier.padding(horizontal = 0.dp)) }
            }
            extras.filter { it.id != "REGISTRY-summary" }.forEach { n -> item(key = n.id) { RegistryNoteRow(n) } }
            items(sorted, key = { it.id }) { r -> RegistryRow(r, me, state.selectedId == r.id, onOpen = { actions.onOpenFinding(r) }) { actions.onFinding(r) } }
            if (records.isEmpty() && !state.running) item { Text("No records for this ZIP.", color = sig.safeText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp)) }
        }
    }
}

@Composable
private fun RegistryNoteRow(f: Finding) {
    val sig = ReconTheme.signal
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(f.title, color = sig.text, style = MaterialTheme.typography.titleSmall)
        Text(f.detail, color = sig.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun RegistryRow(f: Finding, me: GeoPoint?, selected: Boolean, onOpen: (() -> Unit)? = null, onClick: () -> Unit) {
    val sig = ReconTheme.signal
    val dist = distanceMeters(me, f)
    Column(Modifier.fillMaxWidth().background(if (selected) sig.signal.copy(alpha = 0.07f) else Color.Transparent)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClickLabel = "Open record", onClick = onClick).semantics { contentDescription = "${f.title}, ${f.detail.lineSequence().first()}" }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RemoteImage(f.imageUrl, "Photo of ${f.title}", Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.title, color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(f.detail.lineSequence().first(), color = sig.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                    f.tags.forEach { t -> StatusTag(t.removeSuffix(" registry"), if (t in f.hotTags) Tone.ALERT else Tone.INFO) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                dist?.let { Text(vip.ringling.recon.domain.Geo.formatDistance(it), color = sig.signal, style = ReconType.value.copy(fontSize = 12.sp)) }
                Box(
                    Modifier.size(40.dp).let { m -> if (onOpen == null) m else m.clickable(onClickLabel = "Open record details", onClick = onOpen) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = sig.muted) }
            }
        }
    }
}

/** Tappable list of findings on the exposure screen: logo, title, one summary line, the first data classes (risky ones red) and severity. */
@Composable
fun FindingGroup(title: String, trailing: String, items: List<Finding>, handled: Set<String> = emptySet(), showSeverity: Boolean = true, showTags: Boolean = true, onOpen: (Finding) -> Unit) {
    val sig = ReconTheme.signal
    HairPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { SignalLabel(title); SignalLabel(trailing) }
            items.forEachIndexed { i, f ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                val done = vip.ringling.recon.data.HandledRepository.keyOf(f) in handled
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(if (done) 0.5f else 1f).clickable(onClickLabel = "Open details") { onOpen(f) }
                        .semantics { contentDescription = "${f.title}, ${f.severity.name.lowercase()}" }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (f.imageUrl != null) RemoteImage(f.imageUrl, "Logo", Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Fit, placeholderIcon = false)
                    else Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) { Icon(f.module.icon(), null, tint = sig.muted, modifier = Modifier.size(20.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(f.title, color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(f.detail.lineSequence().first(), color = sig.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (showTags && f.tags.isNotEmpty()) {
                            val shown = (f.tags.filter { it in f.hotTags } + f.tags.filter { it !in f.hotTags }).take(3)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                                shown.forEach { StatusTag(it, if (it in f.hotTags) Tone.ALERT else Tone.INFO) }
                                if (f.tags.size > shown.size) StatusTag("+${f.tags.size - shown.size}", Tone.MUTED)
                            }
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (done) StatusTag("Handled ✓", Tone.SAFE) else if (showSeverity) StatusTag(if (f.severity == Severity.EXPOSED) "Exposed" else "Info", if (f.severity == Severity.EXPOSED) Tone.ALERT else Tone.INFO)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = sig.muted)
                    }
                }
            }
        }
    }
}

private sealed interface SheetFor {
    data class Module(val module: ModuleId, val label: String) : SheetFor
    data class Step(val step: vip.ringling.recon.domain.FixStep) : SheetFor
}

/** Preview that floats over the map when a pin is tapped (phones): photo, name, address, tags, distance and a Details button. */
@Composable
fun RecordPreview(f: Finding, me: GeoPoint?, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    val dist = distanceMeters(me, f)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(sig.ground.copy(alpha = 0.95f)).border(1.dp, sig.signal.copy(alpha = 0.45f), RoundedCornerShape(12.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RemoteImage(f.imageUrl, "Photo of ${f.title}", Modifier.size(width = 64.dp, height = 84.dp).clip(RoundedCornerShape(8.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(f.title, Modifier.weight(1f), color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(32.dp).clickable(onClickLabel = "Close preview", onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Close preview", tint = sig.muted, modifier = Modifier.size(18.dp))
                }
            }
            Text(f.detail.lineSequence().first(), color = sig.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                f.tags.take(1).forEach { t -> StatusTag(t.removeSuffix(" registry"), if (t in f.hotTags) Tone.ALERT else Tone.INFO) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SignalButton("Details", onOpen, Modifier.heightIn(min = 48.dp), filled = true, mono = true)
                dist?.let { Text(vip.ringling.recon.domain.Geo.formatDistance(it), color = sig.signal, style = ReconType.value.copy(fontSize = 12.sp), maxLines = 1, softWrap = false) }
            }
        }
    }
}
