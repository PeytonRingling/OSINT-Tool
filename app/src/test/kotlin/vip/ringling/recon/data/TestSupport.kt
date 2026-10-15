package vip.ringling.recon.data

import kotlinx.coroutines.flow.toList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput

fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
        .bufferedReader().readText()

fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

/** Routes by "path?query" prefix so tests can describe a whole fake backend in one place. */
class Router(private val routes: List<Pair<String, (RecordedRequest) -> MockResponse>>) : Dispatcher() {
    val seen = mutableListOf<RecordedRequest>()
    override fun dispatch(request: RecordedRequest): MockResponse {
        synchronized(seen) { seen += request }
        val target = request.path.orEmpty()
        return routes.firstOrNull { target.startsWith(it.first) }?.second?.invoke(request)
            ?: MockResponse().setResponseCode(404).setBody("no route for $target")
    }
}

fun server(vararg routes: Pair<String, (RecordedRequest) -> MockResponse>): Pair<MockWebServer, Router> {
    val router = Router(routes.toList())
    val s = MockWebServer().apply { dispatcher = router; start() }
    return s to router
}

/** Every endpoint pointed at one mock server (paths are distinct per service). */
fun MockWebServer.endpoints(): Endpoints {
    val base = url("/").toString().trimEnd('/')
    return Endpoints(
        hibp = "$base/hibp", leakCheckApi = "$base/lc/v2", leakCheckPublic = "$base/lc/public",
        doh = "$base/doh", crtSh = "$base/crt", rdap = "$base/rdap", spotify = "$base/spotify", gravatar = "$base/gravatar", idahoSor = "$base/idaho", github = "$base/gh", notes800 = "$base/notes", veriphone = "$base/veriphone", twilio = "$base/twilio", archive = "$base/archive", internetDb = "$base/idb", duolingo = "$base/duo",
    )
}

suspend fun OsintSource.collect(input: ScanInput): List<Finding> = run(input).toList()
