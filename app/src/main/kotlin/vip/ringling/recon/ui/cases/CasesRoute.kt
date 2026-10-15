package vip.ringling.recon.ui.cases

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.theme.ReconTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vip.ringling.recon.data.PdfWriter
import vip.ringling.recon.domain.CaseReport
import vip.ringling.recon.domain.CaseTargetState
import vip.ringling.recon.domain.InputDetector
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.ui.ReconActions

/** Cases list (phone). Large screens use [vip.ringling.recon.ui.cases.CasesWorkspace]. */
@Composable
fun CasesRoute(pad: PaddingValues, newCaseSignal: Int, onOpen: (String) -> Unit) {
    val vm: CasesViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    var showNew by rememberSaveable { mutableStateOf(false) }
    var showWatch by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberNotificationRequest()
    var handledSignal by rememberSaveable { mutableStateOf(newCaseSignal) }
    LaunchedEffect(newCaseSignal) { if (newCaseSignal != handledSignal) { handledSignal = newCaseSignal; showNew = true } }
    if (showNew) NewCaseDialog(onDismiss = { showNew = false }, onCreate = { name -> showNew = false; vm.create(name, null, onOpen) })
    if (showWatch) AddWatchDialog(onDismiss = { showWatch = false }, onAdd = { vm.addWatch(it); askNotifications(); showWatch = false })
    CasesScreen(st, CasesActions(onOpen = { onOpen(it.file.id) }, onNewCase = { showNew = true }, onAddWatch = { showWatch = true }, onRemoveWatch = vm::removeWatch), contentPadding = pad)
}

@Composable
fun CaseDetailRoute(
    pad: PaddingValues, actions: ReconActions, onBack: () -> Unit, onChanges: (String) -> Unit,
    onOpenScan: (CaseTargetState) -> Unit, onRun: (String) -> Unit, wide: Boolean = false,
) {
    val vm: CaseDetailViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val c = st.case ?: return
    var dialog by rememberSaveable { mutableStateOf("") }
    val stamp = remember { java.time.LocalDate.now().toString() }
    fun report() = CaseReport.markdown(c.file, c.states, c.events, c.pivots, stamp)
    when (dialog) {
        "add" -> AddTargetDialog({ dialog = "" }, vm::addTarget)
        "rename" -> RenameCaseDialog(c.file.name, { dialog = "" }) { vm.rename(it); dialog = "" }
        "delete" -> ConfirmDialog("Delete ${c.file.name}?", "Removes the case, its targets and notes. The scans themselves stay in the Log.", "Delete", { dialog = "" }) { dialog = ""; vm.delete(onBack) }
    }
    CaseDetailScreen(
        c, st.tab,
        CaseDetailActions(
            onBack = onBack, onTab = { t -> if (t == CaseTab.CHANGES && !wide) onChanges(vm.caseId) else vm.setTab(t) }, onOpenTarget = onOpenScan, onAddTarget = { dialog = "add" },
            onRemoveTarget = vm::removeTarget,
            onRunPivot = { p ->
                InputDetector.detect(p.query)?.let { vm.addTarget(it, InputDetector.normalize(p.query, it)) }
                onRun(p.query)
            },
            onAddNote = vm::addNote, onDeleteNote = { vm.deleteNote(it.id) },
            onSaveMarkdown = { actions.saveFile("recon-case-${c.file.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-$stamp.md", report().toByteArray()) },
            onSavePdf = { actions.saveFile("recon-case-${c.file.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}-$stamp.pdf", PdfWriter.fromMarkdown(report())) },
            onShare = { actions.shareText("Recon case report", report()) },
            onRename = { dialog = "rename" }, onDelete = { dialog = "delete" }, onChanges = { onChanges(vm.caseId) },
        ),
        contentPadding = pad,
        graphHeight = if (wide) 380.dp else 300.dp,
        changesContent = if (wide) {
            {
                var filter by rememberSaveable { mutableStateOf(ChangeFilter.ALL) }
                Column {
                    ExposurePanel(c)
                    SegmentedControl(ChangeFilter.entries.map { it.label }, filter.ordinal, { filter = ChangeFilter.entries[it] }, Modifier.padding(top = 12.dp))
                    ChangeTimeline(c.events.filtered(filter), System.currentTimeMillis())
                }
            }
        } else null,
    )
}

@Composable
fun CaseTimelineRoute(pad: PaddingValues, actions: ReconActions, onBack: () -> Unit) {
    val vm: CaseDetailViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val c = st.case ?: return
    var filter by rememberSaveable { mutableStateOf(ChangeFilter.ALL) }
    val stamp = remember { java.time.LocalDate.now().toString() }
    CaseTimelineScreen(
        c, filter, { filter = it }, onBack,
        onExport = { actions.shareText("Recon case changes", CaseReport.markdown(c.file, c.states, c.events, c.pivots, stamp)) },
        now = System.currentTimeMillis(), contentPadding = pad,
    )
}

/** Cases on large screens: 280dp list pane + the selected case's canvas (link graph, then Targets/Pivots/Notes/Changes below). Selection lives in the route ("cases/{id}"). */
@Composable
fun CasesWorkspace(pad: PaddingValues, newCaseSignal: Int, selectedId: String?, onSelect: (String) -> Unit, detail: @Composable () -> Unit) {
    val vm: CasesViewModel = hiltViewModel()
    val st by vm.state.collectAsStateWithLifecycle()
    val sig = ReconTheme.signal
    var showNew by rememberSaveable { mutableStateOf(false) }
    var showWatch by rememberSaveable { mutableStateOf(false) }
    val askNotifications = rememberNotificationRequest()
    var handledSignal by rememberSaveable { mutableStateOf(newCaseSignal) }
    LaunchedEffect(newCaseSignal) { if (newCaseSignal != handledSignal) { handledSignal = newCaseSignal; showNew = true } }
    if (showNew) NewCaseDialog(onDismiss = { showNew = false }, onCreate = { name -> showNew = false; vm.create(name, null, onSelect) })
    if (showWatch) AddWatchDialog(onDismiss = { showWatch = false }, onAdd = { vm.addWatch(it); askNotifications(); showWatch = false })
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(280.dp).fillMaxSize().border(1.dp, sig.hairline)) {
            CasesScreen(st, CasesActions(onOpen = { onSelect(it.file.id) }, onNewCase = { showNew = true }, onAddWatch = { showWatch = true }, onRemoveWatch = vm::removeWatch), contentPadding = pad, selectedId = selectedId, compact = true)
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            if (selectedId == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Select a case", color = sig.muted) } else detail()
        }
    }
}
