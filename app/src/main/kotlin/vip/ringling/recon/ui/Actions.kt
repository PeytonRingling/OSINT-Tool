package vip.ringling.recon.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import vip.ringling.recon.domain.Export
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.ui.detail.DetailActions

/** Copy / share / export plumbing shared by every screen that shows findings. */
class ReconActions(
    val detail: DetailActions,
    val exportScan: (ScanInput, List<Finding>) -> Unit,
    /** Save arbitrary bytes through the system file picker (case reports). */
    val saveFile: (String, ByteArray) -> Unit = { _, _ -> },
    val shareText: (String, String) -> Unit = { _, _ -> },
)

@Composable
fun rememberReconActions(snackbar: SnackbarHostState, onPivot: (String) -> Unit = {}, onRunPaid: (Finding) -> Unit = {}): ReconActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val holder = remember { arrayOfNulls<String>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val text = holder[0]
        holder[0] = null
        if (uri != null && text != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }.isSuccess
            scope.launch { snackbar.showSnackbar(if (ok) "Exported JSON" else "Export failed") }
        }
    }
    fun export(name: String, json: String) { holder[0] = json; launcher.launch(name) }
    val bytesHolder = remember { arrayOfNulls<ByteArray>(1) }
    val bytesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val data = bytesHolder[0]
        bytesHolder[0] = null
        if (uri != null && data != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(data) } }.isSuccess
            scope.launch { snackbar.showSnackbar(if (ok) "Saved" else "Save failed") }
        }
    }

    val pivot by androidx.compose.runtime.rememberUpdatedState(onPivot)
    val runPaid by androidx.compose.runtime.rememberUpdatedState(onRunPaid)
    return remember(context) {
        ReconActions(
            detail = DetailActions(
                onCopy = { f ->
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Recon finding", Export.text(f)))
                    scope.launch { snackbar.showSnackbar("Copied") }
                },
                onShare = { f ->
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, Export.text(f)) }
                    context.startActivity(Intent.createChooser(send, "Share finding").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                onExport = { f -> export("recon-${f.module.name.lowercase()}-${f.id.takeLast(12)}.json", Export.finding(f)) },
                onEvidence = { f -> val now = System.currentTimeMillis(); bytesHolder[0] = vip.ringling.recon.data.PdfWriter.fromMarkdown(vip.ringling.recon.domain.Evidence.markdown(f, now)); bytesLauncher.launch(vip.ringling.recon.domain.Evidence.fileName(f, now)) },
                onPivot = { pivot(it) },
                onRunPaid = { runPaid(it) },
                onOpenMap = { g ->
                    // Coordinates only: nothing about who is at this address is passed to the maps app.
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, "geo:${g.lat},${g.lng}?q=${g.lat},${g.lng}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                onOpenUrl = { url ->
                    if (!LinkOpener.open(context, url)) {
                        LinkOpener.copy(context, url)
                        scope.launch { snackbar.showSnackbar("Couldn't open that link. It was copied instead.") }
                    }
                },
            ),
            exportScan = { input, findings -> export("recon-scan-${input.type.name.lowercase()}.json", Export.scan(input, findings)) },
            saveFile = { name, data -> bytesHolder[0] = data; bytesLauncher.launch(name) },
            shareText = { title, text ->
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text); putExtra(Intent.EXTRA_SUBJECT, title) }
                context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
    }
}
