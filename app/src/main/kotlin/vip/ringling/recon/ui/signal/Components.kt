package vip.ringling.recon.ui.signal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType
import vip.ringling.recon.ui.theme.SerifFont

/** Status tones for tags, LEDs and numerals. Every tone is always paired with a text label: never colour alone. */
enum class Tone { ALERT, INFO, SAFE, SIGNAL, MUTED }

@Composable
fun Tone.fill(): Color = with(ReconTheme.signal) { when (this@fill) { Tone.ALERT -> alert; Tone.INFO -> info; Tone.SAFE -> safe; Tone.SIGNAL -> signal; Tone.MUTED -> muted } }

@Composable
fun Tone.textColor(): Color = with(ReconTheme.signal) { when (this@textColor) { Tone.ALERT -> alertText; Tone.INFO -> infoText; Tone.SAFE -> safeText; Tone.SIGNAL -> signal; Tone.MUTED -> muted } }

fun Severity.tone(): Tone = when (this) { Severity.EXPOSED -> Tone.ALERT; Severity.INFO -> Tone.INFO; Severity.CLEAN -> Tone.SAFE }

/** Two-pixel signal-coloured L-shaped corners at the top-left and bottom-right, following the panel's rounded corner. Draws over content, unclipped. */
fun Modifier.cornerBrackets(color: Color, length: Dp = 14.dp, stroke: Dp = 2.dp, radius: Dp = 10.dp): Modifier = drawWithContent {
    drawContent()
    val l = length.toPx(); val w = stroke.toPx(); val h = w / 2; val r = radius.toPx()
    val st = Stroke(w, cap = StrokeCap.Butt)
    val tl = Path().apply {
        moveTo(h, l); lineTo(h, r); arcTo(androidx.compose.ui.geometry.Rect(h, h, 2 * r - h, 2 * r - h), 180f, 90f, false); lineTo(l, h)
    }
    val br = Path().apply {
        moveTo(size.width - h, size.height - l); lineTo(size.width - h, size.height - r)
        arcTo(androidx.compose.ui.geometry.Rect(size.width - 2 * r + h, size.height - 2 * r + h, size.width - h, size.height - h), 0f, 90f, false); lineTo(size.width - l, size.height - h)
    }
    drawPath(tl, color, style = st); drawPath(br, color, style = st)
}

/** Key panel: 1dp hairline border, panel fill and (optionally) corner brackets. */
@Composable
fun BracketPanel(modifier: Modifier = Modifier, brackets: Boolean = true, content: @Composable () -> Unit) {
    val sig = ReconTheme.signal
    Box(
        modifier.let { if (brackets) it.cornerBrackets(sig.signal) else it }
            .clip(RoundedCornerShape(10.dp)).background(sig.panel.copy(alpha = 0.78f)).border(1.dp, sig.hairline, RoundedCornerShape(10.dp)),
    ) { content() }
}

/** Plain hairline panel (no brackets). */
@Composable
fun HairPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) = BracketPanel(modifier, brackets = false, content = content)

/** Mono uppercase label, 10.5sp, +0.14em tracking. */
@Composable
fun SignalLabel(text: String, modifier: Modifier = Modifier, color: Color = ReconTheme.signal.muted, small: Boolean = false, maxLines: Int = 1) {
    Text(text.uppercase(), modifier, color = color, style = if (small) ReconType.labelSmall else ReconType.label, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/** Mono uppercase chip tinted by status (22dp tall, 5dp radius). */
@Composable
fun StatusTag(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val bg = if (tone == Tone.MUTED) ReconTheme.signal.muted.copy(alpha = 0.14f) else tone.fill().copy(alpha = if (tone == Tone.ALERT) 0.16f else 0.13f)
    Box(modifier.height(22.dp).clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(text.uppercase(), color = tone.textColor(), style = ReconType.tag, maxLines = 1)
    }
}

/** Segmented control: signal-filled selection, 42dp items inside a hairline frame. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    Row(modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(7.dp)).background(if (on) sig.signal else Color.Transparent)
                    .clickable(role = Role.Tab, onClickLabel = label) { onSelect(i) }.semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(label.uppercase(), color = if (on) sig.onSignal else sig.muted, style = ReconType.button.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontSize = 11.sp), maxLines = 1)
            }
        }
    }
}

/** Small LED dot, optionally glowing. */
@Composable
fun Led(on: Boolean, modifier: Modifier = Modifier, tone: Tone = Tone.SIGNAL) {
    val c = if (on) tone.fill() else ReconTheme.signal.ledOff
    Box(modifier.size(7.dp).let { if (on) it.shadow(6.dp, CircleShape, ambientColor = c, spotColor = c) else it }.clip(CircleShape).background(c))
}

/** Switch with a glowing LED knob when on. 48dp touch height. */
@Composable
fun LedSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    Box(modifier.heightIn(min = 48.dp).widthIn(min = 56.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
                .background(if (checked) sig.signal.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                .border(1.dp, if (checked) sig.signal.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.16f), RoundedCornerShape(13.dp))
                .clickable(role = Role.Switch, onClickLabel = contentDescription) { onCheckedChange(!checked) }
                .semantics { this.contentDescription = contentDescription; stateDescription = if (checked) "On" else "Off" },
        ) {
            Box(
                Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).padding(3.dp).size(18.dp).clip(CircleShape)
                    .background(if (checked) sig.signal else sig.muted),
            )
        }
    }
}

