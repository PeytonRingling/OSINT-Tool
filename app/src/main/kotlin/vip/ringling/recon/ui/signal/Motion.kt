package vip.ringling.recon.ui.signal

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** False when the user turned animations off in system settings ("remove animations"); decorative loops must then stay still. */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val ctx = LocalContext.current
    return remember { runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }.getOrDefault(true) }
}
