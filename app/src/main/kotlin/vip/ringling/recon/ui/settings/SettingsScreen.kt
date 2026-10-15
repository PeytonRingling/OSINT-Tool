package vip.ringling.recon.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.data.net.ProxyMode
import vip.ringling.recon.data.settings.AppSettings
import vip.ringling.recon.data.settings.ThemeMode
import vip.ringling.recon.data.update.UpdateState
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.ui.signal.BracketPanel
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.Led
import vip.ringling.recon.ui.signal.LedSwitch
import vip.ringling.recon.ui.signal.PageHeader
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val keys: Map<ApiKeyId, String> = emptyMap(),
    val historyCount: Int = 0,
    val versionName: String = "0.1.0",
    val installedBuild: Int = 1,
    val update: UpdateState = UpdateState.Idle,
) {
    val keysSet: Int get() = ApiKeyId.entries.count { !keys[it].isNullOrBlank() }
}

data class SettingsActions(
    val onTheme: (ThemeMode) -> Unit = {},
    val onDynamic: (Boolean) -> Unit = {},
    val onHaptics: (Boolean) -> Unit = {},
    val onKeyChange: (ApiKeyId, String) -> Unit = { _, _ -> },
    val onClearHistory: () -> Unit = {},
    val onCheckUpdate: () -> Unit = {},
    val onInstallUpdate: () -> Unit = {},
    val onDismissUpdateError: () -> Unit = {},
    val onProxyMode: (ProxyMode) -> Unit = {},
    val onProxyHost: (String) -> Unit = {},
    val onProxyPort: (Int) -> Unit = {},
    val onAppLock: (Boolean) -> Unit = {},
    val onExportBackup: () -> Unit = {},
    val onImportBackup: () -> Unit = {},
)

