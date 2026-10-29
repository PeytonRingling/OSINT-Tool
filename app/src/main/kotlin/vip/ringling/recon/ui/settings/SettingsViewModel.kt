package vip.ringling.recon.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.ringling.recon.BuildConfig
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.data.update.UpdateManager
import vip.ringling.recon.data.settings.SettingsRepository
import vip.ringling.recon.data.settings.ThemeMode
import vip.ringling.recon.domain.ApiKeyId

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val history: HistoryRepository,
    private val apiKeys: ApiKeys,
    private val updates: UpdateManager,
    private val snapshots: vip.ringling.recon.data.SnapshotRepository,
    private val backup: vip.ringling.recon.data.BackupManager,
    private val route: vip.ringling.recon.data.net.RouteTester,
    private val guard: vip.ringling.recon.data.DataGuard,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {
    fun setRetention(days: Int) = viewModelScope.launch { repo.setRetentionDays(days); guard.purgeOlderThan(days) }
    fun setAutoRescan(on: Boolean) = viewModelScope.launch {
        repo.setAutoRescan(on)
        vip.ringling.recon.data.watch.AutoRescanScheduler.sync(appContext, on)
    }
    fun wipeEverything() = viewModelScope.launch { guard.wipeEverything() }
    suspend fun testRoute() = route.test()
    suspend fun encryptedBackup(passphrase: CharArray): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { try { backup.exportEncrypted(passphrase) } finally { passphrase.fill(' ') } }
    suspend fun importBackup(text: String, passphrase: CharArray? = null) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { try { backup.import(text, passphrase) } finally { passphrase?.fill(' ') } }
    val state: StateFlow<SettingsUiState> = combine(repo.settings, apiKeys.keys, history.records, updates.state) { s, k, h, u ->
        SettingsUiState(s, k, h.size, BuildConfig.VERSION_NAME, updates.installedBuild, u, vip.ringling.recon.data.db.DbEncryption.status)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(m: ThemeMode) = viewModelScope.launch { repo.setTheme(m) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { repo.setDynamic(on) }
    fun setHaptics(on: Boolean) = viewModelScope.launch { repo.setHaptics(on) }
    fun setProxyMode(m: vip.ringling.recon.data.net.ProxyMode) = viewModelScope.launch { repo.setProxyMode(m) }
    fun setProxyHost(h: String) = viewModelScope.launch { repo.setProxyHost(h) }
    fun setProxyPort(p: Int) = viewModelScope.launch { repo.setProxyPort(p) }
    fun setAppLock(on: Boolean) = viewModelScope.launch { repo.setAppLock(on) }
    fun setOpsec(on: Boolean) = viewModelScope.launch { repo.setOpsec(on) }
    fun setKey(id: ApiKeyId, v: String) = apiKeys.set(id, v)
    fun checkUpdate() = updates.check()
    fun installUpdate() = updates.downloadAndInstall()
    fun dismissUpdateError() = updates.dismissError()
    fun clearHistory() = viewModelScope.launch { history.clear(); snapshots.clear() }
}
