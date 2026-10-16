package vip.ringling.recon.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Satellite
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import vip.ringling.recon.domain.Geo
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.ui.components.LocalImagesEnabled
import vip.ringling.recon.ui.components.severityColor

data class MapPin(val id: String, val geo: GeoPoint, val severity: Severity, val label: String, val approximate: Boolean = false)

/** Street = OpenStreetMap; Satellite = Esri World Imagery. The choice is remembered in settings. */
class MapLayerState(val satellite: Boolean, val onChange: (Boolean) -> Unit)
val LocalMapLayer = compositionLocalOf { MapLayerState(false) {} }

private const val TILE_PX = 256.0

/**
 * A small slippy map built on OpenStreetMap raster tiles — no SDK, no API key, no disk cache.
 * Pan, pinch or use the buttons. Pins are tappable; [me] is drawn as a magenta dot with a halo when known.
 */
/** The user's own position marker; deliberately unlike every pin color (azure / coral / mint). */
val YOU_COLOR = Color(0xFFFF2BD6)

@Composable
fun TileMap(
    pins: List<MapPin>,
    me: GeoPoint?,
    selectedId: String?,
    onPinClick: (MapPin) -> Unit,
    modifier: Modifier = Modifier,
    onExpand: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    /** True for a full-screen map; false inside a scrolling page, where one finger should scroll the page. */
    oneFingerPan: Boolean = false,
    /** When given, the camera is restored from it and written back, so the view survives navigation. */
    camera: vip.ringling.recon.domain.MapCamera? = null,
    /** Scan-radius rings (metres) drawn around [ringCenter], e.g. the middle of a ZIP's records. */
    ringCenter: GeoPoint? = null,
    ringMeters: List<Double> = emptyList(),
    clipShape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    /** Keeps the buttons and attribution clear of system bars or a card laid over the map. */
    controlsPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    val cs = MaterialTheme.colorScheme
    val layer = LocalMapLayer.current
    val satellite = layer.satellite
    val dark = isSystemInDarkTheme() || cs.background.red < 0.2f
    BoxWithConstraints(modifier.clip(clipShape).background(cs.surfaceContainerHigh)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }.toDouble()
        val hPx = with(density) { maxHeight.toPx() }.toDouble()
        val fitPoints = pins.map { it.geo } + listOfNotNull(me)
        val key = fitPoints.sortedBy { it.lat * 1000 + it.lng }.joinToString { "%.5f,%.5f".format(it.lat, it.lng) }
        // Fit into a viewport inset on every side so pins never sit under the zoom buttons or the attribution.
        val insetX = with(density) { 72.dp.toPx() }.toDouble(); val insetY = with(density) { 44.dp.toPx() }.toDouble()
        val fitW = (wPx - 2 * insetX).coerceAtLeast(80.0); val fitH = (hPx - 2 * insetY).coerceAtLeast(80.0)
        val fallback = listOf(GeoPoint(43.6, -116.2))
        val first = remember { Geo.fit(fitPoints.ifEmpty { fallback }, fitW, fitH) }
        val restored = camera?.takeIf { it.saved }
        var zoom by remember { mutableDoubleStateOf(restored?.zoom ?: first.second) }
        var cx by remember { mutableDoubleStateOf(restored?.cx ?: Geo.worldX(first.first.lng, first.second)) }
        var cy by remember { mutableDoubleStateOf(restored?.cy ?: Geo.worldY(first.first.lat, first.second)) }
        // Once the user pans or zooms, new results streaming in must NOT yank the camera back.
        var userMoved by remember { mutableStateOf(restored?.userMoved ?: false) }
        androidx.compose.runtime.SideEffect { camera?.let { it.zoom = zoom; it.cx = cx; it.cy = cy; it.userMoved = userMoved } }

        fun fitAll() {
            val (c, z) = Geo.fit(fitPoints.ifEmpty { fallback }, fitW, fitH)
            zoom = z; cx = Geo.worldX(c.lng, z); cy = Geo.worldY(c.lat, z)
        }
        androidx.compose.runtime.LaunchedEffect(key, fitW, fitH) { if (!userMoved) fitAll() }

        /** Zoom keeping the point under ([fx],[fy]) — the fingers or the tapped spot — fixed on screen. */
        fun zoomAround(newZoom: Double, fx: Double = wPx / 2, fy: Double = hPx / 2) {
            val z = newZoom.coerceIn(3.0, 19.0)
            val f = 2.0.pow(z - zoom)
            cx = (cx + fx - wPx / 2) * f - (fx - wPx / 2)
            cy = (cy + fy - hPx / 2) * f - (fy - hPx / 2)
            zoom = z; userMoved = true
        }

        Box(
            Modifier.fillMaxSize().pointerInput(oneFingerPan) {
                // A map inside a scrolling page only claims two-finger gestures, so one finger still scrolls the page.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2 || oneFingerPan) {
                            val zc = event.calculateZoom(); val pan = event.calculatePan(); val c = event.calculateCentroid()
                            if (zc != 1f || pan != Offset.Zero) {
                                userMoved = true
                                cx -= pan.x; cy -= pan.y
                                if (zc != 1f && c.isSpecified) zoomAround(zoom + log2(zc.toDouble()), c.x.toDouble(), c.y.toDouble())
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }.pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { zoomAround(zoom + 1, it.x.toDouble(), it.y.toDouble()) })
            }.semantics { contentDescription = "Map showing ${pins.size} locations" },
        ) {
            val z0 = floor(zoom).toInt().coerceIn(1, 19)
            val scale = 2.0.pow(zoom - z0)
            val tilePx = TILE_PX * scale
            val n = Geo.tileCount(z0)
            val xs = Geo.tileRange(cx, wPx / 2, tilePx, n)
            val ys = Geo.tileRange(cy, hPx / 2, tilePx, n)
            val filter = if (dark && !satellite) ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                -0.8f, 0f, 0f, 0f, 215f,
                0f, -0.8f, 0f, 0f, 215f,
                0f, 0f, -0.8f, 0f, 225f,
                0f, 0f, 0f, 1f, 0f,
            ))) else null
            val context = LocalContext.current
            val imagesOn = LocalImagesEnabled.current
            for (tx in xs) for (ty in ys) {
                val left = (tx * tilePx - cx + wPx / 2).roundToInt()
                val top = (ty * tilePx - cy + hPx / 2).roundToInt()
                if (imagesOn) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(if (satellite) "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z0/$ty/$tx" else "https://tile.openstreetmap.org/$z0/$tx/$ty.png").diskCachePolicy(CachePolicy.DISABLED).build(),
                        contentDescription = null, contentScale = ContentScale.FillBounds, colorFilter = filter,
                        modifier = Modifier.offset(left, top).size(with(density) { (tilePx.roundToInt() + 1).toDp() }),
                    )
                }
            }
            // Faint grid so the map reads as a map while tiles load (and in offline screenshots).
            Canvas(Modifier.fillMaxSize()) {
                if (!imagesOn) {
                    val step = tilePx.toFloat() / 2
                    val ox = ((-cx + wPx / 2) % step).toFloat(); val oy = ((-cy + hPx / 2) % step).toFloat()
                    var x = ox; while (x < size.width) { drawLine(Color.White.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height)); x += step }
                    var y = oy; while (y < size.height) { drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y)); y += step }
                }
            }
            fun screenOf(p: GeoPoint) = Offset((Geo.worldX(p.lng, zoom) - cx + wPx / 2).toFloat(), (Geo.worldY(p.lat, zoom) - cy + hPx / 2).toFloat())
            if (ringCenter != null && ringMeters.isNotEmpty()) {
                val ringColor = vip.ringling.recon.ui.theme.ReconTheme.signal.signal
                Canvas(Modifier.fillMaxSize()) {
                    val o = screenOf(ringCenter)
                    val mpp = Geo.metersPerPixel(ringCenter.lat, zoom)
                    ringMeters.forEachIndexed { i, m ->
                        drawCircle(ringColor.copy(alpha = if (i == 0) 0.5f else 0.3f), (m / mpp).toFloat(), o, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f, pathEffect = if (i == 0) null else androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 10f))))
                    }
                    drawLine(ringColor, Offset(o.x, o.y - 12f), Offset(o.x, o.y + 12f), 1.5f); drawLine(ringColor, Offset(o.x - 12f, o.y), Offset(o.x + 12f, o.y), 1.5f)
                }
            }
            fun screen(p: GeoPoint) = Offset((Geo.worldX(p.lng, zoom) - cx + wPx / 2).toFloat(), (Geo.worldY(p.lat, zoom) - cy + hPx / 2).toFloat())
            pins.forEach { p ->
                val o = screen(p.geo)
                val selected = p.id == selectedId
                val size = if (selected) 34.dp else if (p.approximate) 16.dp else if (pins.size > 25) 18.dp else 26.dp
                val half = with(density) { size.roundToPx() / 2 }
                Box(
                    Modifier.offset(o.x.roundToInt() - half, o.y.roundToInt() - half).size(size).alpha(if (p.approximate && !selected) 0.55f else 1f)
                        .clip(CircleShape).background(Color.White).padding(if (selected) 4.dp else if (pins.size > 25) 2.dp else 3.dp)
                        .clip(CircleShape).background(severityColor(p.severity))
                        .clickable(onClickLabel = "Open ${p.label}") { onPinClick(p) }
                        .semantics { contentDescription = "Pin: ${p.label}" + if (p.approximate) " (approximate location)" else "" },
                )
            }
            me?.let { m ->
                // Drawn above the pins so it is never hidden by one. Magenta: no registrant pin is magenta (they are azure / coral / mint), so "you" can never be mistaken for a record.
                val o = screen(m)
                val half = with(density) { 22.dp.roundToPx() }
                Box(
                    Modifier.offset(o.x.roundToInt() - half, o.y.roundToInt() - half).size(44.dp).semantics { contentDescription = "Your location" },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(YOU_COLOR.copy(alpha = 0.22f)))
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White).padding(3.dp).clip(CircleShape).background(YOU_COLOR))
                }
            }
        }
        val btn = IconButtonDefaults.filledTonalIconButtonColors(containerColor = cs.surface.copy(alpha = 0.88f), contentColor = cs.onSurface)
        Row(Modifier.align(Alignment.TopEnd).padding(controlsPadding).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconButton(onClick = { layer.onChange(!satellite) }, colors = btn, modifier = Modifier.size(48.dp)) {
                Icon(if (satellite) Icons.Rounded.Map else Icons.Rounded.Satellite, if (satellite) "Switch to street map" else "Switch to satellite")
            }
            onExpand?.let { IconButton(onClick = it, colors = btn, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Fullscreen, "Expand map to full screen") } }
            onClose?.let { IconButton(onClick = it, colors = btn, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.FullscreenExit, "Close full screen map") } }
        }
        Column(Modifier.align(Alignment.CenterEnd).padding(end = controlsPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)).padding(6.dp)) {
            IconButton(onClick = { zoomAround(zoom + 1) }, colors = btn, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Add, "Zoom in") }
            IconButton(onClick = { zoomAround(zoom - 1) }, colors = btn, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Remove, "Zoom out") }
        }
        IconButton(
            onClick = { userMoved = false; fitAll() },
            colors = btn, modifier = Modifier.align(Alignment.BottomEnd).padding(controlsPadding).padding(6.dp).size(48.dp),
        ) { Icon(Icons.Rounded.CenterFocusStrong, "Fit all") }
        Text(
            if (satellite) "Imagery © Esri, Vantor, Earthstar Geographics" else "© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall, color = cs.onSurface,
            modifier = Modifier.align(Alignment.BottomStart).padding(controlsPadding).padding(6.dp).background(cs.surface.copy(alpha = 0.75f), MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun Modifier.offset(x: Int, y: Int) = this.offset { IntOffset(x, y) }
