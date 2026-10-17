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
import vip.ringling.recon.data.net.BROWSER_USER_AGENT
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.Pivot
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
    /** Found, with the response body kept so a site's [Extract] spec can mine it. */
    data class Hit(val body: String) : Probe
    data object Absent : Probe
    data class Inconclusive(val reason: String) : Probe
}

/** Username → curated site list; email → a small set of registration checks. Every hit carries a confidence. */
class IdentitySource @Inject constructor(
    private val http: Http,
    private val endpoints: Endpoints,
    private val sites: SiteListProvider,
    private val health: SiteHealth = NoSiteHealth,
) : OsintSource {
    override val id = ModuleId.IDENTITY
    override val supportedInputTypes = setOf(InputType.USERNAME, InputType.EMAIL)

    internal var limiter = RateLimiter(maxConcurrent = 12, minIntervalMs = 40)

    override suspend fun run(input: ScanInput): Flow<Finding> = when (input.type) {
        InputType.USERNAME -> usernameSweep(input.raw)
        InputType.EMAIL -> emailChecks(input.raw)
        else -> channelFlow { }
    }

    private fun usernameSweep(username: String): Flow<Finding> = channelFlow {
        val all = sites.load()
        val formatOk = all.filter { it.usernameRegex == null || Regex(it.usernameRegex).matches(username) }
        val unreliable = formatOk.filter { health.shouldSkip(it.name) }
        val applicable = formatOk - unreliable.toSet()
        val outcomes = mutableMapOf<String, Boolean>()
        var found = 0
        var absent = 0
        val inconclusive = mutableListOf<String>()
        val tally = Mutex()
        val profiles = mutableListOf<Pair<String, Extracted>>()
        val avatars = mutableListOf<Pair<String, String>>()
        coroutineScope { applicable.forEach { site ->
            launch {
                val result = try {
                    limiter.run { probe(site, username) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Probe.Inconclusive(e.javaClass.simpleName)
                }
                tally.withLock { outcomes[site.name] = result !is Probe.Inconclusive }
                when (result) {
                    Probe.Found, is Probe.Hit -> {
                        tally.withLock { found++ }
                        val url = (site.profileUrl ?: site.url).replace("{u}", username)
                        val ex = if (result is Probe.Hit && site.extract != null) Extractor.extract(site.extract, result.body) else null
                        if (ex != null) tally.withLock {
                            profiles += site.name to ex
                            ex.avatar?.let { avatars += site.name to it }
                        }
                        val handleLinks = ex?.handles?.filter { !it.equals(username, true) }?.distinct().orEmpty()
                        send(
                            Finding(
                                id = "IDENTITY-${site.name}", module = ModuleId.IDENTITY, title = site.name,
                                detail = buildString {
                                    append(if (ex != null && ex.facts.isNotEmpty()) ex.facts.take(2).joinToString(" · ") { "${it.first}: ${it.second}" } else "Account found for $username.")
                                },
                                severity = Severity.INFO, url = url,
                                raw = """{"site":"${site.name}","probe":"${site.url.replace("{u}", username)}","method":"${site.method}"}""",
                                confidence = site.confidence,
                                imageUrl = ex?.avatar, imageLargeUrl = ex?.avatar,
                                facts = ex?.facts?.map { Fact(it.first, it.second) }.orEmpty(),
                                pivots = handleLinks.map { Pivot("Search @$it", it) },
                            ),
                        )
                    }
                    Probe.Absent -> tally.withLock { absent++ }
                    is Probe.Inconclusive -> tally.withLock { inconclusive += "${site.name} (${result.reason})" }
                }
            }
        } }
        // Remember reliability only when the sweep reached at least one site (otherwise the network or proxy is down, not the sites).
        if (found + absent > 0) outcomes.forEach { (name, ok) -> health.record(name, ok) }
        send(summary(found, absent, inconclusive, applicable.size, all.size - formatOk.size, unreliable.size))
        aggregate(username, profiles, avatars)?.let { send(it) }
        send(manualChecks(username))
    }

    /** Correlates what the individual profiles disclosed (names, places, sites, mail, linked handles) into one card with pivots. */
    internal fun aggregate(username: String, profiles: List<Pair<String, Extracted>>, avatars: List<Pair<String, String>>): Finding? {
        if (profiles.isEmpty()) return null
        fun collect(label: String) = profiles.flatMap { (site, ex) -> ex.facts.filter { it.first == label }.map { it.second to site } }
            .groupBy({ it.first.trim() }, { it.second }).filterKeys { it.isNotEmpty() }
        val facts = mutableListOf<Fact>()
        val pivots = mutableListOf<Pivot>()
        for (label in listOf("Name", "Location", "Website", "Email", "Bio", "Company")) {
            val m = collect(label)
            if (m.isEmpty()) continue
            facts += Fact(label, m.entries.joinToString("\n") { (v, sites) -> "$v (${sites.distinct().sorted().joinToString(", ")})" })
            if (label == "Email") m.keys.filter { '@' in it }.forEach { pivots += Pivot("Breach check $it", it) }
            if (label == "Website") m.keys.map { it.removePrefix("https://").removePrefix("http://").substringBefore('/').removePrefix("www.") }
                .filter { '.' in it && ' ' !in it }.distinct().forEach { pivots += Pivot("Domain intel $it", it) }
        }
        val linked = profiles.flatMap { (site, ex) -> ex.handles.filter { !it.equals(username, true) }.map { it to site } }.groupBy({ it.first }, { it.second })
        if (linked.isNotEmpty()) {
            facts += Fact("Linked handles", linked.entries.joinToString("\n") { (h, sites) -> "$h (via ${sites.distinct().sorted().joinToString(", ")})" })
            linked.keys.take(8).forEach { pivots += Pivot("Search @$it", it) }
        }
        val names = collect("Name").keys
        val links = avatars.map { (site, url) ->
            Link("Reverse-image $site avatar (Google Lens)", "https://lens.google.com/uploadbyurl?url=" + URLEncoder.encode(url, "UTF-8"))
        } + avatars.take(1).map { (_, url) -> Link("Reverse-image (Yandex)", "https://yandex.com/images/search?rpt=imageview&url=" + URLEncoder.encode(url, "UTF-8")) }
        return Finding(
            id = "IDENTITY-profile", module = ModuleId.IDENTITY,
            title = "Identity profile · ${profiles.size} enriched profile${if (profiles.size == 1) "" else "s"}",
            detail = (if (names.isNotEmpty()) "Names seen: ${names.joinToString(", ")}. " else "") +
                "Compiled from public profile data; same handle on different sites is not proof of the same person.",
            severity = Severity.INFO, confidence = Confidence.MEDIUM,
            imageUrl = avatars.firstOrNull()?.second, imageLargeUrl = avatars.firstOrNull()?.second,
            facts = facts, pivots = pivots.distinctBy { it.query }, links = links,
            raw = "",
        )
    }

    /** Big platforms block automated checks; give one-tap manual lookups instead of pretending we checked. */
    internal fun manualChecks(username: String): Finding {
        val u = URLEncoder.encode(username, "UTF-8")
        val links = listOf(
            "Instagram" to "https://www.instagram.com/$u/", "X / Twitter" to "https://x.com/$u", "TikTok" to "https://www.tiktok.com/@$u",
            "Facebook" to "https://www.facebook.com/$u", "LinkedIn" to "https://www.linkedin.com/in/$u", "Snapchat" to "https://www.snapchat.com/add/$u",
            "YouTube" to "https://www.youtube.com/@$u", "Twitch" to "https://www.twitch.tv/$u", "Pinterest" to "https://www.pinterest.com/$u/",
            "Threads" to "https://www.threads.net/@$u", "Reddit" to "https://www.reddit.com/user/$u", "Telegram" to "https://t.me/$u",
            "Google (exact handle)" to "https://www.google.com/search?q=%22$u%22", "DuckDuckGo (exact handle)" to "https://duckduckgo.com/?q=%22$u%22",
        ).map { Link(it.first, it.second) }
        return Finding(
            id = "IDENTITY-manual", module = ModuleId.IDENTITY, title = "Check manually · bot-walled platforms",
            detail = "These sites block automated lookups, so they were not checked. Open them to verify by eye.",
            severity = Severity.INFO, confidence = Confidence.LOW, links = links,
        )
    }

    internal fun summary(found: Int, absent: Int, inconclusive: List<String>, checked: Int, skipped: Int, unreliable: Int = 0): Finding {
        val parts = buildList {
            add("Checked $checked site${if (checked == 1) "" else "s"}: $found found, $absent not found")
            if (inconclusive.isNotEmpty()) add("${inconclusive.size} inconclusive (blocked, rate-limited or unreachable)")
            if (skipped > 0) add("$skipped skipped (username format invalid there)")
            if (unreliable > 0) add("$unreliable skipped (kept coming back blocked or unreachable; retried weekly)")
        }
        return Finding(
            id = "IDENTITY-summary", module = ModuleId.IDENTITY,
            title = if (found == 0) "No accounts found" else "${checked - found - inconclusive.size} sites with no account",
            detail = parts.joinToString(" · ") + ".",
            severity = if (found == 0 && inconclusive.size < checked) Severity.CLEAN else Severity.INFO,
            raw = if (inconclusive.isEmpty()) "" else "inconclusive: " + inconclusive.joinToString(", "),
        )
    }

    private val challengeMarkers = listOf("just a moment", "client challenge", "attention required", "checking your browser", "cf-browser-verification", "enable javascript and cookies")

    internal suspend fun probe(site: Site, username: String): Probe {
        val enc = URLEncoder.encode(username, "UTF-8")
        val target = site.url.replace("{u}", enc)
        val req = Request.Builder().url(target).header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/json;q=0.9,*/*;q=0.5").header("Accept-Language", "en-US,en;q=0.8").build()
        return http.execute(req) { r ->
            val code = r.code
            val absentCodes = setOf(404, 410) + site.absentCodes
            // Bot walls answer 403/503 (or even 200) with an interstitial; that says nothing about the account.
            val head = if (code in 200..299 || code == 403 || code == 503) r.peekBody(200_000).string() else ""
            val challenged = head.isNotEmpty() && challengeMarkers.any { head.contains(it, ignoreCase = true) } && head.length < 20_000
            val res = when {
                challenged -> Probe.Inconclusive("bot challenge")
                code in absentCodes -> Probe.Absent
                code == 429 || code == 406 -> Probe.Inconclusive("rate-limited")
                code == 403 || code == 401 -> Probe.Inconclusive("blocked")
                code !in 200..299 -> Probe.Inconclusive("HTTP $code")
                site.foundMsg != null -> if (head.contains(site.foundMsg.replace("{u}", username), ignoreCase = true)) Probe.Found else Probe.Absent
                site.method == Method.message ->
                    if (site.errorMsg != null && head.contains(site.errorMsg, ignoreCase = true)) Probe.Absent else Probe.Found
                site.method == Method.redirect ->
                    if (site.errorUrl != null && r.request.url.toString().contains(site.errorUrl)) Probe.Absent else Probe.Found
                else -> Probe.Found
            }
            if (res == Probe.Found && site.extract != null) Probe.Hit(head) else res
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
            Triple("Duolingo", Confidence.MEDIUM) { duolingo(email) },
            Triple("WordPress.com", Confidence.MEDIUM) { wordpress(email) },
        )
        coroutineScope { checks.forEach { (name, conf, fn) ->
            launch {
                val r = try { limiter.run { fn() } } catch (e: CancellationException) { throw e } catch (e: Exception) { Probe.Inconclusive(e.javaClass.simpleName) }
                when (r) {
                    Probe.Found, is Probe.Hit -> send(
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

    /** auth-options answers 403 "email_login_not_allowed" for a registered address and 404 "unknown_user" otherwise. Unofficial. */
    internal suspend fun wordpress(email: String): Probe {
        val req = Request.Builder().url("${endpoints.wordpress}/rest/v1.1/users/${URLEncoder.encode(email, "UTF-8")}/auth-options").build()
        return http.execute(req) { r ->
            val body = r.body.string()
            when {
                r.code == 403 && "email_login_not_allowed" in body -> Probe.Found
                r.code == 404 && "unknown_user" in body -> Probe.Absent
                r.code == 429 -> Probe.Inconclusive("rate-limited")
                else -> Probe.Inconclusive("HTTP ${r.code}")
            }
        }
    }

    /** Public user-search endpoint: a non-empty `users` list means an account uses this address. Unofficial; may change. */
    internal suspend fun duolingo(email: String): Probe {
        val req = Request.Builder().url("${endpoints.duolingo}/2017-06-30/users?email=${URLEncoder.encode(email, "UTF-8")}").build()
        return http.execute(req) { r ->
            if (r.code != 200) return@execute Probe.Inconclusive("HTTP ${r.code}")
            val users = runCatching { Json.parseToJsonElement(r.body.string()).jsonObject["users"] as? kotlinx.serialization.json.JsonArray }.getOrNull()
            when { users == null -> Probe.Inconclusive("unexpected response"); users.isEmpty() -> Probe.Absent; else -> Probe.Found }
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
