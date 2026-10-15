package vip.ringling.recon.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.PaidAction

/** Cost-bearing lookups are never automatic: this card shows the price, and the button opens an explicit confirmation. */
@Composable
fun PaidActionCard(action: PaidAction, onConfirmed: () -> Unit, modifier: Modifier = Modifier, initiallyConfirming: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    var confirming by rememberSaveable { mutableStateOf(initiallyConfirming) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Run a paid lookup?") },
            text = { PaidConfirmBody(action) },
            confirmButton = { TextButton(onClick = { confirming = false; onConfirmed() }) { Text("Confirm and pay") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cs.surfaceContainer, border = BorderStroke(1.dp, cs.tertiary.copy(alpha = 0.5f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("PAID LOOKUP", style = MaterialTheme.typography.labelMedium, color = cs.tertiary)
            Text("Costs ${action.cost}", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text("Nothing is sent and nothing is charged until you confirm.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Button(onClick = { confirming = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("${action.label}…") }
        }
    }
}

/** The body of the confirmation dialog (also rendered on its own for screenshot tests). */
@Composable
fun PaidConfirmBody(action: PaidAction, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cost: ${action.cost}.", style = MaterialTheme.typography.titleMedium)
        Text(action.explanation, style = MaterialTheme.typography.bodyMedium)
        Text("Prices are the provider's published list prices and may differ on your account.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
