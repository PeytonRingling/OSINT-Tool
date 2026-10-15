package vip.ringling.recon.ui.results

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import vip.ringling.recon.domain.Exposure
import vip.ringling.recon.domain.OrbitNode
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.domain.TimelineEvent
import vip.ringling.recon.ui.theme.MonoFont
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.SerifFont

private fun nodeAngle(i: Int, n: Int) = Math.toRadians(-152.0 + i * 360.0 / n)

/** Spoken summary of the orbit graph, so the picture is never the only carrier of its numbers. */
fun orbitDescription(value: String, nodes: List<OrbitNode>) =
    "Exposure map. $value at the center with ${nodes.size} module nodes: " + nodes.joinToString(", ") { "${it.module.label} ${it.count} finding${if (it.count == 1) "" else "s"}" }

/** The target sits in the centre; each module is a node on an orbit sized by its finding count. The breach node wears a ring of dots, one per breach. */
@Composable
fun OrbitGraph(centerIcon: ImageVector, value: String, nodes: List<OrbitNode>, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    val measurer = rememberTextMeasurer()
    val iconPainter = rememberVectorPainter(centerIcon)
    Canvas(modifier.semantics { contentDescription = orbitDescription(value, nodes) }) {
        val s = minOf(size.width / 356f, size.height / 260f)
        val c = Offset(size.width / 2f, size.height / 2f + 2f * s)
        fun px(v: Double) = (v * s).toFloat()
        val dash = PathEffect.dashPathEffect(floatArrayOf(2f * s, 5f * s))
        val n = nodes.size.coerceAtLeast(1)
        val orbit = 92.0
        drawCircle(sig.signal.copy(alpha = 0.22f), px(orbit), c, style = Stroke(1f, pathEffect = dash))
        fun colorOf(sev: Severity?) = when (sev) { Severity.EXPOSED -> sig.alert; Severity.INFO -> sig.info; Severity.CLEAN -> sig.safe; null -> sig.muted }
        fun textOf(sev: Severity?) = when (sev) { Severity.EXPOSED -> sig.alertText; Severity.INFO -> sig.infoText; Severity.CLEAN -> sig.safeText; null -> sig.muted }
        // edges first, so nodes paint over them
        nodes.forEachIndexed { i, nd ->
            val a = nodeAngle(i, n); val p = Offset(c.x + px(orbit * cos(a) * 1.0), c.y + px(orbit * sin(a) * 0.9))
            drawLine(if (nd.worst == Severity.EXPOSED) sig.alert.copy(alpha = 0.6f) else sig.signal.copy(alpha = 0.22f), c, p, strokeWidth = if (nd.worst == Severity.EXPOSED) 1.4f * s else 1f)
        }
        drawCircle(sig.signal.copy(alpha = 0.3f), px(36.0), c, style = Stroke(1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f * s, 4f * s))))
        drawCircle(sig.panel, px(26.0), c); drawCircle(sig.signal, px(26.0), c, style = Stroke(1.5f * s))
        translate(c.x - 12f * s, c.y - 12f * s) { with(iconPainter) { draw(androidx.compose.ui.geometry.Size(24f * s, 24f * s), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(sig.signal)) } }

        nodes.forEachIndexed { i, nd ->
            val a = nodeAngle(i, n); val p = Offset(c.x + px(orbit * cos(a) * 1.0), c.y + px(orbit * sin(a) * 0.9))
            val r = px(nd.radius)
            val col = colorOf(nd.worst); val txt = textOf(nd.worst)
            if (nd.dots.isNotEmpty()) {
                val ringR = r + px(12.0)
                nd.dots.take(36).forEachIndexed { k, sev ->
                    val ang = Math.toRadians(-90.0 + k * 360.0 / nd.dots.size.coerceAtMost(36))
                    drawCircle(if (sev == Severity.EXPOSED) sig.alert else sig.info.copy(alpha = 0.7f), px(2.6), Offset(p.x + ringR * cos(ang).toFloat(), p.y + ringR * sin(ang).toFloat()))
                }
            }
            if (nd.count == 0) {
                drawCircle(sig.muted, r, p, style = Stroke(1.4f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * s, 3f * s))))
            } else {
                drawCircle(col.copy(alpha = 0.14f), r, p); drawCircle(col, r, p, style = Stroke(1.5f * s))
                val numSize = (r / s * 1.1f).coerceIn(15f, 26f)
                val num = measurer.measure(nd.count.toString(), TextStyle(fontFamily = SerifFont, fontSize = (numSize * s).toSp(), color = txt))
                drawText(num, topLeft = Offset(p.x - num.size.width / 2f, p.y - num.size.height / 2f))
            }
            val label = if (nd.count == 0) "${nd.label} 0" else nd.label
            val lab = measurer.measure(label, TextStyle(fontFamily = MonoFont, fontSize = (9f * s).toSp(), color = if (nd.count == 0) sig.muted else txt, letterSpacing = (1f * s).toSp()))
            val ringExtra = if (nd.dots.isNotEmpty()) px(12.0 + 6.0) else px(6.0)
            drawText(lab, topLeft = Offset(p.x - lab.size.width / 2f, p.y + r + ringExtra))
        }
        val title = measurer.measure("EXPOSURE MAP", TextStyle(fontFamily = MonoFont, fontSize = (9f * s).toSp(), color = sig.muted, letterSpacing = (1.4f * s).toSp()))
        drawText(title, topLeft = Offset(14f * s, 14f * s))
    }
}

/** Breach events as dots on a month axis: alert-coloured when exposed, info-coloured otherwise. */
@Composable
fun BreachTimeline(events: List<TimelineEvent>, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    val measurer = rememberTextMeasurer()
    val desc = "Timeline of ${events.size} breaches: " + events.joinToString(", ") { "${monthName(it.month)} ${it.year}" }
    Canvas(modifier.semantics { contentDescription = desc }) {
        if (events.isEmpty()) return@Canvas
        val axisY = size.height * 0.4f
        val pad = 15f * density
        drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, axisY), Offset(size.width, axisY))
        val lo = events.minOf { it.monthIndex }; val hi = events.maxOf { it.monthIndex }
        fun xOf(m: Int) = if (hi == lo) size.width / 2f else pad + (m - lo).toFloat() / (hi - lo) * (size.width - 2 * pad)
        var lastLabelEnd = -1000f
        events.forEachIndexed { i, e ->
            val x = xOf(e.monthIndex)
            drawLine(Color.White.copy(alpha = 0.18f), Offset(x, axisY - 4f * density), Offset(x, axisY + 4f * density))
            drawCircle(if (e.severity == Severity.EXPOSED) sig.alert else sig.info, if (i == events.lastIndex) 6f * density else 5f * density, Offset(x, axisY))
            val text = "${monthName(e.month).uppercase()} ${(e.year % 100).toString().padStart(2, '0')}"
            val m = measurer.measure(text, TextStyle(fontFamily = MonoFont, fontSize = 8.5.sp, color = sig.muted))
            val lx = (x - m.size.width / 2f).coerceIn(0f, size.width - m.size.width)
            if (lx > lastLabelEnd + 6f * density) { drawText(m, topLeft = Offset(lx, axisY + 9f * density)); lastLabelEnd = lx + m.size.width }
        }
    }
}

fun monthName(m: Int) = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[(m - 1).coerceIn(0, 11)]
