package vip.ringling.recon.ui.log

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit
import vip.ringling.recon.domain.LogMath
import vip.ringling.recon.domain.LogTarget
import vip.ringling.recon.domain.ScanSnapshot
import vip.ringling.recon.ui.components.icon
import vip.ringling.recon.ui.signal.PageHeader
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.Sparkline
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.signal.textColor
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

data class LogActions(
    val onMode: (LogMode) -> Unit = {},
    val onOpenTarget: (LogTarget) -> Unit = {},
    val onOpenScan: (ScanSnapshot) -> Unit = {},
    val onDelete: (LogTarget) -> Unit = {},
    val onClearAll: () -> Unit = {},
    val onRescanAll: () -> Unit = {},
    val onUndoClear: () -> Unit = {},
)

fun ago(ts: Long, now: Long): String {
    val m = TimeUnit.MILLISECONDS.toMinutes(now - ts); val h = TimeUnit.MILLISECONDS.toHours(now - ts); val d = TimeUnit.MILLISECONDS.toDays(now - ts)
    return when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; h < 24 -> "${h}h ago"; else -> "${d}d ago" }
}

private fun toneOf(risk: Int) = when (LogMath.riskTone(risk)) { 2 -> Tone.ALERT; 1 -> Tone.INFO; else -> Tone.SAFE }

@Composable
fun LogScreen(state: LogUiState, actions: LogActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val sig = ReconTheme.signal
    var confirmClear by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<LogTarget?>(null) }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("Clear all scans?") },
        text = { Text("This deletes ${state.scanCount} saved scans from this device. It can't be undone.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; actions.onClearAll() }) { Text("Clear all scans") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
    pendingDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null }, title = { Text("Forget ${t.value}?") },
            text = { Text("Deletes its ${t.scans.size} saved scan${if (t.scans.size == 1) "" else "s"} from this device.") },
            confirmButton = { TextButton(onClick = { pendingDelete = null; actions.onDelete(t) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf(sig.signal.copy(alpha = 0.06f), Color.Transparent), radius = 700f))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 24.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                PageHeader("Activity", "Log") {
                    Text("${state.scanCount} SCANS\n${state.targets.size} TARGETS", color = sig.muted, textAlign = TextAlign.End, style = ReconType.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.08.sp))
                }
            }
            item { SegmentedControl(LogMode.entries.map { it.label }, state.mode.ordinal, { actions.onMode(LogMode.entries[it]) }, Modifier.padding(top = 4.dp, bottom = 4.dp)) }
            if (state.undoCount > 0) item {
                HairPanel(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Cleared ${state.undoCount} scans.", Modifier.weight(1f), color = sig.text, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                        SignalButton("Undo", actions.onUndoClear, filled = true, mono = true)
                    }
                }
            }
            if (state.targets.any { it.type != vip.ringling.recon.domain.InputType.ZIP }) item {
                SignalButton("Re-scan all targets", actions.onRescanAll, Modifier.fillMaxWidth(), mono = true)
            }
            if (state.scanCount == 0 && state.undoCount == 0) item {
                Text("Nothing logged yet. Scans are saved on this device only.", color = sig.muted, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 24.dp, horizontal = 4.dp))
            }
            when (state.mode) {
                LogMode.BY_TARGET -> items(state.targets, key = { it.key }) { t -> TargetRow(t, state.now, { actions.onOpenTarget(t) }, { pendingDelete = t }) }
                LogMode.TIMELINE -> {
                    var lastDay = ""
                    state.scans.forEach { s ->
                        val day = dayBucket(s.ts, state.now)
                        if (day != lastDay) { lastDay = day; item(key = "d-$day") { SignalLabel(day, Modifier.padding(top = 10.dp, start = 2.dp)) } }
                        item(key = "s-${s.id}") { ScanRow(s, state.now) { actions.onOpenScan(s) } }
                    }
                }
            }
            if (state.scanCount > 0) item {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { confirmClear = true }, Modifier.heightIn(min = 48.dp)) { SignalLabel("Clear all scans") }
                }
            }
        }
    }
}

private fun dayBucket(ts: Long, now: Long): String {
    val d = TimeUnit.MILLISECONDS.toDays(now - ts)
    return when { d < 1 -> "Today"; d < 2 -> "Yesterday"; d < 8 -> "This week"; else -> "Earlier" }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TargetRow(t: LogTarget, now: Long, onOpen: () -> Unit, onDelete: () -> Unit) {
    val sig = ReconTheme.signal
    val tone = if (t.isArea) Tone.INFO else toneOf(t.latest.risk)
    val desc = "${t.value}, ${t.type.label}, ${t.scans.size} scan${if (t.scans.size == 1) "" else "s"}, " + if (t.isArea) (t.latest.records?.let { "$it records" } ?: "area scan") else "risk ${t.latest.risk}"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(RoundedCornerShape(10.dp)).background(sig.panel.copy(alpha = 0.78f)).border(1.dp, sig.hairline, RoundedCornerShape(10.dp))
            .combinedClickable(onClickLabel = "Open latest results", onLongClickLabel = "Delete this target's scans", onClick = onOpen, onLongClick = onDelete)
            .semantics { contentDescription = desc; customActions = listOf(CustomAccessibilityAction("Delete this target's scans") { onDelete(); true }) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) { Icon(t.type.icon(), null, tint = sig.muted, modifier = Modifier.size(20.dp)) }
        Column(Modifier.weight(1f)) {
            Text(t.value, color = sig.text, style = androidx.compose.material3.MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.type.label} · ${t.scans.size} scan${if (t.scans.size == 1) "" else "s"} · ${ago(t.latest.ts, now)}", color = sig.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        if (t.isArea) {
            if (t.latest.records == 0) StatusTag("Clean", Tone.SAFE) else StatusTag(t.latest.records?.let { "$it records" } ?: "Area scan", Tone.INFO)
        } else if (t.scans.size > 1) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Sparkline(t.risks, tone, Modifier.size(width = 72.dp, height = 28.dp))
                Text(LogMath.deltaLabel(t), color = tone.textColor(), style = ReconType.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.06.sp))
            }
        } else {
            StatusTag(if (t.latest.risk == 0) "Clean · 0" else (if (tone == Tone.ALERT) "Risk" else "Info") + " · ${t.latest.risk}", tone)
        }
    }
}

@Composable
private fun ScanRow(s: ScanSnapshot, now: Long, onOpen: () -> Unit) {
    val sig = ReconTheme.signal
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp)).combinedClickableOpen(onOpen)
            .semantics { contentDescription = "${s.value}, ${s.type.label}, ${ago(s.ts, now)}" }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(s.type.icon(), null, tint = sig.muted, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(s.value, color = sig.text, style = ReconType.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(ago(s.ts, now), color = sig.muted, style = ReconType.labelSmall)
        }
        if (s.isArea) StatusTag(s.records?.let { "$it records" } ?: "Area", Tone.INFO) else StatusTag("Risk ${s.risk}", toneOf(s.risk))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableOpen(onOpen: () -> Unit): Modifier = this.combinedClickable(onClickLabel = "Open results", onClick = onOpen)
