package vip.ringling.recon.ui.signal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

/** Top-level destinations. Enum names are kept from the old nav so routes and call sites stay stable. */
enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Radar", Icons.Outlined.Radar),
    CASES("cases", "Cases", Icons.Outlined.FolderOpen),
    HISTORY("history", "Log", Icons.Outlined.History),
    LAB("lab", "Lab", Icons.Outlined.Science),
    SETTINGS("settings", "System", Icons.Outlined.Tune),
}

/** Bottom bar for phones: four mono-labelled items; the active one gets a 2dp glowing signal bar on top. */
@Composable
fun ReconBottomBar(selected: Tab?, onTab: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    Row(
        modifier.fillMaxWidth().background(sig.ground).border(1.dp, sig.hairline).navigationBarsPadding().height(76.dp),
    ) {
        Tab.entries.forEach { tab ->
            val on = tab == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab, onClickLabel = tab.label) { onTab(tab) }
                    .semantics { this.selected = on; contentDescription = tab.label },
                contentAlignment = Alignment.Center,
            ) {
                if (on) Box(Modifier.align(Alignment.TopCenter).size(width = 30.dp, height = 2.dp).shadow(8.dp, ambientColor = sig.signal, spotColor = sig.signal).background(sig.signal))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(tab.icon, null, tint = if (on) sig.signal else sig.muted, modifier = Modifier.size(20.dp))
                    Text(tab.label.uppercase(), color = if (on) sig.signal else sig.muted, style = ReconType.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.12.sp))
                }
            }
        }
    }
}

/** 72dp navigation rail for widths >= 600dp, with the Loadout shortcut pinned to the bottom. */
@Composable
fun ReconRail(selected: Tab?, onTab: (Tab) -> Unit, armed: Int, total: Int, onLoadout: () -> Unit, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    Column(
        modifier.width(72.dp).fillMaxHeight().background(Color(0xFF070A08).let { if (sig.ground.red > 0.5f) sig.panel else it }).border(1.dp, sig.hairline.copy(alpha = 0.12f)).padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.padding(top = 10.dp, bottom = 22.dp).size(8.dp).shadow(8.dp, CircleShape, ambientColor = sig.signal, spotColor = sig.signal).clip(CircleShape).background(sig.signal))
        Tab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Tab, onClickLabel = tab.label) { onTab(tab) }
                    .background(if (on) sig.signal.copy(alpha = 0.07f) else Color.Transparent).semantics { this.selected = on; contentDescription = tab.label }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(tab.icon, null, tint = if (on) sig.signal else sig.muted, modifier = Modifier.size(20.dp))
                Text(tab.label.uppercase(), color = if (on) sig.signal else sig.muted, style = ReconType.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.1.sp))
            }
        }
        Box(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClickLabel = "Loadout") { onLoadout() }.semantics { contentDescription = "Loadout, $armed of $total armed" }.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(androidx.compose.material.icons.Icons.Outlined.Tune, null, tint = sig.muted, modifier = Modifier.size(20.dp))
            Text("$armed/$total", color = sig.signal, style = ReconType.labelSmall.copy(fontSize = 9.sp))
        }
    }
}
