package vip.ringling.recon.domain

/** Camera of one map, kept outside composition so it survives navigating to a record and back. */
class MapCamera(var zoom: Double = Double.NaN, var cx: Double = Double.NaN, var cy: Double = Double.NaN, var userMoved: Boolean = false) {
    val saved get() = !zoom.isNaN()
}

/** In-memory (never persisted) map UI state for the current scan; cleared whenever a new scan starts. */
object MapMemory {
    private val cameras = mutableMapOf<String, MapCamera>()
    fun camera(key: String) = cameras.getOrPut(key) { MapCamera() }

    /** Selected pin in the full-screen map. */
    var selectedId: String? = null
    /** Set when the user leaves the full-screen map for a record, so coming back reopens it where it was. */
    var reopenFullscreen = false

    fun reset() { cameras.clear(); selectedId = null; reopenFullscreen = false }
}
