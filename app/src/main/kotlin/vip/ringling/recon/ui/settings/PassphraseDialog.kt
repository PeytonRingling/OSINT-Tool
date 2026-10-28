package vip.ringling.recon.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import vip.ringling.recon.data.BackupCrypto

/** Asks for the backup passphrase: twice when creating a backup ([confirm]), once when opening one. */
@Composable
fun PassphraseDialog(title: String, message: String, confirm: Boolean, error: String? = null, onDismiss: () -> Unit, onDone: (CharArray) -> Unit) {
    var pass by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val tooShort = pass.length < BackupCrypto.MIN_PASSPHRASE
    val mismatch = confirm && again.isNotEmpty() && again != pass
    val ok = !tooShort && (!confirm || again == pass)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                val kb = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb)
                if (confirm) OutlinedTextField(again, { again = it }, label = { Text("Repeat passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = kb, isError = mismatch)
                val hint = when {
                    error != null -> error
                    mismatch -> "The two don't match."
                    confirm && tooShort -> "At least ${BackupCrypto.MIN_PASSPHRASE} characters. There is no way to recover a forgotten passphrase."
                    else -> null
                }
                hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(pass.toCharArray()) }, enabled = ok) { Text(if (confirm) "Encrypt and save" else "Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
