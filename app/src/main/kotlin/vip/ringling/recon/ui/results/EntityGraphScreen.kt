package vip.ringling.recon.ui.results

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.hypot
import vip.ringling.recon.domain.EntityGraph
import vip.ringling.recon.domain.EntityGraphModel
import vip.ringling.recon.domain.EntityNode
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SignalIconButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.rememberAnimationsEnabled
import vip.ringling.recon.ui.theme.MonoFont
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

/**
 * Full-screen, pannable and zoomable map of the leads a scan produced: target in the middle, the modules that found leads around it, the leads
 * (handles, e-mails, domains...) beyond. Tap a lead, then "Search this" to pivot into a new scan. Edges are only what the findings reported.
 */
@Composable
fun EntityGraphDialog(model: EntityGraphModel, onPivot: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val sig = ReconTheme.signal
        val measurer = rememberTextMeasurer()
        val animate = rememberAnimationsEnabled()
        val layout = remember(model) { EntityGraph.layout(model) }
        val byId = remember(model) { model.nodes.associateBy { it.id } }
        // The graph unfolds from the centre on open.
        val open = remember { Animatable(if (animate) 0f else 1f) }
        LaunchedEffect(Unit) { if (animate) open.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
        var zoom by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        var selected by remember { mutableStateOf<String?>(null) }
        Box(Modifier.fillMaxSize().background(sig.ground)) {
            Canvas(
                Modifier.fillMaxSize()
                    .semantics { contentDescription = "Lead graph: ${model.entityCount} leads from ${model.nodes.count { it.kind == EntityNode.Kind.MODULE }} sources" }
                    .pointerInput(Unit) { detectTransformGestures { centroid, p, z, _ ->
                        // Keep the point under the fingers fixed while zooming, then follow the drag.
                        val nz = (zoom * z).coerceIn(0.4f, 4f); val f = nz / zoom
                        val rel = centroid - Offset(size.width / 2f, size.height / 2f)
                        pan = rel - (rel - pan) * f + p
                        zoom = nz
                    } }
                    .pointerInput(model) { detectTapGestures { pt ->
                        val unit = minOf(size.width, size.height) / 5.2f * zoom
                        val c = Offset(size.width / 2f, size.height / 2f) + pan
                        selected = model.nodes.filter { it.kind != EntityNode.Kind.ROOT }.minByOrNull { n -> val q = layout[n.id]!!; hypot(c.x + q.first * unit - pt.x, c.y + q.second * unit - pt.y) }
                            ?.takeIf { n -> val q = layout[n.id]!!; hypot(c.x + q.first * unit - pt.x, c.y + q.second * unit - pt.y) <= 28.dp.toPx() }?.id
                    } },
            ) {
                val unit = minOf(size.width, size.height) / 5.2f * zoom
                val c = Offset(size.width / 2f, size.height / 2f) + pan
                fun at(id: String): Offset { val q = layout[id] ?: (0f to 0f); return Offset(c.x + q.first * unit * open.value, c.y + q.second * unit * open.value) }
                val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                drawCircle(sig.signal.copy(alpha = 0.12f), unit * open.value, c, style = Stroke(1f, pathEffect = dash))
                drawCircle(sig.signal.copy(alpha = 0.08f), unit * 1.9f * open.value, c, style = Stroke(1f, pathEffect = dash))
                model.edges.forEach { e ->
                    val hot = selected != null && (e.from == selected || e.to == selected)
                    drawLine(if (hot) sig.signal else sig.signal.copy(alpha = 0.3f), at(e.from), at(e.to), strokeWidth = if (hot) 2.5f else 1.2f)
                }
                model.nodes.forEach { n ->
                    val p = at(n.id); val isSel = n.id == selected
                    val (r, col) = when (n.kind) {
                        EntityNode.Kind.ROOT -> 30.dp.toPx() to sig.signal
                        EntityNode.Kind.MODULE -> 18.dp.toPx() to sig.info
                        EntityNode.Kind.ENTITY -> 11.dp.toPx() to sig.safe
                    }
                    val rr = r * (0.8f + 0.2f * zoom.coerceIn(0.6f, 1.5f))
                    drawCircle(col.copy(alpha = 0.16f), rr, p); drawCircle(col, rr, p, style = Stroke(if (isSel) 3.5f else 1.8f))
                    val label = if (n.kind == EntityNode.Kind.ROOT) n.label.take(22) else n.label
                    val t = measurer.measure(label, TextStyle(fontFamily = MonoFont, fontSize = (if (n.kind == EntityNode.Kind.ENTITY) 9f else 10f).sp, color = if (isSel) sig.text else sig.muted, letterSpacing = 0.6.sp))
                    if (open.value > 0.7f) drawText(t, topLeft = Offset(p.x - t.size.width / 2f, p.y + rr + 4.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SignalIconButton(Icons.Rounded.Close, "Close lead graph", onDismiss)
                Column(Modifier.weight(1f)) {
                    SignalLabel("Lead graph · ${model.entityCount} leads", small = true)
                    Text("Pinch to zoom, drag to move, tap a lead", color = sig.muted, style = ReconType.value.copy(fontSize = 11.sp))
                }
            }
            val sel = selected?.let(byId::get)
            if (sel != null) Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp).background(sig.panel, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SignalLabel(when (sel.kind) { EntityNode.Kind.MODULE -> "Source"; else -> "Lead" + (sel.type?.let { " · ${it.label}" } ?: "") }, small = true)
                Text(sel.query ?: sel.label, color = sig.text, style = ReconType.value)
                if (sel.query != null) SignalButton("Search this", { onPivot(sel.query) }, Modifier.fillMaxWidth(), filled = true)
            }
        }
    }
}
