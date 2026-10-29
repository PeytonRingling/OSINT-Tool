package vip.ringling.recon.data

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.Signature
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import vip.ringling.recon.data.net.Http
import vip.ringling.recon.data.secret.InMemoryApiKeys
import vip.ringling.recon.data.update.ApkInstaller
import vip.ringling.recon.data.update.SignerMatch
import vip.ringling.recon.data.update.SigningCheck
import vip.ringling.recon.data.update.UpdateManager
import vip.ringling.recon.data.update.UpdateRepository
import vip.ringling.recon.data.update.UpdateState

/**
 * The in-app update from the rolling `latest` release: find the release, download Recon.apk, check its signer against the installed app's,
 * hand it to the installer. Robolectric can't parse a real APK's signing block, so the certificates PackageManager reports are set through
 * its shadow; they are real DER certificates whose SHA-256 is what `apksigner verify --print-certs` prints. The installer is a stand-in that
 * records what it was given (Robolectric's PackageInstaller reports a commit as failed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class UpdateSignerTest {
    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private fun cert(name: String) = checkNotNull(javaClass.getResourceAsStream("/fixtures/update/$name")).readBytes()
    private val release = cert("test-signer-a.der")
    private val stranger = cert("test-signer-b.der")
    private val apk = ByteArray(64_000) { (it * 7).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val downloaded get() = File(ctx.cacheDir, "updates/Recon.apk")

    private fun info(vararg certs: ByteArray) = PackageInfo().apply {
        packageName = ctx.packageName
        @Suppress("DEPRECATION")
        signatures = certs.map(::Signature).toTypedArray()
    }

    /** One build newer than this one: CI stamps versionCode with its run number, so a fixed number would be "up to date" there. */
    private val newerBuild = vip.ringling.recon.BuildConfig.VERSION_CODE + 1

    /** The release body exactly as .github/workflows/build.yml writes it. */
    private fun releaseJson(base: String) = """
        {"html_url":"https://github.com/testuser/OSINT-Tool/releases/tag/latest","published_at":"2026-10-28T00:00:00Z",
         "body":"Rolling build of `abc1234` — sideload `Recon.apk`; it installs over the previous build, or use Settings → Updates in the app.\nbuild: $newerBuild\nsha256: $apkSha\n",
         "assets":[{"url":"$base/gh/repos/testuser/OSINT-Tool/releases/assets/42","name":"Recon.apk","size":${apk.size}}]}
    """.trimIndent()

    private class RecordingInstaller(context: Application) : ApkInstaller(context) {
        val installed = mutableListOf<ByteArray>()
        override fun canInstall() = true
        override fun install(apk: File) { synchronized(installed) { installed += apk.readBytes() } }
    }

    /** Runs check() then downloadAndInstall(); returns where the manager ended and what reached the installer. */
    private fun update(downloadSigner: ByteArray?): Pair<UpdateState, List<ByteArray>> {
        val pm = shadowOf(ctx.packageManager)
        pm.installPackage(info(release))
        pm.setPackageArchiveInfo(downloaded.path, if (downloadSigner == null) info() else info(downloadSigner))
        lateinit var base: String
        val (srv, _) = server(
            "/gh/repos/testuser/OSINT-Tool/releases/tags/latest" to { json(releaseJson(base)) },
            "/gh/repos/testuser/OSINT-Tool/releases/assets/42" to { MockResponse().setBody(Buffer().write(apk)) },
        )
        base = srv.url("/").toString().trimEnd('/')
        return srv.use {
            val installer = RecordingInstaller(ctx)
            val manager = UpdateManager(ctx, UpdateRepository(Http(), srv.endpoints()), installer, InMemoryApiKeys())
            manager.check()
            val found = awaitState(manager) { it is UpdateState.Available || it is UpdateState.Failed }
            assertTrue(found is UpdateState.Available, "the latest release is offered as an update: $found")
            manager.downloadAndInstall()
            // Installing is published just before the installer is called, so an accepted update also waits for that call.
            val end = awaitState(manager) { it is UpdateState.Failed || (it is UpdateState.Installing && synchronized(installer.installed) { installer.installed.isNotEmpty() }) }
            end to synchronized(installer.installed) { installer.installed.toList() }
        }
    }

    /** Polls until [done] holds (the manager works on a background dispatcher). */
    private fun awaitState(manager: UpdateManager, done: (UpdateState) -> Boolean): UpdateState {
        val deadline = System.currentTimeMillis() + 10_000
        while (!done(manager.state.value)) {
            check(System.currentTimeMillis() < deadline) { "timed out in ${manager.state.value}" }
            Thread.sleep(10)
        }
        return manager.state.value
    }

    @Test fun theReleaseWorkflowPublishesWhatTheUpdaterReads() {
        val workflow = File("../.github/workflows/build.yml").readText()
        assertTrue(Regex("(?m)^\\s+tag_name: latest$").containsMatchIn(workflow) && Regex("(?m)^\\s+files: Recon\\.apk$").containsMatchIn(workflow), "publishes Recon.apk on the latest tag")
        assertFalse("debug.keystore" in workflow, "no fallback to the public debug key")
        val lines = workflow.lines()
        val start = lines.indexOfFirst { it.trim() == "body: |" } + 1
        val indent = lines[start].takeWhile { it == ' ' }
        val body = lines.drop(start).takeWhile { it.startsWith(indent) }.joinToString("\n") { it.removePrefix(indent) }
            .replace("\${{ github.sha }}", "abc1234").replace("\${{ github.run_number }}", "42").replace("\${{ steps.meta.outputs.sha }}", apkSha)
        val json = kotlinx.serialization.json.buildJsonObject {
            put("body", kotlinx.serialization.json.JsonPrimitive(body))
            put("assets", kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.buildJsonObject { put("name", kotlinx.serialization.json.JsonPrimitive("Recon.apk")); put("url", kotlinx.serialization.json.JsonPrimitive("u")); put("size", kotlinx.serialization.json.JsonPrimitive(1)) }) })
        }.toString()
        val info = UpdateRepository(Http(), vip.ringling.recon.data.net.Endpoints()).parseRelease(json)
        assertEquals(42, info.build); assertEquals(apkSha, info.sha256); assertEquals("Recon.apk", info.assetName)
    }

    @Test fun digestsMatchApksigner() {
        assertEquals(setOf("2f4101cfcd32e2421c3fbb82e436696f63aca7312f60737504f357bed8985b49"), SigningCheck.certs(info(release)))
        assertEquals(setOf("07a8454fab51363f85fbabf623b3c655aec3e1c7b58387b23ef5799c61737b79"), SigningCheck.certs(info(stranger)))
    }

    @Test fun onlyAReadableDifferentSignerIsRefused() {
        val mine = setOf("a"); val other = setOf("b")
        assertEquals(SignerMatch.SAME, SigningCheck.compare(mine, setOf("a")))
        assertEquals(SignerMatch.DIFFERENT, SigningCheck.compare(mine, other))
        assertEquals(SignerMatch.DIFFERENT, SigningCheck.compare(mine, mine + other), "an extra signer is a different signer set")
        assertEquals(SignerMatch.UNKNOWN, SigningCheck.compare(mine, emptySet()), "unreadable APK: PackageInstaller decides")
        assertEquals(SignerMatch.UNKNOWN, SigningCheck.compare(emptySet(), mine), "unreadable installed app: PackageInstaller decides")
    }

    @Test fun anUpdateSignedWithTheReleaseKeyIsInstalled() {
        val (state, installed) = update(downloadSigner = release)
        assertTrue(state is UpdateState.Installing, "accepted: $state")
        assertTrue(installed.single().contentEquals(apk), "the verified download is handed to the installer")
    }

    @Test fun anUpdateSignedWithAnotherKeyIsRefusedAndDeleted() {
        val (state, installed) = update(downloadSigner = stranger)
        assertTrue(state is UpdateState.Failed && "different key" in state.message, "refused: $state")
        assertTrue(installed.isEmpty(), "never handed to the installer")
        assertFalse(downloaded.exists(), "the download is deleted")
    }

    @Test fun anApkWhoseSignerCannotBeReadIsLeftToPackageInstaller() {
        val (state, installed) = update(downloadSigner = null)
        assertTrue(state is UpdateState.Installing, "Android 10 can report no signers for an APK file; the installer still checks: $state")
        assertEquals(1, installed.size)
    }
}
