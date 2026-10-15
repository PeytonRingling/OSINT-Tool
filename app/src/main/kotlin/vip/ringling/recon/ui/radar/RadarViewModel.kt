package vip.ringling.recon.ui.radar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.ScanController
import vip.ringling.recon.domain.InputDetector
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.RadarMath
import vip.ringling.recon.domain.RadarTarget
import vip.ringling.recon.domain.targetsFromSnapshots
import vip.ringling.recon.domain.ScanInput

data class RadarUiState(
    val query: String = "",
    val detected: InputType? = null,
    /** Modules that will run for the detected input (armed ∩ applicable). */
    val modules: List<ModuleId> = emptyList(),
    val enabled: Set<ModuleId> = ModuleId.entries.toSet(),
    val autoPick: Boolean = true,
    val targets: List<RadarTarget> = emptyList(),
    val running: Boolean = false,
) {
    val armed: Int get() = enabled.size
}

sealed interface RadarEvent {
    /** A scan was started: show its live results. */
    data object OpenLive : RadarEvent
    /** Show the saved results of a previous scan. */
    data class OpenRecord(val id: String) : RadarEvent
}

@HiltViewModel
class RadarViewModel @Inject constructor(
    private val controller: ScanController,
    private val history: HistoryRepository,
    private val snapshots: vip.ringling.recon.data.SnapshotRepository,
    private val pwned: vip.ringling.recon.data.sources.breach.PwnedPasswords,
) : ViewModel() {
    suspend fun passwordCount(pw: String): Long = pwned.count(pw)

    private val query = MutableStateFlow("")
    private val _events = MutableSharedFlow<RadarEvent>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RadarEvent> = _events

    init {
        viewModelScope.launch { snapshots.backfill(history.records.first()) }
        viewModelScope.launch {
            controller.pendingQuery.collect { q ->
                if (q != null) { controller.consumePending(); query.value = q; submit() }
            }
        }
    }

    private val base = combine(query, controller.enabled, controller.autoPick, controller.session) { q, enabled, auto, session -> Triple(q, enabled to auto, session?.running == true) }

    val state: StateFlow<RadarUiState> = combine(base, snapshots.snapshots) { (q, ea, running), snaps ->
        val (enabled, auto) = ea
        val detected = InputDetector.detect(q)
        val modules = detected?.let { t -> controller.modulesFor(ScanInput(q, t)).filter { it in enabled } }.orEmpty()
        RadarUiState(q, detected, modules, enabled, auto, RadarMath.targetsFromSnapshots(snaps), running)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadarUiState())

    fun onQueryChange(v: String) { query.value = v }

    fun submit() {
        val raw = query.value.trim()
        val type = InputDetector.detect(raw) ?: return
        controller.start(ScanInput(InputDetector.normalize(raw, type), type))
        _events.tryEmit(RadarEvent.OpenLive)
    }

    fun open(t: RadarTarget) {
        if (t.recordId == null) { controller.start(t.input); _events.tryEmit(RadarEvent.OpenLive) } else _events.tryEmit(RadarEvent.OpenRecord(t.recordId))
    }

    /** Re-runs a scan of [input] (used for ZIP targets, whose registry results are never stored). */
    fun rerun(input: ScanInput) { controller.start(input); _events.tryEmit(RadarEvent.OpenLive) }

    fun analyzePhoto(uri: String) { controller.start(ScanInput(uri, InputType.IMAGE)); _events.tryEmit(RadarEvent.OpenLive) }

    fun toggle(m: ModuleId, on: Boolean) = controller.setEnabled(m, on)
    fun setAuto(on: Boolean) = controller.setAutoPick(on)
}
