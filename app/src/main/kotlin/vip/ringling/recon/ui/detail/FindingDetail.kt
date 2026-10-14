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
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.Severity
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
                            Text(finding.title, style = MaterialTheme.typography.headlineLarge, color = cs.onSurface)
                            SeverityChip(finding.severity)
                        }
                        RiskRing(finding.weight(), size = 104.dp, stroke = 11.dp, caption = "WEIGHT")
                    }
                    finding.confidence?.let { ConfidenceMeter(it) }
                }
            }
        }
        item {
            Text(finding.detail, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface, modifier = Modifier.padding(horizontal = 8.dp))
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
                FilledTonalButton(onClick = { actions.onExport(finding) }, Modifier.heightIn(min = 48.dp)) {
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
