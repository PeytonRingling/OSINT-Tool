package vip.ringling.recon.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.ui.components.RiskRing
import vip.ringling.recon.ui.components.icon

data class HistoryUiState(
    val records: List<ScanRecord> = emptyList(),
    val selectedId: String? = null,
    val now: Long = System.currentTimeMillis(),
)

private fun dayBucket(ts: Long, now: Long): String {
    val days = TimeUnit.MILLISECONDS.toDays(now - ts)
    return when {
        days < 1 -> "Today"
        days < 2 -> "Yesterday"
        days < 8 -> "This week"
        else -> "Earlier"
    }
}

private fun relative(ts: Long, now: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(now - ts)
    val h = TimeUnit.MILLISECONDS.toHours(now - ts)
    val d = TimeUnit.MILLISECONDS.toDays(now - ts)
    return when {
        m < 1 -> "just now"
        m < 60 -> "${m}m ago"
        h < 24 -> "${h}h ago"
        else -> "${d}d ago"
    }
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onOpen: (ScanRecord) -> Unit,
    onDelete: (ScanRecord) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val cs = MaterialTheme.colorScheme
    var confirmClear by remember { mutableStateOf(false) }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all history?") },
            text = { Text("This deletes ${state.records.size} saved scans from this device. It can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClearAll() }) { Text("Clear all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    val grouped = state.records.groupBy { dayBucket(it.timestampMillis, state.now) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("HISTORY", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Text("${state.records.size} scans", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
                }
                if (state.records.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }, Modifier.heightIn(min = 48.dp)) { Text("Clear all") }
                }
            }
        }
        if (state.records.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Rounded.History, null, tint = cs.onSurfaceVariant)
                    Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Text("Completed scans are saved on this device only.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
        }
        grouped.forEach { (bucket, rows) ->
            item(key = "h-$bucket") {
                Text(bucket.uppercase(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, top = 8.dp))
            }
            items(rows, key = { it.id }) { r ->
                HistoryRow(r, r.id == state.selectedId, state.now, { onOpen(r) }, { onDelete(r) })
            }
        }
    }
}

@Composable
private fun HistoryRow(r: ScanRecord, selected: Boolean, now: Long, onOpen: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val dismiss = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismiss.currentValue) {
        if (dismiss.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.padding(horizontal = 16.dp),
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(cs.errorContainer, MaterialTheme.shapes.large).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = cs.onErrorContainer) }
        },
    ) {
        Surface(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open scan", onClick = onOpen),
            shape = MaterialTheme.shapes.large,
            color = if (selected) cs.surfaceContainerHighest else cs.surfaceContainer,
            border = BorderStroke(1.dp, if (selected) cs.primary.copy(alpha = 0.6f) else cs.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Row(
                Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RiskRing(r.risk, size = 64.dp, stroke = 7.dp, caption = "")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(r.input.raw, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(r.input.type.icon(), null, Modifier.padding(0.dp), tint = cs.onSurfaceVariant)
                        Text(r.input.type.label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        Text("·", color = cs.onSurfaceVariant)
                        Text(relative(r.timestampMillis, now), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    val ex = r.findings.count { it.severity == Severity.EXPOSED }
                    val info = r.findings.count { it.severity == Severity.INFO }
                    Text("$ex exposed · $info info", style = MaterialTheme.typography.labelMedium, color = if (ex > 0) vip.ringling.recon.ui.theme.ReconTheme.severity.exposed else cs.onSurfaceVariant)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete scan of ${r.input.raw}", tint = cs.onSurfaceVariant) }
            }
        }
    }
}
