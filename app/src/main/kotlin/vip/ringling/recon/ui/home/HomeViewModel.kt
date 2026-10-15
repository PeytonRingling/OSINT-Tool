package vip.ringling.recon.ui.home

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
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.ScanController
import vip.ringling.recon.domain.InputDetector
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val controller: ScanController,
    history: HistoryRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val override = MutableStateFlow<InputType?>(null)
    private val registryAck = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            controller.pendingQuery.collect { q ->
                if (q != null) {
                    controller.consumePending()
                    onQueryChange(q); override.value = null
                    submit()
                }
            }
        }
    }
    private val selectedId = MutableStateFlow<String?>(null)

    val state: StateFlow<HomeUiState> = combine(
        query, override, controller.session, history.records, combine(registryAck, selectedId) { a, s -> a to s },
    ) { q, o, session, recents, (ack, sel) ->
        HomeUiState(q, o, InputDetector.detect(q), session, recents, ack, sel)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    val selectedFinding = combine(controller.session, selectedId) { s, id -> s?.findings?.firstOrNull { it.id == id } }

    fun onQueryChange(v: String) {
        query.value = v
        override.update { if (InputDetector.detect(v) == null) null else it }
    }

    fun onOverride(t: InputType?) { override.value = t }
    fun select(id: String?) { selectedId.value = id }
    fun ackRegistry() { registryAck.value = true }

    fun submit() {
        val s = state.value
        val type = s.effective ?: return
        val raw = InputDetector.normalize(s.query, type)
        registryAck.value = false
        selectedId.value = null
        controller.start(ScanInput(raw, type))
    }

    fun stop() = controller.cancel()

    fun reuse(r: ScanRecord) {
        query.value = r.input.raw
        override.value = null
    }
}
