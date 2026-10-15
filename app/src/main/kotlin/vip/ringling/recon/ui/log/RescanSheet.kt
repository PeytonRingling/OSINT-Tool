package vip.ringling.recon.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.ringling.recon.data.BatchState
import vip.ringling.recon.domain.ScanDiff
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

fun diffLabel(d: ScanDiff): String = when {
    d.failed -> "FAILED"
    d.before == null -> "NEW · ${d.after}"
    d.delta == 0 -> "NO CHANGE"
    d.delta!! > 0 -> "▲ ${d.delta} · ${d.after}"
    else -> "▼ ${-d.delta!!} · ${d.after}"
}

fun diffTone(d: ScanDiff) = when { d.failed -> Tone.MUTED; (d.delta ?: 0) > 0 -> Tone.ALERT; (d.delta ?: 0) < 0 -> Tone.SAFE; else -> Tone.INFO }

/** Progress while "Re-scan all" runs, then one row per target: score change and the titles of anything new. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RescanSheet(state: BatchState, onClose: () -> Unit, onCancel: () -> Unit) {
    val sig = ReconTheme.signal
    ModalBottomSheet(onDismissRequest = { if (state.running) onCancel() else onClose() }, containerColor = sig.panel) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SignalLabel(if (state.running) "Re-scanning ${state.done + 1} of ${state.total}" else "Re-scan complete")
            if (state.running) {
                LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, Modifier.fillMaxWidth(), color = sig.signal, trackColor = sig.ledOff)
                state.current?.let { Text(it, color = sig.muted, style = ReconType.value) }
            } else {
                val changed = state.results.count { it.changed }
                Text(if (changed == 0) "Nothing changed since the last scan." else "$changed of ${state.total} changed.", color = sig.text, style = MaterialTheme.typography.titleMedium)
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.results.sortedByDescending { it.delta ?: 0 }.forEach { d ->
                    HairPanel(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(d.input.raw, Modifier.weight(1f), color = sig.text, style = ReconType.value, maxLines = 1)
                                StatusTag(diffLabel(d), diffTone(d))
                            }
                            if (d.newTitles.isNotEmpty()) Text("New: " + d.newTitles.take(4).joinToString(", ") + if (d.newTitles.size > 4) " +${d.newTitles.size - 4} more" else "", color = sig.alertText, style = MaterialTheme.typography.bodySmall)
                            if (d.goneCount > 0) Text("${d.goneCount} no longer found", color = sig.safeText, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            SignalButton(if (state.running) "Stop" else "Done", if (state.running) onCancel else onClose, Modifier.fillMaxWidth(), filled = !state.running, mono = true)
        }
    }
}
