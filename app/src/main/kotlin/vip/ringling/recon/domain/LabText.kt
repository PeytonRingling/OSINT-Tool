package vip.ringling.recon.domain

import java.util.Base64

/** Result of the scam-text check: a level, the cues that triggered it and the links / numbers found in the text. */
data class ScamReport(val verdict: Verdict, val score: Int, val flags: List<Flag>, val links: List<String>)

/** Looks at a pasted message (SMS, e-mail, chat) for the usual scam cues. Offline and heuristic: it raises questions, it does not prove anything. */
object ScamCheck {
    private val url = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"')]+|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|info|top|xyz|click|link|ru|cn|co|io|me|ly|gl)/[^\s]*""")
    private val urgency = listOf("urgent", "immediately", "within 24 hours", "act now", "final notice", "last warning", "expires today", "right away", "asap")
    private val threat = listOf("suspended", "locked", "disabled", "unauthorized", "unusual activity", "will be closed", "legal action", "arrest", "penalty", "deactivated")
    private val credentials = listOf("verify your account", "confirm your identity", "enter your password", "login", "log in", "sign in", "update your payment", "confirm your details", "security code", "one-time code", "otp", "pin")
    private val money = listOf("gift card", "wire transfer", "bitcoin", "crypto", "western union", "bank transfer", "refund", "invoice", "payment failed", "you have won", "prize", "lottery", "inheritance")
    private val secrecy = listOf("don't tell", "do not tell", "keep this secret", "confidential", "between us")
    private val brands = mapOf("paypal" to "paypal.com", "amazon" to "amazon.com", "apple" to "apple.com", "microsoft" to "microsoft.com", "google" to "google.com", "netflix" to "netflix.com", "usps" to "usps.com", "fedex" to "fedex.com", "ups" to "ups.com", "dhl" to "dhl.com", "irs" to "irs.gov", "chase" to "chase.com", "wells fargo" to "wellsfargo.com", "bank of america" to "bankofamerica.com", "coinbase" to "coinbase.com", "venmo" to "venmo.com")

    fun analyze(text: String): ScamReport {
        val t = text.lowercase()
        val links = url.findAll(text).map { it.value.trimEnd('.', ',', ';', '!', '?') }.distinct().toList()
        val flags = mutableListOf<Flag>(); var score = 0
        fun cue(list: List<String>, label: String, pts: Int, sev: Severity = Severity.INFO) {
            val hit = list.filter { it in t }
            if (hit.isNotEmpty()) { flags += Flag(sev, "$label: ${hit.take(3).joinToString(", ") { "\"$it\"" }}."); score += pts }
        }
        cue(urgency, "Pressure to act fast", 2); cue(threat, "Threat of losing something", 2); cue(credentials, "Asks you to sign in or share a code", 3, Severity.EXPOSED)
        cue(money, "Money, prizes or hard-to-trace payment", 2); cue(secrecy, "Asks for secrecy", 3, Severity.EXPOSED)
        links.forEach { l ->
            val h = LinkAnalysis.host("https://" + l.removePrefix("https://").removePrefix("http://"))?.removePrefix("www.").orEmpty()
            val tricks = LinkAnalysis.analyze(listOf(if (l.startsWith("http", true)) l else "https://$l")).filter { it.severity != Severity.CLEAN }
            if (tricks.isNotEmpty()) { flags += Flag(Severity.EXPOSED, "Link $h: ${tricks.first().text}"); score += 3 }
            brands.forEach { (name, real) -> if (name in t && h.isNotEmpty() && !(h == real || h.endsWith(".$real"))) { flags += Flag(Severity.EXPOSED, "Mentions $name but the link goes to $h, not $real."); score += 4 } }
            if (h.split('.').lastOrNull() in setOf("top", "xyz", "click", "link", "info", "ru", "cn")) { flags += Flag(Severity.INFO, "Link ends in .${h.substringAfterLast('.')}, a suffix often used by throwaway sites."); score += 1 }
        }
        if (Regex("""\b(?:\+?\d[\d\s().-]{8,}\d)\b""").containsMatchIn(text) && (t.contains("call") || t.contains("text us"))) { flags += Flag(Severity.INFO, "Asks you to call or text a number; scammers use this to dodge link checks."); score += 1 }
        if (Regex("""\.(?:html?|zip|exe|apk|scr|iso)\b""").containsMatchIn(t)) { flags += Flag(Severity.EXPOSED, "Mentions an attachment type that is often malicious (.html, .zip, .exe, .apk)."); score += 3 }
        val verdict = when { score >= 9 -> Verdict.FORGED; score >= 5 -> Verdict.SUSPICIOUS; score >= 2 -> Verdict.CHECK; else -> Verdict.CLEAN }
        if (flags.isEmpty()) flags += Flag(Severity.CLEAN, "No common scam cues found. That doesn't prove it's real: when in doubt, contact the sender through a number or site you already trust.")
        return ScamReport(verdict, score, flags, links)
    }
}

