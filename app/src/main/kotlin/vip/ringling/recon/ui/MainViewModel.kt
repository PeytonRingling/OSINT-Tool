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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import vip.ringling.recon.data.HistoryRepository
import kotlinx.coroutines.launch
import vip.ringling.recon.data.ScanController
import vip.ringling.recon.data.location.UserLocation
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.data.settings.AppSettings
import vip.ringling.recon.data.settings.SettingsRepository
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ScanRecord

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: SettingsRepository,
    controller: ScanController,
    private val userLocation: UserLocation,
) : ViewModel() {
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
) : ViewModel() {
    private val id: String = checkNotNull(handle["id"])
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
