package vip.ringling.recon.ui.cases

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.domain.CaseNote
import vip.ringling.recon.domain.CasePivot
import vip.ringling.recon.domain.CaseTarget
import vip.ringling.recon.domain.CaseTargetState
import vip.ringling.recon.ui.components.icon
import vip.ringling.recon.ui.log.ago
import vip.ringling.recon.ui.signal.BracketPanel
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SignalIconButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.SignalTextField
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

data class CaseDetailActions(
    val onBack: () -> Unit = {},
    val onTab: (CaseTab) -> Unit = {},
    val onOpenTarget: (CaseTargetState) -> Unit = {},
    val onAddTarget: () -> Unit = {},
    val onRemoveTarget: (CaseTarget) -> Unit = {},
    val onRunPivot: (CasePivot) -> Unit = {},
    val onAddNote: (String) -> Unit = {},
    val onDeleteNote: (CaseNote) -> Unit = {},
    val onSaveMarkdown: () -> Unit = {},
    val onSavePdf: () -> Unit = {},
    val onSaveEvidence: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onChanges: () -> Unit = {},
    val onRescan: () -> Unit = {},
    val onExpand: () -> Unit = {},
    val onTags: () -> Unit = {},
)

/** Case detail: header, link graph, Targets / Pivots / Notes / Changes tabs. [showChangesInline] renders the timeline on the Changes tab (large screens). */
@Composable
fun CaseDetailScreen(
    c: CaseView, tab: CaseTab, actions: CaseDetailActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(),
    graphHeight: androidx.compose.ui.unit.Dp = 300.dp, showHeader: Boolean = true, showGraph: Boolean = true, changesContent: (@Composable () -> Unit)? = null,
) {
    val sig = ReconTheme.signal
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (showHeader) item { CaseHeader(c, actions) }
        if (showGraph) item {
            BracketPanel(Modifier.fillMaxWidth().height(graphHeight)) { LinkGraph(c.states, c.edges, actions.onOpenTarget, actions.onAddTarget, Modifier.fillMaxSize()) }
        }
        item {
            SegmentedControl(CaseTab.entries.map { it.label }, tab.ordinal, { actions.onTab(CaseTab.entries[it]) }, Modifier.padding(top = 2.dp))
        }
        when (tab) {
            CaseTab.TARGETS -> {
                if (c.states.isEmpty()) item { Empty("No targets yet. Tap the + node in the graph, or pin a scan to this case.") }
                items(c.states, key = { it.target.key }) { st -> TargetRow(st, actions) }
            }
            CaseTab.PIVOTS -> {
                if (c.pivots.isEmpty()) item { Empty("No suggestions yet. Scan the targets in this case; leads found in their results appear here.") }
                else item {
                    HairPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                            c.pivots.forEachIndexed { i, p ->
                                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f)))
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Column(Modifier.weight(1f)) {
                                        Text(p.title, color = sig.text, style = MaterialTheme.typography.titleSmall)
                                        Text(p.reason, color = sig.muted, style = MaterialTheme.typography.bodySmall)
                                        Text(p.query, color = sig.signal, style = ReconType.value.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    SignalButton("Run", { actions.onRunPivot(p) }, mono = true)
                                }
                            }
                        }
                    }
                }
            }
            CaseTab.NOTES -> {
                item { NoteInput(actions.onAddNote) }
                items(c.file.notes, key = { it.id }) { n -> NoteRow(n, actions.onDeleteNote) }
            }
            CaseTab.CHANGES -> {
                if (changesContent != null) item { changesContent() }
                else item { SignalButton("Open changes timeline", actions.onChanges, Modifier.fillMaxWidth(), mono = true) }
            }
        }
    }
}

@Composable
fun CaseHeader(c: CaseView, actions: CaseDetailActions, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    var menu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SignalIconButton(Icons.Rounded.ArrowBack, "Back to cases", actions.onBack)
        Column(Modifier.weight(1f)) {
            SignalLabel("${caseLabel(c.file.number)} · ${c.file.targets.size} target${if (c.file.targets.size == 1) "" else "s"}", color = sig.signal, small = true)
            Text(c.file.name, color = sig.text, style = ReconType.pageTitle.copy(fontSize = 28.sp, lineHeight = 30.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            SignalIconButton(Icons.Rounded.FileDownload, "Export case report", { exportMenu = true })
            DropdownMenu(exportMenu, { exportMenu = false }) {
                DropdownMenuItem({ Text("Save Markdown") }, { exportMenu = false; actions.onSaveMarkdown() })
                DropdownMenuItem({ Text("Save PDF") }, { exportMenu = false; actions.onSavePdf() })
                DropdownMenuItem({ Text("Save evidence bundle (ZIP)") }, { exportMenu = false; actions.onSaveEvidence() })
                DropdownMenuItem({ Text("Share") }, { exportMenu = false; actions.onShare() })
            }
        }
        Box {
            SignalIconButton(Icons.Rounded.MoreVert, "More actions", { menu = true })
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Re-scan all targets") }, { menu = false; actions.onRescan() })
                DropdownMenuItem({ Text("Expand leads (up to 8)") }, { menu = false; actions.onExpand() })
                DropdownMenuItem({ Text("Edit tags") }, { menu = false; actions.onTags() })
                DropdownMenuItem({ Text("Rename case") }, { menu = false; actions.onRename() })
                DropdownMenuItem({ Text("Delete case") }, { menu = false; actions.onDelete() })
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(text, color = ReconTheme.signal.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
}

@Composable
private fun TargetRow(st: CaseTargetState, actions: CaseDetailActions) {
    val sig = ReconTheme.signal
    val tone = when { st.isArea -> Tone.INFO; st.risk == null -> Tone.MUTED; st.risk!! >= 50 -> Tone.ALERT; st.risk!! >= 1 -> Tone.INFO; else -> Tone.SAFE }
    HairPanel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = "Open latest results") { actions.onOpenTarget(st) }
                .semantics { contentDescription = "${st.target.value}, ${st.target.type.label}" }.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(st.target.type.icon(), null, tint = sig.muted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(st.target.value, color = sig.text, style = ReconType.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SignalLabel("${st.target.type.label} · " + (st.latest?.let { "${st.scans.size} scan${if (st.scans.size == 1) "" else "s"} · ${ago(it.ts, System.currentTimeMillis())}" } ?: "not scanned yet"), small = true)
            }
            StatusTag(
                when { st.isArea -> st.records?.let { "$it records" } ?: "Area"; st.risk == null -> "Not scanned"; st.risk == 0 -> "Clean"; else -> "Risk ${st.risk}" }, tone,
            )
            SignalIconButton(Icons.Rounded.Close, "Remove ${st.target.value} from case", { actions.onRemoveTarget(st.target) })
        }
    }
}

@Composable
private fun NoteInput(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { SignalTextField(text, { text = it }, "Add a note…", description = "New note", onGo = { if (text.isNotBlank()) { onAdd(text); text = "" } }) }
        SignalButton("Save", { onAdd(text); text = "" }, enabled = text.isNotBlank(), filled = true)
    }
}

@Composable
private fun NoteRow(n: CaseNote, onDelete: (CaseNote) -> Unit) {
    val sig = ReconTheme.signal
    HairPanel(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(n.text, color = sig.text, style = MaterialTheme.typography.bodyMedium)
                SignalLabel(ago(n.ts, System.currentTimeMillis()), small = true, modifier = Modifier.padding(top = 4.dp))
            }
            SignalIconButton(Icons.Rounded.Close, "Delete note", { onDelete(n) })
        }
    }
}
