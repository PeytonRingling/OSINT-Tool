package vip.ringling.recon.domain

/** Turns what another app handed to Recon (shared text, selected text, mailto:/tel: links, clipboard) into the one-line query to search. */
object IncomingText {
    fun clean(text: String?): String? = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(200)?.takeIf { it.isNotEmpty() }

    /** (Android already percent-decodes the scheme-specific part.) `mailto:ann@b.com?subject=x` → `ann@b.com`; `tel:+1 (208) 555-0100` → `+1 (208) 555-0100`. */
    fun fromUri(scheme: String?, schemeSpecificPart: String?): String? {
        val ssp = schemeSpecificPart ?: return null
        return when (scheme?.lowercase()) {
            "mailto" -> clean(ssp.substringBefore('?').substringBefore(','))
            "tel" -> clean(ssp.substringBefore(';').substringBefore('?'))
            else -> null
        }
    }
}
