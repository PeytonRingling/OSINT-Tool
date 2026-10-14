package vip.ringling.recon.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import vip.ringling.recon.data.ScanController
import vip.ringling.recon.domain.ModuleId

@HiltViewModel
class ScanViewModel @Inject constructor(private val controller: ScanController) : ViewModel() {
    val state: StateFlow<ScanUiState> = combine(controller.session, controller.enabled) { s, e -> ScanUiState(s, e) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    fun toggle(m: ModuleId, on: Boolean) = controller.setEnabled(m, on)
    fun rescan() = controller.rescan()
}
