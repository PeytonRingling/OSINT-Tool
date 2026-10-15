package vip.ringling.recon.ui.radar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.RadarTarget
import vip.ringling.recon.ui.components.icon
import vip.ringling.recon.ui.signal.BracketPanel
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.Led
import vip.ringling.recon.ui.signal.ModuleTile
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.signal.Wordmark
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

data class RadarActions(
    val onQuery: (String) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onTarget: (RadarTarget) -> Unit = {},
    val onLoadout: () -> Unit = {},
    val onPhoto: () -> Unit = {},
    val onNewCase: () -> Unit = {},
    val onPassword: () -> Unit = {},
)

private val shortNames = mapOf(
    ModuleId.BREACH to "Breach", ModuleId.IDENTITY to "Identity", ModuleId.PHONE to "Phone intel", ModuleId.DOMAIN to "Domain",
    ModuleId.REGISTRY to "Registry", ModuleId.IMAGE to "Photo", ModuleId.NETWORK to "Network", ModuleId.CRYPTO to "Crypto",
    ModuleId.CODE to "Code", ModuleId.EMAILINTEL to "Email intel", ModuleId.DORKS to "Dorks",
)

fun typeLabel(t: InputType?): String = t?.label ?: "none"

@Composable
fun RadarScreen(state: RadarUiState, actions: RadarActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(), wide: Boolean = false) {
    val sig = ReconTheme.signal
    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf(sig.signal.copy(alpha = 0.08f), androidx.compose.ui.graphics.Color.Transparent), radius = 900f))) {
        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()).padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(Modifier.weight(1.1f)) {
                    RadarHeader(state, actions, Modifier.padding(bottom = 8.dp))
                    RadarCanvas(state.targets, actions.onTarget, Modifier.fillMaxWidth().weight(1f))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TargetDock(state, actions)
                    ActionButtons(actions)
                    SignalLabel("Recent targets", Modifier.padding(top = 6.dp, start = 2.dp))
                    state.targets.take(8).forEach { RecentRow(it, actions.onTarget) }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                RadarHeader(state, actions, Modifier.padding(horizontal = 20.dp))
                RadarCanvas(state.targets, actions.onTarget, Modifier.fillMaxWidth().aspectRatio(390f / 420f))
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TargetDock(state, actions)
                    ActionButtons(actions)
                }
            }
        }
    }
}

@Composable
private fun RadarHeader(state: RadarUiState, actions: RadarActions, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    Row(modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Wordmark()
        Row(
            Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, sig.line, RoundedCornerShape(10.dp))
                .clickable(onClickLabel = "Open loadout") { actions.onLoadout() }
                .semantics { contentDescription = "Open loadout, ${state.armed} of ${ModuleId.entries.size} modules armed" }.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SignalLabel("Loadout", color = sig.text)
            SignalLabel("${state.armed}/${ModuleId.entries.size}", color = sig.signal)
        }
    }
}

