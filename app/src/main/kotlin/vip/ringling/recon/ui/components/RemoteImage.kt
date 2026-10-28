package vip.ringling.recon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest

/** Screenshot tests switch this off so renders never depend on the network. */
val LocalImagesEnabled = compositionLocalOf { true }

/** OPSEC mode: a remote photo (often hosted by the target) loads only when the user taps it. */
val LocalOpsecImages = compositionLocalOf { false }

/**
 * Registry photos and map tiles are shown but never kept: memory cache only, no disk cache.
 * (The global ImageLoader is configured the same way in ReconApp; the per-request policy is belt and braces.)
 */
@Composable
fun RemoteImage(url: String?, description: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, placeholderIcon: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.background(cs.surfaceContainerHighest).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (placeholderIcon) Icon(Icons.Rounded.Person, null, tint = cs.onSurfaceVariant)
        var tapped by androidx.compose.runtime.saveable.rememberSaveable(url) { androidx.compose.runtime.mutableStateOf(false) }
        val held = url != null && LocalImagesEnabled.current && LocalOpsecImages.current && !tapped
        if (held) {
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "Load photo") { tapped = true }.semantics { contentDescription = "Photo not loaded (OPSEC mode). Tap to load." }, contentAlignment = Alignment.BottomCenter) {
                Text("Tap to load", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
            }
        } else if (url != null && LocalImagesEnabled.current) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).diskCachePolicy(CachePolicy.DISABLED).build(),
                contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