/** Loadout tile: icon + LED on top, name and one-line detail below. */
@Composable
fun ModuleTile(icon: ImageVector, name: String, detail: String, armed: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, wide: Boolean = false) {
    val sig = ReconTheme.signal
    val fg = if (armed) sig.signal else Color(0xFF6F786C)
    Column(
        modifier.height(if (wide) 64.dp else 84.dp).clip(RoundedCornerShape(10.dp))
            .background(if (armed) sig.signal.copy(alpha = 0.07f) else Color(0xFF0A0D0B))
            .border(1.dp, if (armed) sig.signal.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .clickable(role = Role.Switch, onClickLabel = name, onClick = onClick)
            .semantics { stateDescription = if (armed) "Armed" else "Off"; contentDescription = "$name, $detail" }
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (wide) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(detail, color = sig.muted, style = ReconType.labelSmall.copy(letterSpacing = 0.02.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Led(armed)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp)); Led(armed)
            }
            Column {
                Text(name, color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(detail, color = sig.muted, style = ReconType.labelSmall.copy(letterSpacing = 0.02.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Large serif risk numeral with optional "/100" suffix, coloured by the risk band. */
@Composable
fun RiskNumeral(score: Int, modifier: Modifier = Modifier, size: Int = 104, showSuffix: Boolean = true, tone: Tone = riskTone(score)) {
    // Counts up from 0 on first show and glides to every later change; stays still when the system's animations are off.
    val animate = rememberAnimationsEnabled()
    val shown = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(if (animate) 0f else score.toFloat()) }
    androidx.compose.runtime.LaunchedEffect(score) { if (animate) shown.animateTo(score.toFloat(), androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing)) else shown.snapTo(score.toFloat()) }
    Row(modifier.semantics { contentDescription = "Risk $score of 100" }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(shown.value.toInt().toString(), color = tone.textColor().let { if (tone == Tone.ALERT) ReconTheme.signal.alert else it }, style = ReconType.numeral.copy(fontSize = size.sp, lineHeight = (size * 0.84f).sp))
        if (showSuffix) Text("/100", color = ReconTheme.signal.muted, style = ReconType.value.copy(fontSize = 12.sp), modifier = Modifier.padding(bottom = 6.dp))
    }
}

fun riskTone(score: Int): Tone = when {
    score >= 50 -> Tone.ALERT
    score >= 14 -> Tone.INFO
    else -> Tone.SAFE
}

/** 20-segment tick bar. */
@Composable
fun TickBar(filled: Int, total: Int = 20, tone: Tone = Tone.ALERT, modifier: Modifier = Modifier) {
    val on = tone.fill(); val off = Color.White.copy(alpha = 0.10f)
    Row(modifier.fillMaxWidth().height(8.dp).semantics { contentDescription = "$filled of $total" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { i -> Box(Modifier.weight(1f).fillMaxHeight().background(if (i < filled) on else off)) }
    }
}

/** 44dp square outlined icon button. */
@Composable
fun SignalIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, ReconTheme.signal.line, RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = ReconTheme.signal.text, modifier = Modifier.size(20.dp)) }
}

/** Filled (signal) or outlined 44-48dp button with a mono or body label. */
@Composable
fun SignalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = false, icon: ImageVector? = null, enabled: Boolean = true, mono: Boolean = false, dashed: Boolean = false) {
    val sig = ReconTheme.signal
    val shape = RoundedCornerShape(if (filled) 10.dp else 8.dp)
    Row(
        modifier.heightIn(min = 48.dp).clip(shape)
            .background(if (filled) sig.signal.copy(alpha = if (enabled) 1f else 0.35f) else Color.Transparent)
            .let { if (filled) it else it.border(1.dp, if (dashed || mono) sig.signal.copy(alpha = 0.4f) else sig.line, shape) }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = if (filled) sig.onSignal else if (mono) sig.signal else sig.text, modifier = Modifier.size(18.dp)) }
        if (mono) Text(text.uppercase(), color = if (filled) sig.onSignal else sig.signal, style = ReconType.button, maxLines = 1)
        else Text(text, color = if (filled) sig.onSignal else sig.text, style = MaterialTheme.typography.titleSmall.copy(fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1, textAlign = TextAlign.Center)
    }
}

/** Serif page title with a mono kicker above, as used on Cases / Log / System. */
@Composable
fun PageHeader(kicker: String, title: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 84.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            SignalLabel(kicker)
            Text(title, color = ReconTheme.signal.text, style = ReconType.pageTitle)
        }
        Box(Modifier.padding(bottom = 8.dp)) { trailing() }
    }
}

/** Serif italic wordmark with the signal dot. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(8.dp).shadow(8.dp, CircleShape, ambientColor = ReconTheme.signal.signal, spotColor = ReconTheme.signal.signal).clip(CircleShape).background(ReconTheme.signal.signal))
        Text("recon", color = ReconTheme.signal.text, style = ReconType.pageTitle.copy(fontSize = 28.sp, lineHeight = 30.sp))
    }
}

/** Single-line mono input with a signal hairline. */
@Composable
fun SignalTextField(
    value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
    description: String = placeholder, onGo: () -> Unit = {}, keyboard: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
) {
    val sig = ReconTheme.signal
    Box(
        modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(sig.panel).border(1.dp, sig.signal.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(placeholder, color = sig.muted, style = ReconType.value.copy(fontSize = 14.sp), maxLines = 1)
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange, singleLine = true,
            textStyle = ReconType.value.copy(fontSize = 15.sp, color = sig.text), cursorBrush = androidx.compose.ui.graphics.SolidColor(sig.signal),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboard, imeAction = androidx.compose.ui.text.input.ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { onGo() }),
            modifier = Modifier.fillMaxWidth().semantics { this.contentDescription = description },
        )
    }
}
