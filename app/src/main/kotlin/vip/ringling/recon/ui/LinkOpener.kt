package vip.ringling.recon.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** Opens links in an in-app Custom Tab, falling back to the default browser, then a chooser. Never silent: the caller is told when nothing worked. */
object LinkOpener {
    /** Only web links are opened. Adds https:// to bare hosts and to protocol-relative ("//host/path") links. */
    fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        return when {
            s.startsWith("https://", true) || s.startsWith("http://", true) -> s
            s.startsWith("//") -> "https:$s"
            "://" in s || s.startsWith("javascript:", true) || s.startsWith("intent:", true) -> null
            "." in s -> "https://$s"
            else -> null
        }
    }

    private fun Context.activity(): Activity? = generateSequence(this) { (it as? ContextWrapper)?.baseContext }.firstOrNull { it is Activity } as? Activity

    /** True if some app was started. */
    fun open(context: Context, raw: String): Boolean {
        val uri = normalize(raw)?.toUri() ?: return false
        val activity = context.activity()
        val host: Context = activity ?: context
        fun tag(i: Intent) = i.also { if (activity == null) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (runCatching { val t = CustomTabsIntent.Builder().setShowTitle(true).build(); tag(t.intent); t.launchUrl(host, uri) }.isSuccess) return true
        if (runCatching { host.startActivity(tag(Intent(Intent.ACTION_VIEW, uri))) }.isSuccess) return true
        return runCatching { host.startActivity(tag(Intent.createChooser(Intent(Intent.ACTION_VIEW, uri), "Open with"))) }.isSuccess
    }

    const val TOR_BROWSER = "org.torproject.torbrowser"

    enum class Route { BROWSER, TOR_BROWSER, COPY }

    /** Browsers and Custom Tabs don't use Recon's proxy, so in OPSEC mode a link opens only in Tor Browser, or is copied. */
    fun route(opsec: Boolean, torBrowserInstalled: Boolean): Route = when {
        !opsec -> Route.BROWSER
        torBrowserInstalled -> Route.TOR_BROWSER
        else -> Route.COPY
    }

    private fun hasTorBrowser(context: Context) = runCatching { context.packageManager.getPackageInfo(TOR_BROWSER, 0) }.isSuccess

    /** Opens [raw] the way OPSEC mode allows. Returns what to tell the user when the link was copied instead of opened, or null. */
    fun openOrCopy(context: Context, raw: String, opsec: Boolean = vip.ringling.recon.data.net.Opsec.on): String? {
        when (route(opsec, hasTorBrowser(context))) {
            Route.BROWSER -> if (open(context, raw)) return null
            Route.TOR_BROWSER -> {
                val uri = normalize(raw)?.toUri()
                if (uri != null && runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(TOR_BROWSER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return null
            }
            Route.COPY -> { copy(context, raw); return "OPSEC mode: link copied, not opened (your browser doesn't go through Tor). Paste it into Tor Browser." }
        }
        copy(context, raw)
        return "Couldn't open that link. It was copied instead."
    }

    fun copy(context: Context, text: String) {
        runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", text)) }
    }
}
