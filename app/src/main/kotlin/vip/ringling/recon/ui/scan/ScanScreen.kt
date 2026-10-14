package vip.ringling.recon.ui.scan

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ScanSession
import vip.ringling.recon.ui.components.icon
import vip.ringling.recon.ui.components.severityColor

data class Readiness(val label: String, val ok: Boolean)

data class ScanUiState(
    val session: ScanSession? = null,
    val enabled: Set<ModuleId> = ModuleId.entries.toSet(),
    val readiness: Map<ModuleId, Readiness> = defaultReadiness,
)

val defaultReadiness = mapOf(
    ModuleId.BREACH to Readiness("HIBP key missing · LeakCheck free tier", false),
    ModuleId.IDENTITY to Readiness("Ready · curated site list", true),
    ModuleId.DOMAIN to Readiness("Ready · passive only", true),
    ModuleId.REGISTRY to Readiness("Ready · live ZIP lookup", true),
)

@Composable
fun ScanScreen(
    state: ScanUiState,
    onToggle: (ModuleId, Boolean) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 700.dp
        val pad = PaddingValues(
            top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        )
        if (wide) {
            Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Header(); RadarPanel(state); ActivityLog(state.session)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(end = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ModuleControls(state, onToggle, onRescan)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item { Header() }
                item { RadarPanel(state) }
                item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { ModuleControls(state, onToggle, onRescan) } }
                item { ActivityLog(state.session) }
            }
        }
    }
}

@Composable
private fun Header() {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text("SCAN", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Text("Control room", style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
    }
}

@Composable
private fun RadarPanel(state: ScanUiState) {
    val cs = MaterialTheme.colorScheme
    val s = state.session
    val active = s?.running == true
    val shownFindings = s?.findings.orEmpty()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(260.dp).semantics { contentDescription = if (active) "Scanning, ${shownFindings.size} findings so far" else "Radar idle" }) {
            Radar(shownFindings, active, Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    state.enabled.size.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.displayLarge.copy(fontSize = androidx.compose.ui.unit.TextUnit(64f, androidx.compose.ui.unit.TextUnitType.Sp)),
                    color = cs.onSurface,
                )
                Text(if (active) "MODULES RUNNING" else "MODULES ARMED", style = MaterialTheme.typography.labelSmall, color = if (active) cs.primary else cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Radar(findings: List<Finding>, active: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "radar")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (active) 2600 else 9000, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    val ring = cs.outlineVariant
    val primary = cs.primary
    val colors = findings.map { severityColor(it.severity) }
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        for (i in 1..4) drawCircle(ring, r * i / 4f, c, style = Stroke(1.5f))
        drawLine(ring, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
        drawLine(ring, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
        val alpha = if (active) 0.55f else 0.18f
        drawArc(
            brush = Brush.sweepGradient(
                0f to Color.Transparent, 0.82f to Color.Transparent, 1f to primary.copy(alpha = alpha),
                center = c,
            ),
            startAngle = angle - 90f, sweepAngle = 90f, useCenter = true,
            topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
        )
        // Blips: stable position per finding id so they don't jump as new ones stream in.
        findings.forEachIndexed { i, f ->
            val h = f.id.hashCode()
            val a = Math.toRadians(((h and 0x7fffffff) % 360).toDouble())
            val d = r * (0.38f + ((h ushr 9) and 0x7fffffff) % 55 / 100f)
            val p = Offset(c.x + (cos(a) * d).toFloat(), c.y + (sin(a) * d).toFloat())
            drawCircle(colors[i].copy(alpha = 0.25f), 16f, p)
            drawCircle(colors[i], 7f, p)
        }
    }
}

@Composable
private fun ModuleControls(state: ScanUiState, onToggle: (ModuleId, Boolean) -> Unit, onRescan: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text("MODULES", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
    ModuleId.entries.forEach { m ->
        val on = m in state.enabled
        val ready = state.readiness[m]
        Surface(
            shape = MaterialTheme.shapes.large, color = cs.surfaceContainer,
            border = BorderStroke(1.dp, if (on) cs.primary.copy(alpha = 0.45f) else cs.outlineVariant.copy(alpha = 0.6f)),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) cs.primaryContainer else cs.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(m.icon(), null, tint = if (on) cs.onPrimaryContainer else cs.onSurfaceVariant)
                }
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    if (ready != null) {
                        Text(
                            ready.label, style = MaterialTheme.typography.bodySmall,
                            color = if (ready.ok) cs.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Switch(checked = on, onCheckedChange = { onToggle(m, it) }, modifier = Modifier.semantics { contentDescription = "Enable ${m.label}" })
            }
        }
    }
    if (state.session != null) {
        Button(onClick = onRescan, enabled = state.session.running.not(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Rounded.Refresh, null); Text("Rescan ${state.session.input.raw}", Modifier.padding(start = 8.dp), maxLines = 1)
        }
    }
}

@Composable
private fun ActivityLog(session: ScanSession?) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("ACTIVITY", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant.copy(alpha = 0.5f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val lines = session?.findings?.takeLast(7)?.reversed().orEmpty()
                if (lines.isEmpty()) {
                    Text("No scan running. Start one from Home.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                lines.forEach { f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("▸", color = severityColor(f.severity), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                        Text(
                            "${f.module.name.lowercase()}  ${f.title}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface, maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
