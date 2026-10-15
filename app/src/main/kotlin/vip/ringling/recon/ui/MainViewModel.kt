package vip.ringling.recon.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import vip.ringling.recon.data.HistoryRepository
import kotlinx.coroutines.launch
import vip.ringling.recon.data.ScanController
import vip.ringling.recon.data.location.UserLocation
import vip.ringling.recon.data.update.UpdateManager
import vip.ringling.recon.data.update.UpdateState
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.data.settings.AppSettings
import vip.ringling.recon.data.settings.SettingsRepository
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ScanRecord

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    settings: SettingsRepository,
    private val controller: ScanController,
    private val userLocation: UserLocation,
    private val updates: UpdateManager,
    private val caseFiles: vip.ringling.recon.data.CaseFileRepository,
) : ViewModel() {
    /** Bumped when something outside the Cases screen (the radar's "New case" button) asks it to open the new-case dialog. */
    private val _newCase = kotlinx.coroutines.flow.MutableStateFlow(0)
    val newCaseRequests: StateFlow<Int> = _newCase
    fun requestNewCase() { _newCase.value += 1 }

    /** Pin a scanned target to a case (only the target is kept, never findings). */
    fun pin(caseId: String, input: vip.ringling.recon.domain.ScanInput) { viewModelScope.launch { caseFiles.addTarget(caseId, input.type, input.raw) } }
    fun createCaseWith(name: String, input: vip.ringling.recon.domain.ScanInput) {
        viewModelScope.launch { caseFiles.create(name, vip.ringling.recon.domain.CaseTarget(input.type, input.raw, System.currentTimeMillis())) }
    }
    val update: StateFlow<UpdateState> = updates.state
    fun checkForUpdateQuietly() = updates.checkQuietly()
    fun requestSearch(query: String) = controller.requestSearch(query)
    fun runPaid(f: Finding) = controller.runPaid(f)
    fun setMapSatellite(on: Boolean) { viewModelScope.launch { settingsRepo.setMapSatellite(on) } }
    /** Synchronous read of the lock setting for the very first frame (DataStore is fast but async). */
    fun appLockNow(): Boolean = kotlinx.coroutines.runBlocking { settingsRepo.settings.first().appLock }
    val me: StateFlow<GeoPoint?> = userLocation.location
    fun hasLocationPermission() = userLocation.hasPermission()
    fun refreshLocation() { viewModelScope.launch { userLocation.refresh() } }

    val settings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val scanCompleted: SharedFlow<Unit> = controller.completed
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    controller: ScanController,
    history: HistoryRepository,
    private val handledRepo: vip.ringling.recon.data.HandledRepository,
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])
    val handled: StateFlow<Set<String>> = handledRepo.handled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    fun setHandled(f: Finding, on: Boolean) { viewModelScope.launch { handledRepo.set(f, on) } }
    val finding: StateFlow<Finding?> = combine(controller.session, history.records) { s, recs ->
        s?.findings?.firstOrNull { it.id == id }
            ?: recs.firstNotNullOfOrNull { r -> r.findings.firstOrNull { it.id == id } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@HiltViewModel
class RecordViewModel @Inject constructor(
    handle: SavedStateHandle,
    history: HistoryRepository,
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])
    val record: StateFlow<ScanRecord?> = flow { emit(history.find(id)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
