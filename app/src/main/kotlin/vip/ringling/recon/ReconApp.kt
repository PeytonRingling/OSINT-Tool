package vip.ringling.recon

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import vip.ringling.recon.data.net.ProxyState
import vip.ringling.recon.data.settings.SettingsRepository
import okhttp3.OkHttpClient
import vip.ringling.recon.data.net.USER_AGENT

@HiltAndroidApp
class ReconApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var settingsRepo: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        // Keep the global proxy in sync with settings (Tor / custom SOCKS5 for every lookup).
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settingsRepo.settings.map { Triple(it.proxyMode, it.proxyHost, it.proxyPort) }.distinctUntilChanged()
                .collect { (m, h, p) -> ProxyState.apply(m, h, p) }
        }
    }

    /** Memory cache only: registry photos and map tiles are displayed, never written to disk. */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
        .diskCache(null)
        .components {
            add(
                OkHttpNetworkFetcherFactory(callFactory = {
                    OkHttpClient.Builder()
                        .proxySelector(ProxyState.selector)
                        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
                        .build()
                }),
            )
        }
        .build()
}
