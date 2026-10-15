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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
                    }
                }
            }
            item { TickBar(ceil(score / 5.0).toInt().coerceIn(0, 20), tone = tone, modifier = Modifier.padding(top = 2.dp)) }
            item {
                BracketPanel(Modifier.fillMaxWidth().height(262.dp)) {
                    OrbitGraph(input?.type?.icon() ?: ModuleId.BREACH.icon(), input?.raw.orEmpty(), nodes, Modifier.fillMaxSize())
                    if (showFindingsButton) SignalButton("${state.findings.count { it.module != ModuleId.DORKS && it.scorable }} findings ›", actions.onShowFindings, Modifier.align(Alignment.BottomEnd).padding(10.dp), mono = true)
                }
            }
            if (events.isNotEmpty()) item {
                HairPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { SignalLabel("Breach timeline"); SignalLabel("${events.size} events") }
                        BreachTimeline(events, Modifier.fillMaxWidth().padding(top = 6.dp).height(46.dp))
                    }
                }
            }
            if (steps.isNotEmpty()) item {
                HairPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { SignalLabel("Fix first"); SignalLabel("${steps.size} steps") }
                        steps.forEachIndexed { i, st ->
                            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                            Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(
                                    Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).let { if (st.urgent) it.background(sig.alert) else it.border(1.dp, Color(0xFF3A423A), RoundedCornerShape(6.dp)) },
                                    contentAlignment = Alignment.Center,
                                ) { Text("${i + 1}", color = if (st.urgent) Color(0xFF1A0804) else sig.muted, style = ReconType.value.copy(fontSize = 12.sp)) }
                                Column(Modifier.weight(1f)) {
                                    Text(st.title, color = sig.text, style = MaterialTheme.typography.titleSmall)
                                    Text(st.detail, color = sig.muted, style = MaterialTheme.typography.bodySmall)
                                }
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
fun FindingsList(state: ResultsUiState, actions: ResultsActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(), onBack: (() -> Unit)? = null, showHeader: Boolean = true) {
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
                me = me, selectedId = state.selectedId, onPinClick = { p -> located.firstOrNull { it.id == p.id }?.let(actions.onFinding) },
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
        }
    }
    if (split) {
        // Large screens: map fills the centre, a 320dp pane on the right carries the selected record and the list.
        Row(modifier.fillMaxSize().background(sig.ground)) {
            mapBox(Modifier.weight(1f).fillMaxSize())
            Column(Modifier.width(320.dp).fillMaxSize().background(sig.panel).border(1.dp, sig.hairline)) {
                state.findings.firstOrNull { it.id == state.selectedId && it.isRegistryRecord }?.let { r ->
                    SelectedFindingCard(r, state.selectedId in state.handled, actions.onOpenFinding, actions.onPin, "Open record", Modifier.padding(start = 12.dp, end = 12.dp, top = contentPadding.calculateTopPadding() + 8.dp), onHandled = null)
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
            Text(f.title, color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(f.detail.lineSequence().first(), color = sig.muted, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
            items(sorted, key = { it.id }) { r -> RegistryRow(r, me, state.selectedId == r.id) { actions.onFinding(r) } }
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
fun RegistryRow(f: Finding, me: GeoPoint?, selected: Boolean, onClick: () -> Unit) {
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
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = sig.muted)
            }
        }
    }
}
