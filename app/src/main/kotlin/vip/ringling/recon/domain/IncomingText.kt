package vip.ringling.recon.domain

/** Turns what another app handed to Recon (shared text, selected text, mailto:/tel: links, clipboard) into the one-line query to search. */
/** What Recon does with something another app (or a web page, through a mailto:/tel: link) handed it. Nothing here ever starts a search. */
sealed interface Incoming {
    /** Put in the search box; the user presses go. */
    data class Prefill(val text: String) : Incoming
    /** Shown in the Lab's link inspector; followed only when the user taps. */
    data class Link(val url: String) : Incoming
    /** Read the clipboard into the search box once the window has focus. */
    data object ClipboardOnFocus : Incoming
}

object IncomingText {
    const val ACTION_SCAN_CLIPBOARD = "vip.ringling.recon.SCAN_CLIPBOARD"

    /** Maps an intent's parts to what to do; null = ignore. Any app can send these (the activity is exported), so none of them searches. */
    fun route(action: String?, mimeType: String?, text: String?, scheme: String?, schemeSpecificPart: String?): Incoming? {
        val query = when (action) {
            "android.intent.action.SEND" -> if (mimeType?.startsWith("text/") == true) clean(text) else null
            "android.intent.action.PROCESS_TEXT" -> clean(text)
            "android.intent.action.VIEW", "android.intent.action.SENDTO", "android.intent.action.DIAL" -> fromUri(scheme, schemeSpecificPart)
            ACTION_SCAN_CLIPBOARD -> return Incoming.ClipboardOnFocus
            else -> null
        } ?: return null
        return if (action != "android.intent.action.VIEW" && isLink(query)) Incoming.Link(query) else Incoming.Prefill(query)
    }

    fun clean(text: String?): String? = text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(200)?.takeIf { it.isNotEmpty() }

    /** A shared web link (http/https, no spaces) goes to the Lab's link inspector instead of being searched. */
    fun isLink(text: String?): Boolean = text != null && Regex("""(?i)^https?://\S+$""").matches(text.trim())

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
