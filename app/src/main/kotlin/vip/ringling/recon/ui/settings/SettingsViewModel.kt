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
    private val watch: vip.ringling.recon.data.watch.WatchRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(repo.settings, apiKeys.keys, history.records, updates.state, watch.items) { s, k, h, u, w ->
        SettingsUiState(s, k, h.size, BuildConfig.VERSION_NAME, updates.installedBuild, u, w.map { vip.ringling.recon.data.watch.WatchItem(it.raw, watch.known(it)?.size ?: -1, it.lastChecked) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(m: ThemeMode) = viewModelScope.launch { repo.setTheme(m) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { repo.setDynamic(on) }
    fun setHaptics(on: Boolean) = viewModelScope.launch { repo.setHaptics(on) }
    fun setProxyMode(m: vip.ringling.recon.data.net.ProxyMode) = viewModelScope.launch { repo.setProxyMode(m) }
    fun setProxyHost(h: String) = viewModelScope.launch { repo.setProxyHost(h) }
    fun setProxyPort(p: Int) = viewModelScope.launch { repo.setProxyPort(p) }
    fun setAppLock(on: Boolean) = viewModelScope.launch { repo.setAppLock(on) }
    fun addWatch(email: String) = viewModelScope.launch {
        if (!email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) return@launch
        watch.add(email)
        vip.ringling.recon.data.watch.WatchScheduler.sync(context, true)
        vip.ringling.recon.data.watch.WatchScheduler.runNow(context)
    }
    fun removeWatch(email: String) = viewModelScope.launch {
        watch.remove(email)
        vip.ringling.recon.data.watch.WatchScheduler.sync(context, watch.all().isNotEmpty())
    }
    fun setKey(id: ApiKeyId, v: String) = apiKeys.set(id, v)
    fun checkUpdate() = updates.check()
    fun installUpdate() = updates.downloadAndInstall()
    fun dismissUpdateError() = updates.dismissError()
    fun clearHistory() = viewModelScope.launch { history.clear() }
}
