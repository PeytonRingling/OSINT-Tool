package vip.ringling.recon.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanSession
import vip.ringling.recon.ui.components.ReconSearchField
import vip.ringling.recon.ui.components.SeverityChip
import vip.ringling.recon.ui.components.TypeChipRow
import vip.ringling.recon.ui.components.icon
import vip.ringling.recon.domain.Severity

data class HomeUiState(
    val query: String = "",
    val override: InputType? = null,
    val detected: InputType? = null,
    val session: ScanSession? = null,
    val recents: List<ScanRecord> = emptyList(),
    val registryAck: Boolean = false,
    val selectedId: String? = null,
    val caseCount: Int = 0,
) {
    val effective: InputType? get() = override ?: detected
}

data class HomeActions(
    val onQueryChange: (String) -> Unit = {},
    val onOverride: (InputType?) -> Unit = {},
    val onSubmit: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onFindingClick: (Finding) -> Unit = {},
    val onAckRegistry: () -> Unit = {},
    val onRecent: (ScanRecord) -> Unit = {},
    val onExportAll: () -> Unit = {},
    val onRequestLocation: () -> Unit = {},
    val onPickPhoto: () -> Unit = {},
    val onOpenCase: () -> Unit = {},
)

@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
) {
    val session = state.session
    LazyColumn(
        modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "hero") { Hero(compact = session != null) }
        item(key = "search") {
            ReconSearchField(
                value = state.query,
                onValueChange = actions.onQueryChange,
                effectiveType = state.effective,
                running = session?.running == true,
                onSubmit = actions.onSubmit,
                onStop = actions.onStop,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item(key = "chips") {
            TypeChipRow(auto = state.detected, override = state.override, onOverride = actions.onOverride)
        }
        item(key = "photo") {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.AssistChip(
                    onClick = actions.onPickPhoto,
                    label = { Text("Analyze a photo") },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.Image, null, Modifier.size(18.dp)) },
                )
                androidx.compose.material3.AssistChip(
                    onClick = actions.onOpenCase,
                    label = { Text(if (state.caseCount > 0) "Case (${state.caseCount})" else "Case") },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Outlined.BookmarkBorder, null, Modifier.size(18.dp)) },
                )
            }
        }
        if (session == null) {
            item(key = "modules") { ModuleTiles() }
            if (state.recents.isNotEmpty()) {
                item(key = "recent-h") {
                    Text(
                        "RECENT", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp).padding(top = 8.dp),
                    )
                }
                items(state.recents.take(3), key = { "recent-${it.id}" }) { r -> RecentRow(r) { actions.onRecent(r) } }
            }
        } else {
            scanResults(
                input = session.input,
                findings = session.findings,
                statuses = session.statuses,
                errors = session.errors,
                selectedId = state.selectedId,
                registryAck = state.registryAck,
                onAckRegistry = actions.onAckRegistry,
                onFindingClick = actions.onFindingClick,
                onExportAll = actions.onExportAll,
                onRequestLocation = actions.onRequestLocation,
            )
        }
    }
}

@Composable
private fun Hero(compact: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(cs.primary, androidx.compose.foundation.shape.CircleShape))
            Text("RECON", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        }
        if (!compact) {
            Text(
                buildAnnotatedString {
                    append("Trace\n")
                    withStyle(SpanStyle(color = cs.primary)) { append("anything.") }
                },
                style = MaterialTheme.typography.displayMedium,
                color = cs.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                "One field. Email, phone, handle, domain or ZIP — the right modules run in parallel.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ModuleTiles() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ModuleId.entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    Surface(
                        Modifier.weight(1f).heightIn(min = 96.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = cs.surfaceContainer,
                        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(m.icon(), null, tint = cs.primary)
                            Column {
                                Text(m.label, style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
                                Text(m.tagline, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(r: ScanRecord, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val worst = r.findings.maxByOrNull { it.severity.ordinal }?.severity ?: Severity.CLEAN
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(r.input.type.icon(), null, tint = cs.onSurfaceVariant)
        Text(r.input.raw, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SeverityChip(worst)
    }
}
