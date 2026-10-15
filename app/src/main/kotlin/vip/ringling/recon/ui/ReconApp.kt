package vip.ringling.recon.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.ui.components.LocalSharedScopes
import vip.ringling.recon.ui.components.LocalUserLocation
import vip.ringling.recon.ui.map.LocalMapLayer
import vip.ringling.recon.ui.map.MapLayerState
import vip.ringling.recon.ui.components.SharedScopes
import vip.ringling.recon.ui.detail.EmptyDetailPane
import vip.ringling.recon.ui.detail.FindingDetail
import vip.ringling.recon.data.update.UpdateState
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.ui.history.HistoryScreen
import vip.ringling.recon.ui.history.HistoryUiState
import vip.ringling.recon.ui.history.HistoryViewModel
import vip.ringling.recon.ui.home.HomeActions
import vip.ringling.recon.ui.home.HomeScreen
import vip.ringling.recon.ui.home.HomeUiState
import vip.ringling.recon.ui.home.HomeViewModel
import vip.ringling.recon.ui.home.scanResults
import vip.ringling.recon.ui.scan.ScanScreen
import vip.ringling.recon.ui.scan.ScanViewModel
import vip.ringling.recon.ui.settings.SettingsActions
import vip.ringling.recon.ui.settings.SettingsScreen
import vip.ringling.recon.ui.settings.SettingsViewModel
import vip.ringling.recon.ui.theme.ReconMotion
import vip.ringling.recon.ui.theme.ReconTheme

