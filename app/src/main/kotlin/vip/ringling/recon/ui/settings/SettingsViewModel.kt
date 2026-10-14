package vip.ringling.recon.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vip.ringling.recon.BuildConfig
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.settings.SettingsRepository
import vip.ringling.recon.data.settings.ThemeMode

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val history: HistoryRepository,
) : ViewModel() {
    // Phase 1: keys live in memory only (never persisted, never logged). Phase 2 moves them to encrypted storage.
    private val keys = MutableStateFlow<Map<ApiKeyId, String>>(emptyMap())

    val state: StateFlow<SettingsUiState> = combine(repo.settings, keys, history.records) { s, k, h ->
        SettingsUiState(s, k, h.size, BuildConfig.VERSION_NAME)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setTheme(m: ThemeMode) = viewModelScope.launch { repo.setTheme(m) }
    fun setDynamic(on: Boolean) = viewModelScope.launch { repo.setDynamic(on) }
    fun setHaptics(on: Boolean) = viewModelScope.launch { repo.setHaptics(on) }
    fun setKey(id: ApiKeyId, v: String) = keys.update { it + (id to v.trim()) }
    fun clearHistory() = viewModelScope.launch { history.clear() }
}
