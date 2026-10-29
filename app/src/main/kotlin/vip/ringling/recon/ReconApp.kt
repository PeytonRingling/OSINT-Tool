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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import vip.ringling.recon.data.net.Opsec
import vip.ringling.recon.data.net.ProxyState
import vip.ringling.recon.data.settings.SettingsRepository
import okhttp3.OkHttpClient
import vip.ringling.recon.data.net.USER_AGENT

@HiltAndroidApp
class ReconApp : Application(), SingletonImageLoader.Factory {
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var dataGuard: vip.ringling.recon.data.DataGuard
    @Inject lateinit var snapshotRepo: vip.ringling.recon.data.SnapshotRepository

    override fun onCreate() {
        super.onCreate()
        // Age-based deletion runs at every launch and whenever the setting changes; the daily case re-scan is (re)scheduled to match the setting.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settingsRepo.settings.filter { it.loaded }.map { it.retentionDays to it.autoRescan }.distinctUntilChanged().collect { (days, rescan) ->
                runCatching { dataGuard.purgeOlderThan(days) }
                runCatching { vip.ringling.recon.data.watch.AutoRescanScheduler.sync(this@ReconApp, rescan) } // WorkManager may be absent (e.g. under test)
            }
        }
        // Keep the risk widget's single number in step with the stored scans (also clears it when everything is wiped).
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { snapshotRepo.snapshots.map { vip.ringling.recon.domain.WidgetRisk.overall(it) }.distinctUntilChanged().collect { RiskWidget.publish(this@ReconApp, it) } }
        }
        // Every request is held at a dead proxy (ProxyState.UNSET) until the saved setting is known. Read it now, before any activity or
        // worker in this process can send anything, then keep the global proxy in sync (Tor / custom SOCKS5 for every lookup).
        runCatching { kotlinx.coroutines.runBlocking { settingsRepo.settings.first() } }.getOrNull()?.let { Opsec.on = it.opsec; ProxyState.apply(it.proxyMode, it.proxyHost, it.proxyPort) }
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settingsRepo.settings.map { Triple(it.proxyMode, it.proxyHost, it.proxyPort) to it.opsec }.distinctUntilChanged()
                .collect { (proxy, opsec) -> Opsec.on = opsec; ProxyState.apply(proxy.first, proxy.second, proxy.third) }
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
