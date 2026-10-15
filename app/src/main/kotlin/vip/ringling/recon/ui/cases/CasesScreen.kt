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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.data.watch.WatchItem
import vip.ringling.recon.domain.CaseStatus
import vip.ringling.recon.ui.components.icon
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.PageHeader
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.signal.textColor
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

data class CasesActions(
    val onOpen: (CaseView) -> Unit = {},
    val onNewCase: () -> Unit = {},
    val onAddWatch: () -> Unit = {},
    val onRemoveWatch: (String) -> Unit = {},
)

fun statusTone(s: CaseStatus): Tone = when (s) { CaseStatus.CRITICAL -> Tone.ALERT; CaseStatus.MODERATE -> Tone.INFO; CaseStatus.CLEAN -> Tone.SAFE; CaseStatus.AREA -> Tone.INFO; CaseStatus.UNSCANNED -> Tone.MUTED }

fun caseLabel(n: Int) = "Case " + n.toString().padStart(2, '0')

@Composable
fun CasesScreen(state: CasesUiState, actions: CasesActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(), selectedId: String? = null, compact: Boolean = false) {
    val sig = ReconTheme.signal
    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf(sig.signal.copy(alpha = 0.07f), Color.Transparent), radius = 700f))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + if (compact) 12.dp else 24.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PageHeader("Dossiers", "Cases") { Text("${state.cases.size.toString().padStart(2, '0')} OPEN", color = sig.muted, style = ReconType.value.copy(fontSize = 12.sp)) }
            }
            item { WatchlistStrip(state.watches, actions) }
            items(state.cases, key = { it.file.id }) { c -> CaseCard(c, actions.onOpen, selected = c.file.id == selectedId, compact = compact) }
            item {
                SignalButton("New case", actions.onNewCase, Modifier.fillMaxWidth().height(56.dp), icon = Icons.Outlined.AddCircleOutline, mono = true, dashed = true)
            }
        }
    }
}

@Composable
private fun WatchlistStrip(items: List<WatchItem>, actions: CasesActions) {
    val sig = ReconTheme.signal
    var open by remember { mutableStateOf(false) }
    HairPanel(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.NotificationsNone, null, tint = sig.signal)
                Column(Modifier.weight(1f).clickable(onClickLabel = if (open) "Hide watched emails" else "Show watched emails") { open = !open }) {
                    Text("Watchlist", color = sig.text, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                    Text(if (items.isEmpty()) "Daily breach check on watched emails" else "${items.size} watched · daily breach check", color = sig.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                SignalButton("Add", actions.onAddWatch, mono = true)
            }
            if (open) items.forEach { w ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.email, color = sig.text, style = ReconType.value.copy(fontSize = 12.sp), maxLines = 1)
                        SignalLabel(if (w.lastChecked == 0L) "Waiting for first check" else "${w.knownCount.coerceAtLeast(0)} known breaches", small = true)
                    }
                    TextButton(onClick = { actions.onRemoveWatch(w.email) }, Modifier.heightIn(min = 48.dp)) { SignalLabel("Remove", color = sig.alertText) }
                }
            }
        }
    }
}

/** Dossier card: folder tab "CASE 01", serif name, target-type icons, exposure bar and a status tag. */
@Composable
fun CaseCard(c: CaseView, onOpen: (CaseView) -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, compact: Boolean = false) {
    val sig = ReconTheme.signal
    val tone = statusTone(c.status)
    Box(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) sig.signal.copy(alpha = 0.06f) else sig.panel.copy(alpha = 0.78f))
                .border(1.dp, if (selected) sig.signal.copy(alpha = 0.5f) else sig.hairline, RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Open case") { onOpen(c) }
                .semantics { contentDescription = "${caseLabel(c.file.number)}, ${c.file.name}, ${c.status.label}, ${c.file.targets.size} targets" }
                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(c.file.name, color = sig.text, style = ReconType.pageTitle.copy(fontSize = if (compact) 26.sp else 30.sp, lineHeight = if (compact) 28.sp else 32.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                StatusTag(c.status.label, tone)
            }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                c.file.targets.take(6).forEach { t ->
                    Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) { Icon(t.type.icon(), null, tint = sig.muted, modifier = Modifier.size(16.dp)) }
                }
                Text(
                    (if (c.file.targets.isEmpty()) "NO TARGETS" else "${c.file.targets.size} TARGET${if (c.file.targets.size == 1) "" else "S"}") + if (c.status == CaseStatus.AREA) " · NO SCORE FOR AREAS" else "",
                    color = sig.muted, style = ReconType.value.copy(fontSize = 11.sp), modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (c.status != CaseStatus.AREA && c.status != CaseStatus.UNSCANNED) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.08f))) {
                        Box(Modifier.fillMaxWidth((c.exposure ?: 0) / 100f).height(6.dp).background(tone.let { if (it == Tone.ALERT) sig.alert else if (it == Tone.SAFE) sig.safe else sig.info }))
                    }
                    Text("${c.exposed} EXPOSED", color = tone.textColor(), style = ReconType.value.copy(fontSize = 10.5.sp))
                }
            }
        }
        SignalLabel(caseLabel(c.file.number), Modifier.offset(x = 14.dp, y = (-1).dp).background(sig.ground).padding(horizontal = 6.dp), color = if (selected || c.status != CaseStatus.UNSCANNED) sig.signal else sig.muted, small = true)
    }
}
