package vip.ringling.recon.ui.results

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.isPersonMatch
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

/** Public-records results for a name: a neutral match count (no risk score), matches grouped by source, source status lines and the portals finding. */
@Composable
fun PersonScreen(state: ResultsUiState, actions: ResultsActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val sig = ReconTheme.signal
    val matches = state.findings.filter { it.isPersonMatch }
    val groups = matches.groupBy { it.tags.firstOrNull().orEmpty() }
    val status = state.findings.filter { it.module == vip.ringling.recon.domain.ModuleId.PEOPLE && !it.isPersonMatch && it.id != "PEOPLE-links" }
    val portals = state.findings.firstOrNull { it.id == "PEOPLE-links" }
    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf(sig.signal.copy(alpha = 0.07f), Color.Transparent), radius = 800f))) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxSize().align(Alignment.TopCenter),
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { ResultsHeader(state, actions) }
            if (state.running) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = sig.signal, trackColor = sig.ledOff) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(matches.size.toString(), color = sig.signal, style = ReconType.numeral.copy(fontSize = 72.sp, lineHeight = 58.sp))
                    Column(Modifier.padding(bottom = 4.dp)) {
                        SignalLabel(if (matches.size == 1) "Public record match" else "Public record matches", color = sig.text)
                        SignalLabel("By name only · shown live, not saved", small = true)
                    }
                }
            }
            item {
                HairPanel(Modifier.fillMaxWidth()) {
                    Text(
                        "These are matches on the name alone. A common name returns other people, so confirm each one before relying on it. Official records (state courts, property, voter files) are searched on each jurisdiction's own site: see the portals below.",
                        Modifier.padding(14.dp), color = sig.muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            groups.forEach { (source, list) -> item(key = "g-$source") { FindingGroup(source.ifBlank { "Other" }, "${list.size}", list, state.handled, showSeverity = false, showTags = false, onOpen = actions.onFinding) } }
            if (status.isNotEmpty()) item(key = "status") {
                HairPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SignalLabel("Sources with no result")
                        status.forEach { f -> Text("${f.title} — ${f.detail.lineSequence().first()}", color = sig.muted, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            portals?.let { p -> item(key = "portals") { FindingGroup("Official and public portals", "${p.links.size} links", listOf(p), state.handled, showSeverity = false, onOpen = actions.onFinding) } }
            if (!state.running && state.findings.isEmpty()) item { Text("No sources answered. Check your connection.", color = sig.muted, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
