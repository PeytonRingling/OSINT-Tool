package vip.ringling.recon.ui.cases

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.roundToInt
import vip.ringling.recon.domain.CaseEdge
import vip.ringling.recon.domain.CaseTargetState
import vip.ringling.recon.domain.EdgeKind
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.ui.theme.MonoFont
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.SerifFont

/** Node centres in the design's 356 × 298 space: the first target on the left, the rest in a column (two for large cases) on the right. */
internal fun linkLayout(n: Int): List<Offset> {
    if (n == 0) return emptyList()
    val out = mutableListOf(Offset(104f, 122f))
    val others = n - 1
    if (others <= 4) {
        repeat(others) { i -> out += Offset(256f, if (others == 1) 134f else 64f + i * (140f / (others - 1))) }
    } else {
        repeat(others) { i -> out += Offset(if (i % 2 == 0) 214f else 298f, 40f + (i / 2) * (220f / ((others + 1) / 2).coerceAtLeast(1)) + (if (i % 2 == 0) 0f else 18f)) }
    }
    return out
}

internal fun linkDescription(states: List<CaseTargetState>, edges: List<CaseEdge>): String {
    val linked = edges.filter { it.kind != EdgeKind.NONE }
    val loose = edges.filter { it.kind == EdgeKind.NONE }
    return "Link graph with ${states.size} targets. " + linked.joinToString(" ") { "${states[it.a].target.value} and ${states[it.b].target.value} are linked: ${it.kind.label.lowercase()}." } +
        loose.joinToString(" ") { " ${states[it.b].target.value} has no link yet." }
}

