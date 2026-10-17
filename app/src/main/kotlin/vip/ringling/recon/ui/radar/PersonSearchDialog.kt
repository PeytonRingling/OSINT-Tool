package vip.ringling.recon.ui.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import vip.ringling.recon.ui.signal.SignalTextField
import vip.ringling.recon.ui.theme.ReconTheme

/** Public-records search by name: asks for a first and last name and states what this is and isn't before anything is sent. */
@Composable
fun PersonSearchDialog(onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    val sig = ReconTheme.signal
    var name by remember { mutableStateOf("") }
    val valid = name.trim().split(Regex("\\s+")).count { it.length >= 2 } >= 2
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Public records by name") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SignalTextField(name, { name = it }, "First Last", description = "Person's name", onGo = { if (valid) onSearch(name) })
                Text("Searches open public sources (Wikidata, ORCID, OpenAlex, court dockets, the NPI provider registry and FEC donations) and lists official portals and people-finder sites to continue on. Those sites open in your browser; Recon sends them nothing.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                Text("Results are matched by name only, so they can be other people. Use this lawfully: not to harass, stalk or intimidate anyone. Results are shown live and not saved.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSearch(name) }, enabled = valid) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
