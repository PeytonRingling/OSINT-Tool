package vip.ringling.recon.data.sources.identity

import java.net.URLEncoder
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** Bounds both parallelism and request start rate, so a 40-site sweep stays polite. */
class RateLimiter(maxConcurrent: Int, private val minIntervalMs: Long) {
    val permits = Semaphore(maxConcurrent)
    private val gate = Mutex()
    private var last = 0L

    suspend fun <T> run(block: suspend () -> T): T = permits.withPermit {
        if (minIntervalMs > 0) {
            gate.withLock {
                val wait = last + minIntervalMs - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                last = System.currentTimeMillis()
            }
        }
        block()
    }
}

internal sealed interface Probe {
    data object Found : Probe
    data object Absent : Probe
    data class Inconclusive(val reason: String) : Probe
}

/** Username → curated site list; email → a small set of registration checks. Every hit carries a confidence. */
class IdentitySource @Inject constructor(
    private val http: Http,
    private val endpoints: Endpoints,
    private val sites: SiteListProvider,
) : OsintSource {
    override val id = ModuleId.IDENTITY
    override val supportedInputTypes = setOf(InputType.USERNAME, InputType.EMAIL)

    internal var limiter = RateLimiter(maxConcurrent = 8, minIntervalMs = 60)

    override suspend fun run(input: ScanInput): Flow<Finding> = when (input.type) {
        InputType.USERNAME -> usernameSweep(input.raw)
        InputType.EMAIL -> emailChecks(input.raw)
        else -> channelFlow { }
    }

    private fun usernameSweep(username: String): Flow<Finding> = channelFlow {
        val all = sites.load()
        val applicable = all.filter { it.usernameRegex == null || Regex(it.usernameRegex).matches(username) }
        var found = 0
        var absent = 0
        val inconclusive = mutableListOf<String>()
        val tally = Mutex()
        coroutineScope { applicable.forEach { site ->
            launch {
                val result = try {
                    limiter.run { probe(site, username) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Probe.Inconclusive(e.javaClass.simpleName)
                }
                when (result) {
                    Probe.Found -> {
                        tally.withLock { found++ }
                        val url = (site.profileUrl ?: site.url).replace("{u}", username)
                        send(
                            Finding(
                                id = "IDENTITY-${site.name}", module = ModuleId.IDENTITY, title = site.name,
                                detail = "Account found for $username.", severity = Severity.INFO, url = url,
                                raw = """{"site":"${site.name}","probe":"${site.url.replace("{u}", username)}","method":"${site.method}"}""",
                                confidence = site.confidence,
                            ),
                        )
                    }
                    Probe.Absent -> tally.withLock { absent++ }
                    is Probe.Inconclusive -> tally.withLock { inconclusive += "${site.name} (${result.reason})" }
                }
            }
        } }
        send(summary(found, absent, inconclusive, applicable.size, all.size - applicable.size))
    }

    internal fun summary(found: Int, absent: Int, inconclusive: List<String>, checked: Int, skipped: Int): Finding {
        val parts = buildList {
            add("Checked $checked site${if (checked == 1) "" else "s"}: $found found, $absent not found")
            if (inconclusive.isNotEmpty()) add("${inconclusive.size} inconclusive (blocked, rate-limited or unreachable)")
            if (skipped > 0) add("$skipped skipped (username format invalid there)")
        }
        return Finding(
            id = "IDENTITY-summary", module = ModuleId.IDENTITY,
            title = if (found == 0) "No accounts found" else "${checked - found - inconclusive.size} sites with no account",
            detail = parts.joinToString(" · ") + ".",
            severity = if (found == 0 && inconclusive.size < checked) Severity.CLEAN else Severity.INFO,
            raw = if (inconclusive.isEmpty()) "" else "inconclusive: " + inconclusive.joinToString(", "),
        )
    }

    internal suspend fun probe(site: Site, username: String): Probe {
        val enc = URLEncoder.encode(username, "UTF-8")
        val target = site.url.replace("{u}", enc)
        val req = Request.Builder().url(target).header("Accept", "text/html,application/json;q=0.9,*/*;q=0.5").build()
        return http.execute(req) { r ->
            val code = r.code
            val absentCodes = setOf(404, 410) + site.absentCodes
            when {
                code in absentCodes -> Probe.Absent
                code == 429 -> Probe.Inconclusive("rate-limited")
                code == 403 || code == 401 -> Probe.Inconclusive("blocked")
                code in 500..599 -> Probe.Inconclusive("HTTP $code")
                code !in 200..299 -> Probe.Inconclusive("HTTP $code")
                else -> when (site.method) {
                    Method.status -> Probe.Found
                    Method.message -> {
                        val body = r.peekBody(200_000).string()
                        if (site.errorMsg != null && body.contains(site.errorMsg, ignoreCase = true)) Probe.Absent else Probe.Found
                    }
                    Method.redirect -> {
                        val finalUrl = r.request.url.toString()
                        if (site.errorUrl != null && finalUrl.contains(site.errorUrl)) Probe.Absent else Probe.Found
                    }
                }
            }
        }
    }

    // ---- email registration checks: brittle by nature, so each carries an honest confidence ----

    private fun emailChecks(email: String): Flow<Finding> = channelFlow {
        val results = mutableListOf<String>()
        val inconclusive = mutableListOf<String>()
        val lock = Mutex()
        val checks = listOf<Triple<String, Confidence, suspend () -> Probe>>(
            Triple("Gravatar", Confidence.HIGH) { gravatar(email) },
            Triple("Spotify", Confidence.MEDIUM) { spotify(email) },
        )
        coroutineScope { checks.forEach { (name, conf, fn) ->
            launch {
                val r = try { limiter.run { fn() } } catch (e: CancellationException) { throw e } catch (e: Exception) { Probe.Inconclusive(e.javaClass.simpleName) }
                when (r) {
                    Probe.Found -> send(
                        Finding(
                            "IDENTITY-$name", ModuleId.IDENTITY, name,
                            if (name == "Gravatar") "A public Gravatar profile exists for this address." else "This address is registered (the signup flow reports it as taken).",
                            Severity.INFO, null, """{"check":"$name"}""", conf,
                        ),
                    )
                    Probe.Absent -> lock.withLock { results += name }
                    is Probe.Inconclusive -> lock.withLock { inconclusive += "$name (${r.reason})" }
                }
            }
        } }
        val d = buildList {
            if (results.isNotEmpty()) add("Not registered on: ${results.joinToString(", ")}")
            if (inconclusive.isNotEmpty()) add("Inconclusive: ${inconclusive.joinToString(", ")}")
        }
        if (d.isNotEmpty()) send(Finding("IDENTITY-email-summary", ModuleId.IDENTITY, "Other registration checks", d.joinToString(" · ") + ".", if (inconclusive.isEmpty()) Severity.CLEAN else Severity.INFO))
    }

    internal suspend fun gravatar(email: String): Probe {
        val hash = MessageDigest.getInstance("MD5").digest(email.trim().lowercase().toByteArray()).joinToString("") { "%02x".format(it) }
        val req = Request.Builder().url("${endpoints.gravatar}/$hash.json").build()
        return http.execute(req) { r ->
            when (r.code) { 200 -> Probe.Found; 404 -> Probe.Absent; 429 -> Probe.Inconclusive("rate-limited"); else -> Probe.Inconclusive("HTTP ${r.code}") }
        }
    }

    /** Unofficial signup-validation endpoint; status 20 = already registered, 1 = available. May change without notice. */
    internal suspend fun spotify(email: String): Probe {
        val enc = URLEncoder.encode(email, "UTF-8")
        val req = Request.Builder().url("${endpoints.spotify}/signup/public/v1/account?validate=1&email=$enc").build()
        return http.execute(req) { r ->
            if (r.code != 200) return@execute Probe.Inconclusive("HTTP ${r.code}")
            val status = runCatching { Json.parseToJsonElement(r.body.string()).jsonObject["status"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() }.getOrNull()
            when (status) { 20 -> Probe.Found; 1 -> Probe.Absent; else -> Probe.Inconclusive("unexpected response") }
        }
    }
}