/** Targets as nodes (size follows risk), edges labelled by relationship; dashed edges are "NO LINK YET"; a ghost node adds a target. */
@Composable
fun LinkGraph(states: List<CaseTargetState>, edges: List<CaseEdge>, onNode: (CaseTargetState) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    val measurer = rememberTextMeasurer()
    val pos = linkLayout(states.size)
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        val s = minOf(w / 356f, h / 298f)
        val ox = (w - 356f * s) / 2f; val oy = (h - 298f * s) / 2f
        val ghost = Offset(48f, 250f)
        fun px(o: Offset) = Offset(ox + o.x * s, oy + o.y * s)
        Canvas(Modifier.matchParentSize().semantics { contentDescription = linkDescription(states, edges) }) {
            // dot grid
            var gx = 0f
            while (gx < size.width) { var gy = 0f; while (gy < size.height) { drawCircle(sig.signal.copy(alpha = 0.14f), 1f * density.density, Offset(gx + 10f, gy + 10f)); gy += 20f * density.density }; gx += 20f * density.density }
            fun radius(t: CaseTargetState) = (if (t.isArea) 18f else 16f + (t.risk ?: 0) / 100f * 8f) * s
            edges.forEach { e ->
                val a = px(pos[e.a]); val b = px(pos[e.b])
                val dash = e.kind == EdgeKind.NONE
                drawLine(if (dash) Color.White.copy(alpha = 0.28f) else sig.signal, a, b, strokeWidth = (if (dash) 1.4f else 1.6f) * s, pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(4f * s, 5f * s)) else null)
                val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                val ang = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat().let { if (it > 90f) it - 180f else if (it < -90f) it + 180f else it }
                val label = measurer.measure(e.kind.label, TextStyle(fontFamily = MonoFont, fontSize = (8.5f * s).toSp(), color = if (dash) sig.muted else sig.signal, letterSpacing = (0.8f * s).toSp()))
                rotate(ang, mid) {
                    val pw = label.size.width + 14f * s; val ph = label.size.height + 5f * s
                    drawRoundRect(sig.panel, Offset(mid.x - pw / 2, mid.y - ph / 2), Size(pw, ph), CornerRadius(4f * s))
                    drawRoundRect(if (dash) Color.White.copy(alpha = 0.2f) else sig.signal.copy(alpha = 0.4f), Offset(mid.x - pw / 2, mid.y - ph / 2), Size(pw, ph), CornerRadius(4f * s), style = Stroke(1f))
                    drawText(label, topLeft = Offset(mid.x - label.size.width / 2f, mid.y - label.size.height / 2f))
                }
            }
            states.forEachIndexed { i, st ->
                val c = px(pos[i]); val r = radius(st)
                val risk = st.risk
                val tone = when { st.isArea -> sig.info; risk == null -> sig.muted; risk >= 50 -> sig.alert; risk >= 1 -> sig.info; else -> sig.safe }
                val txtTone = when { st.isArea -> sig.infoText; risk == null -> sig.muted; risk >= 50 -> sig.alertText; risk >= 1 -> sig.infoText; else -> sig.safeText }
                if (i == 0) drawCircle(tone.copy(alpha = 0.3f), r + 6f * s, c, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * s, 4f * s))))
                if (st.isArea) {
                    drawRoundRect(tone.copy(alpha = 0.12f), Offset(c.x - r, c.y - r), Size(r * 2, r * 2), CornerRadius(5f * s))
                    drawRoundRect(tone, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), CornerRadius(5f * s), style = Stroke((if (i == 0) 2f else 1.6f) * s))
                } else {
                    drawCircle(tone.copy(alpha = 0.12f), r, c); drawCircle(tone, r, c, style = Stroke((if (i == 0) 2f else 1.6f) * s, cap = StrokeCap.Round))
                }
                val num = measurer.measure(if (st.isArea) (st.records?.toString() ?: "·") else (risk?.toString() ?: "–"), TextStyle(fontFamily = SerifFont, fontSize = ((if (i == 0) 26f else 18f) * s).toSp(), color = txtTone))
                drawText(num, topLeft = Offset(c.x - num.size.width / 2f, c.y - num.size.height / 2f))
                val v = st.target.value.let { if (it.length > 18) it.take(17) + "…" else it }
                val l1 = measurer.measure(v, TextStyle(fontFamily = MonoFont, fontSize = (9f * s).toSp(), color = sig.text))
                val l2 = measurer.measure(st.target.type.label.uppercase(), TextStyle(fontFamily = MonoFont, fontSize = (8.5f * s).toSp(), color = sig.muted))
                drawText(l1, topLeft = Offset(c.x - l1.size.width / 2f, c.y + r + 5f * s)); drawText(l2, topLeft = Offset(c.x - l2.size.width / 2f, c.y + r + 5f * s + l1.size.height))
            }
            val g = px(ghost)
            drawCircle(sig.signal.copy(alpha = 0.5f), 16f * s, g, style = Stroke(1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * s, 4f * s))))
            drawLine(sig.signal, Offset(g.x, g.y - 7f * s), Offset(g.x, g.y + 7f * s), 1.6f * s, StrokeCap.Round); drawLine(sig.signal, Offset(g.x - 7f * s, g.y), Offset(g.x + 7f * s, g.y), 1.6f * s, StrokeCap.Round)
            val al = measurer.measure("ADD TARGET", TextStyle(fontFamily = MonoFont, fontSize = (8.5f * s).toSp(), color = sig.muted))
            drawText(al, topLeft = Offset(g.x - al.size.width / 2f, g.y + 24f * s))
            val title = measurer.measure("LINK GRAPH", TextStyle(fontFamily = MonoFont, fontSize = (9f * s).toSp(), color = sig.muted, letterSpacing = (1.4f * s).toSp()))
            drawText(title, topLeft = Offset(14f * s, 14f * s))
        }
        val half = with(density) { 24.dp.toPx() }
        states.forEachIndexed { i, st ->
            val c = px(pos[i])
            Box(
                Modifier.offset { IntOffset((c.x - half).roundToInt(), (c.y - half).roundToInt()) }.size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open ${st.target.value}") { onNode(st) }
                    .semantics { contentDescription = "${st.target.value}, ${st.target.type.label}, " + (st.risk?.let { "risk $it" } ?: st.records?.let { "$it records" } ?: "not scanned") },
            )
        }
        val g = px(ghost)
        Box(Modifier.offset { IntOffset((g.x - half).roundToInt(), (g.y - half).roundToInt()) }.size(48.dp).clickable(role = Role.Button, onClickLabel = "Add target") { onAdd() }.semantics { contentDescription = "Add target" })
    }
}
