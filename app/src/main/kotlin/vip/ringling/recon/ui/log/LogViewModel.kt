package vip.ringling.recon.ui.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.SnapshotRepository
import vip.ringling.recon.domain.LogMath
import vip.ringling.recon.domain.LogTarget
import vip.ringling.recon.domain.ScanSnapshot

enum class LogMode(val label: String) { BY_TARGET("By target"), TIMELINE("Timeline") }

data class LogUiState(
    val targets: List<LogTarget> = emptyList(),
    /** Every scan, newest first (the "Timeline" view). */
    val scans: List<ScanSnapshot> = emptyList(),
    val mode: LogMode = LogMode.BY_TARGET,
    val now: Long = System.currentTimeMillis(),
) {
    val scanCount: Int get() = scans.size
}

@HiltViewModel
class LogViewModel @Inject constructor(
    private val history: HistoryRepository,
    private val snapshots: SnapshotRepository,
) : ViewModel() {
    private val mode = MutableStateFlow(LogMode.BY_TARGET)

    init { viewModelScope.launch { snapshots.backfill(history.records.let { f -> f.first() }) } }

    val state: StateFlow<LogUiState> = combine(snapshots.snapshots, mode) { snaps, m ->
        LogUiState(LogMath.targets(snaps), snaps.sortedByDescending { it.ts }, m, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogUiState())

    fun setMode(m: LogMode) { mode.value = m }

    /** Forget every scan of one target (history rows and snapshots). */
    fun deleteTarget(t: LogTarget) = viewModelScope.launch {
        t.scans.forEach { history.delete(it.id) }
        snapshots.deleteTarget(t.type, t.value)
    }

    fun clearAll() = viewModelScope.launch { history.clear(); snapshots.clear() }
}
