package vip.ringling.recon.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import vip.ringling.recon.data.net.ProxyMode
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
    private val proxyModeKey = stringPreferencesKey("proxy_mode")
    private val proxyHostKey = stringPreferencesKey("proxy_host")
    private val proxyPortKey = intPreferencesKey("proxy_port")
    private val lockKey = booleanPreferencesKey("app_lock")
    private val retentionKey = intPreferencesKey("retention_days")
    private val rescanKey = booleanPreferencesKey("auto_rescan")
    private val onboardedKey = booleanPreferencesKey("onboarded")
    private val opsecKey = booleanPreferencesKey("opsec")

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        AppSettings(
            themeMode = p[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            dynamicColor = p[dynamicKey] ?: false,
            haptics = p[hapticsKey] ?: true,
            mapSatellite = p[satelliteKey] ?: false,
            proxyMode = p[proxyModeKey]?.let { runCatching { ProxyMode.valueOf(it) }.getOrNull() } ?: ProxyMode.OFF,
            proxyHost = p[proxyHostKey].orEmpty(),
            proxyPort = p[proxyPortKey] ?: 1080,
            appLock = p[lockKey] ?: false,
            retentionDays = p[retentionKey] ?: 0,
            autoRescan = p[rescanKey] ?: false,
            onboarded = p[onboardedKey] ?: false,
            opsec = p[opsecKey] ?: false,
            loaded = true,
        )
    }

    suspend fun setTheme(mode: ThemeMode) = context.store.edit { it[themeKey] = mode.name }
    suspend fun setDynamic(on: Boolean) = context.store.edit { it[dynamicKey] = on }
    suspend fun setHaptics(on: Boolean) = context.store.edit { it[hapticsKey] = on }
    suspend fun setMapSatellite(on: Boolean) = context.store.edit { it[satelliteKey] = on }
    suspend fun setProxyMode(m: ProxyMode) = context.store.edit { it[proxyModeKey] = m.name }
    suspend fun setProxyHost(h: String) = context.store.edit { it[proxyHostKey] = h }
    suspend fun setProxyPort(p: Int) = context.store.edit { it[proxyPortKey] = p }
    suspend fun setAppLock(on: Boolean) = context.store.edit { it[lockKey] = on }
    suspend fun setRetentionDays(d: Int) = context.store.edit { it[retentionKey] = d }
    suspend fun setAutoRescan(on: Boolean) = context.store.edit { it[rescanKey] = on }
    suspend fun setOpsec(on: Boolean) = context.store.edit { it[opsecKey] = on }
    suspend fun setOnboarded(on: Boolean = true) = context.store.edit { it[onboardedKey] = on }
    /** Forget every setting (used by "Wipe everything"). */
    suspend fun reset() { context.store.edit { it.clear() } }
}
