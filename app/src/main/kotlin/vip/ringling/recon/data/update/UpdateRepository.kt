package vip.ringling.recon.data.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.net.HttpException

/** What the rolling `latest` release says about itself. [build] is the CI run number (= versionCode). */
data class ReleaseInfo(
    val build: Int,
    val assetApiUrl: String,
    val assetName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val publishedAt: String?,
    val pageUrl: String?,
)

@Serializable private data class ReleaseJson(
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<AssetJson> = emptyList(),
)

@Serializable private data class AssetJson(val name: String = "", val url: String = "", val size: Long = 0)

class UpdateException(message: String) : Exception(message)

/**
 * Finds and downloads new builds from the rolling GitHub pre-release. The repo is private, so every call carries
 * the user's fine-grained read-only token. The token is only ever sent to api.github.com (OkHttp drops the
 * Authorization header when GitHub redirects the asset download to its storage host).
 */
class UpdateRepository @Inject constructor(private val http: Http, private val endpoints: Endpoints) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(token: String?): ReleaseInfo {
        val req = Request.Builder().url("${endpoints.github}/repos/${endpoints.updateRepo}/releases/tags/latest")
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        val body = http.execute(req) { r ->
            when (r.code) {
                200 -> r.body.string()
                401 -> throw UpdateException("GitHub rejected the token. Check it in Settings → API keys.")
                403, 429 -> throw UpdateException("GitHub refused or rate-limited the request (HTTP ${r.code}). Try again later.")
                404 -> throw UpdateException(
                    if (token == null) "No release visible. The repo is private, so add a GitHub token (Contents: read) in Settings."
                    else "No 'latest' release found, or the token can't see this repo. It needs Contents: read on ${endpoints.updateRepo}.",
                )
                else -> throw HttpException(r.code, "GitHub returned HTTP ${r.code}")
            }
        }
        return parseRelease(body)
    }

    internal fun parseRelease(body: String): ReleaseInfo {
        val r = try { json.decodeFromString<ReleaseJson>(body) } catch (e: Exception) { throw UpdateException("Unexpected response from GitHub.") }
        val asset = r.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?: throw UpdateException("The latest release has no APK attached (the build may still be running).")
        val text = r.body.orEmpty()
        val build = Regex("(?m)^build:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw UpdateException("The latest release doesn't say which build it is.")
        val sha = Regex("(?m)^sha256:\\s*([0-9a-fA-F]{64})").find(text)?.groupValues?.get(1)?.lowercase()
        return ReleaseInfo(build, asset.url, asset.name, asset.size, sha, r.publishedAt, r.htmlUrl)
    }

    /** Streams the APK to [dest], reporting 0..1. Verifies size and SHA-256 (when published); a bad file is deleted. */
    suspend fun download(info: ReleaseInfo, token: String?, dest: File, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        dest.delete()
        val req = Request.Builder().url(info.assetApiUrl).header("Accept", "application/octet-stream")
            .apply { if (token != null) header("Authorization", "Bearer $token") }.build()
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            http.execute(req) { r ->
                if (r.code != 200) throw UpdateException("Download failed (HTTP ${r.code}).")
                val total = r.body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
                var done = 0L
                r.body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); digest.update(buf, 0, n); done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            dest.delete(); throw UpdateException("Download interrupted: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: UpdateException) {
            dest.delete(); throw e
        }
        if (info.sizeBytes > 0 && dest.length() != info.sizeBytes) {
            dest.delete(); throw UpdateException("Downloaded file is the wrong size (${dest.length()} of ${info.sizeBytes} bytes).")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (info.sha256 != null && actual != info.sha256) {
            dest.delete(); throw UpdateException("Checksum mismatch — the download was corrupted or tampered with, so it was discarded.")
        }
        dest
    }

    companion object {
        fun isNewer(releaseBuild: Int, installedBuild: Int) = releaseBuild > installedBuild
    }
}
