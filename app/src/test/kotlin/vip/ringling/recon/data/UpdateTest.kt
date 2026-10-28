package vip.ringling.recon.data

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.update.UpdateException
import vip.ringling.recon.data.update.UpdateRepository

class UpdateTest {
    init { directNetworkForTests() }
    private val apk = ByteArray(300_000) { (it * 31).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private fun release(sha: String? = this.sha, size: Long = apk.size.toLong(), build: String = "build: 7") =
        fixture("github_release.json").replace("SHA_PLACEHOLDER", sha ?: "").replace("SIZE_PLACEHOLDER", size.toString())
            .let { if (sha == null) it.replace("sha256: \\n", "") else it }.replace("build: 7", build)

    private fun tmp(): File = File.createTempFile("recon-update", ".apk").also { it.deleteOnExit() }

    @Test fun parsesLatestReleaseAndSendsToken() = runTest {
        val (srv, router) = server("/gh/repos/testuser/OSINT-Tool/releases/tags/latest" to { json(release()) })
        srv.use {
            val info = UpdateRepository(Http(), srv.endpoints()).latest("ghp_secret")
            assertEquals(7, info.build); assertEquals("Recon.apk", info.assetName); assertEquals(apk.size.toLong(), info.sizeBytes)
            assertEquals(sha, info.sha256)
            val req = router.seen.single()
            assertEquals("Bearer ghp_secret", req.getHeader("Authorization"))
            assertEquals("application/vnd.github+json", req.getHeader("Accept"))
        }
    }

    @Test fun errorsAreExplained() = runTest {
        val notFound = server("/gh/" to { MockResponse().setResponseCode(404) })
        notFound.first.use {
            val repo = UpdateRepository(Http(), it.endpoints())
            assertTrue("GitHub token" in assertFailsWith<UpdateException> { repo.latest(null) }.message!!)
            assertTrue("Contents: read" in assertFailsWith<UpdateException> { repo.latest("t") }.message!!)
        }
        val unauthorized = server("/gh/" to { MockResponse().setResponseCode(401) })
        unauthorized.first.use { assertTrue("rejected the token" in assertFailsWith<UpdateException> { UpdateRepository(Http(), it.endpoints()).latest("bad") }.message!!) }
        val noApk = server("/gh/" to { json("""{"body":"build: 3","assets":[]}""") })
        noApk.first.use { assertTrue("no APK" in assertFailsWith<UpdateException> { UpdateRepository(Http(), it.endpoints()).latest("t") }.message!!) }
        val noBuild = server("/gh/" to { json("""{"body":"hello","assets":[{"name":"Recon.apk","url":"u","size":1}]}""") })
        noBuild.first.use { assertTrue("which build" in assertFailsWith<UpdateException> { UpdateRepository(Http(), it.endpoints()).latest("t") }.message!!) }
        val junk = server("/gh/" to { json("<html>") })
        junk.first.use { assertTrue("Unexpected" in assertFailsWith<UpdateException> { UpdateRepository(Http(), it.endpoints()).latest("t") }.message!!) }
    }

    @Test fun newerLogic() {
        assertTrue(UpdateRepository.isNewer(8, 7)); assertFalse(UpdateRepository.isNewer(7, 7)); assertFalse(UpdateRepository.isNewer(6, 7))
    }

    @Test fun downloadsVerifiesAndReportsProgress() = runTest {
        val (srv, router) = server(
            "/gh/repos/testuser/OSINT-Tool/releases/tags/latest" to { json(release()) },
            "/gh/repos/testuser/OSINT-Tool/releases/assets/42" to { MockResponse().setBody(Buffer().write(apk)) },
        )
        srv.use {
            val repo = UpdateRepository(Http(), srv.endpoints())
            val info = repo.latest("tok").copy(assetApiUrl = srv.url("/gh/repos/testuser/OSINT-Tool/releases/assets/42").toString())
            val progress = mutableListOf<Float>()
            val f = repo.download(info, "tok", tmp()) { progress += it }
            assertTrue(f.readBytes().contentEquals(apk))
            assertEquals(1f, progress.last()); assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
            val dl = router.seen.last()
            assertEquals("application/octet-stream", dl.getHeader("Accept")); assertEquals("Bearer tok", dl.getHeader("Authorization"))
        }
    }

    @Test fun corruptDownloadsAreDiscarded() = runTest {
        val bad = apk.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val (srv, _) = server("/gh/asset" to { MockResponse().setBody(Buffer().write(bad)) })
        srv.use {
            val repo = UpdateRepository(Http(), srv.endpoints())
            val base = repo.parseRelease(release())
            val info = base.copy(assetApiUrl = srv.url("/gh/asset").toString())
            val out = tmp()
            assertTrue("Checksum mismatch" in assertFailsWith<UpdateException> { repo.download(info, null, out) {} }.message!!)
            assertFalse(out.exists(), "a bad file must be deleted, never left to install")
            val wrongSize = info.copy(sizeBytes = 5, sha256 = null)
            assertTrue("wrong size" in assertFailsWith<UpdateException> { repo.download(wrongSize, null, out) {} }.message!!)
            assertFalse(out.exists())
        }
        val (down, _) = server("/gh/asset" to { MockResponse().setResponseCode(500) })
        down.use {
            val repo = UpdateRepository(Http(), it.endpoints())
            val info = repo.parseRelease(release()).copy(assetApiUrl = it.url("/gh/asset").toString())
            assertTrue("HTTP 500" in assertFailsWith<UpdateException> { repo.download(info, null, tmp()) {} }.message!!)
        }
    }

    @Test fun tokenIsNotForwardedWhenGitHubRedirectsToStorage() = runTest {
        val storage = MockWebServer().apply { enqueue(MockResponse().setBody(Buffer().write(apk))); start() }
        val (api, _) = server("/gh/asset" to { MockResponse().setResponseCode(302).setHeader("Location", storage.url("/signed-blob").toString()) })
        try {
            val repo = UpdateRepository(Http(), api.endpoints())
            // 127.0.0.1 vs localhost are different hosts to OkHttp, which is what matters for header stripping.
            val loc = storage.url("/signed-blob").toString().replace("127.0.0.1", "localhost")
            api.dispatcher = Router(listOf("/gh/asset" to { MockResponse().setResponseCode(302).setHeader("Location", loc) }))
            val info = repo.parseRelease(release()).copy(assetApiUrl = api.url("/gh/asset").toString())
            repo.download(info, "super-secret-token", tmp()) {}
            assertNull(storage.takeRequest().getHeader("Authorization"), "the token must never reach the storage host")
        } finally { api.shutdown(); storage.shutdown() }
    }
}
