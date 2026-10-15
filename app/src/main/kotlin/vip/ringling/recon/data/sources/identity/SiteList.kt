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
    private val cached by lazy { context.assets.open("sites.json").bufferedReader().use { SiteList.parse(it.readText()) } }
    override fun load() = cached
}
