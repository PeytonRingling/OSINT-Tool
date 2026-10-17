package vip.ringling.recon.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import vip.ringling.recon.data.db.HistoryDb
import vip.ringling.recon.data.db.ScanDao
import vip.ringling.recon.data.db.SnapshotDao
import vip.ringling.recon.data.secret.ApiKeys
import vip.ringling.recon.data.settings.SettingsRepository
import vip.ringling.recon.data.watch.WatchScheduler
import vip.ringling.recon.domain.ApiKeyId

/** Housekeeping for sensitive data: age-based deletion of scans and the "wipe everything" panic button. Everything is on-device. */
@Singleton
class DataGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: HistoryDb,
    private val scans: ScanDao,
    private val snapshots: SnapshotDao,
    private val settings: SettingsRepository,
    private val handled: HandledRepository,
    private val keys: ApiKeys,
) {
    /** Deletes scans (history rows and their Log snapshots) older than [days]; 0 keeps everything. Cases, notes and the watchlist are untouched. */
    suspend fun purgeOlderThan(days: Int, now: Long = System.currentTimeMillis()) {
        if (days <= 0) return
        val cutoff = now - days * DAY
        scans.deleteBefore(cutoff); snapshots.deleteBefore(cutoff)
    }

    /** Deletes scans, snapshots, cases, notes, the watchlist, handled flags, API keys, settings and downloaded updates; cancels background work. */
    suspend fun wipeEverything() = withContext(Dispatchers.IO) {
        db.clearAllTables()
        handled.clear()
        ApiKeyId.entries.forEach { keys.set(it, "") }
        settings.reset()
        WatchScheduler.sync(context, false)
        runCatching { File(context.cacheDir, "updates").deleteRecursively() }
        Unit
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
}
