package vip.ringling.recon.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import vip.ringling.recon.data.lab.TraceResult
import vip.ringling.recon.domain.Flag
import vip.ringling.recon.domain.HeaderAnalyzer
import vip.ringling.recon.domain.LinkAnalysis
import vip.ringling.recon.domain.Severity
import vip.ringling.recon.domain.Verdict
import vip.ringling.recon.ui.signal.HairPanel
import vip.ringling.recon.ui.signal.SegmentedControl
import vip.ringling.recon.ui.signal.SignalButton
import vip.ringling.recon.ui.signal.SignalLabel
import vip.ringling.recon.ui.signal.SignalTextField
import vip.ringling.recon.ui.signal.StatusTag
import vip.ringling.recon.ui.signal.Tone
import vip.ringling.recon.ui.theme.ReconTheme
import vip.ringling.recon.ui.theme.ReconType

private val tools = listOf("Headers", "Message", "Link", "QR", "Decode", "Password")

/** The Lab tab: small one-shot tools that answer "is this message / link / password safe?" without creating a scan. */
@Composable
fun LabScreen(vm: LabViewModel, onPivot: (String) -> Unit, modifier: Modifier = Modifier, incomingLink: String? = null, onIncomingUsed: () -> Unit = {}, contentPadding: PaddingValues = PaddingValues()) {
    val sig = ReconTheme.signal
    var tool by rememberSaveable { mutableStateOf(0) }
    var prefill by remember { mutableStateOf<String?>(null) }
    // A link shared into Recon from another app is inspected here.
    androidx.compose.runtime.LaunchedEffect(incomingLink) { if (incomingLink != null) { prefill = incomingLink; tool = 2; onIncomingUsed() } }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SignalLabel("Lab")
            Text("Check a message, a link or a password", color = sig.text, style = MaterialTheme.typography.titleMedium)
        }
        ToolChips(tools, tool) { tool = it }
        when (tool) {
            0 -> HeaderTool(onPivot)
            1 -> MessageTool { prefill = it; tool = 2 }
            2 -> LinkTool(vm, onPivot, prefill) { prefill = null }
            3 -> QrTool { prefill = it; tool = 2 }
            4 -> DecodeTool()
            else -> PasswordTool(vm)
        }
    }
}

@Composable
private fun MultiLineField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val sig = ReconTheme.signal
    Box(Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 260.dp).clip(RoundedCornerShape(10.dp)).background(sig.panel).border(1.dp, sig.signal.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).padding(14.dp)) {
        if (value.isEmpty()) Text(placeholder, color = sig.muted, style = ReconType.value.copy(fontSize = 13.sp))
        BasicTextField(value, onChange, Modifier.fillMaxWidth(), textStyle = ReconType.value.copy(fontSize = 12.sp, color = sig.text), cursorBrush = SolidColor(sig.signal))
    }
}

@Composable
private fun FlagRow(f: Flag) {
    val sig = ReconTheme.signal
    val (mark, col) = when (f.severity) { Severity.CLEAN -> "✓" to sig.safeText; Severity.INFO -> "~" to sig.infoText; Severity.EXPOSED -> "✗" to sig.alertText }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, color = col, style = MaterialTheme.typography.titleSmall)
        Text(f.text, color = sig.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AuthTag(n: String, v: String?) = StatusTag("$n ${v ?: "?"}", when (v) { "pass" -> Tone.SAFE; null -> Tone.MUTED; "fail", "permerror" -> Tone.ALERT; else -> Tone.INFO })

@Composable
private fun VerdictTag(v: Verdict) = StatusTag(v.label, when (v) { Verdict.CLEAN -> Tone.SAFE; Verdict.CHECK -> Tone.INFO; else -> Tone.ALERT })

@Composable
private fun HeaderTool(onPivot: (String) -> Unit) {
    val sig = ReconTheme.signal
    var raw by rememberSaveable { mutableStateOf("") }
    var run by rememberSaveable { mutableStateOf(false) }
    val report = if (run) HeaderAnalyzer.analyze(raw) else null
    Text("In your mail app open the message's \"original\" / \"view source\" / \"headers\", copy the top part and paste it here. Nothing is sent anywhere; it is read on this phone.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
    MultiLineField(raw, { raw = it; run = false }, "Paste the message headers here")
    SignalButton("Analyze headers", { run = true }, Modifier.fillMaxWidth(), filled = true, enabled = raw.isNotBlank())
    if (run && report == null) Text("That doesn't look like e-mail headers (no From or Received lines found).", color = sig.alertText, style = MaterialTheme.typography.bodyMedium)
    report?.let { r ->
        HairPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { SignalLabel("Verdict"); VerdictTag(r.verdict) }
                listOf("From" to r.from, "Reply-To" to r.replyTo, "Return-Path" to r.returnPath, "Subject" to r.subject, "Date" to r.date).forEach { (k, v) ->
                    if (v != null) Column { SignalLabel(k, small = true); Text(v, color = sig.text, style = ReconType.value.copy(fontSize = 12.sp)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthTag("SPF", r.spf); AuthTag("DKIM", r.dkim); AuthTag("DMARC", r.dmarc)
                }
            }
        }
        HairPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SignalLabel("What stands out"); r.flags.sortedBy { -it.severity.ordinal }.forEach { FlagRow(it) } } }
        if (r.hops.isNotEmpty()) HairPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SignalLabel("Path (first server first)")
                r.hops.forEachIndexed { i, h -> Text("${i + 1}. ${h.from.ifBlank { "?" }} → ${h.by.ifBlank { "?" }}" + (h.ip?.let { " [$it]" } ?: ""), color = sig.muted, style = ReconType.value.copy(fontSize = 11.sp)) }
            }
        }
        if (r.leads.isNotEmpty()) HairPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalLabel("Search next")
                r.leads.forEach { SignalButton(it.label, { onPivot(it.query) }, Modifier.fillMaxWidth(), mono = true) }
            }
        }
    }
}

