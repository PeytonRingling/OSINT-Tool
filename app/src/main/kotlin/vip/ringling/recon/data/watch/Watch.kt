package vip.ringling.recon.data.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vip.ringling.recon.MainActivity
import vip.ringling.recon.R
import vip.ringling.recon.data.db.WatchDao
import vip.ringling.recon.data.db.WatchEntity
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput

data class WatchItem(val email: String, val knownCount: Int, val lastChecked: Long)

/** Pure diff logic, kept separate from Android so it is unit-tested. */
object WatchLogic {
    private val baseFacts = setOf("Records found", "Leaks listed", "Endpoint", "Queries left", "…")

    /** Stable identifiers of every breach/leak in a breach scan; null when a lookup failed (so we neither alert nor update the baseline). */
    fun signature(findings: List<Finding>): Set<String>? {
        val breach = findings.filter { it.module == ModuleId.BREACH }
        if (breach.any { it.id.endsWith("-error") }) return null
        return buildSet {
            breach.forEach { f ->
                when {
                    f.id.startsWith("BREACH-hibp-") && f.id != "BREACH-hibp-pastes" && f.id != "BREACH-hibp-none" -> add(f.id.removePrefix("BREACH-"))
                    f.id == "BREACH-leakcheck" -> f.facts.filter { it.label !in baseFacts }.forEach { add("leakcheck-" + it.label.lowercase()) }
                }
            }
        }
    }

    /** New identifiers since the last check. A first check (no baseline) records silently. */
    fun newItems(known: Set<String>?, now: Set<String>): Set<String> = if (known == null) emptySet() else now - known
}

@Singleton
class WatchRepository @Inject constructor(private val dao: WatchDao) {
    private val json = Json
    val items: Flow<List<WatchEntity>> = dao.all()

    suspend fun add(email: String, now: Long = System.currentTimeMillis()) {
        val e = email.trim().lowercase()
        // knownJson "null" = baseline not yet recorded.
        dao.upsert(WatchEntity(e, e, InputType.EMAIL.name, "null", 0L, now))
    }

    suspend fun remove(email: String) = dao.delete(email)
    suspend fun all() = dao.snapshot()
    suspend fun record(e: WatchEntity, signature: Set<String>, now: Long) =
        dao.upsert(e.copy(knownJson = json.encodeToString(signature.sorted()), lastChecked = now, lastChange = if (WatchLogic.newItems(known(e), signature).isNotEmpty()) now else e.lastChange))

    fun known(e: WatchEntity): Set<String>? = runCatching { json.decodeFromString<List<String>?>(e.knownJson)?.toSet() }.getOrNull()
}

/** Re-runs breach checks for watched emails and returns the ones with something new. */
class WatchChecker @Inject constructor(private val repo: WatchRepository, private val source: OsintSource) {
    suspend fun run(now: Long = System.currentTimeMillis()): Map<String, Set<String>> {
        val alerts = mutableMapOf<String, Set<String>>()
        for (w in repo.all()) {
            val findings = runCatching { source.run(ScanInput(w.raw, InputType.EMAIL)).toList() }.getOrNull() ?: continue
            val sig = WatchLogic.signature(findings) ?: continue
            val fresh = WatchLogic.newItems(repo.known(w), sig)
            if (fresh.isNotEmpty()) alerts[w.raw] = fresh
            repo.record(w, sig, now)
        }
        return alerts
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WatchEntryPoint {
    fun repo(): WatchRepository
    fun breachSource(): vip.ringling.recon.data.sources.breach.BreachSource
}

class WatchWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(ctx, WatchEntryPoint::class.java)
        val alerts = WatchChecker(ep.repo(), ep.breachSource()).run()
        alerts.forEach { (email, fresh) -> Notifier.breach(ctx, email, fresh.size) }
        return Result.success()
    }
}

object WatchScheduler {
    /** Baseline a newly watched address right away instead of waiting a day. */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WatchWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    private const val NAME = "recon-watch"
    fun sync(context: Context, hasWatches: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!hasWatches) { wm.cancelUniqueWork(NAME); return }
        val req = PeriodicWorkRequestBuilder<WatchWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

object Notifier {
    private const val CHANNEL = "watch"
    fun breach(ctx: Context, email: String, count: Int) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watchlist alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("New exposure for a watched address")
            .setContentText("$count new breach${if (count == 1) "" else "es"} found for ${email.substringBefore('@').take(2)}…@${email.substringAfter('@')}")
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(email.hashCode(), n)
    }
}