/** "System": status panel, privacy & network, display, API keys (status list that expands to edit), updates, data and about. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    initiallyExpandedKey: ApiKeyId? = null,
) {
    val sig = ReconTheme.signal
    BoxWithConstraints(modifier.fillMaxSize().background(Brush.radialGradient(listOf(sig.signal.copy(alpha = 0.06f), Color.Transparent), radius = 700f))) {
        val wide = maxWidth >= 700.dp
        val pad = PaddingValues(top = contentPadding.calculateTopPadding() + 24.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad)) {
            PageHeader("Configuration", "System") {
                Text("v${state.versionName}\nBUILD ${state.installedBuild}", color = sig.muted, textAlign = TextAlign.End, style = ReconType.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.08.sp))
            }
            StatusPanel(state, Modifier.padding(top = 12.dp))
            if (wide) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f)) { PrivacySection(state, actions); DisplaySection(state, actions) }
                    Column(Modifier.weight(1f)) { KeysSection(state, actions, initiallyExpandedKey); UpdatesSection(state, actions); DataSection(state, actions); AboutSection(state) }
                }
            } else {
                PrivacySection(state, actions); DisplaySection(state, actions); KeysSection(state, actions, initiallyExpandedKey); UpdatesSection(state, actions); DataSection(state, actions); AboutSection(state)
            }
        }
    }
}

@Composable
private fun StatusPanel(state: SettingsUiState, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    BracketPanel(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Cell("Routing", when (state.settings.proxyMode) { ProxyMode.OFF -> "Direct"; ProxyMode.TOR -> "Tor"; ProxyMode.CUSTOM -> "Proxy" }, Modifier.weight(1f))
            Cell("App lock", if (state.settings.appLock) "On" else "Off", Modifier.weight(1f))
            Cell("Keys", "${state.keysSet}", Modifier.weight(1f), suffix = "/${ApiKeyId.entries.size}", valueColor = sig.signal)
        }
    }
}

@Composable
private fun Cell(label: String, value: String, modifier: Modifier, suffix: String = "", valueColor: Color = ReconTheme.signal.text) {
    Column(modifier.padding(horizontal = 14.dp, vertical = 12.dp).semantics { contentDescription = "$label: $value$suffix" }) {
        SignalLabel(label, small = true)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor, style = ReconType.pageTitle.copy(fontSize = 26.sp, lineHeight = 30.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal))
            if (suffix.isNotEmpty()) Text(suffix, color = ReconTheme.signal.muted, style = ReconType.value.copy(fontSize = 12.sp), modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun SysSection(title: String, content: @Composable () -> Unit) {
    SignalLabel(title, Modifier.padding(start = 2.dp, top = 16.dp, bottom = 8.dp))
    HairPanel(Modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
private fun Divider() = Row(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.07f))) {}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val sig = ReconTheme.signal
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = sig.text, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = sig.muted, style = MaterialTheme.typography.bodySmall)
        }
        LedSwitch(checked, onChange, title)
    }
}

@Composable
private fun PrivacySection(state: SettingsUiState, a: SettingsActions) {
    val sig = ReconTheme.signal
    val modes = ProxyMode.entries
    SysSection("Privacy & network") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Route lookups through a proxy", color = sig.text, style = MaterialTheme.typography.titleSmall)
            SegmentedControl(modes.map { it.label.substringBefore(" (").let { l -> if (l == "Custom SOCKS5") "Custom" else l } }, state.settings.proxyMode.ordinal, { a.onProxyMode(modes[it]) })
            when (state.settings.proxyMode) {
                ProxyMode.OFF -> Text("Requests go straight from this phone.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                ProxyMode.TOR -> Text("Uses Orbot's SOCKS proxy at 127.0.0.1:9050 (start Orbot first). If it isn't running, lookups fail rather than leaking. Map tiles and photos are not proxied.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                ProxyMode.CUSTOM -> {
                    ProxyField("Host", state.settings.proxyHost, KeyboardType.Uri) { a.onProxyHost(it) }
                    ProxyField("Port", state.settings.proxyPort.toString(), KeyboardType.Number) { it.toIntOrNull()?.let(a.onProxyPort) }
                    Text("SOCKS5 only. Invalid settings fail closed.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Divider()
        SwitchRow("App lock", "Fingerprint, face or PIN. Hides Recon from recents.", state.settings.appLock, a.onAppLock)
    }
}

@Composable
private fun ProxyField(label: String, value: String, type: KeyboardType, onChange: (String) -> Unit) {
    val sig = ReconTheme.signal
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(sig.panel, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SignalLabel(label, Modifier.width(44.dp), small = true)
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onChange, singleLine = true, textStyle = ReconType.value.copy(fontSize = 14.sp, color = sig.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(sig.signal),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).semantics { contentDescription = "Proxy $label" },
        )
    }
}

@Composable
private fun DisplaySection(state: SettingsUiState, a: SettingsActions) {
    SysSection("Display") {
        Column(Modifier.padding(14.dp)) {
            SegmentedControl(ThemeMode.entries.map { it.label }, state.settings.themeMode.ordinal, { a.onTheme(ThemeMode.entries[it]) })
        }
        Divider()
        SwitchRow("Dynamic color", "Material You. Dark stays true black.", state.settings.dynamicColor, a.onDynamic)
        Divider()
        SwitchRow("Haptics", "Vibrate when a scan completes.", state.settings.haptics, a.onHaptics)
    }
}

@Composable
private fun KeysSection(state: SettingsUiState, a: SettingsActions, initiallyExpanded: ApiKeyId?) {
    val sig = ReconTheme.signal
    var open by remember { mutableStateOf(initiallyExpanded) }
    SysSection("API keys") {
        ApiKeyId.entries.forEachIndexed { i, id ->
            if (i > 0) Divider()
            val set = !state.keys[id].isNullOrBlank()
            Column {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClickLabel = if (open == id) "Collapse" else "Edit ${id.label}") { open = if (open == id) null else id }
                        .semantics { contentDescription = "${id.label}, ${if (set) "saved" else "not set"}" }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Led(set, tone = Tone.SAFE)
                    Column(Modifier.weight(1f)) {
                        Text(id.label, color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(id.hint.substringBefore(".").substringBefore(":").take(60), color = sig.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    StatusTag(if (set) "Saved" else "Not set", if (set) Tone.SAFE else Tone.MUTED)
                }
                if (open == id) KeyEditor(id, state.keys[id].orEmpty()) { a.onKeyChange(id, it) }
            }
        }
        Divider()
        Text(
            "Keys are entered here at runtime. They are encrypted with a hardware-backed Android Keystore key, never logged, and never leave this device except in requests to the service they belong to.",
            color = sig.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp),
        )
    }
}

@Composable
private fun KeyEditor(id: ApiKeyId, value: String, onChange: (String) -> Unit) {
    val sig = ReconTheme.signal
    var reveal by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(id.hint, color = sig.muted, style = MaterialTheme.typography.bodySmall)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).background(sig.panel, androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty()) Text("Paste key", color = sig.muted, style = ReconType.value.copy(fontSize = 14.sp))
                androidx.compose.foundation.text.BasicTextField(
                    value = value, onValueChange = onChange, singleLine = true, textStyle = ReconType.value.copy(fontSize = 14.sp, color = sig.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(sig.signal),
                    visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation('•'),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${id.label} API key" },
                )
            }
            IconButton(onClick = { reveal = !reveal }, Modifier.size(48.dp)) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Hide key" else "Reveal key", tint = sig.muted) }
        }
    }
}

@Composable
private fun UpdatesSection(state: SettingsUiState, a: SettingsActions) {
    val sig = ReconTheme.signal
    val u = state.update
    val busy = u is UpdateState.Checking || u is UpdateState.Downloading || u is UpdateState.Installing
    SysSection("Updates") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Installed: v${state.versionName} · build ${state.installedBuild}", color = sig.text, style = MaterialTheme.typography.titleSmall)
            when (u) {
                UpdateState.Idle -> Text("Check whether a newer build has been published.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                UpdateState.Checking -> { Text("Checking GitHub…", color = sig.muted, style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth(), color = sig.signal, trackColor = sig.ledOff) }
                is UpdateState.UpToDate -> Text("You're on the latest build.", color = sig.safeText, style = MaterialTheme.typography.bodyMedium)
                is UpdateState.Available -> {
                    Text("Build ${u.info.build} is available · ${"%.1f".format(u.info.sizeBytes / 1_048_576.0)} MB" + (u.info.publishedAt?.let { " · ${it.take(10)}" } ?: ""), color = sig.signal, style = MaterialTheme.typography.bodyMedium)
                    SignalButton("Download and install", a.onInstallUpdate, Modifier.fillMaxWidth(), filled = true)
                }
                is UpdateState.Downloading -> {
                    Text("Downloading build ${u.info.build}… ${(u.progress * 100).toInt()}%", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { u.progress }, Modifier.fillMaxWidth(), color = sig.signal, trackColor = sig.ledOff)
                }
                is UpdateState.NeedsPermission -> {
                    Text("Android needs your OK before Recon can install updates. Allow it in the screen that opened, come back, and tap install again.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                    SignalButton("Install build ${u.info.build}", a.onInstallUpdate, Modifier.fillMaxWidth(), filled = true)
                }
                is UpdateState.Installing -> { Text("Installing build ${u.info.build}… Recon will restart.", color = sig.muted, style = MaterialTheme.typography.bodySmall); LinearProgressIndicator(Modifier.fillMaxWidth(), color = sig.signal, trackColor = sig.ledOff) }
                is UpdateState.Failed -> {
                    Text(u.message, color = sig.alertText, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = a.onDismissUpdateError, Modifier.heightIn(min = 48.dp)) { SignalLabel("Dismiss") }
                }
            }
            SignalButton("Check for updates", a.onCheckUpdate, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

@Composable
private fun DataSection(state: SettingsUiState, a: SettingsActions) {
    val sig = ReconTheme.signal
    var confirm by remember { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("Delete scan history?") },
            text = { Text("Removes ${state.historyCount} saved scans (and the Log built from them) from this device. Cases keep their targets.") },
            confirmButton = { TextButton(onClick = { confirm = false; a.onClearHistory() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
    SysSection("Data") {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Scan history", color = sig.text, style = MaterialTheme.typography.titleSmall)
                Text("${state.historyCount} saved on this device only", color = sig.muted, style = MaterialTheme.typography.bodySmall)
            }
            SignalButton("Clear", { confirm = true }, enabled = state.historyCount > 0, mono = true)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Backup", color = sig.text, style = MaterialTheme.typography.titleSmall)
                Text("Scans, cases and watchlist. Never keys or settings.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
            }
            SignalButton("Export", a.onExportBackup, mono = true)
            SignalButton("Import", a.onImportBackup, mono = true)
        }
    }
}

@Composable
private fun AboutSection(state: SettingsUiState) {
    val sig = ReconTheme.signal
    SysSection("About") {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Recon ${state.versionName}", color = sig.text, style = MaterialTheme.typography.titleSmall)
            Text(
                "Everything runs on this device: no backend, no telemetry. The offender registry module performs live lookups of your query only and never stores or compiles registry data " +
                    "(Idaho Code §74-120: no commercial use, no telephone or mailing lists). Registry photos and map tiles are shown but never saved, registry records can't be exported, " +
                    "and your location is used only on this device to show distances.",
                color = sig.muted, style = MaterialTheme.typography.bodySmall,
            )
            Text("Extra username sites derived from WhatsMyName © Micah Hoffman and contributors, CC BY-SA 4.0 (github.com/WebBreacher/WhatsMyName).", color = sig.muted, style = MaterialTheme.typography.bodySmall)
            Text("Fonts: Instrument Serif, Geist and Geist Mono (SIL Open Font License 1.1).", color = sig.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