@Composable
private fun LinkTool(vm: LabViewModel, onPivot: (String) -> Unit, prefill: String? = null, onPrefillUsed: () -> Unit = {}) {
    val sig = ReconTheme.signal
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<TraceResult?>(null) }
    var domainFlags by remember { mutableStateOf<List<Flag>?>(null) }
    var checking by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val hasVt = remember { vm.hasVtKey() }
    var vtFlags by remember { mutableStateOf<List<Flag>?>(null) }
    var vtTarget by remember { mutableStateOf<String?>(null) }     // the domain VirusTotal's page should open for
    var vtBusy by remember { mutableStateOf(false) }
    fun checkDomain(target: String) {
        LinkAnalysis.host(LinkTracerNormalize(target))?.let { h ->
            checking = true; vtTarget = h; vtFlags = null
            scope.launch {
                domainFlags = vm.domainCheck(h); checking = false
                // With a VirusTotal key the DOMAIN (only) is looked up automatically.
                if (hasVt) vtFlags = vip.ringling.recon.data.lab.VirusTotal.flags(vm.vtDomain(h), "domain")
            }
        }
    }
    // A link handed over from the QR or message tool is followed straight away.
    androidx.compose.runtime.LaunchedEffect(prefill) { if (prefill != null) { url = prefill; onPrefillUsed(); busy = true; domainFlags = null; result = vm.trace(prefill); busy = false; result?.chain?.lastOrNull()?.let { checkDomain(it) } } }
    Text("Follows the link's redirects to show where it really ends up. Recon contacts the link (through your Tor/proxy setting if on) but never loads the page.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
    SignalTextField(url, { url = it; result = null }, "Paste a link", description = "Link to inspect", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SignalButton("Check domain only", { checkDomain(url) }, Modifier.weight(1f), enabled = url.isNotBlank() && !checking, mono = true)
        SignalButton(if (busy) "Following…" else "Follow link", { busy = true; domainFlags = null; scope.launch { result = vm.trace(url); busy = false; result?.chain?.lastOrNull()?.let { checkDomain(it) } } }, Modifier.weight(1f), filled = true, enabled = url.isNotBlank() && !busy)
    }
    Text("\"Check domain only\" never contacts the site: it asks a DNS block list and the domain registry (RDAP) instead.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
    domainFlags?.let { fl -> HairPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SignalLabel("Domain check · no contact"); fl.sortedBy { -it.severity.ordinal }.forEach { FlagRow(it) } } } }
    vtTarget?.let { host ->
        HairPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalLabel("VirusTotal")
                vtFlags?.sortedBy { -it.severity.ordinal }?.forEach { FlagRow(it) }
                if (!hasVt) Text("Add a free VirusTotal key under System → API keys to see what ~70 security engines say about this domain here. Without one you can open the report in your browser.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
                SignalButton("Open domain report on VirusTotal", { vip.ringling.recon.ui.LinkOpener.open(ctx, vip.ringling.recon.data.lab.VirusTotal.domainPage(host)) }, Modifier.fillMaxWidth(), mono = true)
                val full = result?.chain?.lastOrNull() ?: LinkTracerNormalize(url)
                if (hasVt) {
                    SignalButton(if (vtBusy) "Looking up…" else "Look up the full link (sends it to VirusTotal)", { vtBusy = true; scope.launch { vtFlags = (vtFlags.orEmpty().filter { it.text.startsWith("VirusTotal:") && "domain" in it.text }) + vip.ringling.recon.data.lab.VirusTotal.flags(vm.vtUrl(full), "link").map { it.copy(text = "Link " + it.text) }; vtBusy = false } }, Modifier.fillMaxWidth(), enabled = !vtBusy, mono = true)
                } else SignalButton("Open link report on VirusTotal", { vip.ringling.recon.ui.LinkOpener.open(ctx, vip.ringling.recon.data.lab.VirusTotal.urlPage(full)) }, Modifier.fillMaxWidth(), mono = true)
            }
        }
    }
    result?.let { r ->
        val flags = LinkAnalysis.analyze(r.chain, failed = r.error != null && r.hops.isNotEmpty())
        r.error?.takeIf { r.hops.isEmpty() }?.let { Text(it, color = sig.alertText, style = MaterialTheme.typography.bodyMedium) }
        if (r.hops.isNotEmpty()) {
            HairPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SignalLabel("Redirect chain · ${r.hops.size} step${if (r.hops.size == 1) "" else "s"}")
                    r.hops.forEachIndexed { i, h ->
                        Column { Text("${i + 1}. ${h.code?.toString() ?: "no reply"}", color = sig.muted, style = ReconType.value.copy(fontSize = 11.sp)); Text(h.url, color = if (i == r.hops.lastIndex) sig.signal else sig.text, style = ReconType.value.copy(fontSize = 12.sp)) }
                    }
                    r.error?.let { Text(it, color = sig.muted, style = MaterialTheme.typography.bodySmall) }
                }
            }
            HairPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { SignalLabel("What stands out"); flags.sortedBy { -it.severity.ordinal }.forEach { FlagRow(it) } } }
            vip.ringling.recon.domain.LinkAnalysis.host(r.chain.last())?.let { h -> SignalButton("Scan $h", { onPivot(h) }, Modifier.fillMaxWidth(), mono = true) }
        }
    }
}

