package vip.ringling.recon.ui.casefile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import vip.ringling.recon.data.CaseItem

data class CaseActions(
    val onBack: () -> Unit = {},
    val onOpen: (CaseItem) -> Unit = {},
    val onNote: (String, String) -> Unit = { _, _ -> },
    val onRemove: (String) -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onSaveMarkdown: () -> Unit = {},
    val onSavePdf: () -> Unit = {},
    val onShare: () -> Unit = {},
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CaseScreen(state: CaseUiState, actions: CaseActions, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val cs = MaterialTheme.colorScheme
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.onBack) { Icon(Icons.Rounded.ArrowBack, "Back") }
                Column(Modifier.padding(start = 4.dp)) {
                    Text("CASE", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Text("${state.items.size} pinned", style = MaterialTheme.typography.headlineMedium, color = cs.onSurface)
                }
            }
        }
        if (state.items.isEmpty()) {
            item {
                Text(
                    "Pin findings from any result (\"Add to case\") to collect them here with notes, then export a report. Offender-registry records can't be pinned.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        } else {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = actions.onSaveMarkdown, Modifier.heightIn(min = 48.dp)) { Text("Save Markdown") }
                    FilledTonalButton(onClick = actions.onSavePdf, Modifier.heightIn(min = 48.dp)) { Text("Save PDF") }
                    FilledTonalButton(onClick = actions.onShare, Modifier.heightIn(min = 48.dp)) { Text("Share") }
                }
            }
            if (state.entities.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ENTITIES · tap to search", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.entities.forEach { (q, n) ->
                                AssistChip(onClick = { actions.onSearch(q) }, label = { Text(if (n > 1) "$q ×$n" else q) })
                            }
                        }
                    }
                }
            }
            items(state.items, key = { it.key }) { item -> CaseCard(item, actions) }
        }
    }
}

@Composable
private fun CaseCard(item: CaseItem, actions: CaseActions) {
    val cs = MaterialTheme.colorScheme
    var note by remember(item.key) { mutableStateOf(item.note) }
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = cs.surfaceContainer, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickableOpen { actions.onOpen(item) }) {
                    Text(item.finding.module.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    Text(item.finding.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Text(item.finding.detail.lineSequence().first(), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = { actions.onRemove(item.key) }) { Icon(Icons.Rounded.DeleteOutline, "Remove from case") }
            }
            Surface(shape = MaterialTheme.shapes.small, color = cs.surfaceContainerHigh, border = BorderStroke(1.dp, cs.outlineVariant)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (note.isEmpty()) Text("Add a note…", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    BasicTextField(
                        value = note, onValueChange = { note = it; actions.onNote(item.key, it) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Note for ${item.finding.title}" },
                    )
                }
            }
        }
    }
}

private fun Modifier.clickableOpen(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
