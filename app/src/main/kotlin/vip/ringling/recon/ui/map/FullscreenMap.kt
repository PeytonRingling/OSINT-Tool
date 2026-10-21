package vip.ringling.recon.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.offenseLines
import vip.ringling.recon.domain.neutral
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.ui.components.RemoteImage
import vip.ringling.recon.ui.components.SeverityChip
import vip.ringling.recon.ui.components.distanceLabel

/** Edge-to-edge map of every located result. Tap a pin for a summary card; "Details" closes the map and opens the record. */
@Composable
fun FullscreenMap(
    findings: List<Finding>,
    me: GeoPoint?,
    initialSelectedId: String?,
    onDismiss: () -> Unit,
    onOpen: (Finding) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FullscreenMapContent(findings, me, initialSelectedId, onDismiss, onOpen)
    }
}

@Composable
fun FullscreenMapContent(
    findings: List<Finding>,
    me: GeoPoint?,
    initialSelectedId: String?,
    onDismiss: () -> Unit,
    onOpen: (Finding) -> Unit,
) {
    val located = findings.filter { it.geo != null }
    var selectedId by remember { mutableStateOf(vip.ringling.recon.domain.MapMemory.selectedId ?: initialSelectedId) }
    val selected = located.firstOrNull { it.id == selectedId }
    val ins = androidx.compose.foundation.layout.WindowInsets.safeDrawing.asPaddingValues()
    val ltr = androidx.compose.ui.unit.LayoutDirection.Ltr
    val controlsPadding = androidx.compose.foundation.layout.PaddingValues(
        start = ins.calculateLeftPadding(ltr), top = ins.calculateTopPadding(), end = ins.calculateRightPadding(ltr),
        bottom = ins.calculateBottomPadding() + if (selected != null) (140 + 40 * minOf(selected.offenseLines().size, 3)).dp else 0.dp,
    )
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TileMap(
            pins = located.map { MapPin(it.id, it.geo!!, it.severity, it.title, vip.ringling.recon.domain.APPROXIMATE_TAG in it.tags) },
            me = me, selectedId = selectedId, onPinClick = { selectedId = it.id; vip.ringling.recon.domain.MapMemory.selectedId = it.id },
            camera = vip.ringling.recon.domain.MapMemory.camera("full"),
            modifier = Modifier.fillMaxSize(), onClose = onDismiss, oneFingerPan = true, clipShape = androidx.compose.ui.graphics.RectangleShape, controlsPadding = controlsPadding,
        )
        Column(Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(12.dp).widthIn(max = 560.dp)) {
            if (selected != null) SelectedCard(selected, onOpen = { onOpen(selected) })
            else Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                Text("${located.size} located · tap a pin", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun SelectedCard(f: Finding, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHigh.copy(alpha = 0.97f), tonalElevation = 6.dp) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(f.imageUrl, "Photo of ${f.title}", Modifier.size(72.dp).clip(MaterialTheme.shapes.small))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(f.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(f.detail.lines().firstOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2)
                if (f.tags.isNotEmpty()) Text(f.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                val offenses = f.offenseLines()
                offenses.take(3).forEach { o -> Text("• $o", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if (offenses.size > 3) Text("+ ${offenses.size - 3} more in Details", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!f.neutral) SeverityChip(f.severity)
                    distanceLabel(f)?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = cs.primary) }
                }
            }
            Button(onClick = onOpen, Modifier.heightIn(min = 48.dp)) { Text("Details") }
        }
    }
}
