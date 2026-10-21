package vip.ringling.recon.ui.radar

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vip.ringling.recon.domain.Blip
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.RadarMath
import vip.ringling.recon.domain.RadarTarget
import vip.ringling.recon.ui.signal.rememberAnimationsEnabled
import vip.ringling.recon.ui.theme.MonoFont
import vip.ringling.recon.ui.theme.ReconTheme

private const val MAX_BLIPS = 12
private const val LABELLED = 3

/** Text shown under a blip's value. */
fun blipStatus(t: RadarTarget): String = when {
    t.input.type == InputType.PERSON -> if (t.records == null) "NAME SEARCH" else if (t.records == 0) "NO MATCHES" else "${t.records} MATCHES"
    t.input.type == InputType.ADDRESS -> if (t.records == null) "ADDRESS" else if (t.records == 0) "CLEAN" else "${t.records} NEARBY"
    t.isArea -> if (t.records == null) "AREA SCAN" else if (t.records == 0) "CLEAN" else "${t.records} RECORDS"
    t.clean -> "CLEAN"
    else -> "RISK ${t.risk}"
}

fun blipDescription(t: RadarTarget): String = when {
    t.input.type == InputType.PERSON -> "${t.input.raw}, name search, ${t.records ?: 0} matches"
    t.input.type == InputType.ADDRESS -> "${t.input.raw}, address search, ${t.records ?: 0} records"
    t.isArea -> "ZIP ${t.input.raw}, ${if (t.records == null) "area scan" else if (t.records == 0) "clean" else "${t.records} records"}"
    t.clean -> "${t.input.raw}, clean"
    else -> "${t.input.raw}, risk ${t.risk}"
}

/**
 * Concentric rings at risk 90/60/30/0, crosshair, a rotating sweep and one blip per scanned target. Radius = 34 + (100 − risk) × 1.55
 * (scaled to the view); circles for email/phone/username, squares for ZIP. Critical blips pulse. Every blip also has a 48dp tap target.
 */
@Composable
fun RadarCanvas(targets: List<RadarTarget>, onTarget: (RadarTarget) -> Unit, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    val anim = rememberAnimationsEnabled()
    val transition = rememberInfiniteTransition(label = "radar")
    val sweep by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "sweep")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400)), label = "pulse")
    val sweepAngle = if (anim) sweep else 35f
    val pulseT = if (anim) pulse else 0f
    // Declutter: at most the 12 newest targets are drawn (the Log has them all), and only the 3 newest carry a label.
    val blips = remember(targets) { RadarMath.place(targets.take(MAX_BLIPS)) }
    val labelled = remember(targets) { targets.take(LABELLED).map { it.key }.toSet() }
    val measurer = rememberTextMeasurer()
    val alert = sig.alert; val info = sig.info; val safe = sig.safe; val alertText = sig.alertText; val infoText = sig.infoText; val safeText = sig.safeText
    val text = sig.text; val muted = sig.muted; val ground = sig.ground; val signal = sig.signal

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        // Design space is 390 × 420; fit it into the available box.
        val s = minOf(w / 390f, h / 420f)
        val ox = (w - 390f * s) / 2f; val oy = (h - 420f * s) / 2f
        Canvas(
            Modifier.matchParentSize().semantics { contentDescription = "Radar of scanned targets. Closer to the center means higher risk. ${targets.size} targets." },
        ) {
            fun p(x: Double, y: Double) = Offset(ox + x.toFloat() * s, oy + y.toFloat() * s)
            val c = p(RadarMath.CX, RadarMath.CY)
            val dash = PathEffect.dashPathEffect(floatArrayOf(2f * s, 5f * s))
            // critical zone
            drawCircle(alert.copy(alpha = 0.07f), 48f * s, c); drawCircle(alert.copy(alpha = 0.4f), 48f * s, c, style = Stroke(1f * s.coerceAtLeast(1f)))
            val ring = Stroke(1f, pathEffect = null)
            drawCircle(signal.copy(alpha = 0.17f), 96f * s, c, style = Stroke(1f, pathEffect = dash))
            drawCircle(signal.copy(alpha = 0.17f), 144f * s, c, style = Stroke(1f, pathEffect = dash))
            drawCircle(signal.copy(alpha = 0.17f), 192f * s, c, style = ring)
            // sweep
            rotate(sweepAngle - 90f, c) {
                drawArc(
                    brush = Brush.sweepGradient(0f to Color.Transparent, 0.875f to Color.Transparent, 1f to signal.copy(alpha = 0.34f), center = c),
                    startAngle = -45f, sweepAngle = 45f, useCenter = true, topLeft = Offset(c.x - 192f * s, c.y - 192f * s), size = Size(384f * s, 384f * s),
                )
                drawLine(signal.copy(alpha = 0.7f), c, Offset(c.x + 192f * s, c.y), strokeWidth = 1.2f * s)
            }
            drawCircle(signal, 3f * s, c); drawCircle(signal.copy(alpha = 0.5f), 9f * s, c, style = Stroke(1f))

            blips.forEach { b ->
                val t = b.target
                val pos = p(b.x, b.y)
                val fill = if (t.clean) safe else if (t.critical) alert else info
                val txt = if (t.clean) safeText else if (t.critical) alertText else infoText
                if (t.critical) drawCircle(alert.copy(alpha = 0.7f * (1f - pulseT)), 6f * s * (1f + 2.2f * pulseT), pos)
                if (t.isArea) {
                    val half = 5f * s
                    drawRoundRect(ground, Offset(pos.x - half - 2f, pos.y - half - 2f), Size((half + 2f) * 2, (half + 2f) * 2), CornerRadius(3f))
                    drawRoundRect(fill, Offset(pos.x - half, pos.y - half), Size(half * 2, half * 2), CornerRadius(2f))
                } else {
                    drawCircle(ground, 5.5f * s + 2f, pos); drawCircle(fill, 5.5f * s, pos)
                }
                if (t.key in labelled) {
                    val right = b.x < 195.0 + 40.0
                    val label = t.input.raw.let { if (it.length > 20) it.take(19) + "…" else it }
                    val l1 = measurer.measure(label, androidx.compose.ui.text.TextStyle(fontFamily = MonoFont, fontSize = (9.5f * s).toSp(), color = text))
                    val lx = if (right) pos.x + 11f * s else pos.x - 11f * s - l1.size.width
                    drawText(l1, topLeft = Offset(lx, pos.y - l1.size.height * 0.5f))
                }
            }
            drawText(measurer, "CLOSER = HIGHER RISK", p(16.0, 400.0), style = androidx.compose.ui.text.TextStyle(fontFamily = MonoFont, fontSize = (8.5f * s).toSp(), color = muted, letterSpacing = (1.2f * s).toSp()))
            val tc = measurer.measure((if (targets.size > MAX_BLIPS) "NEWEST $MAX_BLIPS OF ${targets.size}" else "${targets.size} TARGETS"), androidx.compose.ui.text.TextStyle(fontFamily = MonoFont, fontSize = (8.5f * s).toSp(), color = muted, letterSpacing = (1.2f * s).toSp()))
            drawText(tc, topLeft = Offset(p(374.0, 0.0).x - tc.size.width, p(0.0, 400.0).y))
        }
        // 48dp tap targets with spoken descriptions.
        blips.forEach { b ->
            val cx = ox + b.x.toFloat() * s; val cy = oy + b.y.toFloat() * s
            val half = with(density) { 24.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((cx - half).roundToInt(), (cy - half).roundToInt()) }.size(48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open results") { onTarget(b.target) }
                    .semantics { contentDescription = blipDescription(b.target) },
            )
        }
    }
}