@Composable
private fun PasswordTool(vm: LabViewModel) {
    val sig = ReconTheme.signal
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Text("Has this password appeared in a breach? It is hashed on this phone and only the first 5 characters of the hash are sent. Nothing is saved.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(10.dp)).background(sig.panel).border(1.dp, sig.signal.copy(alpha = 0.4f), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (pw.isEmpty()) Text("Type a password to check", color = sig.muted, style = ReconType.value.copy(fontSize = 14.sp))
        BasicTextField(pw, { pw = it; msg = null }, Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation(), textStyle = ReconType.value.copy(fontSize = 15.sp, color = sig.text), cursorBrush = SolidColor(sig.signal))
    }
    SignalButton(if (busy) "Checking…" else "Check password", {
        busy = true
        scope.launch {
            msg = runCatching { vm.passwordCount(pw) }.fold(
                { n -> if (n == 0L) "Not found in known breaches. That doesn't make it strong: use a long, unique one." to true else "Seen ${"%,d".format(n)} times in breaches. Don't use it anywhere." to false },
                { "Couldn't check: ${it.message ?: it.javaClass.simpleName}" to true },
            )
            busy = false
        }
    }, Modifier.fillMaxWidth(), filled = true, enabled = pw.isNotEmpty() && !busy)
    msg?.let { (text, ok) -> Text(text, color = if (ok) sig.safeText else sig.alertText, style = MaterialTheme.typography.titleSmall) }
}