data class Decoded(val label: String, val value: String)

/** One-paste decoder for the encodings that show up in links, tokens and logs: Base64, hex, URL-encoding, JWT, epoch times. */
object LabDecoder {
    private fun printable(b: ByteArray): String? {
        val s = runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString() }.getOrNull() ?: return null
        return s.takeIf { it.isNotEmpty() && it.count { c -> c.isISOControl() && c !in "\n\r\t" } == 0 }
    }
    private fun b64(s: String): ByteArray? = runCatching { Base64.getDecoder().decode(s.trim().replace('-', '+').replace('_', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }) }.getOrNull()
    private fun time(sec: Long) = java.time.Instant.ofEpochSecond(sec).toString().replace("T", " ").removeSuffix("Z") + " UTC"

    fun run(raw: String): List<Decoded> {
        val s = raw.trim(); if (s.isEmpty()) return emptyList()
        val out = mutableListOf<Decoded>()
        // JWT first: three base64url parts.
        val parts = s.split('.')
        if (parts.size == 3 && parts.all { it.matches(Regex("[A-Za-z0-9_-]*")) }) {
            val h = b64(parts[0])?.let(::printable); val p = b64(parts[1])?.let(::printable)
            if (h != null && p != null && h.trimStart().startsWith("{") && p.trimStart().startsWith("{")) {
                out += Decoded("JWT header", h); out += Decoded("JWT payload", p)
                listOf("exp" to "Expires", "iat" to "Issued", "nbf" to "Not before").forEach { (k, l) -> Regex("\"$k\"\\s*:\\s*(\\d{9,11})").find(p)?.groupValues?.get(1)?.toLongOrNull()?.let { out += Decoded(l, time(it)) } }
                out += Decoded("Note", "The signature is NOT verified here: a decoded token proves nothing about who issued it.")
                return out
            }
        }
        if (s.matches(Regex("^\\d{10}$"))) out += Decoded("Unix time (seconds)", time(s.toLong()))
        if (s.matches(Regex("^\\d{13}$"))) out += Decoded("Unix time (milliseconds)", time(s.toLong() / 1000))
        if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(s) || '+' in s && '%' in s) runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrNull()?.takeIf { it != s }?.let { out += Decoded("URL-decoded", it) }
        val hex = s.replace(Regex("(?i)0x|[\\s:]"), "")
        if (hex.length >= 4 && hex.length % 2 == 0 && hex.matches(Regex("(?i)[0-9a-f]+"))) printable(ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })?.let { out += Decoded("Hex → text", it) }
        if (s.length >= 8 && s.matches(Regex("[A-Za-z0-9+/_-]+={0,2}"))) b64(s)?.let(::printable)?.let { out += Decoded("Base64 → text", it) }
        if (s.matches(Regex("(?i)[a-z]{3,}")) || s.contains(' ')) {
            val rot = s.map { c -> when (c) { in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26; in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26; else -> c } }.joinToString("")
            if (rot != s && s.length >= 6 && s.any { it.isLetter() }) out += Decoded("ROT13", rot)
        }
        return out
    }
}
