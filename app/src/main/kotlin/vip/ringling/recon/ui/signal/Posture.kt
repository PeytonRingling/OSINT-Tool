package vip.ringling.recon.ui.signal

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/** True while a foldable is half-open with a horizontal hinge (tabletop): content above the hinge, controls below. */
val LocalTabletop = compositionLocalOf { false }

@Composable
fun rememberTabletop(): Boolean {
    val activity = androidx.activity.compose.LocalActivity.current as? ComponentActivity ?: return false
    val state by produceState(false, activity) {
        runCatching {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
                .map { info -> info.displayFeatures.filterIsInstance<FoldingFeature>().any { it.state == FoldingFeature.State.HALF_OPENED && it.orientation == FoldingFeature.Orientation.HORIZONTAL } }
                .collect { value = it }
        }
    }
    return state
}
