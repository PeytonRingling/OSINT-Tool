package vip.ringling.recon.ui.results

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.ui.MainViewModel

/** Phone results route: exposure map (+ findings list) for most targets, the area scan for a ZIP. State lives in [ResultsViewModel]. */
@Composable
fun ResultsRoute(
    pad: PaddingValues,
    onBack: () -> Unit,
    onFinding: (Finding) -> Unit,
    onExport: (state: ResultsUiState) -> Unit,
    onPin: (state: ResultsUiState) -> Unit,
) {
    val vm: ResultsViewModel = hiltViewModel()
    val main: MainViewModel = hiltViewModel(LocalContext.current as androidx.activity.ComponentActivity)
    val st by vm.state.collectAsStateWithLifecycle()
    var showFindings by rememberSaveable { mutableStateOf(false) }
    val hasLocated = st.findings.any { it.geo != null }
    LaunchedEffect(hasLocated) { if (hasLocated && main.hasLocationPermission()) main.refreshLocation() }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { main.refreshLocation() }
    val actions = ResultsActions(
        onBack = onBack, onExport = { onExport(st) }, onPin = { onPin(st) }, onFinding = onFinding, onFilter = vm::setFilter, onSort = vm::setSort,
        onAckRegistry = vm::ackRegistry, onShowFindings = { showFindings = true }, onStop = vm::stop,
        onRequestLocation = {
            if (main.hasLocationPermission()) main.refreshLocation()
            else permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        },
    )
    when {
        st.input?.type == InputType.ZIP -> AreaScreen(st, actions, contentPadding = pad)
        showFindings -> { BackHandler { showFindings = false }; FindingsList(st, actions, contentPadding = pad, onBack = { showFindings = false }) }
        else -> ExposureScreen(st, actions, contentPadding = pad)
    }
}
