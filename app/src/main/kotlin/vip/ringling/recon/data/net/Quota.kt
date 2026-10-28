package vip.ringling.recon.data.net

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import vip.ringling.recon.domain.ApiKeyId

enum class QuotaWindow { DAY, MONTH }

/** Keyed services whose free tier or bill is worth watching. [limit] is the published free tier; null when it depends on the plan. */
enum class Metered(val key: ApiKeyId, val host: String, val limit: Int?, val window: QuotaWindow?, val note: String) {
    HIBP(ApiKeyId.HIBP, "haveibeenpwned.com", null, null, "rate limit depends on your plan"),
    LEAKCHECK(ApiKeyId.LEAKCHECK, "leakcheck.io", null, null, "queries left shown when LeakCheck reports it"),
    VERIPHONE(ApiKeyId.VERIPHONE, "api.veriphone.io", 1000, QuotaWindow.MONTH, "free tier"),
    TWILIO(ApiKeyId.TWILIO_SID, "lookups.twilio.com", null, null, "billed per lookup"),
    FEC(ApiKeyId.FEC, "api.open.fec.gov", null, null, "1,000/hour on api.data.gov"),
    VIRUSTOTAL(ApiKeyId.VIRUSTOTAL, "www.virustotal.com", 500, QuotaWindow.DAY, "free tier, also 4/minute");

    companion object {
        fun of(host: String): Metered? = entries.firstOrNull { host == it.host || host.endsWith("." + it.host) }
    }
}

/** Local per-service counters. Only counts and status codes: never URLs, queries, or keys. */
@Serializable
data class Usage(
    val day: String = "", val today: Int = 0,
    val month: String = "", val thisMonth: Int = 0,
    val lastCode: Int? = null, val limitedAt: Long? = null,
    val remaining: Int? = null,
)

class QuotaMeter(private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val _usage = MutableStateFlow<Map<Metered, Usage>>(emptyMap())
    val usage: StateFlow<Map<Metered, Usage>> = _usage
    private var save: ((String) -> Unit)? = null
    private val json = Json { ignoreUnknownKeys = true }

    /** Restores saved counters and persists every later change through [save]. */
    fun attach(saved: String?, save: (String) -> Unit) {
        saved?.let { s -> runCatching { json.decodeFromString<Map<Metered, Usage>>(s) }.getOrNull()?.let { m -> _usage.value = m } }
        this.save = save
    }

    /** One request to [m] at [now]; [code] null when it never got a response. */
    fun record(m: Metered, now: Long, code: Int?, remaining: Int? = null) = change(m) { u ->
        val d = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        val day = d.toString(); val month = day.take(7)
        u.copy(
            day = day, today = if (u.day == day) u.today + 1 else 1,
            month = month, thisMonth = if (u.month == month) u.thisMonth + 1 else 1,
            lastCode = code ?: u.lastCode, limitedAt = if (code == 429) now else u.limitedAt,
            remaining = remaining ?: u.remaining,
        )
    }

    /** A source that reads the remaining allowance from the response body (LeakCheck). */
    fun reportRemaining(m: Metered, remaining: Int) = change(m) { it.copy(remaining = remaining) }

    fun reset() = _usage.update { emptyMap() }.also { persist() }

    private fun change(m: Metered, f: (Usage) -> Usage) {
        _usage.update { it + (m to f(it[m] ?: Usage())) }
        persist()
    }

    private fun persist() { save?.let { s -> runCatching { s(json.encodeToString<Map<Metered, Usage>>(_usage.value)) } } }

    /** Settings line, e.g. "3 today · 140 of 1,000 this month · rate-limited 2 h ago". Counts from a previous day/month read as 0. */
    fun describe(m: Metered, u: Usage?, now: Long): String {
        val d = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate().toString()
        val today = if (u?.day == d) u.today else 0
        val month = if (u?.month == d.take(7)) u.thisMonth else 0
        return buildList {
            when (m.window) {
                QuotaWindow.DAY -> add("$today of ${"%,d".format(m.limit)} today")
                QuotaWindow.MONTH -> { add("$today today"); add("$month of ${"%,d".format(m.limit)} this month") }
                null -> { add("$today today"); add("$month this month") }
            }
            u?.remaining?.let { add("$it left") }
            u?.limitedAt?.let { t -> (now - t).takeIf { it < 24 * 3_600_000L }?.let { add("rate-limited ${ago(it)}") } }
            if (m.limit != null && m.window != null && (if (m.window == QuotaWindow.DAY) today else month) >= m.limit * 0.8) add("near the free limit")
        }.joinToString(" · ")
    }

    private fun ago(ms: Long) = when { ms < 60_000 -> "just now"; ms < 3_600_000 -> "${ms / 60_000} min ago"; else -> "${ms / 3_600_000} h ago" }

    companion object { val shared = QuotaMeter() }
}

/** Counts requests to [Metered] hosts. [resolve] is injectable so tests can meter a MockWebServer. */
class QuotaInterceptor(private val meter: QuotaMeter, private val resolve: (String) -> Metered? = Metered::of, private val clock: () -> Long = System::currentTimeMillis) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val m = resolve(chain.request().url.host) ?: return chain.proceed(chain.request())
        val r = try { chain.proceed(chain.request()) } catch (e: IOException) { meter.record(m, clock(), null); throw e }
        meter.record(m, clock(), r.code, r.header("X-RateLimit-Remaining")?.toIntOrNull())
        return r
    }
}
