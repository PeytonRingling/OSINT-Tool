package vip.ringling.recon.data.sources.identity

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vip.ringling.recon.domain.Confidence

@Serializable
enum class Method { status, message, redirect }

@Serializable
data class Site(
    val name: String,
    /** URL probed; {u} is replaced by the (URL-encoded) username. */
    val url: String,
    val method: Method = Method.status,
    val confidence: Confidence = Confidence.MEDIUM,
    /** Human-facing profile URL when it differs from the probed one. */
    val profileUrl: String? = null,
    /** message: the body contains this when the account does NOT exist. */
    val errorMsg: String? = null,
    /** message variant: the body must CONTAIN this ({u} is substituted) for the account to count as found. */
    val foundMsg: String? = null,
    /** redirect: the final URL contains this when the account does NOT exist. */
    val errorUrl: String? = null,
    /** Extra status codes that mean "does not exist" (default 404/410). */
    val absentCodes: List<Int> = emptyList(),
    /** Usernames not matching this can't exist on the site, so it is skipped. */
    val usernameRegex: String? = null,
    /** Optional profile enrichment (see [Extract]); the response body of a hit is mined for facts, avatar and linked handles. */
    val extract: Extract? = null,
    /** Free-form grouping (WhatsMyName category). */
    val category: String? = null,
    /** A handle known to exist, used by tools/check_sites.py to detect drifted detection rules. Unused at runtime. */
    val known: String? = null,
)

@Serializable
private data class SiteFile(val version: Int = 1, val sites: List<Site>)

object SiteList {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(text: String): List<Site> = json.decodeFromString<SiteFile>(text).sites
}

fun interface SiteListProvider { fun load(): List<Site> }

@Singleton
class AssetSiteList @Inject constructor(@ApplicationContext private val context: Context) : SiteListProvider {
    /** Curated list first (it wins on name clashes), then the calibrated WhatsMyName-derived list (CC BY-SA 4.0, attributed in Settings). */
    private val cached by lazy {
        val own = context.assets.open("sites.json").bufferedReader().use { SiteList.parse(it.readText()) }
        val wmn = runCatching { context.assets.open("wmn_sites.json").bufferedReader().use { SiteList.parse(it.readText()) } }.getOrDefault(emptyList())
        val names = own.map { it.name.lowercase() }.toSet()
        own + wmn.filter { it.name.lowercase() !in names }
    }
    override fun load() = cached
}
