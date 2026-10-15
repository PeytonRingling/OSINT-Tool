package vip.ringling.recon.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("recon_settings")

/** Non-secret preferences only. API keys will live in Keystore-backed encrypted storage (Phase 2). */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val dynamicKey = booleanPreferencesKey("dynamic")
    private val hapticsKey = booleanPreferencesKey("haptics")
    private val satelliteKey = booleanPreferencesKey("map_satellite")

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            themeMode = p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            dynamicColor = p[dynamicKey] ?: false,
            haptics = p[hapticsKey] ?: true,
            mapSatellite = p[satelliteKey] ?: false,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.store.edit { it[themeKey] = mode.name }
    suspend fun setDynamic(on: Boolean) = context.store.edit { it[dynamicKey] = on }
    suspend fun setHaptics(on: Boolean) = context.store.edit { it[hapticsKey] = on }
    suspend fun setMapSatellite(on: Boolean) = context.store.edit { it[satelliteKey] = on }
}
