package vip.ringling.recon.ui.radar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import vip.ringling.recon.ui.theme.ReconTheme

/** "Has this password been in a breach?" Hashing happens on the device; only a 5-character hash prefix is sent. Nothing is kept. */
@Composable
fun PasswordCheckDialog(check: suspend (String) -> Long, onDismiss: () -> Unit) {
    val sig = ReconTheme.signal
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { pw = ""; onDismiss() },
        title = { Text("Password check") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    pw, { pw = it; result = null }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                result?.let { Text(it, color = if (bad) sig.alertText else sig.safeText) }
                Text("Only the first 5 characters of the password's SHA-1 hash are sent. The password never leaves this phone and is not saved.", color = sig.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = pw.isNotEmpty() && !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { check(pw) }.onSuccess { n ->
                        bad = n > 0
                        result = if (n > 0) "Seen ${"%,d".format(n)} times in breaches. Don't use it anywhere." else "Not found in known breaches. That doesn't prove it's strong."
                    }.onFailure { bad = true; result = "Couldn't check (${it.message ?: "network error"})." }
                    busy = false
                }
            }) { Text(if (busy) "Checking…" else "Check") }
        },
        dismissButton = { TextButton(onClick = { pw = ""; onDismiss() }) { Text("Close") } },
    )
}
