package vip.ringling.recon.ui.casefile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.ringling.recon.data.CaseItem
import vip.ringling.recon.data.CaseRepository
import vip.ringling.recon.domain.CaseReport

data class CaseUiState(val items: List<CaseItem> = emptyList(), val entities: List<Pair<String, Int>> = emptyList())

@HiltViewModel
class CaseViewModel @Inject constructor(private val repo: CaseRepository) : ViewModel() {
    val state: StateFlow<CaseUiState> = repo.items.map { CaseUiState(it, CaseReport.entities(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaseUiState())

    fun setNote(key: String, note: String) = viewModelScope.launch { repo.setNote(key, note) }
    fun remove(key: String) = viewModelScope.launch { repo.remove(key) }
    fun clear() = viewModelScope.launch { repo.clear() }
}
