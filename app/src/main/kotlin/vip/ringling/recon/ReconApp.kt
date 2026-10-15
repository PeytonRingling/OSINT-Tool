package vip.ringling.recon

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import vip.ringling.recon.data.net.USER_AGENT

@HiltAndroidApp
class ReconApp : Application(), SingletonImageLoader.Factory {
    /** Memory cache only: registry photos and map tiles are displayed, never written to disk. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache(null)
        .components {
            add(
                OkHttpNetworkFetcherFactory(callFactory = {
                    OkHttpClient.Builder()
                        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
                        .build()
                }),
            )
        }
        .build()
}
