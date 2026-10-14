package vip.ringling.recon.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import vip.ringling.recon.data.settings.AppSettings
import vip.ringling.recon.data.settings.ThemeMode
import vip.ringling.recon.ui.theme.ReconTheme

enum class ApiKeyId(val label: String, val hint: String) {
    HIBP("Have I Been Pwned", "Required for email breach lookups (hibp-api-key)."),
    LEAKCHECK("LeakCheck", "Optional. Without a key the limited public endpoint is used."),
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val keys: Map<ApiKeyId, String> = emptyMap(),
    val historyCount: Int = 0,
    val versionName: String = "0.1.0",
)

data class SettingsActions(
    val onTheme: (ThemeMode) -> Unit = {},
    val onDynamic: (Boolean) -> Unit = {},
    val onHaptics: (Boolean) -> Unit = {},
    val onKeyChange: (ApiKeyId, String) -> Unit = { _, _ -> },
    val onClearHistory: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val pad = PaddingValues(top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("SETTINGS", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                Text("Make it yours", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
            }
            if (wide) {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        AppearanceSection(state, actions); DataSection(state, actions)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        KeysSection(state, actions); LegalSection(state)
                    }
                }
            } else {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    AppearanceSection(state, actions); KeysSection(state, actions); DataSection(state, actions); LegalSection(state)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        Surface(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cs.surfaceContainer,
            border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
        ) { Column(Modifier.padding(vertical = 8.dp)) { content() } }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(state: SettingsUiState, a: SettingsActions) {
    Section("Appearance") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Theme", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = state.settings.themeMode == m, onClick = { a.onTheme(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(m.label) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ToggleRow("Dynamic color", "Use Material You colors from your wallpaper. Dark surfaces stay true black.", state.settings.dynamicColor, a.onDynamic)
        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        ToggleRow("Haptics", "Vibrate when a scan completes.", state.settings.haptics, a.onHaptics)
    }
}

@Composable
private fun KeysSection(state: SettingsUiState, a: SettingsActions) {
    Section("API keys") {
        ApiKeyId.entries.forEachIndexed { i, id ->
            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            KeyField(id, state.keys[id].orEmpty()) { a.onKeyChange(id, it) }
        }
        Text(
            "Keys are entered here at runtime — nothing ships in the app. They are never logged and are held in Keystore-backed encrypted storage once the modules are wired (Phase 2).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun KeyField(id: ApiKeyId, value: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(id.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(
                if (value.isBlank()) "Not set" else "Saved",
                style = MaterialTheme.typography.labelMedium,
                color = if (value.isBlank()) cs.onSurfaceVariant else ReconTheme.severity.clean,
            )
        }
        Text(id.hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        Surface(shape = MaterialTheme.shapes.small, color = cs.surfaceContainerHigh, border = BorderStroke(1.dp, cs.outlineVariant)) {
            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    if (value.isEmpty()) Text("Paste key", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    BasicTextField(
                        value = value, onValueChange = onChange, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation('•'),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${id.label} API key" },
                    )
                }
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide key" else "Reveal key")
                }
            }
        }
    }
}

@Composable
private fun DataSection(state: SettingsUiState, a: SettingsActions) {
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete scan history?") },
            text = { Text("Removes ${state.historyCount} saved scans from this device.") },
            confirmButton = { TextButton(onClick = { confirm = false; a.onClearHistory() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
    Section("Data") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scan history", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("${state.historyCount} saved on this device only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { confirm = true }, enabled = state.historyCount > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
        }
    }
}

@Composable
private fun LegalSection(state: SettingsUiState) {
    val cs = MaterialTheme.colorScheme
    Section("About") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recon ${state.versionName}", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
            Text(
                "Everything runs on this device — no backend, no telemetry. The offender registry module performs live, " +
                    "per-query lookups only and never stores or compiles registry data (Idaho Code §74-120).",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
    }
}
