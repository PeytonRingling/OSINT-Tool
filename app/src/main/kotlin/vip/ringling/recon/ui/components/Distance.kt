package vip.ringling.recon.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.Geo
import vip.ringling.recon.domain.GeoPoint

/** The user's position when they've allowed it; used only to show distances and the "you" dot. */
val LocalUserLocation = compositionLocalOf<GeoPoint?> { null }

/** Distance to show for a record: from the searched address when this was an address search, else from the user's own position (when allowed). */
fun distanceMeters(me: GeoPoint?, f: Finding): Double? = when {
    vip.ringling.recon.domain.APPROXIMATE_TAG in f.tags -> null
    f.fromSearchMeters != null -> f.fromSearchMeters
    me != null && f.geo != null -> Geo.distanceMeters(me, f.geo)
    else -> null
}

fun distanceSuffix(f: Finding) = if (f.fromSearchMeters != null) "from the address" else "from you"

@Composable
fun distanceLabel(f: Finding): String? = distanceMeters(LocalUserLocation.current, f)?.let { Geo.formatDistance(it) }
