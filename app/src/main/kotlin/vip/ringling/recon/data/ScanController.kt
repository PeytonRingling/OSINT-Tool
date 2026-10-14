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
import kotlinx.coroutines.launch
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
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

    fun modulesFor(input: ScanInput) = runner.modulesFor(input.type)

    fun start(input: ScanInput, enabled: Set<ModuleId>? = null) {
        job?.cancel()
        _session.value = ScanSession(input)
        job = scope.launch {
            var last = ScanSession(input)
            runner.session(input, enabled).collect { s -> last = s; _session.value = s }
            if (last.findings.isNotEmpty()) {
                history.add(ScanRecord(UUID.randomUUID().toString(), input, System.currentTimeMillis(), last.findings))
            }
            _completed.emit(Unit)
        }
    }

    fun clear() {
        job?.cancel()
        _session.value = null
    }

    fun findingById(id: String): Finding? = _session.value?.findings?.firstOrNull { it.id == id }
}
