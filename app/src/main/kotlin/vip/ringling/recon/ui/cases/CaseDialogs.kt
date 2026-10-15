package vip.ringling.recon.ui.cases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.InputDetector
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.SignalTextField
import vip.ringling.recon.ui.theme.ReconTheme

@Composable
fun NewCaseDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit, initial: String = "") {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("New case") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Name the dossier. Add targets afterwards, or pin a scan to it."); SignalTextField(name, { name = it }, "e.g. My footprint", description = "Case name", onGo = { if (name.isNotBlank()) onCreate(name) }) } },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddTargetDialog(onDismiss: () -> Unit, onAdd: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    val type = InputDetector.detect(text)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Add target") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SignalTextField(text, { text = it }, "email · phone · @user · domain · zip", description = "Target", onGo = { if (onAdd(text)) onDismiss() })
                SignalLabel(if (type == null) "Auto-detect on" else "Auto › ${type.label}", color = ReconTheme.signal.signal)
            }
        },
        confirmButton = { TextButton(onClick = { if (onAdd(text)) onDismiss() }, enabled = type != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RenameCaseDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Rename case") }, text = { SignalTextField(name, { name = it }, "Case name", onGo = { onRename(name) }) },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AddWatchDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var email by remember { mutableStateOf("") }
    val valid = email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Watch an email") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Checked once a day against your breach sources. You're notified only about NEW breaches. Needs a HIBP or LeakCheck key."); SignalTextField(email, { email = it }, "name@example.com", description = "Email to watch", keyboard = androidx.compose.ui.text.input.KeyboardType.Email, onGo = { if (valid) onAdd(email) }) } },
        confirmButton = { TextButton(onClick = { onAdd(email) }, enabled = valid) { Text("Watch") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Choose a case to pin a scan's target to (or start a new one). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinToCaseSheet(target: String, type: InputType, cases: List<CaseView>, onPick: (CaseView) -> Unit, onNew: () -> Unit, onDismiss: () -> Unit) {
    val sig = ReconTheme.signal
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = sig.panel, shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SignalLabel("Pin to case")
            Text(target, color = sig.text, style = vip.ringling.recon.ui.theme.ReconType.value)
            cases.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Pin to ${c.file.name}") { onPick(c) }.background(sig.signal.copy(alpha = 0.05f)).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SignalLabel(caseLabel(c.file.number), color = sig.signal, small = true)
                    Text(c.file.name, color = sig.text, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (c.file.targets.any { it.type == type && it.value == target }) SignalLabel("Pinned", small = true)
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Create a new case") { onNew() }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) { Icon(Icons.Outlined.AddCircleOutline, null, tint = sig.signal, modifier = Modifier.size(20.dp)); SignalLabel("New case", color = sig.signal) }
        }
    }
}

/** Asks for POST_NOTIFICATIONS (Android 13+) once, so watchlist alerts can actually be shown. Returns the function to call when a watch is added. */
@androidx.compose.runtime.Composable
fun rememberNotificationRequest(): () -> Unit {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
    return {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
}
