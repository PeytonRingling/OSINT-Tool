package vip.ringling.recon.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.RiskScore
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.ui.components.LegalNotice
import vip.ringling.recon.ui.components.ModuleCard
import vip.ringling.recon.ui.components.RiskRing
import vip.ringling.recon.ui.components.icon

/** Shared by Home (live scan) and History (saved scan): summary header + one card per module. */
fun LazyListScope.scanResults(
    input: ScanInput,
    findings: List<Finding>,
    statuses: Map<ModuleId, ModuleStatus>,
    errors: Map<ModuleId, String>,
    selectedId: String?,
    registryAck: Boolean,
    onAckRegistry: () -> Unit,
    onFindingClick: (Finding) -> Unit,
    onExportAll: () -> Unit,
) {
    item(key = "summary") {
        val total = statuses.size
        val done = statuses.values.count { it == ModuleStatus.DONE || it == ModuleStatus.FAILED }
        val running = done < total
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                RiskRing(RiskScore.compute(findings), size = 132.dp, stroke = 13.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(input.type.icon(), null, Modifier.padding(end = 2.dp), tint = cs.primary)
                        Text(input.type.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    Text(input.raw, style = MaterialTheme.typography.headlineSmall, color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$done of $total modules · ${findings.size} findings · ${findings.count { it.severity == Severity.EXPOSED }} exposed",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onExportAll) { Icon(Icons.Rounded.FileDownload, "Export all as JSON") }
            }
            if (running) {
                LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, Modifier.fillMaxWidth(), color = cs.primary, trackColor = cs.surfaceContainerHighest)
            }
        }
    }
    val order = statuses.keys.sortedBy { it.ordinal }
    order.forEach { module ->
        item(key = "module-$module") {
            val mine = findings.filter { it.module == module }
            val gated = module == ModuleId.REGISTRY && !registryAck
            ModuleCard(
                module = module,
                status = statuses[module] ?: ModuleStatus.QUEUED,
                findings = mine,
                error = errors[module],
                selectedId = selectedId,
                onFindingClick = onFindingClick,
                modifier = Modifier.padding(horizontal = 16.dp),
                gate = if (gated) ({ LegalNotice(onAckRegistry) }) else null,
            )
        }
    }
}
