package vip.ringling.recon.ui.cases

import androidx.compose.foundation.layout.PaddingValues
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
    var handledSignal by rememberSaveable { mutableStateOf(newCaseSignal) }
    LaunchedEffect(newCaseSignal) { if (newCaseSignal != handledSignal) { handledSignal = newCaseSignal; showNew = true } }
    if (showNew) NewCaseDialog(onDismiss = { showNew = false }, onCreate = { name -> showNew = false; vm.create(name, null, onOpen) })
    if (showWatch) AddWatchDialog(onDismiss = { showWatch = false }, onAdd = { vm.addWatch(it); showWatch = false })
    CasesScreen(st, CasesActions(onOpen = { onOpen(it.file.id) }, onNewCase = { showNew = true }, onAddWatch = { showWatch = true }, onRemoveWatch = vm::removeWatch), contentPadding = pad)
}

@Composable
fun CaseDetailRoute(
    pad: PaddingValues, actions: ReconActions, onBack: () -> Unit, onChanges: (String) -> Unit,
    onOpenScan: (CaseTargetState) -> Unit, onRun: (String) -> Unit,
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
            onBack = onBack, onTab = { t -> if (t == CaseTab.CHANGES) onChanges(vm.caseId) else vm.setTab(t) }, onOpenTarget = onOpenScan, onAddTarget = { dialog = "add" },
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