/** All tools at once, in a grid (no sideways scrolling): three per row. */
@Composable
private fun ToolChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val sig = ReconTheme.signal
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(3).forEachIndexed { row, group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                group.forEachIndexed { col, label ->
                    val i = row * 3 + col
                    val on = i == selected
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp)).background(if (on) sig.signal else androidx.compose.ui.graphics.Color.Transparent)
                            .border(1.dp, if (on) sig.signal else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Tab, onClickLabel = label) { onSelect(i) }.semantics { this.selected = on },
                        contentAlignment = Alignment.Center,
                    ) { Text(label.uppercase(), color = if (on) sig.onSignal else sig.muted, style = ReconType.button.copy(fontSize = 11.sp), maxLines = 1) }
                }
                repeat(3 - group.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MessageTool(onInspectLink: (String) -> Unit) {
    val sig = ReconTheme.signal
    var raw by rememberSaveable { mutableStateOf("") }
    var run by rememberSaveable { mutableStateOf(false) }
    val r = if (run) vip.ringling.recon.domain.ScamCheck.analyze(raw) else null
    Text("Paste a suspicious text, e-mail or chat message. It is checked for the usual scam cues on this phone: nothing is sent or saved.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
    MultiLineField(raw, { raw = it; run = false }, "Paste the message here")
    SignalButton("Check message", { run = true }, Modifier.fillMaxWidth(), filled = true, enabled = raw.isNotBlank())
    r?.let {
        HairPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    SignalLabel("Verdict")
                    StatusTag(when (it.verdict) { Verdict.CLEAN -> "No scam cues"; Verdict.CHECK -> "A few cues"; Verdict.SUSPICIOUS -> "Likely a scam"; Verdict.FORGED -> "Very likely a scam" }, when (it.verdict) { Verdict.CLEAN -> Tone.SAFE; Verdict.CHECK -> Tone.INFO; else -> Tone.ALERT })
                }
                it.flags.sortedBy { f -> -f.severity.ordinal }.forEach { f -> FlagRow(f) }
            }
        }
        if (it.links.isNotEmpty()) HairPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalLabel("Links in the message")
                it.links.take(5).forEach { l -> SignalButton("Inspect " + l.take(40), { onInspectLink(l) }, Modifier.fillMaxWidth(), mono = true) }
            }
        }
    }
}

@Composable
private fun QrTool(onInspectLink: (String) -> Unit) {
    val sig = ReconTheme.signal
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val bmp = runCatching { ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            text = bmp?.let { vip.ringling.recon.data.lab.QrReader.decode(it) }; failed = text == null
        }
    }
    var scanning by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val cameraPermission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> denied = !ok; scanning = ok }
    Text("Point the camera at a QR code, or pick a screenshot or photo of one. It is decoded on this phone (nothing is uploaded or saved) so you can see where it leads before you open it.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SignalButton(if (scanning) "Stop camera" else "Scan with camera", {
            if (scanning) scanning = false
            else if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) { denied = false; scanning = true }
            else cameraPermission.launch(android.Manifest.permission.CAMERA)
        }, Modifier.weight(1f), filled = true)
        SignalButton("Choose image", { scanning = false; pick.launch("image/*") }, Modifier.weight(1f))
    }
    if (denied) Text("Camera permission was declined. You can still pick an image, or allow the camera for Recon in system settings.", color = sig.alertText, style = MaterialTheme.typography.bodySmall)
    if (scanning) Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, sig.signal.copy(alpha = 0.5f), RoundedCornerShape(12.dp))) {
        QrCameraScanner(onResult = { t -> text = t; failed = false; scanning = false }, modifier = Modifier.fillMaxSize())
    }
    if (failed) Text("No QR code found in that image. Try a sharper, closer crop.", color = sig.alertText, style = MaterialTheme.typography.bodyMedium)
    text?.let { t ->
        HairPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SignalLabel("Decoded")
                Text(t, color = sig.text, style = ReconType.value.copy(fontSize = 13.sp))
                if (vip.ringling.recon.data.lab.LinkTracer.normalize(t) != null && (t.startsWith("http", true) || '.' in t)) SignalButton("Inspect this link", { onInspectLink(t) }, Modifier.fillMaxWidth(), filled = true)
            }
        }
    }
}

@Composable
private fun DecodeTool() {
    val sig = ReconTheme.signal
    var raw by rememberSaveable { mutableStateOf("") }
    val results = vip.ringling.recon.domain.LabDecoder.run(raw)
    Text("Paste Base64, hex, a URL-encoded string, a JWT or a Unix timestamp. Everything decodes here, live, on this phone.", color = sig.muted, style = MaterialTheme.typography.bodySmall)
    MultiLineField(raw, { raw = it }, "Paste text to decode")
    if (raw.isNotBlank() && results.isEmpty()) Text("Nothing recognisable. It may be encrypted or hashed, which can't be reversed.", color = sig.muted, style = MaterialTheme.typography.bodyMedium)
    results.forEach { d -> HairPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { SignalLabel(d.label, small = true); Text(d.value, color = sig.text, style = ReconType.value.copy(fontSize = 12.sp)) } } }
}

private fun LinkTracerNormalize(raw: String) = vip.ringling.recon.data.lab.LinkTracer.normalize(raw) ?: raw
