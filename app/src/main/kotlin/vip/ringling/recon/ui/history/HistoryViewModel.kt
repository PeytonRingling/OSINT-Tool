package vip.ringling.recon.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.domain.ScanRecord

@HiltViewModel
class HistoryViewModel @Inject constructor(private val repo: HistoryRepository) : ViewModel() {
    private val selected = MutableStateFlow<String?>(null)

    val state: StateFlow<HistoryUiState> = combine(repo.records, selected) { r, s ->
        HistoryUiState(r, s.takeIf { id -> r.any { it.id == id } })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    val selectedRecord = combine(repo.records, selected) { r, s -> r.firstOrNull { it.id == s } }

    fun open(r: ScanRecord) { selected.value = r.id }
    fun close() { selected.value = null }
    fun delete(r: ScanRecord) = viewModelScope.launch { repo.delete(r.id) }
    fun clearAll() = viewModelScope.launch { repo.clear(); selected.value = null }
}
