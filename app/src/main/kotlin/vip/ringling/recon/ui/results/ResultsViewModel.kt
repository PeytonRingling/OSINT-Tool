package vip.ringling.recon.ui.results

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.ScanController
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord

enum class FindingFilter(val label: String) { ALL("All"), EXPOSED("Exposed"), INFO("Info") }
enum class AreaSort(val label: String) { NEAREST("Nearest"), AZ("A–Z") }

data class ResultsUiState(
    val input: ScanInput? = null,
    val findings: List<Finding> = emptyList(),
    val statuses: Map<ModuleId, ModuleStatus> = emptyMap(),
    val errors: Map<ModuleId, String> = emptyMap(),
    val running: Boolean = false,
    val live: Boolean = true,
    /** The offender-registry legal notice has been acknowledged for THIS scan (it resets on every new scan). */
    val registryAck: Boolean = false,
    val selectedId: String? = null,
    val filter: FindingFilter = FindingFilter.ALL,
    val sort: AreaSort = AreaSort.NEAREST,
    val handled: Set<String> = emptySet(),
    /** The previous saved scan of this same target, for the "since last scan" banner. */
    val previous: vip.ringling.recon.domain.ScanRecord? = null,
)

/**
 * Results of one scan: either the live session (route "results") or a saved record ("results/{id}"). All UI state lives here so it
 * survives rotation and fold/unfold without re-querying anything.
 */
@HiltViewModel
class ResultsViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val controller: ScanController,
    history: HistoryRepository,
    private val handledRepo: vip.ringling.recon.data.HandledRepository,
) : ViewModel() {
    private val recordId: String? = handle["id"]

    private val record = flow { emit(recordId?.let { history.find(it) }) }
    private val ackSerial = MutableStateFlow(-1)
    private val selected = MutableStateFlow<String?>(null)
    private val filter = MutableStateFlow(FindingFilter.ALL)
    private val sort = MutableStateFlow(AreaSort.NEAREST)

    private fun sameTarget(a: ScanInput, b: ScanInput) = a.type == b.type && a.raw.trim().equals(b.raw.trim(), ignoreCase = true)

    /** Live scan: the newest saved scan of the same target at the moment the scan starts. Saved record: the next-older scan of that target. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val previous: kotlinx.coroutines.flow.Flow<ScanRecord?> =
        if (recordId != null) record.map { r -> if (r == null) null else history.records.first().filter { it.timestampMillis < r.timestampMillis && sameTarget(it.input, r.input) }.maxByOrNull { it.timestampMillis } }
        else combine(controller.serial, controller.session.map { it?.input }.distinctUntilChanged()) { n, i -> n to i }.distinctUntilChanged().flatMapLatest { (_, i) ->
            flow { emit(i?.let { inp -> history.records.first().filter { sameTarget(it.input, inp) }.maxByOrNull { it.timestampMillis } }) }
        }

    private val ui = combine(selected, filter, sort, handledRepo.handled, previous) { sel, f, so, h, p -> arrayOf(sel, f, so, h, p) }

    val state: StateFlow<ResultsUiState> = combine(controller.session, controller.serial, record, ackSerial, ui) { session, serial, rec, ack, u ->
        @Suppress("UNCHECKED_CAST")
        val common = ResultsUiState(selectedId = u[0] as String?, filter = u[1] as FindingFilter, sort = u[2] as AreaSort, handled = u[3] as Set<String>, previous = u[4] as ScanRecord?)
        if (recordId != null) {
            rec?.let { r -> common.copy(input = r.input, findings = r.findings, statuses = r.findings.map { it.module }.distinct().sortedBy { it.ordinal }.associateWith { ModuleStatus.DONE }, live = false, registryAck = true) }
                ?: common.copy(live = false)
        } else {
            common.copy(
                input = session?.input, findings = session?.findings.orEmpty(), statuses = session?.statuses.orEmpty(), errors = session?.errors.orEmpty(),
                running = session?.running == true, registryAck = ack == serial,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResultsUiState())

    fun ackRegistry() { ackSerial.value = controller.serial.value }
    fun select(id: String?) { selected.value = id }
    fun setFilter(f: FindingFilter) { filter.value = f }
    fun setSort(s: AreaSort) { sort.value = s }
    fun stop() = controller.cancel()
    fun setHandled(f: Finding, on: Boolean) { viewModelScope.launch { handledRepo.set(f, on) } }
}
