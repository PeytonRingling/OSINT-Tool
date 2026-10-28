package vip.ringling.recon.data.net

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

val USER_AGENT = "Recon-Android/0.2 (personal OSINT toolkit" + vip.ringling.recon.BuildConfig.UPDATE_REPO.let { if (it.isBlank()) "" else "; +https://github.com/$it" } + ")"

fun buildHttpClient(meter: QuotaMeter = QuotaMeter.shared): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .followRedirects(true)
    .proxySelector(ProxyState.selector)
    // Deliberately no logging interceptor: request headers carry API keys. The quota meter keeps only counts and status codes.
    .addInterceptor(QuotaInterceptor(meter))
    .build()

/** Suspends until the response headers arrive; cancelling the coroutine cancels the call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Thin wrapper so sources are testable and never touch OkHttp directly. */
class Http(val client: OkHttpClient = buildHttpClient()) {
    /**
     * Runs [block] (usually reading the body) on an IO thread. Without this, a caller on the main thread (a button's coroutine) would read the
     * response there and Android throws NetworkOnMainThreadException.
     */
    suspend fun <T> execute(request: Request, block: (Response) -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            client.newCall(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", USER_AGENT).build() else request).await().use(block)
        }
}

/** Browser-like UA for site probes: many sites 403 non-browser agents. Requests run from the user's own device. */
const val BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

class HttpException(val code: Int, message: String) : IOException(message)
