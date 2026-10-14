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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanRunner
import vip.ringling.recon.domain.ScanSession

/** App-scoped so a scan survives tab switches and rotation. Saves to history when it completes. */
@Singleton
class ScanController @Inject constructor(
    private val runner: ScanRunner,
    private val history: HistoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _session = MutableStateFlow<ScanSession?>(null)
    val session: StateFlow<ScanSession?> = _session.asStateFlow()

    private val _completed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once each time a scan finishes (drives the completion haptic). */
    val completed: SharedFlow<Unit> = _completed

    private val _enabled = MutableStateFlow(ModuleId.entries.toSet())
    /** Modules the user has switched on in the Scan tab. */
    val enabled: StateFlow<Set<ModuleId>> = _enabled.asStateFlow()
    fun setEnabled(module: ModuleId, on: Boolean) = _enabled.update { if (on) it + module else it - module }

    fun modulesFor(input: ScanInput) = runner.modulesFor(input.type)
    fun rescan() { _session.value?.input?.let { start(it) } }

    fun start(input: ScanInput, enabled: Set<ModuleId>? = _enabled.value) {
        job?.cancel()
        _session.value = ScanSession(input)
        job = scope.launch {
            var last = ScanSession(input)
            runner.session(input, enabled).collect { s -> last = s; _session.value = s }
            // Registry results are live-lookup only and must never be stored (Idaho Code §74-120): keep the
            // query in history, drop the findings.
            val storable = last.findings.filter { it.module != ModuleId.REGISTRY }
            if (last.findings.isNotEmpty()) {
                history.add(ScanRecord(UUID.randomUUID().toString(), input, System.currentTimeMillis(), storable))
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

    fun findingById(id: String): Finding? = _session.value?.findings?.firstOrNull { it.id == id }
}
