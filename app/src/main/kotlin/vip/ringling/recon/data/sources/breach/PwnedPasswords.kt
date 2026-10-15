package vip.ringling.recon.data.sources.breach

import java.security.MessageDigest
import javax.inject.Inject
import okhttp3.Request
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException

/**
 * HIBP "Pwned Passwords" range API (k-anonymity). The password is SHA-1 hashed on the device and only the first 5 hex characters
 * are sent; the matching suffix is looked up locally in the returned list. No API key; nothing is stored or logged.
 */
class PwnedPasswords(private val http: Http, private val base: String) {
    @Inject constructor(http: Http) : this(http, "https://api.pwnedpasswords.com")

    /** Number of times the password appears in known breaches (0 = not found). */
    suspend fun count(password: String): Long {
        val hash = sha1(password)
        val prefix = hash.take(5); val suffix = hash.drop(5)
        val body = http.execute(Request.Builder().url("$base/range/$prefix").header("Add-Padding", "true").build()) { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "Pwned Passwords answered ${r.code}")
            r.body.string()
        }
        return parse(body, suffix)
    }

    companion object {
        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02X".format(it) }

        /** Finds [suffix] in a "SUFFIX:COUNT" list. Padding rows (count 0) mean "not found". */
        fun parse(body: String, suffix: String): Long =
            body.lineSequence().map { it.trim() }.firstNotNullOfOrNull { l ->
                val (s, c) = l.split(':', limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
                if (s.equals(suffix, ignoreCase = true)) c?.toLongOrNull() else null
            } ?: 0L
    }
}
