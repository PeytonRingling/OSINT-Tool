package vip.ringling.recon.data.settings

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("System") }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val haptics: Boolean = true,
    val mapSatellite: Boolean = false,
    val proxyMode: vip.ringling.recon.data.net.ProxyMode = vip.ringling.recon.data.net.ProxyMode.OFF,
    val proxyHost: String = "",
    val proxyPort: Int = 1080,
    /** Require device biometrics / PIN to open the app, and hide it from the recents screen. */
    val appLock: Boolean = false,
    /** Delete scans older than this many days (0 = keep everything). Cases are never auto-deleted. */
    val retentionDays: Int = 0,
    /** Re-scan every case target once a day in the background and notify only when something changed. */
    val autoRescan: Boolean = false,
    val onboarded: Boolean = false,
    /** False until DataStore has delivered its first value, so first-run UI doesn't flash for existing users. */
    val loaded: Boolean = false,
)
