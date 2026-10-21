package vip.ringling.recon.ui.results

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    onPivot: (String) -> Unit = {},
    onReport: (state: ResultsUiState) -> Unit = {},
    wide: Boolean = false,
) {
    val vm: ResultsViewModel = hiltViewModel()
    val main: MainViewModel = hiltViewModel(androidx.activity.compose.LocalActivity.current as androidx.activity.ComponentActivity)
    val st by vm.state.collectAsStateWithLifecycle()
    var showFindings by rememberSaveable { mutableStateOf(false) }
    val hasLocated = st.findings.any { it.geo != null }
    LaunchedEffect(hasLocated) { if (hasLocated && main.hasLocationPermission()) main.refreshLocation() }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { main.refreshLocation() }
    val actions = ResultsActions(
        onBack = onBack, onExport = { onExport(st) }, onPin = { onPin(st) }, onReport = { onReport(st) }, onFinding = if (wide) { f -> if (st.selectedId == f.id) onFinding(f) else vm.select(f.id) } else onFinding, onOpenFinding = onFinding, onSelect = { f -> if (st.selectedId == f.id) onFinding(f) else vm.select(f.id) }, onClearSelection = { vm.select(null) }, onHandled = vm::setHandled, onFilter = vm::setFilter, onSort = vm::setSort,
        onAckRegistry = vm::ackRegistry, onShowFindings = { showFindings = true }, onStop = vm::stop, onPivot = onPivot,
        onRequestLocation = {
            if (main.hasLocationPermission()) main.refreshLocation()
            else permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        },
    )
    val tabletop = vip.ringling.recon.ui.signal.LocalTabletop.current
    when {
        st.input?.type == InputType.PERSON -> PersonScreen(st, actions, contentPadding = pad)
        st.input?.type == InputType.ZIP || st.input?.type == InputType.ADDRESS -> AreaScreen(st, actions, contentPadding = pad, split = wide && !tabletop)
        wide && tabletop -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            ExposureScreen(st, actions, Modifier.weight(1f), pad, showFindingsButton = false)
            FindingsList(st, actions, Modifier.weight(1f), PaddingValues(bottom = pad.calculateBottomPadding()), showHeader = false, compact = true)
        }
        wide -> ExposureWide(st, actions, pad)
        showFindings -> { BackHandler { showFindings = false }; FindingsList(st, actions, contentPadding = pad, onBack = { showFindings = false }) }
        else -> ExposureScreen(st, actions, contentPadding = pad)
    }
}

/** Unfolded / tablet exposure map: graph + timeline + fix-first in the centre, 320dp findings pane (All/Exposed/Info) with the selected finding on the right. */
@Composable
fun ExposureWide(st: ResultsUiState, actions: ResultsActions, pad: PaddingValues) {
    val sig = vip.ringling.recon.ui.theme.ReconTheme.signal
    androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxSize()) {
        ExposureScreen(st, actions, Modifier.weight(1f), pad, showFindingsButton = false)
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(320.dp).fillMaxSize().background(sig.panel).border(1.dp, sig.hairline)) {
            val selected = st.findings.firstOrNull { it.id == st.selectedId }
            selected?.let { f ->
                SelectedFindingCard(f, vip.ringling.recon.data.HandledRepository.keyOf(f) in st.handled, actions.onOpenFinding, actions.onPin, "Open details", androidx.compose.ui.Modifier.padding(start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 8.dp), onHandled = { actions.onHandled(f, it) })
            }
            FindingsList(st, actions, Modifier.weight(1f), PaddingValues(top = if (selected == null) pad.calculateTopPadding() else 0.dp, bottom = pad.calculateBottomPadding()), showHeader = false, compact = true)
        }
    }
}
