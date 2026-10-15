package vip.ringling.recon.ui.radar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.ui.theme.ReconTheme

/** Loadout bottom sheet opened from the radar's "Loadout n/11" button (and the rail). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoadoutSheet(enabled: Set<ModuleId>, autoPick: Boolean, onToggle: (ModuleId, Boolean) -> Unit, onAuto: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val sig = ReconTheme.signal
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = androidx.compose.ui.graphics.Color(0xFF070A08).let { if (sig.ground.red > 0.5f) sig.panel else it },
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        dragHandle = { Box(androidx.compose.ui.Modifier.size(width = 36.dp, height = 4.dp).background(sig.ledOff, RoundedCornerShape(2.dp))) },
    ) {
        LoadoutContent(enabled, autoPick, onToggle, onAuto, onDismiss, Modifier.navigationBarsPadding())
    }
}
