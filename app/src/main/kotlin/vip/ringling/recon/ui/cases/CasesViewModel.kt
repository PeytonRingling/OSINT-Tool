package vip.ringling.recon.ui.cases

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.ringling.recon.data.CaseFileRepository
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.SnapshotRepository
import vip.ringling.recon.data.watch.WatchItem
import vip.ringling.recon.data.watch.WatchRepository
import vip.ringling.recon.data.watch.WatchScheduler
import vip.ringling.recon.domain.CaseChanges
import vip.ringling.recon.domain.CaseEdge
import vip.ringling.recon.domain.CaseFile
import vip.ringling.recon.domain.CaseLogic
import vip.ringling.recon.domain.CasePivot
import vip.ringling.recon.domain.CaseStatus
import vip.ringling.recon.domain.CaseTarget
import vip.ringling.recon.domain.CaseTargetState
import vip.ringling.recon.domain.ChangeEvent
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputDetector
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanSnapshot

/** A case plus everything derived from its stored scans. */
data class CaseView(
    val file: CaseFile,
    val states: List<CaseTargetState>,
    val edges: List<CaseEdge>,
    val pivots: List<CasePivot>,
    val events: List<ChangeEvent>,
    val series: List<Pair<Long, Int>>,
) {
    val exposure: Int? get() = CaseLogic.exposure(states)
    val status: CaseStatus get() = CaseLogic.status(states)
    val exposed: Int get() = CaseLogic.exposedTotal(states)
}

internal fun buildCaseView(file: CaseFile, snapshots: List<ScanSnapshot>, findings: (CaseTarget) -> List<Finding>): CaseView {
    val bySnap = snapshots.groupBy { it.key }
    val states = file.targets.map { t -> bySnap[t.key].orEmpty().sortedBy { it.ts }.let { CaseTargetState(t, it.lastOrNull(), it) } }
    return CaseView(
        file, states, CaseLogic.edges(file.targets), CaseLogic.pivots(file.targets, file.targets.associate { it.key to findings(it) }),
        CaseChanges.events(states), CaseChanges.exposureSeries(states),
    )
}

data class CasesUiState(val cases: List<CaseView> = emptyList(), val watches: List<WatchItem> = emptyList())

@HiltViewModel
class CasesViewModel @Inject constructor(
    private val repo: CaseFileRepository,
    snapshots: SnapshotRepository,
    history: HistoryRepository,
    private val watch: WatchRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state: StateFlow<CasesUiState> = combine(repo.cases, snapshots.snapshots, history.records, watch.items) { cases, snaps, records, watches ->
        val latest = records.groupBy { it.input.type to it.input.raw }.mapValues { (_, l) -> l.maxBy { it.timestampMillis }.findings }
        CasesUiState(
            cases.map { c -> buildCaseView(c, snaps) { t -> latest[t.type to t.value].orEmpty() } },
            watches.map { w -> WatchItem(w.raw, watch.known(w)?.size ?: -1, w.lastChecked) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CasesUiState())

    fun create(name: String, first: CaseTarget? = null, onCreated: (String) -> Unit = {}) = viewModelScope.launch { onCreated(repo.create(name, first)) }

    fun addWatch(email: String) = viewModelScope.launch {
        if (!email.trim().matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) return@launch
        watch.add(email)
        WatchScheduler.sync(context, true)
        WatchScheduler.runNow(context)
    }

    fun removeWatch(email: String) = viewModelScope.launch {
        watch.remove(email)
        WatchScheduler.sync(context, watch.all().isNotEmpty())
    }
}

@HiltViewModel
class CaseDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: CaseFileRepository,
    snapshots: SnapshotRepository,
    history: HistoryRepository,
) : ViewModel() {
    val caseId: String = checkNotNull(handle["id"])
    private val tab = MutableStateFlow(CaseTab.TARGETS)

    val state: StateFlow<CaseDetailUiState> = combine(repo.cases, snapshots.snapshots, history.records, tab) { cases, snaps, records, t ->
        val file = cases.firstOrNull { it.id == caseId } ?: return@combine CaseDetailUiState(null, t)
        val latest = records.groupBy { it.input.type to it.input.raw }.mapValues { (_, l) -> l.maxBy { it.timestampMillis }.findings }
        CaseDetailUiState(buildCaseView(file, snaps) { tg -> latest[tg.type to tg.value].orEmpty() }, t)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CaseDetailUiState(null, CaseTab.TARGETS))

    fun setTab(t: CaseTab) { tab.value = t }
    fun addTarget(raw: String): Boolean {
        val type = InputDetector.detect(raw) ?: return false
        viewModelScope.launch { repo.addTarget(caseId, type, InputDetector.normalize(raw, type)) }
        return true
    }
    fun addTarget(type: InputType, value: String) = viewModelScope.launch { repo.addTarget(caseId, type, value) }
    fun removeTarget(t: CaseTarget) = viewModelScope.launch { repo.removeTarget(caseId, t.type, t.value) }
    fun addNote(text: String) = viewModelScope.launch { repo.addNote(caseId, text) }
    fun deleteNote(id: Long) = viewModelScope.launch { repo.deleteNote(id) }
    fun rename(name: String) = viewModelScope.launch { repo.rename(caseId, name) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { repo.delete(caseId); onDone() }
}

enum class CaseTab(val label: String) { TARGETS("Targets"), PIVOTS("Pivots"), NOTES("Notes"), CHANGES("Changes") }

data class CaseDetailUiState(val case: CaseView?, val tab: CaseTab)
