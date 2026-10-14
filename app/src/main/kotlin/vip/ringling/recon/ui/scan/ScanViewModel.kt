package vip.ringling.recon.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import vip.ringling.recon.data.ScanController
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.ModuleId

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val controller: ScanController,
    apiKeys: ApiKeys,
) : ViewModel() {
    val state: StateFlow<ScanUiState> = combine(controller.session, controller.enabled, apiKeys.keys) { s, e, k ->
        ScanUiState(s, e, readiness(k))
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    fun toggle(m: ModuleId, on: Boolean) = controller.setEnabled(m, on)
    fun rescan() = controller.rescan()
}

internal fun readiness(keys: Map<ApiKeyId, String>) = mapOf(
    ModuleId.BREACH to when {
        keys[ApiKeyId.HIBP].isNullOrBlank() && keys[ApiKeyId.LEAKCHECK].isNullOrBlank() -> Readiness("No keys · LeakCheck public endpoint (email only)", false)
        keys[ApiKeyId.HIBP].isNullOrBlank() -> Readiness("HIBP key missing · email breaches limited", false)
        else -> Readiness("Ready · HIBP key set", true)
    },
    ModuleId.IDENTITY to Readiness("Ready · 39 sites + email checks", true),
    ModuleId.DOMAIN to Readiness("Ready · passive only", true),
    ModuleId.REGISTRY to Readiness("Idaho adapter pending form confirmation", false),
)
