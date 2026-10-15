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
import vip.ringling.recon.ui.settings.SettingsActions
import vip.ringling.recon.ui.settings.SettingsScreen
import vip.ringling.recon.ui.settings.SettingsViewModel
import vip.ringling.recon.ui.theme.ReconMotion
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.signal.Tab
import vip.ringling.recon.ui.signal.ReconBottomBar
import vip.ringling.recon.ui.signal.ReconRail

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
    val actions = rememberReconActions(snackbar, onPivot = { q -> main.requestSearch(q); nav.goTab(Tab.HOME) }, onAddToCase = main::addToCase, onRunPaid = main::runPaid)
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
    var showLoadout by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val radarVm: vip.ringling.recon.ui.radar.RadarViewModel = hiltViewModel(androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity)
    val radarState by radarVm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        radarVm.events.collect { e ->
            when (e) {
                vip.ringling.recon.ui.radar.RadarEvent.OpenLive -> nav.navigate("results") { launchSingleTop = true }
                is vip.ringling.recon.ui.radar.RadarEvent.OpenRecord -> nav.navigate("results/${e.id}")
            }
        }
    }
    if (showLoadout) {
        vip.ringling.recon.ui.radar.LoadoutSheet(radarState.enabled, radarState.autoPick, radarVm::toggle, radarVm::setAuto, onDismiss = { showLoadout = false })
    }

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
                    popExitTransition = { fadeOut(tween(90)) },
                ) {
                    composable(Tab.HOME.route) {
                        RadarRoute(nav, expanded, pad, onLoadout = { showLoadout = true })
                    }
                    composable("results") { ResultsEntry(nav, actions, pad) }
                    composable("results/{id}") { ResultsEntry(nav, actions, pad) }
                    composable(Tab.HISTORY.route) {
                        val vm: vip.ringling.recon.ui.log.LogViewModel = hiltViewModel()
                        val st by vm.state.collectAsStateWithLifecycle()
                        vip.ringling.recon.ui.log.LogScreen(
                            st,
                            vip.ringling.recon.ui.log.LogActions(
                                onMode = vm::setMode,
                                onOpenTarget = { t -> if (t.isArea) radarVm.rerun(vip.ringling.recon.domain.ScanInput(t.value, t.type)) else nav.navigate("results/${t.latest.id}") },
                                onOpenScan = { sn -> if (sn.isArea) radarVm.rerun(vip.ringling.recon.domain.ScanInput(sn.value, sn.type)) else nav.navigate("results/${sn.id}") },
                                onDelete = { vm.deleteTarget(it) }, onClearAll = vm::clearAll,
                            ),
                            contentPadding = pad,
                        )
                    }
                    composable(Tab.SETTINGS.route) {
                        val vm: SettingsViewModel = hiltViewModel()
                        val st by vm.state.collectAsStateWithLifecycle()
                        var pendingWatch by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
                        val notifPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
                            pendingWatch?.let(vm::addWatch); pendingWatch = null
                        }
                        val watchAdd: (String) -> Unit = { email ->
                            pendingWatch = email
                            if (android.os.Build.VERSION.SDK_INT >= 33) notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else { vm.addWatch(email); pendingWatch = null }
                        }
                        SettingsScreen(
                            st,
                            SettingsActions(vm::setTheme, vm::setDynamic, vm::setHaptics, vm::setKey, vm::clearHistory, vm::checkUpdate, vm::installUpdate, vm::dismissUpdateError, vm::setProxyMode, vm::setProxyHost, vm::setProxyPort, vm::setAppLock, watchAdd, vm::removeWatch),
                            contentPadding = pad,
                        )
                    }
                    composable(Tab.CASES.route) {
                        val vm: vip.ringling.recon.ui.casefile.CaseViewModel = hiltViewModel()
                        val st by vm.state.collectAsStateWithLifecycle()
                        val stamp = remember { java.time.LocalDate.now().toString() }
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { vip.ringling.recon.ui.casefile.CaseScreen(
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
                        ) }
                    }
                    composable("finding/{id}") {
                        val vm: DetailViewModel = hiltViewModel()
                        val f by vm.finding.collectAsStateWithLifecycle()
                        CompositionLocalProvider(LocalSharedScopes provides SharedScopes(this@SharedTransitionLayout, this)) {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                f?.let { FindingDetail(it, actions.detail, onBack = { nav.popBackStack() }, contentPadding = pad) }
                            }
                        }
                    }
                }
            }
        }

        AppShell(
            selected = topLevel, useRail = useRail, snackbar = snackbar,
            onTab = { nav.goTab(it) }, armed = radarState.armed, onLoadout = { showLoadout = true }, content = content,
        )
    }
}

/** Bottom bar on phones, 72dp rail from 600dp up. Stateless so screenshot tests render the real thing. */
@Composable
fun AppShell(
    selected: Tab?,
    useRail: Boolean,
    snackbar: SnackbarHostState,
    onTab: (Tab) -> Unit,
    armed: Int = 11,
    onLoadout: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    if (useRail) {
        Row(Modifier.fillMaxSize()) {
            ReconRail(selected ?: Tab.HOME, onTab, armed, 11, onLoadout)
            Scaffold(
                Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
            ) { content(it) }
        }
    } else {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (selected != null) ReconBottomBar(selected, onTab) },
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
private fun ResultsEntry(nav: NavHostController, actions: ReconActions, pad: PaddingValues) {
    vip.ringling.recon.ui.results.ResultsRoute(
        pad, onBack = { nav.popBackStack() }, onFinding = { f -> nav.navigate("finding/${f.id}") },
        onExport = { st -> st.input?.let { actions.exportScan(it, st.findings) } }, onPin = { },
    )
}

@Composable
private fun RadarRoute(nav: NavHostController, expanded: Boolean, pad: PaddingValues, onLoadout: () -> Unit) {
    val vm: vip.ringling.recon.ui.radar.RadarViewModel = hiltViewModel(androidx.compose.ui.platform.LocalContext.current as androidx.activity.ComponentActivity)
    val st by vm.state.collectAsStateWithLifecycle()
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri -> uri?.let { vm.analyzePhoto(it.toString()) } }
    vip.ringling.recon.ui.radar.RadarScreen(
        st,
        vip.ringling.recon.ui.radar.RadarActions(
            onQuery = vm::onQueryChange, onSubmit = vm::submit, onTarget = vm::open, onLoadout = onLoadout,
            onPhoto = { pickPhoto.launch("image/*") }, onNewCase = { nav.goTab(Tab.CASES) },
        ),
        contentPadding = pad, wide = expanded,
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
