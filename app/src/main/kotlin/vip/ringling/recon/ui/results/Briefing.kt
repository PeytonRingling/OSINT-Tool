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
