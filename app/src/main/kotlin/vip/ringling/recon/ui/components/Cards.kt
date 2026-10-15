package vip.ringling.recon.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.withStyle
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import vip.ringling.recon.domain.neutral
import vip.ringling.recon.domain.ModuleStatus
import vip.ringling.recon.domain.Severity

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
    extra: (@Composable () -> Unit)? = null,
    collapseAfter: Int? = null,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
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
                    val sub = if (gate != null) "Locked · read the notice first" else when (status) {
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
                        if (worst != null && gate == null && module != ModuleId.REGISTRY) SeverityChip(worst)
                    }
                }
            }
            if (gate != null) {
                gate()
            } else {
                if (module == ModuleId.REGISTRY) LegalNoticeBanner()
                extra?.invoke()
                val limit = if (collapseAfter != null && !showAll) collapseAfter else Int.MAX_VALUE
                findings.take(limit).forEachIndexed { i, f ->
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    if (f.id == "REGISTRY-summary") RegistrySummaryRow(f) else FindingRow(f, selected = f.id == selectedId, onClick = { onFindingClick(f) })
                }
                if (collapseAfter != null && findings.size > collapseAfter) {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = cs.outlineVariant.copy(alpha = 0.5f))
                    TextButton(onClick = { showAll = !showAll }, Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(if (showAll) "Show fewer" else "Show all ${findings.size} results")
                    }
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
    val distance = distanceLabel(finding)
    val hasPortrait = finding.module == ModuleId.REGISTRY
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
        if (hasPortrait) {
            RemoteImage(finding.imageUrl, "Photo of ${finding.title}", Modifier.size(56.dp).clip(MaterialTheme.shapes.small))
        } else if (finding.imageUrl != null) {
            RemoteImage(finding.imageUrl, "Logo", Modifier.size(40.dp).clip(MaterialTheme.shapes.extraSmall), contentScale = androidx.compose.ui.layout.ContentScale.Fit, placeholderIcon = false)
        } else {
            Box(Modifier.size(10.dp).clip(CircleShape).background(severityColor(finding.severity)))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(finding.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            // Breach rows summarise in their first line (count · date); the rest of the text (story, advice) lives in the detail screen. Two lines, never one.
            val summary = if (finding.module == ModuleId.BREACH) finding.detail.lineSequence().first() else finding.detail
            Text(summary, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (finding.tags.isNotEmpty()) {
                val hot = severityColor(Severity.EXPOSED)
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        finding.tags.forEachIndexed { i, t ->
                            if (i > 0) append(" · ")
                            if (t in finding.hotTags) withStyle(androidx.compose.ui.text.SpanStyle(color = hot)) { append(t) } else append(t)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
            }
            finding.confidence?.takeIf { !hasPortrait }?.let { ConfidenceMeter(it, Modifier.padding(top = 4.dp)) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!finding.neutral) SeverityChip(finding.severity)
            if (distance != null) Text(distance, style = MaterialTheme.typography.titleSmall, color = cs.primary)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = cs.onSurfaceVariant)
    }
}

const val LEGAL_NOTICE_TEXT = "Idaho's registry is an open record, but Idaho Code §74-120 bars using this inquiry for any commercial " +
    "purpose or for compiling a telephone or mailing list. Using registry information to harass or intimidate " +
    "anyone, or to commit a crime against a listed person, is prosecutable (Idaho Code §18-8326, §18-8413). " +
    "Recon looks up only what you search, live, and never stores or compiles registry data. Records may not " +
    "match the person you have in mind — verify before relying on them."

/** Text that shows two lines and expands in place, so nothing important is ever cut off with an ellipsis. */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 2, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium, key: String = text.take(24), initiallyExpanded: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    var expanded by rememberSaveable(key) { mutableStateOf(initiallyExpanded) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text, style = style, color = cs.onSurfaceVariant, maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            TextButton(onClick = { expanded = !expanded }, Modifier.heightIn(min = 48.dp)) { Text(if (expanded) "Show less" else "Read more") }
        }
    }
}

/** The legal notice stays on screen above registry results (collapsed to two lines, expandable) once it has been acknowledged. */
@Composable
fun LegalNoticeBanner(modifier: Modifier = Modifier, initiallyExpanded: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Gavel, null, tint = ReconThemeInfo())
            Text("Legal notice", style = MaterialTheme.typography.titleSmall, color = cs.onSurface)
        }
        ExpandableText(LEGAL_NOTICE_TEXT, key = "legal-banner", style = MaterialTheme.typography.bodySmall, initiallyExpanded = initiallyExpanded)
    }
}

/** Registry summary row: the "matches are by location only" disclaimer, fully readable. */
@Composable
fun RegistrySummaryRow(f: Finding, modifier: Modifier = Modifier, initiallyExpanded: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(f.title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        ExpandableText(f.detail, key = "registry-summary", initiallyExpanded = initiallyExpanded)
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
        Text(LEGAL_NOTICE_TEXT, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Button(onClick = onAcknowledge, modifier = Modifier.heightIn(min = 48.dp)) { Text("I understand — show results") }
    }
}

@Composable
private fun ReconThemeInfo() = vip.ringling.recon.ui.theme.ReconTheme.severity.info
