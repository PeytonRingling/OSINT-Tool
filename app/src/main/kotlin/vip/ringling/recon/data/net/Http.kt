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

const val USER_AGENT = "Recon-Android/0.2 (personal OSINT toolkit; +https://github.com/testuser/OSINT-Tool)"

fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(40, TimeUnit.SECONDS)
    .followRedirects(true)
    // Deliberately no logging interceptor: request headers carry API keys.
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
    suspend fun <T> execute(request: Request, block: (Response) -> T): T =
        client.newCall(request.newBuilder().header("User-Agent", USER_AGENT).build()).await().use(block)
}

class HttpException(val code: Int, message: String) : IOException(message)
