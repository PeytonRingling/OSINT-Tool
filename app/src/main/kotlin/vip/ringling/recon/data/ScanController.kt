package vip.ringling.recon.data

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanRunner
import vip.ringling.recon.domain.ScanSession

data class BatchState(
    val total: Int = 0, val done: Int = 0, val current: String? = null, val results: List<vip.ringling.recon.domain.ScanDiff> = emptyList(),
) {
    val active: Boolean get() = total > 0
    val running: Boolean get() = active && done < total
}

/** App-scoped so a scan survives tab switches and rotation. Saves to history when it completes. */
@Singleton
class ScanController @Inject constructor(
    private val runner: ScanRunner,
    private val history: HistoryRepository,
    private val snapshots: SnapshotRepository,
    private val paidLookups: Set<@JvmSuppressWildcards vip.ringling.recon.domain.PaidLookups>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _session = MutableStateFlow<ScanSession?>(null)
    val session: StateFlow<ScanSession?> = _session.asStateFlow()

    private val _completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once each time a scan finishes (drives the completion haptic). */
    val completed: SharedFlow<Unit> = _completed

    private val _serial = MutableStateFlow(0)
    /** Increments each time a scan starts; UI state that must reset per scan (e.g. the registry notice) keys off it. */
    val serial: StateFlow<Int> = _serial.asStateFlow()

    private val _enabled = MutableStateFlow(ModuleId.entries.toSet())
    /** Modules the user has switched on in the Scan tab. */
    val enabled: StateFlow<Set<ModuleId>> = _enabled.asStateFlow()
    fun setEnabled(module: ModuleId, on: Boolean) {
        _autoPick.value = false
        _enabled.update { if (on) it + module else it - module }
    }

    private val _autoPick = MutableStateFlow(true)
    /** Auto-pick arms every module (each input then runs the ones that apply to it); Manual runs only the armed ones. */
    val autoPick: StateFlow<Boolean> = _autoPick.asStateFlow()
    fun setAutoPick(on: Boolean) {
        _autoPick.value = on
        if (on) _enabled.value = ModuleId.entries.toSet()
    }

    private val _pendingQuery = MutableStateFlow<String?>(null)
    /** A search requested from elsewhere (e.g. a "Search next" pivot); Home consumes it and runs it. */
    val pendingQuery: StateFlow<String?> = _pendingQuery.asStateFlow()
    fun requestSearch(query: String) { _pendingQuery.value = query }
    fun consumePending() { _pendingQuery.value = null }

    private val _prefill = MutableStateFlow<String?>(null)
    /**
     * Text to put in the Radar's search box WITHOUT searching it (share sheet, selected text, mailto:/tel:, clipboard shortcut): anything
     * another app can trigger must wait for the user to press go. Held until consumed, so text shared on a cold start isn't lost.
     */
    val prefill: StateFlow<String?> = _prefill.asStateFlow()
    fun requestPrefill(text: String) { _prefill.value = text }
    fun consumePrefill() { _prefill.value = null }

    private val _pendingLink = MutableStateFlow<String?>(null)
    /** A web link shared into Recon: the Lab's link inspector picks it up (it is inspected, never opened). */
    val pendingLink: StateFlow<String?> = _pendingLink.asStateFlow()
    fun requestLink(url: String) { _pendingLink.value = url }
    fun consumeLink() { _pendingLink.value = null }

    private var batchJob: Job? = null
    private val _batch = MutableStateFlow(BatchState())
    /** Progress and per-target diff of a "Re-scan all" run (background; the main scan session is untouched). */
    val batch: StateFlow<BatchState> = _batch.asStateFlow()

    /** Re-runs every target one after another, saving each scan like a normal one and reporting what changed since its previous scan. ZIP/photo targets are skipped. */
    fun rescanAll(inputs: List<ScanInput>) {
        val todo = vip.ringling.recon.domain.ScanDiffs.batchable(inputs)
        if (todo.isEmpty() || _batch.value.running) return
        _batch.value = BatchState(total = todo.size)
        batchJob = scope.launch {
            val before = snapshots.snapshots.first()
            for (input in todo) {
                _batch.update { it.copy(current = input.raw) }
                var last = ScanSession(input)
                val ok = runCatching { runner.session(input, _enabled.value).collect { last = it } }.isSuccess
                val prev = before.filter { it.type == input.type && it.value == input.raw }.maxByOrNull { it.ts }
                val diff = if (ok && last.findings.isNotEmpty()) {
                    val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
                    val storable = last.findings.filter { it.module != ModuleId.REGISTRY && it.module != ModuleId.PEOPLE && it.paid == null }
                    history.add(ScanRecord(id, input, now, storable))
                    val snap = vip.ringling.recon.domain.ScanSnapshot.of(id, input, last.findings, now)
                    snapshots.add(snap)
                    vip.ringling.recon.domain.ScanDiffs.compare(prev, snap, last.findings)
                } else vip.ringling.recon.domain.ScanDiff(input, prev?.risk, prev?.risk ?: 0, emptyList(), 0, failed = true)
                _batch.update { it.copy(done = it.done + 1, results = it.results + diff) }
            }
            _batch.update { it.copy(current = null) }
            _completed.emit(Unit)
        }
    }

    fun cancelBatch() { batchJob?.cancel(); _batch.value = BatchState() }
    fun dismissBatch() { if (!_batch.value.running) _batch.value = BatchState() }

    fun modulesFor(input: ScanInput) = runner.modulesFor(input.type)
    fun rescan() { _session.value?.input?.let { start(it) } }

    fun start(input: ScanInput, enabled: Set<ModuleId>? = _enabled.value) {
        job?.cancel()
        vip.ringling.recon.domain.MapMemory.reset()
        _serial.update { it + 1 }
        _session.value = ScanSession(input)
        job = scope.launch {
            var last = ScanSession(input)
            runner.session(input, enabled).collect { s -> last = s; _session.value = s }
            // Registry results are live-lookup only and must never be stored (Idaho Code §74-120): keep the
            // query in history, drop the findings.
            val storable = last.findings.filter { it.module != ModuleId.REGISTRY && it.module != ModuleId.PEOPLE && it.paid == null }
            if (last.findings.isNotEmpty()) {
                val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
                history.add(ScanRecord(id, input, now, storable))
                snapshots.add(vip.ringling.recon.domain.ScanSnapshot.of(id, input, last.findings, now))
            }
            _completed.emit(Unit)
        }
    }

    /** Stops a running scan, keeping what has streamed in so far; unfinished modules are marked stopped. */
    fun cancel() {
        job?.cancel()
        _session.update { s ->
            s?.copy(
                statuses = s.statuses.mapValues { (_, v) -> if (v == ModuleStatus.RUNNING || v == ModuleStatus.QUEUED) ModuleStatus.FAILED else v },
                errors = s.errors + s.statuses.filterValues { it == ModuleStatus.RUNNING || it == ModuleStatus.QUEUED }.keys.associateWith { "Stopped" },
            )
        }
    }

    fun clear() {
        job?.cancel()
        _session.value = null
    }

    /** Runs a paid lookup the user has already confirmed in the UI. The offer row turns into a pending row, then the result. */
    fun runPaid(offer: Finding) {
        val action = offer.paid ?: return
        val input = _session.value?.input ?: return
        val pending = Finding(offer.id.removeSuffix("-offer") + "-pending", offer.module, "Looking up…", "Waiting for the provider to answer.", vip.ringling.recon.domain.Severity.INFO)
        _session.update { s -> s?.copy(findings = s.findings.map { if (it.id == offer.id) pending else it }) }
        scope.launch {
            val results = paidLookups.firstNotNullOfOrNull { p -> runCatching { p.runPaid(action.id, input) }.getOrNull()?.takeIf { it.isNotEmpty() } }
                ?: listOf(Finding(offer.id.removeSuffix("-offer") + "-error", offer.module, "Lookup failed", "No result came back.", vip.ringling.recon.domain.Severity.INFO))
            _session.update { s -> s?.copy(findings = s.findings.flatMap { if (it.id == pending.id) results else listOf(it) }) }
        }
    }

    fun findingById(id: String): Finding? = _session.value?.findings?.firstOrNull { it.id == id }
}
