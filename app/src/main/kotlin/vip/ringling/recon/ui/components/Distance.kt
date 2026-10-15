package vip.ringling.recon.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.Geo
import vip.ringling.recon.domain.GeoPoint

/** The user's position when they've allowed it; used only to show distances and the "you" dot. */
val LocalUserLocation = compositionLocalOf<GeoPoint?> { null }

fun distanceMeters(me: GeoPoint?, f: Finding): Double? = if (me != null && f.geo != null && vip.ringling.recon.domain.APPROXIMATE_TAG !in f.tags) Geo.distanceMeters(me, f.geo) else null

@Composable
fun distanceLabel(f: Finding): String? = distanceMeters(LocalUserLocation.current, f)?.let { Geo.formatDistance(it) }
