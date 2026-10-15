package vip.ringling.recon.ui.cases

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import vip.ringling.recon.domain.CaseChanges
import vip.ringling.recon.domain.ChangeEvent
import vip.ringling.recon.domain.ChangeKind
import vip.ringling.recon.ui.log.ago
import vip.ringling.recon.ui.signal.BracketPanel
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.signal.SignalIconButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.signal.riskTone
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

enum class ChangeFilter(val label: String) { ALL("All changes"), WORSE("Worse"), BETTER("Better") }

fun List<ChangeEvent>.filtered(f: ChangeFilter) = when (f) {
    ChangeFilter.ALL -> this
    ChangeFilter.WORSE -> filter { it.worse }
    ChangeFilter.BETTER -> filter { it.better }
}

private fun ChangeEvent.tone() = when (kind) {
    ChangeKind.WORSE -> Tone.ALERT
    ChangeKind.BETTER -> Tone.SAFE
    ChangeKind.INFO_MORE -> Tone.INFO
    ChangeKind.FIRST, ChangeKind.SAME -> Tone.MUTED
}

private fun dayLabel(ts: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate(); val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.toString() }
}

/** Current case exposure, "▲ n since first scan" and a small risk-over-scans line. */
@Composable
fun ExposurePanel(c: CaseView, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    val exposure = c.exposure
    val since = CaseChanges.sinceFirst(c.series)
    val tone = exposure?.let { riskTone(it) } ?: Tone.MUTED
    BracketPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp)) {
            SignalLabel("Case exposure", small = true)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(exposure?.toString() ?: "–", color = if (tone == Tone.ALERT) sig.alert else if (tone == Tone.SAFE) sig.safeText else sig.infoText, style = ReconType.numeral.copy(fontSize = 52.sp, lineHeight = 46.sp))
                if (since != null && since != 0) Text(
                    "${if (since > 0) "▲" else "▼"} ${kotlin.math.abs(since)} SINCE FIRST SCAN", color = if (since > 0) sig.alertText else sig.safeText,
                    style = ReconType.value.copy(fontSize = 11.sp, letterSpacing = 0.06.sp), modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            ExposureLine(c.series.map { it.second }, if (tone == Tone.ALERT) sig.alert else sig.info, Modifier.fillMaxWidth().padding(top = 6.dp).height(44.dp))
        }
    }
}

@Composable
private fun ExposureLine(values: List<Int>, color: Color, modifier: Modifier) {
    val sig = ReconTheme.signal
    Canvas(modifier.semantics { contentDescription = "Case exposure over ${values.size} scans: ${values.joinToString(", ")}" }) {
        if (values.isEmpty()) return@Canvas
        val pad = 6f * density
        fun x(i: Int) = if (values.size == 1) size.width - pad else pad + i.toFloat() / (values.size - 1) * (size.width - 2 * pad)
        fun y(v: Int) = 4f * density + (1f - v.coerceIn(0, 100) / 100f) * (size.height - 8f * density)
        drawLine(Color.White.copy(alpha = 0.1f), Offset(0f, size.height - 2f), Offset(size.width, size.height - 2f))
        if (values.size > 1) drawPath(Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }, color, style = Stroke(1.8f * density, join = StrokeJoin.Round))
        values.forEachIndexed { i, v ->
            if (i == values.lastIndex) drawCircle(color, 3.5f * density, Offset(x(i), y(v)))
            else if (i == 0) { drawCircle(sig.ground, 3f * density, Offset(x(i), y(v))); drawCircle(color, 3f * density, Offset(x(i), y(v)), style = Stroke(1.6f * density)) }
            else drawCircle(color, 3f * density, Offset(x(i), y(v)))
        }
    }
}

/** Vertical timeline grouped by day. Each event shows time, target type, value, a delta tag and a one-line diff taken from stored scan snapshots. */
@Composable
fun ChangeTimeline(events: List<ChangeEvent>, now: Long, modifier: Modifier = Modifier) {
    val sig = ReconTheme.signal
    if (events.isEmpty()) {
        Text("No changes to show.", color = sig.muted, style = MaterialTheme.typography.bodyMedium, modifier = modifier.padding(vertical = 16.dp))
        return
    }
    Column(modifier) {
        var last = ""
        events.forEach { e ->
            val day = dayLabel(e.snapshot.ts, now)
            if (day != last) { last = day; SignalLabel(day, Modifier.padding(top = 14.dp, bottom = 8.dp, start = 2.dp)) }
            TimelineEvent(e, now)
        }
    }
}

@Composable
private fun TimelineEvent(e: ChangeEvent, now: Long) {
    val sig = ReconTheme.signal
    val tone = e.tone()
    val dot = when (tone) { Tone.ALERT -> sig.alert; Tone.SAFE -> sig.safe; Tone.INFO -> sig.info; else -> sig.muted }
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = "${ago(e.snapshot.ts, now)}, ${e.snapshot.type.label} ${e.snapshot.value}, ${e.tag}. ${e.summary}" },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(11.dp).fillMaxHeight()) {
            Box(Modifier.padding(start = 5.dp).width(1.dp).fillMaxHeight().background(sig.signal.copy(alpha = 0.22f)))
            Box(Modifier.padding(top = 6.dp).size(11.dp).clip(CircleShape).background(dot))
        }
        Column(Modifier.weight(1f).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SignalLabel("${ago(e.snapshot.ts, now)} · ${e.snapshot.type.label}", small = true)
                StatusTag(e.tag, tone)
            }
            Text(e.snapshot.value, color = sig.text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            Text(e.summary, color = sig.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Full-screen "Changes" view for one case (phone). */
@Composable
fun CaseTimelineScreen(c: CaseView, filter: ChangeFilter, onFilter: (ChangeFilter) -> Unit, onBack: () -> Unit, onExport: () -> Unit, now: Long, modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues()) {
    val sig = ReconTheme.signal
    val tone = c.exposure?.let { riskTone(it) } ?: Tone.MUTED
    Box(modifier.fillMaxSize().background(Brush.radialGradient(listOf((if (tone == Tone.ALERT) sig.alert else sig.signal).copy(alpha = 0.07f), Color.Transparent), radius = 700f))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SignalIconButton(Icons.Rounded.ArrowBack, "Back to case", onBack)
                    Column(Modifier.weight(1f)) {
                        SignalLabel("${caseLabel(c.file.number)} · Changes", color = sig.signal, small = true)
                        Text(c.file.name, color = sig.text, style = ReconType.pageTitle.copy(fontSize = 28.sp, lineHeight = 30.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Normal), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SignalIconButton(Icons.Rounded.FileDownload, "Export timeline", onExport)
                }
            }
            item { ExposurePanel(c, Modifier.padding(top = 14.dp)) }
            item { SegmentedControl(ChangeFilter.entries.map { it.label }, filter.ordinal, { onFilter(ChangeFilter.entries[it]) }, Modifier.padding(top = 12.dp)) }
            item { ChangeTimeline(c.events.filtered(filter), now) }
        }
    }
}
