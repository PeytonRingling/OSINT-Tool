package vip.ringling.recon.ui.signal

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Risk over scans (0..100), oldest left. The newest point is filled; earlier points are hollow. */
@Composable
fun Sparkline(values: List<Int>, tone: Tone, modifier: Modifier = Modifier, description: String = "Risk over ${values.size} scans: ${values.joinToString(", ")}") {
    val color = tone.fill(); val bg = ReconThemeGround()
    Canvas(modifier.semantics { contentDescription = description }) {
        if (values.isEmpty()) return@Canvas
        val padX = 3f * density; val padY = 3f * density
        fun x(i: Int) = if (values.size == 1) size.width - padX else padX + i.toFloat() / (values.size - 1) * (size.width - 2 * padX)
        fun y(v: Int) = padY + (1f - v.coerceIn(0, 100) / 100f) * (size.height - 2 * padY)
        drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f))
        if (values.size > 1) {
            val path = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
            drawPath(path, color, style = Stroke(1.6f * density, join = StrokeJoin.Round))
        }
        values.forEachIndexed { i, v ->
            val last = i == values.lastIndex
            if (last) drawCircle(color, 2.6f * density, Offset(x(i), y(v)))
            else { drawCircle(bg, 3f * density, Offset(x(i), y(v))); drawCircle(color, 3f * density, Offset(x(i), y(v)), style = Stroke(1.4f * density)) }
        }
    }
}

@Composable
private fun ReconThemeGround(): Color = vip.ringling.recon.ui.theme.ReconTheme.signal.ground
