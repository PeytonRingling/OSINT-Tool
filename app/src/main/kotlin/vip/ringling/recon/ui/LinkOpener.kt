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

    fun copy(context: Context, text: String) {
        runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Link", text)) }
    }
}
