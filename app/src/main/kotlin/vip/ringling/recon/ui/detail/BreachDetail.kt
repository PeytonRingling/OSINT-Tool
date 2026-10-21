package vip.ringling.recon.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vip.ringling.recon.data.sources.breach.BreachAdvice
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.ui.components.ExpandableText
import vip.ringling.recon.ui.signal.BracketPanel
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

/** True for a breach record that carries a breach date (HIBP-style); LeakCheck and paste findings keep the generic layout. */
fun Finding.isBreachRecord() = module == ModuleId.BREACH && facts.any { it.label == "Breach date" }

private fun Finding.fact(label: String) = facts.firstOrNull { it.label == label }?.value

/** The story paragraph of a breach finding: the lines between the "Exposed:" summary and "What to do:". */
fun Finding.breachStory(): String = detail.lines().drop(1).filterNot { it.startsWith("Exposed:") }.takeWhile { it != "What to do:" }.joinToString("\n").trim()

/**
 * Readable breach page: headline numbers, WHAT LEAKED (every data class with a one-line meaning, risky ones in red),
 * WHAT TO DO as numbered steps, the story, then the reference table. Everything comes from the finding; no extra requests.
 */
fun LazyListScope.breachSections(f: Finding, actions: DetailActions, doneSteps: Set<String> = emptySet()) {
    item(key = "breach-stats") {
        BracketPanel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 8.dp)) {
                Stat("Accounts", f.fact("Accounts affected") ?: "—", Modifier.weight(1f))
                Stat("Breached", f.fact("Breach date") ?: "—", Modifier.weight(1.2f))
                Stat("Verified", f.fact("Verified") ?: "—", Modifier.weight(0.8f))
            }
        }
    }
    f.fact("Flags")?.let { flags ->
        item(key = "breach-flags") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                flags.split(", ").forEach { StatusTag(it.substringBefore(" —"), if (it.startsWith("Stealer") || it.startsWith("Malware")) Tone.ALERT else Tone.INFO) }
            }
        }
    }
    if (f.tags.isNotEmpty()) item(key = "breach-leaked") {
        Section("What leaked", "${f.tags.size}") {
            f.tags.forEachIndexed { i, t ->
                val hot = t in f.hotTags
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 5.dp).size(8.dp).clip(RoundedCornerShape(2.dp)).background(if (hot) ReconTheme.signal.alert else ReconTheme.signal.info))
                    Column(Modifier.weight(1f)) {
                        Text(t, color = if (hot) ReconTheme.signal.alertText else ReconTheme.signal.text, style = MaterialTheme.typography.titleSmall)
                        BreachAdvice.explain(t)?.let { Text(it, color = ReconTheme.signal.muted, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (hot) StatusTag("Risky", Tone.ALERT)
                }
            }
        }
    }
    item(key = "breach-todo") {
        val steps = BreachAdvice.forClasses(f.tags)
        val fk = vip.ringling.recon.data.HandledRepository.keyOf(f)
        val done = steps.indices.count { "$fk:$it" in doneSteps }
        Section("What to do", "$done of ${steps.size} done") {
            steps.forEachIndexed { i, s ->
                if (i > 0) Divider()
                val ticked = "$fk:$i" in doneSteps
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Checkbox, onClickLabel = if (ticked) "Mark step not done" else "Mark step done") { actions.onStep(f, i, !ticked, steps.size) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).background(if (ticked) ReconTheme.signal.signal else if (i == 0 && f.hotTags.isNotEmpty()) ReconTheme.signal.alert else ReconTheme.signal.ledOff),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (ticked) "✓" else "${i + 1}", color = if (ticked) ReconTheme.signal.onSignal else if (i == 0 && f.hotTags.isNotEmpty()) androidx.compose.ui.graphics.Color(0xFF1A0804) else ReconTheme.signal.text, style = ReconType.value.copy(fontSize = 11.sp))
                    }
                    Text(s, Modifier.weight(1f), color = if (ticked) ReconTheme.signal.muted else ReconTheme.signal.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            f.fact("Website")?.takeIf { it.contains('.') }?.let { d -> Text("Open $d to change the password and review the account.", Modifier.clickable { actions.onOpenUrl("https://$d") }.heightIn(min = 48.dp).padding(top = 8.dp), color = ReconTheme.signal.signal, style = MaterialTheme.typography.bodySmall) }
            if (done == steps.size && steps.isNotEmpty()) Text("All steps done: this breach is marked handled and no longer counts in your checkup.", color = ReconTheme.signal.safeText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
    f.breachStory().takeIf { it.isNotBlank() }?.let { story ->
        item(key = "breach-story") {
            Section("About this breach", null) { ExpandableText(story, Modifier.padding(vertical = 10.dp), collapsedLines = 5, key = "story-${f.id}") }
        }
    }
    val rest = f.facts.filterNot { it.label in setOf("Accounts affected", "Breach date", "Verified", "Flags") }
    if (rest.isNotEmpty()) item(key = "breach-facts") {
        Section("Details", null) {
            rest.forEachIndexed { i, fa ->
                if (i > 0) Divider()
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(fa.label, Modifier.weight(0.42f), color = ReconTheme.signal.muted, style = MaterialTheme.typography.bodyMedium)
                    Text(fa.value, Modifier.weight(0.58f), color = ReconTheme.signal.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    f.url?.let { url ->
        item(key = "breach-open") {
            HairPanel(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClickLabel = "Open on Have I Been Pwned") { actions.onOpenUrl(url) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Full entry on Have I Been Pwned", Modifier.weight(1f), color = ReconTheme.signal.text, style = MaterialTheme.typography.titleSmall)
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = ReconTheme.signal.muted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SignalLabel(label, small = true)
        Text(value, color = ReconTheme.signal.text, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f))) }

@Composable
private fun Section(title: String, trailing: String?, content: @Composable () -> Unit) {
    HairPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 14.dp).padding(top = 12.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { SignalLabel(title); trailing?.let { SignalLabel(it) } }
            content()
        }
    }
}
