package vip.ringling.recon.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import vip.ringling.recon.domain.GeoPoint

/**
 * The user's position, used ONLY to compute distances and draw the "you" dot on-device. It is held in memory,
 * never written to disk, never logged, and never sent anywhere (map tiles are requested by area, not by identity).
 */
interface UserLocation {
    val location: StateFlow<GeoPoint?>
    fun hasPermission(): Boolean
    suspend fun refresh()
}

@Singleton
class AndroidUserLocation @Inject constructor(@ApplicationContext private val context: Context) : UserLocation {
    private val state = MutableStateFlow<GeoPoint?>(null)
    override val location: StateFlow<GeoPoint?> = state.asStateFlow()

    override fun hasPermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    override suspend fun refresh() {
        if (!hasPermission()) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = buildList {
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        // A recent fix is plenty for "how far away is it"; otherwise ask for a fresh one.
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }.maxByOrNull { it.time }
        val fix = last ?: providers.firstNotNullOfOrNull { current(lm, it) }
        fix?.let { state.value = GeoPoint(it.latitude, it.longitude) }
    }

    @android.annotation.SuppressLint("MissingPermission") // only called after hasPermission(); a revoked grant is caught below
    private suspend fun current(lm: LocationManager, provider: String): Location? = try { currentOrThrow(lm, provider) } catch (e: SecurityException) { null }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun currentOrThrow(lm: LocationManager, provider: String): Location? = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(context)) { cont.resume(it) }
        }
    }
}
