package vip.ringling.recon.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ModuleStatus

import vip.ringling.recon.ui.theme.ReconMotion

/** One module's results: header with live status, streamed rows, skeletons while running. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ModuleCard(
    module: ModuleId,
    status: ModuleStatus,
    findings: List<Finding>,
    error: String?,
    selectedId: String?,
    onFindingClick: (Finding) -> Unit,
    modifier: Modifier = Modifier,
    gate: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier.fillMaxWidth().animateContentSize(ReconMotion.spatial()),
        shape = MaterialTheme.shapes.large,
        color = cs.surfaceContainer,
        border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column {
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(module.icon(), null, tint = cs.onSecondaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(module.label, style = MaterialTheme.typography.titleLarge, color = cs.onSurface)
                    val sub = when (status) {
                        ModuleStatus.QUEUED -> "Queued"
                        ModuleStatus.RUNNING -> if (findings.isEmpty()) "Querying…" else "Streaming · ${findings.size} so far"
                        ModuleStatus.DONE -> "${findings.size} result${if (findings.size == 1) "" else "s"}"
                        ModuleStatus.FAILED -> "Failed"
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                when (status) {
                    ModuleStatus.RUNNING, ModuleStatus.QUEUED ->
                        LoadingIndicator(Modifier.size(40.dp), color = cs.primary)
                    else -> {
                        val worst = findings.maxByOrNull { it.severity.ordinal }?.severity
                        if (worst != null) SeverityChip(worst)
                    }
                }
            }
            if (gate != null) {
                gate()
            } else {
                findings.forEachIndexed { i, f ->
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    FindingRow(f, selected = f.id == selectedId, onClick = { onFindingClick(f) })
                }
                if (status == ModuleStatus.RUNNING || status == ModuleStatus.QUEUED) {
                    repeat(if (findings.isEmpty()) 3 else 1) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                        SkeletonFindingRow()
                    }
                }
                if (status == ModuleStatus.FAILED) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Text(error ?: "Module failed", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun FindingRow(finding: Finding, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, ReconMotion.spatial()) }
    Row(
        modifier
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f); translationY = (1f - appear.value) * 24f }
            .sharedFinding(finding.id)
            .fillMaxWidth()
            .background(if (selected) cs.primaryContainer.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = "Open details", onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(severityColor(finding.severity)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(finding.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(finding.detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            finding.confidence?.let { ConfidenceMeter(it, Modifier.padding(top = 4.dp)) }
        }
        SeverityChip(finding.severity)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}

@Composable
fun LegalNotice(onAcknowledge: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Gavel, null, tint = ReconThemeInfo())
            Text("Legal notice", style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        }
        Text(
            "Registry results are public-record data under Idaho Code §74-120. Recon performs a live, per-query " +
                "lookup and never stores, caches or compiles registry data. Use it only for lawful personal-safety " +
                "purposes — harassment, retaliation or discrimination against listed individuals is unlawful.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
        Button(onClick = onAcknowledge, modifier = Modifier.heightIn(min = 48.dp)) { Text("I understand — show results") }
    }
}

@Composable
private fun ReconThemeInfo() = vip.ringling.recon.ui.theme.ReconTheme.severity.info
