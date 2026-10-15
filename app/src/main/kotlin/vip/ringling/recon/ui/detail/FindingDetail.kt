package vip.ringling.recon.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.Export
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Severity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Map
import androidx.compose.ui.layout.ContentScale
import vip.ringling.recon.ui.components.LocalUserLocation
import vip.ringling.recon.ui.components.RemoteImage
import vip.ringling.recon.ui.components.distanceLabel
import vip.ringling.recon.ui.map.MapPin
import vip.ringling.recon.ui.map.TileMap
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.components.ConfidenceMeter
import vip.ringling.recon.ui.components.RiskRing
import vip.ringling.recon.ui.components.SeverityChip
import vip.ringling.recon.ui.components.icon
import vip.ringling.recon.ui.components.sharedFinding

data class DetailActions(
    val onCopy: (Finding) -> Unit = {},
    val onShare: (Finding) -> Unit = {},
    val onExport: (Finding) -> Unit = {},
    val onOpenUrl: (String) -> Unit = {},
    val onOpenMap: (vip.ringling.recon.domain.GeoPoint) -> Unit = {},
    val onPivot: (String) -> Unit = {},
)

private fun Finding.weight() = when (severity) {
    Severity.EXPOSED -> 82
    Severity.INFO -> 38
    Severity.CLEAN -> 6
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FindingDetail(
    finding: Finding,
    actions: DetailActions,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (onBack != null) {
            item {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            }
        }
        item {
            Surface(
                Modifier.fillMaxWidth().sharedFinding(finding.id),
                shape = MaterialTheme.shapes.extraLarge,
                color = cs.surfaceContainer,
            ) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(finding.module.icon(), null, tint = cs.primary, modifier = Modifier.size(20.dp))
                        Text(finding.module.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (finding.module != ModuleId.REGISTRY && finding.imageUrl != null) {
                                RemoteImage(finding.imageUrl, "Logo", Modifier.size(56.dp).clip(MaterialTheme.shapes.small), contentScale = ContentScale.Fit, placeholderIcon = false)
                            }
                            Text(finding.title, style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
                            SeverityChip(finding.severity)
                        }
                        RiskRing(finding.weight(), size = 104.dp, stroke = 11.dp, caption = "WEIGHT")
                    }
                    finding.confidence?.takeIf { finding.imageLargeUrl == null && finding.module != ModuleId.REGISTRY }?.let { ConfidenceMeter(it) }
                    if (finding.module == ModuleId.REGISTRY) {
                        RemoteImage(
                            finding.imageLargeUrl ?: finding.imageUrl, "Photo of ${finding.title}",
                            Modifier.fillMaxWidth().height(if (finding.imageLargeUrl ?: finding.imageUrl != null) 260.dp else 72.dp).clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Fit,
                        )
                    }
                    if (finding.tags.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            finding.tags.forEach { tag ->
                                val hot = tag in finding.hotTags
                                Text(
                                    tag, style = MaterialTheme.typography.labelMedium,
                                    color = if (hot) ReconTheme.severity.exposed else cs.onSurfaceVariant,
                                    modifier = Modifier.clip(RoundedCornerShape(50))
                                        .background(if (hot) ReconTheme.severity.exposedContainer else cs.surfaceContainerHighest)
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                    distanceLabel(finding)?.let { Text("$it from you", style = MaterialTheme.typography.titleMedium, color = cs.primary) }
                }
            }
        }
        item {
            DetailText(finding.detail, Modifier.padding(horizontal = 8.dp))
        }
        if (finding.pivots.isNotEmpty()) item { PivotsCard(finding.pivots, actions.onPivot) }
        if (finding.links.isNotEmpty()) item { LinksCard(finding.links, actions.onOpenUrl) }
        if (finding.facts.isNotEmpty()) item { FactsCard(finding.facts) }
        finding.geo?.let { g ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TileMap(
                        pins = listOf(MapPin(finding.id, g, finding.severity, finding.title)), me = LocalUserLocation.current,
                        selectedId = finding.id, onPinClick = {}, modifier = Modifier.fillMaxWidth().height(220.dp),
                    )
                    OutlinedButton(onClick = { actions.onOpenMap(g) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Map, null, Modifier.size(18.dp)); Text("Open in Maps", Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
        finding.url?.let { url ->
            item {
                OutlinedButton(onClick = { actions.onOpenUrl(url) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
                    Text(url.removePrefix("https://").take(42), Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.onCopy(finding) }, Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Text("Copy", Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = { actions.onShare(finding) }, Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Text("Share", Modifier.padding(start = 8.dp))
                }
                // Registry records can't be exported: a saved file of registrants is a compiled list (Idaho Code §74-120).
                if (Export.exportable(finding)) FilledTonalButton(onClick = { actions.onExport(finding) }, Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)); Text("Export JSON", Modifier.padding(start = 8.dp))
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("RAW", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(cs.surfaceContainerLow).padding(16.dp)) {
                    SelectionContainer {
                        Text(
                            finding.raw.ifBlank { "—" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyDetailPane(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(32.dp)) {
            RiskRing(0, size = 132.dp, stroke = 12.dp, caption = "IDLE")
            Text("Select a result", style = MaterialTheme.typography.headlineSmall, color = cs.onSurface)
            Text("Details, raw data and export options appear here.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun FactsCard(facts: List<vip.ringling.recon.domain.Fact>) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("DETAILS", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainer) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                facts.forEachIndexed { i, f ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(f.label, Modifier.weight(0.42f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                        Text(f.value, Modifier.weight(0.58f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                    }
                }
            }
        }
    }
}

/** Renders a multi-line detail: short "Heading:" lines, "• " bullets, and ordinary paragraphs. */
@Composable
private fun DetailText(detail: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.lines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
            when {
                line.endsWith(":") && line.length <= 24 -> Text(line.removeSuffix(":").uppercase(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                line.startsWith("• ") -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyLarge, color = cs.primary)
                    Text(line.removePrefix("• "), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
                }
                i == 0 -> Text(line, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                else -> Text(line, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun PivotsCard(pivots: List<vip.ringling.recon.domain.Pivot>, onPivot: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SEARCH NEXT", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            pivots.forEach { p ->
                androidx.compose.material3.AssistChip(
                    onClick = { onPivot(p.query) }, label = { Text(p.label) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
}

@Composable
private fun LinksCard(links: List<vip.ringling.recon.domain.Link>, onOpen: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("OPEN IN BROWSER", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainer) {
            Column {
                links.forEachIndexed { i, l ->
                    if (i > 0) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.4f))
                    Row(
                        Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${l.label}") { onOpen(l.url) }.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(l.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
