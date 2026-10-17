package vip.ringling.recon.data.sources.identity

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which username sites keep coming back inconclusive (blocked, rate-limited, gone), so a sweep stops wasting time on them.
 * After [STREAK_LIMIT] inconclusive sweeps in a row a site is skipped for a week, then tried once more.
 */
interface SiteHealth {
    fun shouldSkip(site: String, now: Long = System.currentTimeMillis()): Boolean
    fun record(site: String, conclusive: Boolean, now: Long = System.currentTimeMillis())

    companion object {
        const val STREAK_LIMIT = 5
        const val RETRY_AFTER_MS = 7L * 24 * 60 * 60 * 1000
        /** Pure policy: skip while the streak is at the limit and the last failure is recent. */
        fun skip(streak: Int, lastFailure: Long, now: Long) = streak >= STREAK_LIMIT && now - lastFailure < RETRY_AFTER_MS
    }
}

/** No memory: nothing is ever skipped (default for tests). */
object NoSiteHealth : SiteHealth {
    override fun shouldSkip(site: String, now: Long) = false
    override fun record(site: String, conclusive: Boolean, now: Long) {}
}

class InMemorySiteHealth : SiteHealth {
    private val map = mutableMapOf<String, Pair<Int, Long>>()
    override fun shouldSkip(site: String, now: Long) = map[site]?.let { SiteHealth.skip(it.first, it.second, now) } ?: false
    override fun record(site: String, conclusive: Boolean, now: Long) { if (conclusive) map.remove(site) else map[site] = ((map[site]?.first ?: 0) + 1) to now }
}

/** Persisted in a private SharedPreferences file as "streak|lastFailureMillis" per site. Contains no user data. */
@Singleton
class PrefsSiteHealth @Inject constructor(@ApplicationContext context: Context) : SiteHealth {
    private val prefs = context.getSharedPreferences("recon_site_health", Context.MODE_PRIVATE)

    private fun read(site: String): Pair<Int, Long>? = prefs.getString(site, null)?.split('|')?.let { p -> p.getOrNull(0)?.toIntOrNull()?.let { s -> s to (p.getOrNull(1)?.toLongOrNull() ?: 0L) } }

    override fun shouldSkip(site: String, now: Long) = read(site)?.let { SiteHealth.skip(it.first, it.second, now) } ?: false

    override fun record(site: String, conclusive: Boolean, now: Long) {
        if (conclusive) { if (prefs.contains(site)) prefs.edit().remove(site).apply() }
        else prefs.edit().putString(site, "${(read(site)?.first ?: 0) + 1}|$now").apply()
    }
}