@Composable
fun TargetDock(state: RadarUiState, actions: RadarActions, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    BracketPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SignalLabel("Target")
                SignalLabel(
                    if (state.detected == null) "Auto-detect on" else "Auto › ${typeLabel(state.detected)} · ${state.modules.size} mod",
                    color = sig.signal,
                )
            }
            Row(
                Modifier.padding(top = 8.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(androidx.compose.ui.graphics.Color(0xFF0A0F0B).let { if (sig.ground.red > 0.5f) sig.panel else it })
                    .border(1.dp, sig.signal.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (state.query.isEmpty()) Text("email · phone · @user · domain · zip", color = sig.muted, style = ReconType.value.copy(fontSize = 14.sp), maxLines = 1)
                    BasicTextField(
                        value = state.query, onValueChange = actions.onQuery, singleLine = true,
                        textStyle = ReconType.value.copy(fontSize = 15.sp, color = sig.text), cursorBrush = SolidColor(sig.signal),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onGo = { actions.onSubmit() }),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Email, phone, username, domain or ZIP" },
                    )
                }
                if (state.query.isNotEmpty()) Box(Modifier.size(48.dp).clickable(onClickLabel = "Clear") { actions.onQuery("") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, "Clear", tint = sig.muted, modifier = Modifier.size(20.dp))
                }
                Box(
                    Modifier.size(width = 48.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(sig.signal.copy(alpha = if (state.detected != null) 1f else 0.4f))
                        .clickable(enabled = state.detected != null, onClickLabel = "Run scan") { actions.onSubmit() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Run scan", tint = sig.onSignal, modifier = Modifier.size(20.dp)) }
            }
            if (state.modules.isNotEmpty()) {
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.modules.forEach { StatusTag(shortNames[it] ?: it.label, Tone.SIGNAL) }
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(actions: RadarActions) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SignalButton("Photo meta", actions.onPhoto, Modifier.weight(1f), icon = Icons.Outlined.Image)
        SignalButton("New case", actions.onNewCase, Modifier.weight(1f), icon = Icons.Outlined.AddCircleOutline)
    }
    SignalButton("Password check", actions.onPassword, Modifier.fillMaxWidth(), icon = Icons.Outlined.Lock, mono = true)
}

@Composable
private fun RecentRow(t: RadarTarget, onTarget: (RadarTarget) -> Unit) {
    val sig = ReconTheme.signal
    HairPanel(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Open results") { onTarget(t) }.semantics { contentDescription = blipDescription(t) }.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(t.input.type.icon(), null, tint = sig.muted, modifier = Modifier.size(18.dp))
            Text(t.input.raw, Modifier.weight(1f), color = sig.text, style = ReconType.value, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            StatusTag(blipStatus(t), if (t.clean) Tone.SAFE else if (t.critical) Tone.ALERT else Tone.INFO)
        }
    }
}

private data class TileSpec(val module: ModuleId, val name: String, val detail: String)

private val tiles = listOf(
    TileSpec(ModuleId.BREACH, "Breach check", "HIBP · LeakCheck"),
    TileSpec(ModuleId.IDENTITY, "Identity", "Sites + email checks"),
    TileSpec(ModuleId.PHONE, "Phone intel", "Carrier · spam reports"),
    TileSpec(ModuleId.DOMAIN, "Domain recon", "DNS · mail auth · RDAP"),
    TileSpec(ModuleId.REGISTRY, "Offender registry", "Idaho · live ZIP lookup"),
    TileSpec(ModuleId.IMAGE, "Photo metadata", "On-device EXIF + hashes"),
    TileSpec(ModuleId.NETWORK, "Network intel", "RDAP · rDNS · Tor list"),
    TileSpec(ModuleId.CRYPTO, "Crypto wallet", "Public block explorers"),
    TileSpec(ModuleId.CODE, "Code footprint", "GitHub public API"),
    TileSpec(ModuleId.EMAILINTEL, "Email intel", "Offline checks + DNS"),
    TileSpec(ModuleId.DORKS, "Search dorks", "Ready-made operators · no network"),
)

/** Body of the Loadout bottom sheet: segmented Auto-pick / Manual, 2-column tile grid (last tile spans both), armed counter and Done. */
@Composable
fun LoadoutContent(enabled: Set<ModuleId>, autoPick: Boolean, onToggle: (ModuleId, Boolean) -> Unit, onAuto: (Boolean) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Column { SignalLabel("Sensors"); Text("Loadout", color = sig.text, style = ReconType.pageTitle.copy(fontSize = 38.sp, lineHeight = 38.sp)) }
            Text("${enabled.size}/${ModuleId.entries.size} armed", color = sig.signal, style = ReconType.value, modifier = Modifier.padding(bottom = 4.dp))
        }
        SegmentedControl(listOf("Auto-pick", "Manual"), if (autoPick) 0 else 1, { onAuto(it == 0) }, Modifier.padding(top = 14.dp, bottom = 12.dp))
        val rows = tiles.dropLast(1).chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { t -> ModuleTile(t.module.icon(), t.name, t.detail, t.module in enabled, { onToggle(t.module, t.module !in enabled) }, Modifier.weight(1f)) }
                    if (pair.size == 1) Box(Modifier.weight(1f))
                }
            }
            tiles.last().let { t -> ModuleTile(t.module.icon(), t.name, t.detail, t.module in enabled, { onToggle(t.module, t.module !in enabled) }, Modifier.fillMaxWidth(), wide = true) }
        }
        SignalButton("Done", onDone, Modifier.fillMaxWidth().padding(top = 14.dp).height(48.dp), filled = true)
    }
}
