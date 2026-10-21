package vip.ringling.recon.domain

enum class CheckState { PASS, PARTIAL, FAIL }

data class MailCheck(val name: String, val state: CheckState, val advice: String)

/** An A–F grade for a domain's e-mail spoofing protection, with the checklist behind it. */
data class MailGrade(val letter: String, val score: Int, val checks: List<MailCheck>)

/**
 * Scores what the domain scan already found (SPF 30, DMARC 40, DKIM 15, MTA-STS 10, BIMI 5). Null when SPF or DMARC didn't come back at all,
 * so a failed lookup never turns into a bad grade. Pure, so it is unit-tested.
 */
object MailGradeBuilder {
    fun compute(findings: List<Finding>): MailGrade? {
        fun get(key: String) = findings.firstOrNull { it.id == "DOMAIN-$key" }
        val spf = get("spf") ?: return null; val dmarc = get("dmarc") ?: return null
        val dkim = get("dkim"); val sts = get("mtasts"); val bimi = get("bimi")
        fun state(f: Finding?) = when (f?.severity) { Severity.CLEAN -> CheckState.PASS; Severity.INFO -> CheckState.PARTIAL; else -> CheckState.FAIL }
        fun pts(f: Finding?, full: Int, partial: Int) = when (state(f)) { CheckState.PASS -> full; CheckState.PARTIAL -> partial; CheckState.FAIL -> 0 }
        val checks = buildList {
            add(MailCheck("SPF", state(spf), when (state(spf)) { CheckState.PASS -> "Only listed senders are accepted."; CheckState.PARTIAL -> "Soft fail: tighten to -all once senders are listed."; CheckState.FAIL -> "Missing or too open: publish v=spf1 … -all." }))
            add(MailCheck("DMARC", state(dmarc), when (state(dmarc)) { CheckState.PASS -> "Spoofed mail is quarantined or rejected."; else -> "Missing or p=none: move to p=quarantine, then p=reject." }))
            dkim?.let { add(MailCheck("DKIM", state(it), if (state(it) == CheckState.PASS) "A signing key is published." else "No common selector found; make sure outgoing mail is signed.")) }
            sts?.let { add(MailCheck("MTA-STS", state(it), if (state(it) == CheckState.PASS) "Inbound mail TLS is enforced." else "Publish an MTA-STS policy to stop TLS downgrades.")) }
            bimi?.let { add(MailCheck("BIMI", state(it), if (state(it) == CheckState.PASS) "Brand logo is published." else "Optional: needs DMARC enforcement first.")) }
        }
        val score = pts(spf, 30, 15) + pts(dmarc, 40, 0) + pts(dkim, 15, 5) + pts(sts, 10, 0) + pts(bimi, 5, 0)
        val letter = when { score >= 90 -> "A"; score >= 75 -> "B"; score >= 55 -> "C"; score >= 35 -> "D"; else -> "F" }
        return MailGrade(letter, score, checks)
    }
}