enum class Tab(val route: String, val label: String, val icon: ImageVector, val selected: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home, Icons.Rounded.Home),
    SCAN("scan", "Scan", Icons.Outlined.Radar, Icons.Rounded.Radar),
    HISTORY("history", "History", Icons.Outlined.History, Icons.Rounded.History),
    SETTINGS("settings", "Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@Composable
fun ReconRoot(vm: MainViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    ReconTheme(settings.themeMode, settings.dynamicColor) {
        val haptic = LocalHapticFeedback.current
        LaunchedEffect(Unit) {
            vm.scanCompleted.collect {
                if (vm.settings.value.haptics) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
        val me by vm.me.collectAsStateWithLifecycle()
        CompositionLocalProvider(LocalUserLocation provides me, LocalMapLayer provides MapLayerState(settings.mapSatellite, vm::setMapSatellite)) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { ReconApp() }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReconApp() {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val main: MainViewModel = hiltViewModel(androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity)
    val actions = rememberReconActions(snackbar, onPivot = { q -> main.requestSearch(q); nav.goTab(Tab.HOME) }, onAddToCase = main::addToCase)
    val updateState by main.update.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { main.checkForUpdateQuietly() }
    LaunchedEffect((updateState as? UpdateState.Available)?.info?.build) {
        val u = updateState as? UpdateState.Available ?: return@LaunchedEffect
        val r = snackbar.showSnackbar("Update available: build ${u.info.build}", actionLabel = "View", withDismissAction = true)
        if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) nav.navigate(Tab.SETTINGS.route) { launchSingleTop = true }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = Tab.entries.firstOrNull { it.route == currentRoute }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val expanded = width >= 840.dp
        val useRail = width >= 600.dp
        val content: @Composable (PaddingValues) -> Unit = { pad ->
            SharedTransitionLayout {
                NavHost(
                    nav, startDestination = Tab.HOME.route,
                    enterTransition = { fadeIn(tween(220)) },
                    exitTransition = { fadeOut(tween(160)) },
                    popEnterTransition = { fadeIn(tween(220)) },
                    popExitTransition = { fadeOut(tween(160)) },
                ) {
                    composable(Tab.HOME.route) {
                        CompositionLocalProvider(LocalSharedScopes provides if (expanded) null else SharedScopes(this@SharedTransitionLayout, this)) {
                            HomeRoute(nav, expanded, actions, pad)
                        }
                    }
                    composable(Tab.SCAN.route) {
                        val vm: ScanViewModel = hiltViewModel()
                        val st by vm.state.collectAsStateWithLifecycle()
                        ScanScreen(st, vm::toggle, vm::rescan, contentPadding = pad)
                    }
                    composable(Tab.HISTORY.route) {
                        CompositionLocalProvider(LocalSharedScopes provides if (expanded) null else SharedScopes(this@SharedTransitionLayout, this)) {
                            HistoryRoute(nav, expanded, actions, pad)
                        }
                    }
                    composable(Tab.SETTINGS.route) {
                        val vm: SettingsViewModel = hiltViewModel()
                        val st by vm.state.collectAsStateWithLifecycle()
                        SettingsScreen(
                            st,
                            SettingsActions(vm::setTheme, vm::setDynamic, vm::setHaptics, vm::setKey, vm::clearHistory, vm::checkUpdate, vm::installUpdate, vm::dismissUpdateError, vm::setProxyMode, vm::setProxyHost, vm::setProxyPort, vm::setAppLock),
                            contentPadding = pad,
                        )
                    }
                    composable("case") {
                        val vm: vip.ringling.recon.ui.casefile.CaseViewModel = hiltViewModel()
                        val st by vm.state.collectAsStateWithLifecycle()
                        val stamp = remember { java.time.LocalDate.now().toString() }
                        vip.ringling.recon.ui.casefile.CaseScreen(
                            st,
                            vip.ringling.recon.ui.casefile.CaseActions(
                                onBack = { nav.popBackStack() },
                                onNote = vm::setNote, onRemove = vm::remove,
                                onSearch = { q -> main.requestSearch(q); nav.goTab(Tab.HOME) },
                                onSaveMarkdown = { actions.saveFile("recon-case-$stamp.md", vip.ringling.recon.domain.CaseReport.markdown(st.items, stamp).toByteArray()) },
                                onSavePdf = { actions.saveFile("recon-case-$stamp.pdf", vip.ringling.recon.data.PdfWriter.fromMarkdown(vip.ringling.recon.domain.CaseReport.markdown(st.items, stamp))) },
                                onShare = { actions.shareText("Recon case report", vip.ringling.recon.domain.CaseReport.markdown(st.items, stamp)) },
                            ),
                            contentPadding = pad,
                        )
                    }
                    composable("finding/{id}") {
                        val vm: DetailViewModel = hiltViewModel()
                        val f by vm.finding.collectAsStateWithLifecycle()
                        CompositionLocalProvider(LocalSharedScopes provides SharedScopes(this@SharedTransitionLayout, this)) {
                            f?.let { FindingDetail(it, actions.detail, onBack = { nav.popBackStack() }, contentPadding = pad) }
                        }
                    }
                    composable("record/{id}") {
                        val vm: RecordViewModel = hiltViewModel()
                        val r by vm.record.collectAsStateWithLifecycle()
                        CompositionLocalProvider(LocalSharedScopes provides SharedScopes(this@SharedTransitionLayout, this)) {
                            r?.let { rec ->
                                LazyColumn(Modifier.fillMaxSize(), contentPadding = pad.listInsets(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                                    scanResults(
                                        rec.input, rec.findings, recordStatuses(rec), emptyMap(), null, true, {},
                                        { nav.navigate("finding/${it.id}") }, { actions.exportScan(rec.input, rec.findings) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        AppShell(
            selected = topLevel, useRail = useRail, snackbar = snackbar,
            onTab = { nav.goTab(it) }, content = content,
        )
    }
}

/** Bottom bar on phones, rail from 600dp up. Stateless so screenshot tests render the real thing. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppShell(
    selected: Tab?,
    useRail: Boolean,
    snackbar: SnackbarHostState,
    onTab: (Tab) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (useRail) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                Box(Modifier.weight(1f))
                Tab.entries.forEach { tab ->
                    val sel = tab == selected || (selected == null && tab == Tab.HOME)
                    NavigationRailItem(
                        selected = sel, onClick = { onTab(tab) },
                        icon = { Icon(if (sel) tab.selected else tab.icon, null) }, label = { Text(tab.label) },
                    )
                }
                Box(Modifier.weight(1f))
            }
            Scaffold(
                Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
            ) { content(it) }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (selected != null) {
                    ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Tab.entries.forEach { tab ->
                            val sel = tab == selected
                            ShortNavigationBarItem(
                                selected = sel, onClick = { onTab(tab) },
                                icon = { Icon(if (sel) tab.selected else tab.icon, null) }, label = { Text(tab.label) },
                            )
                        }
                    }
                }
            },
        ) { content(it) }
    }
}

private fun NavHostController.goTab(tab: Tab) = navigate(tab.route) {
    popUpTo(Tab.HOME.route) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

fun recordStatuses(r: vip.ringling.recon.domain.ScanRecord) =
    r.findings.map { it.module }.distinct().sortedBy { it.ordinal }.associateWith { ModuleStatus.DONE }

@Composable
private fun HomeRoute(nav: NavHostController, expanded: Boolean, actions: ReconActions, pad: PaddingValues) {
    val vm: HomeViewModel = hiltViewModel()
    val main: MainViewModel = hiltViewModel(androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity)
    val st by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selectedFinding.collectAsStateWithLifecycle(null)
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { main.refreshLocation() }
    // Registry results on screen + permission already granted → quietly fetch a position for distances.
    val hasRegistry = st.session?.findings?.any { it.geo != null } == true
    androidx.compose.runtime.LaunchedEffect(hasRegistry) { if (hasRegistry && main.hasLocationPermission()) main.refreshLocation() }
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.analyzePhoto(it.toString()) } }
    val ha = HomeActions(
        onPickPhoto = { pickPhoto.launch("image/*") }, onOpenCase = { nav.navigate("case") { launchSingleTop = true } },
        onQueryChange = vm::onQueryChange, onOverride = vm::onOverride, onSubmit = vm::submit, onStop = vm::stop,
        onFindingClick = { f -> if (expanded) vm.select(f.id) else nav.navigate("finding/${f.id}") },
        onAckRegistry = vm::ackRegistry, onRecent = vm::reuse,
        onRequestLocation = {
            if (main.hasLocationPermission()) main.refreshLocation()
            else permission.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onExportAll = { st.session?.let { actions.exportScan(it.input, it.findings) } },
    )
    HomePane(st, ha, selected, actions, expanded, pad)
}

@Composable
fun HomePane(st: HomeUiState, ha: HomeActions, selected: Finding?, actions: ReconActions, expanded: Boolean, pad: PaddingValues) {
    if (!expanded) {
        HomeScreen(st, ha, contentPadding = pad)
    } else {
        ListDetail(
            list = { HomeScreen(st, ha, contentPadding = pad) },
            detail = {
                AnimatedContent(selected, transitionSpec = paneTransition(), label = "pane") { f ->
                    if (f == null) EmptyDetailPane() else FindingDetail(f, actions.detail, contentPadding = pad)
                }
            },
        )
    }
}

@Composable
private fun HistoryRoute(nav: NavHostController, expanded: Boolean, actions: ReconActions, pad: PaddingValues) {
    val vm: HistoryViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val rec by vm.selectedRecord.collectAsStateWithLifecycle(null)
    var findingId by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    if (!expanded) {
        HistoryScreen(st, { nav.navigate("record/${it.id}") }, vm::delete, vm::clearAll, contentPadding = pad)
    } else {
        HistoryPane(
            st, rec, findingId, actions, pad,
            onOpen = { findingId = null; vm.open(it) }, onDelete = vm::delete, onClearAll = vm::clearAll,
            onFinding = { findingId = it },
        )
    }
}

@Composable
fun HistoryPane(
    st: HistoryUiState, rec: ScanRecord?, findingId: String?, actions: ReconActions, pad: PaddingValues,
    onOpen: (ScanRecord) -> Unit, onDelete: (ScanRecord) -> Unit, onClearAll: () -> Unit, onFinding: (String?) -> Unit,
) {
    ListDetail(
        list = { HistoryScreen(st, onOpen, onDelete, onClearAll, contentPadding = pad) },
        detail = {
            AnimatedContent(rec to findingId, transitionSpec = paneTransition(), label = "pane") { (r, fid) ->
                val f = r?.findings?.firstOrNull { it.id == fid }
                when {
                    r == null -> EmptyDetailPane()
                    f != null -> FindingDetail(f, actions.detail, onBack = { onFinding(null) }, contentPadding = pad)
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = pad.listInsets(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)) {
                        scanResults(r.input, r.findings, recordStatuses(r), emptyMap(), null, true, {}, { onFinding(it.id) }, { actions.exportScan(r.input, r.findings) })
                    }
                }
            }
        },
    )
}

private fun <S> paneTransition(): androidx.compose.animation.AnimatedContentTransitionScope<S>.() -> androidx.compose.animation.ContentTransform = {
    (fadeIn(ReconMotion.effects()) + scaleIn(ReconMotion.spatial(), 0.96f)) togetherWith (fadeOut(tween(120)) + scaleOut(targetScale = 0.98f))
}

/** Two-pane layout for the unfolded Pixel Fold / tablets: fixed-width list, flexible detail. */
@Composable
fun ListDetail(list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(380.dp).fillMaxSize()) { list() }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { detail() }
        }
    }
}

private fun PaddingValues.listInsets() = PaddingValues(top = calculateTopPadding() + 12.dp, bottom = calculateBottomPadding() + 24.dp)
