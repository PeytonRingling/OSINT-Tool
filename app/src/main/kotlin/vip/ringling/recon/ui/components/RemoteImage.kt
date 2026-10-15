package vip.ringling.recon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
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

/**
 * Registry photos and map tiles are shown but never kept: memory cache only, no disk cache.
 * (The global ImageLoader is configured the same way in ReconApp; the per-request policy is belt and braces.)
 */
@Composable
fun RemoteImage(url: String?, description: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, placeholderIcon: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.background(cs.surfaceContainerHighest).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        if (placeholderIcon) Icon(Icons.Rounded.Person, null, tint = cs.onSurfaceVariant)
        if (url != null && LocalImagesEnabled.current) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(url).diskCachePolicy(CachePolicy.DISABLED).build(),
                contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
