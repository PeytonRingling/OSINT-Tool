package vip.ringling.recon.data.sources.code

import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.domain.ApiKeyId
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.Pivot
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/**
 * GitHub public footprint for a username: profile, organisations, SSH-key count, and the e-mail addresses that appear in the author field of
 * commits the user recently pushed (public events). Uses only unauthenticated public endpoints; a GitHub token (Settings) just raises the rate limit.
 */
class GithubSource @Inject constructor(private val http: Http, private val endpoints: Endpoints, private val keys: ApiKeys) : OsintSource {
    override val id = ModuleId.CODE
    override val supportedInputTypes = setOf(InputType.USERNAME)

    private sealed interface Reply { data class Ok(val body: String) : Reply; data object Missing : Reply; data class Failed(val code: Int) : Reply }

    override suspend fun run(input: ScanInput): Flow<Finding> = channelFlow {
        val u = input.raw
        if (!Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$").matches(u)) return@channelFlow
        val token = keys.get(ApiKeyId.GITHUB)?.takeIf { it.isNotBlank() }
        suspend fun get(path: String): Reply = try {
            http.execute(Request.Builder().url("${endpoints.github}/$path").header("Accept", "application/vnd.github+json").apply { if (token != null) header("Authorization", "Bearer $token") }.build()) { r ->
                when (r.code) { 200 -> Reply.Ok(r.body.string()); 404 -> Reply.Missing; else -> Reply.Failed(r.code) }
            }
        } catch (e: java.io.IOException) { Reply.Failed(0) }
        val (profile, events, orgs, ssh) = coroutineScope {
            listOf(async { get("users/$u") }, async { get("users/$u/events/public?per_page=100") }, async { get("users/$u/orgs") }, async { get("users/$u/keys") }).map { it.await() }
        }
        when (profile) {
            Reply.Missing -> return@channelFlow
            is Reply.Failed -> {
                send(Finding("CODE-github-error", ModuleId.CODE, "GitHub unavailable", if (profile.code == 403 || profile.code == 429) "GitHub's unauthenticated rate limit (60/hour) was hit. Add a GitHub token in Settings to raise it." else "GitHub returned HTTP ${profile.code}.", Severity.INFO))
                return@channelFlow
            }
            is Reply.Ok -> {
                val orgNames = (orgs as? Reply.Ok)?.let { GithubAnalysis.orgs(it.body) }.orEmpty()
                val keyCount = (ssh as? Reply.Ok)?.let { GithubAnalysis.count(it.body) }
                send(GithubAnalysis.profile(profile.body, orgNames, keyCount))
                (events as? Reply.Ok)?.let { GithubAnalysis.commitEmails(u, it.body) }?.let { send(it) }
            }
        }
    }
}

object GithubAnalysis {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.s(k: String) = this[k]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    fun orgs(body: String): List<String> = runCatching { json.parseToJsonElement(body).jsonArray.mapNotNull { it.jsonObject.s("login") } }.getOrDefault(emptyList())
    fun count(body: String): Int? = runCatching { json.parseToJsonElement(body).jsonArray.size }.getOrNull()

