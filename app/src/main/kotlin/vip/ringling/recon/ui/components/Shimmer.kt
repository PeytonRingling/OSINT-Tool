package vip.ringling.recon.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun Modifier.shimmer(): Modifier {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val top = MaterialTheme.colorScheme.surfaceContainerHighest
    val glint = Color(
        red = (top.red + 0.07f).coerceAtMost(1f),
        green = (top.green + 0.07f).coerceAtMost(1f),
        blue = (top.blue + 0.07f).coerceAtMost(1f),
    )
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x")
    // x is read in the draw phase only, so the shimmer never triggers recomposition.
    return drawBehind {
        drawRect(base)
        val cx = size.width * x.value
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.Transparent, glint, Color.Transparent),
                start = Offset(cx - size.width * 0.4f, 0f),
                end = Offset(cx + size.width * 0.4f, size.height),
            ),
        )
    }
}

@Composable
fun SkeletonBar(width: Dp? = null, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    val m = if (width != null) modifier.size(width, height) else modifier.fillMaxWidth().height(height)
    Box(m.clip(RoundedCornerShape(50)).shimmer())
}

/** Placeholder row shown while a module is still running. */
@Composable
fun SkeletonFindingRow(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).clearAndSetSemantics { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).shimmer())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkeletonBar(width = 150.dp, height = 14.dp)
            SkeletonBar(height = 10.dp)
        }
        SkeletonBar(width = 56.dp, height = 24.dp)
    }
}
