package vip.ringling.recon.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
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
import vip.ringling.recon.domain.Geo
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.ui.components.LegalNotice
import vip.ringling.recon.ui.components.LocalUserLocation
import vip.ringling.recon.ui.components.distanceMeters
import vip.ringling.recon.ui.map.MapPin
import vip.ringling.recon.ui.map.TileMap
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
    onRequestLocation: () -> Unit = {},
) {
    item(key = "summary") {
        // Registry results stay hidden (including in counts and the risk score) until the legal notice is accepted.
        val findings = findings.filter { !(it.module == ModuleId.REGISTRY && !registryAck) }
        val total = statuses.size
        val done = statuses.values.count { it == ModuleStatus.DONE || it == ModuleStatus.FAILED }
        val running = done < total
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints {
                val narrow = maxWidth < 420.dp
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (narrow) 12.dp else 20.dp)) {
                    RiskRing(RiskScore.compute(findings), size = if (narrow) 96.dp else 132.dp, stroke = if (narrow) 10.dp else 13.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(input.type.icon(), null, Modifier.padding(end = 2.dp), tint = cs.primary)
                            Text(input.type.label.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                            IconButton(onClick = onExportAll) { Icon(Icons.Rounded.FileDownload, "Export all as JSON") }
                        }
                        Text(
                            input.raw,
                            style = if (narrow) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                            color = cs.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "$done of $total modules · ${findings.size} findings · ${findings.count { it.severity == Severity.EXPOSED }} exposed",
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                        )
                    }
                }
            }
            if (running) {
                LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, Modifier.fillMaxWidth(), color = cs.primary, trackColor = cs.surfaceContainerHighest)
            }
        }
    }
    val order = statuses.keys.sortedBy { it.ordinal }
    order.forEach { module ->
        item(key = "module-$module") {
            val me = LocalUserLocation.current
            val gated = module == ModuleId.REGISTRY && !registryAck
            // Registry results are listed nearest-first once the user has shared their position.
            val mine = findings.filter { it.module == module }.let { l ->
                if (module == ModuleId.REGISTRY && me != null) l.sortedBy { distanceMeters(me, it) ?: Double.MAX_VALUE } else l
            }
            ModuleCard(
                module = module,
                status = statuses[module] ?: ModuleStatus.QUEUED,
                findings = mine,
                error = errors[module],
                selectedId = selectedId,
                onFindingClick = onFindingClick,
                modifier = Modifier.padding(horizontal = 16.dp),
                gate = if (gated) ({ LegalNotice(onAckRegistry) }) else null,
                extra = if (module == ModuleId.REGISTRY && mine.any { it.geo != null }) ({ RegistryMapPanel(mine, me, selectedId, onFindingClick, onRequestLocation) }) else null,
            )
        }
    }
}

/** Map of every located registrant plus the user, and the prompt to share location for distances. */
@Composable
private fun RegistryMapPanel(
    findings: List<Finding>,
    me: GeoPoint?,
    selectedId: String?,
    onFindingClick: (Finding) -> Unit,
    onRequestLocation: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val located = findings.filter { it.geo != null }
    Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TileMap(
            pins = located.map { MapPin(it.id, it.geo!!, it.severity, it.title) },
            me = me, selectedId = selectedId,
            onPinClick = { pin -> located.firstOrNull { it.id == pin.id }?.let(onFindingClick) },
            modifier = Modifier.fillMaxWidth().height(240.dp),
        )
        if (me == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Show how far each address is from you. Your location stays on this device.",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                )
                FilledTonalButton(onClick = onRequestLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp)); Text("Use my location", Modifier.padding(start = 6.dp))
                }
            }
        } else {
            val nearest = located.mapNotNull { distanceMeters(me, it) }.minOrNull()
            if (nearest != null) Text("Nearest: ${Geo.formatDistance(nearest)} away", style = MaterialTheme.typography.labelLarge, color = cs.primary)
        }
    }
}