    fun profile(body: String, orgs: List<String>, sshKeys: Int?): Finding {
        val o = json.parseToJsonElement(body).jsonObject
        val login = o.s("login") ?: "?"
        val blog = o.s("blog")?.let { if (it.startsWith("http")) it else "https://$it" }
        val twitter = o.s("twitter_username")
        val email = o.s("email")
        val host = blog?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/')?.removePrefix("www.")?.takeIf { '.' in it }
        val facts = buildList {
            o.s("name")?.let { add(Fact("Name", it)) }; o.s("company")?.let { add(Fact("Company", it)) }; o.s("location")?.let { add(Fact("Location", it)) }
            email?.let { add(Fact("Public email", it)) }; blog?.let { add(Fact("Website", it)) }; twitter?.let { add(Fact("Twitter / X", "@$it")) }
            o.s("bio")?.let { add(Fact("Bio", it)) }
            add(Fact("Created", o.s("created_at")?.take(10) ?: "?")); o.s("updated_at")?.let { add(Fact("Last profile update", it.take(10))) }
            add(Fact("Public repos", o.s("public_repos") ?: "0")); add(Fact("Followers", o.s("followers") ?: "0")); add(Fact("Following", o.s("following") ?: "0"))
            o.s("id")?.let { add(Fact("Account ID", it)) }
            if (orgs.isNotEmpty()) add(Fact("Organisations", orgs.joinToString(", ")))
            sshKeys?.let { add(Fact("Public SSH keys", it.toString())) }
        }
        return Finding(
            "CODE-github", ModuleId.CODE, "GitHub · @$login", listOfNotNull(o.s("name"), o.s("company"), o.s("location")).joinToString(" · ").ifEmpty { "Public profile found." },
            Severity.INFO, url = o.s("html_url"), confidence = Confidence.HIGH, imageUrl = o.s("avatar_url"), imageLargeUrl = o.s("avatar_url"), facts = facts,
            pivots = listOfNotNull(
                email?.let { Pivot("Breach check $it", it) }, host?.let { Pivot("Domain intel $it", it) }, twitter?.let { Pivot("Search @$it", it) },
            ),
            links = listOf(
                Link("Public SSH keys (.keys)", "https://github.com/$login.keys"), Link("GPG keys (.gpg)", "https://github.com/$login.gpg"),
                Link("Their repositories", "https://github.com/$login?tab=repositories"), Link("Code search for the handle", "https://github.com/search?q=%22$login%22&type=code"),
            ),
        )
    }

    private val noreply = Regex("""(?i)(^|\+)[^@]*@users\.noreply\.github\.com$|^noreply@github\.com$""")

    /** Author names/emails from PushEvent commits. Those are whatever git was configured with, so they can be old, shared or spoofed. */
    fun commitEmails(login: String, body: String): Finding? {
        val events = runCatching { json.parseToJsonElement(body).jsonArray }.getOrNull() ?: return null
        val seen = linkedMapOf<String, MutableSet<String>>()
        events.forEach { e ->
            val ev = e as? JsonObject ?: return@forEach
            if (ev.s("type") != "PushEvent") return@forEach
            val commits = ev["payload"]?.jsonObject?.get("commits") as? JsonArray ?: return@forEach
            commits.forEach { c ->
                val a = c.jsonObject["author"]?.jsonObject ?: return@forEach
                val mail = a.s("email")?.lowercase() ?: return@forEach
                if (noreply.containsMatchIn(mail)) return@forEach
                seen.getOrPut(mail) { linkedSetOf() }.also { s -> a.s("name")?.let(s::add) }
            }
        }
        if (seen.isEmpty()) return null
        return Finding(
            "CODE-github-emails", ModuleId.CODE, "Commit e-mails · ${seen.size} found",
            "Addresses git recorded as the author of commits this user pushed publicly:\n" + seen.entries.joinToString("\n") { (m, n) -> "$m" + if (n.isNotEmpty()) " (${n.joinToString(", ")})" else "" } +
                "\nThese can be personal, a work address or someone else's (co-authors, shared machines), so treat as leads.",
            Severity.EXPOSED, confidence = Confidence.MEDIUM, tags = listOf("Email"), hotTags = setOf("Email"),
            facts = seen.entries.map { (m, n) -> Fact(m, n.joinToString(", ").ifEmpty { "name not recorded" }) },
            pivots = seen.keys.take(8).map { Pivot("Breach check $it", it) },
            links = listOf(Link("Prevent this (GitHub docs)", "https://docs.github.com/en/account-and-profile/setting-up-and-managing-your-personal-account-on-github/managing-email-preferences/blocking-command-line-pushes-that-expose-your-personal-email-address")),
            raw = "",
        )
    }
}
