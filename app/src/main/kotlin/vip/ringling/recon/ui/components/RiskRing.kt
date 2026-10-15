package vip.ringling.recon.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import vip.ringling.recon.domain.RiskScore
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.ui.theme.ReconMotion
import vip.ringling.recon.ui.theme.ReconTheme

@Composable
fun severityColor(s: Severity) = when (s) {
    Severity.CLEAN -> ReconTheme.severity.clean
    Severity.INFO -> ReconTheme.severity.info
    Severity.EXPOSED -> ReconTheme.severity.exposed
}

/** 270° open gauge that springs to [score] (0..100). Big display numeral in the middle. */
@Composable
fun RiskRing(
    score: Int,
    modifier: Modifier = Modifier,
    size: Dp = 176.dp,
    stroke: Dp = 16.dp,
    caption: String = "RISK",
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(score) { progress.animateTo(score / 100f, ReconMotion.ring()) }
    val band = RiskScore.band(score)
    val color = severityColor(band)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val shown = (progress.value * 100).roundToInt().coerceIn(0, 100)

    Box(
        modifier.size(size).semantics { contentDescription = "Risk score $score of 100, ${band.label}" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val sw = stroke.toPx()
            val inset = sw / 2
            val arcSize = Size(this.size.width - sw, this.size.height - sw)
            val topLeft = Offset(inset, inset)
            drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            val sweep = 270f * progress.value.coerceIn(0f, 1f)
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(0f to color.copy(alpha = 0.35f), 0.75f to color, 1f to color),
                    startAngle = 135f, sweepAngle = sweep, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                shown.toString(),
                style = MaterialTheme.typography.displayMedium.copy(fontSize = (size.value * 0.30f).sp, lineHeight = (size.value * 0.32f).sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Neutral stand-in for the risk ring on offender-registry results: just a count, no colour judgement and no "Exposed" wording. */
@Composable
fun CountTile(count: Int, caption: String, modifier: Modifier = Modifier, size: Dp = 132.dp) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape).background(cs.surfaceContainerHigh)
            .border(androidx.compose.foundation.BorderStroke(1.dp, cs.outlineVariant), androidx.compose.foundation.shape.CircleShape)
            .semantics { contentDescription = "$count $caption" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.displayMedium.copy(fontSize = (size.value * 0.30f).sp, lineHeight = (size.value * 0.32f).sp), color = cs.onSurface)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
    }
}
