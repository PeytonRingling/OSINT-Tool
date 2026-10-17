package vip.ringling.recon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import vip.ringling.recon.ui.theme.ReconTheme

/** Shown once on a fresh install: what the optional keys unlock, and that everything stays on the phone. */
@Composable
fun WelcomeDialog(onSetupKeys: () -> Unit, onSkip: () -> Unit) {
    val sig = ReconTheme.signal
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Welcome to Recon") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Searches run on this phone, with no account and no server. Most sources need no key. These make it much stronger:", color = sig.muted)
                Text("Have I Been Pwned: breach and paste checks for emails (needed for those).", style = MaterialTheme.typography.bodyMedium)
                Text("LeakCheck: more breach detail. A free limited lookup works without a key.", style = MaterialTheme.typography.bodyMedium)
                Text("Twilio or Veriphone: phone line type and, with Twilio, the registered name (paid, only after you confirm).", style = MaterialTheme.typography.bodyMedium)
                Text("GitHub token: in-app updates while the repo is private.", style = MaterialTheme.typography.bodyMedium)
                Text("Keys are stored encrypted with the Android Keystore and never leave the device. You can add them any time in System.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onSetupKeys) { Text("Add keys") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Not now") } },
    )
}
