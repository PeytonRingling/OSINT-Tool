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
import vip.ringling.recon.data.settings.SettingsRepository
import vip.ringling.recon.data.settings.ThemeMode
import vip.ringling.recon.domain.ApiKeyId

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val history: HistoryRepository,
    private val apiKeys: ApiKeys,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> = combine(repo.settings, apiKeys.keys, history.records) { s, k, h ->
        SettingsUiState(s, k, h.size, BuildConfig.VERSION_NAME)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(m: ThemeMode) = viewModelScope.launch { repo.setTheme(m) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { repo.setDynamic(on) }
    fun setHaptics(on: Boolean) = viewModelScope.launch { repo.setHaptics(on) }
    fun setKey(id: ApiKeyId, v: String) = apiKeys.set(id, v)
    fun clearHistory() = viewModelScope.launch { history.clear() }
}
