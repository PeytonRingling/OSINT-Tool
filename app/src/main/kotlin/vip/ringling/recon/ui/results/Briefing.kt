package vip.ringling.recon.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.Briefing
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.theme.ReconTheme

/** Plain-English summary card at the top of the exposure screen. */
@Composable
fun BriefingCard(b: Briefing, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    HairPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SignalLabel("Briefing")
            Text(b.headline, color = sig.text, style = MaterialTheme.typography.titleSmall)
            b.points.forEach { Text("· $it", color = sig.muted, style = MaterialTheme.typography.bodySmall) }
            b.firstStep?.let { Text("Start with: $it", color = sig.signal, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

/** "Since your last scan": risk change plus what is new, so a re-scan answers "what's different?" without opening the Log. */
@Composable
fun SinceLastCard(s: vip.ringling.recon.domain.SinceLast, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    val date = java.time.Instant.ofEpochMilli(s.previousTs).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { "${it.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.dayOfMonth}" }
    val col = when { s.delta > 0 -> sig.alertText; s.delta < 0 -> sig.safeText; else -> sig.muted }
    HairPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SignalLabel("Since last scan")
            Text(vip.ringling.recon.domain.SinceLastBuilder.headline(s, date), color = col, style = MaterialTheme.typography.titleSmall)
            s.newTitles.take(4).forEach { Text("+ $it", color = sig.text, style = MaterialTheme.typography.bodySmall) }
            if (s.newTitles.size > 4) Text("+ ${s.newTitles.size - 4} more", color = sig.muted, style = MaterialTheme.typography.bodySmall)
            if (!s.changed) Text("Nothing new or gone since then.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
            if (s.incomplete) Text("A source failed this time, so removed findings aren't counted.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
