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
import kotlinx.coroutines.flow.lastOrNull
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
import vip.ringling.recon.domain.scorable

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

    private val identityHousekeeping = setOf("IDENTITY-summary", "IDENTITY-profile", "IDENTITY-manual", "IDENTITY-email-summary")

    /** In every username/domain signature, so a baseline with no accounts or subdomains yet is still told apart from a pre-token one. */
    const val TYPED = "v:2"

    /**
     * Signature for watched targets. Email: breach ids ([signature]). Username: `account:<site>` for every site an account was ever seen on.
     * Domain: `sub:<name>` for every certificate-transparency subdomain ever seen (crt.sh), one `whois:<registrar|expiry|status|nameservers>`
     * token (RDAP) and one `wayback:<latest capture>` token. Accounts and subdomains only accumulate: a site that was blocked, rate-limited or
     * skipped as unreliable, or a crt.sh answer cut short, then answering again is not news. WHOIS and Wayback are current-state tokens that
     * keep their [known] value when their lookup failed. Null when nothing at all was learned, so a failed run neither alerts nor moves the
     * baseline.
     */
    fun signatureFor(type: InputType, findings: List<Finding>, known: Set<String>? = null): Set<String>? {
        val prev = known.orEmpty()
        fun carried(prefix: String) = prev.filter { it.startsWith(prefix) }
        return when (type) {
            InputType.EMAIL -> signature(findings)
            InputType.USERNAME -> {
                if (findings.none { it.id == "IDENTITY-summary" }) return null
                val found = findings.filter { it.module == ModuleId.IDENTITY && it.id !in identityHousekeeping && !it.id.endsWith("-error") }.map { "account:" + it.id.removePrefix("IDENTITY-") }
                (found + carried("account:") + TYPED).toSet()
            }
            InputType.DOMAIN -> {
                val byId = findings.associateBy { it.id }
                var learned = false
                val out = mutableSetOf(TYPED)
                out += carried("sub:")
                byId["DOMAIN-subs"]?.let { subs -> learned = true; out += subs.raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { "sub:$it" } }
                val rdap = byId["DOMAIN-rdap"]
                if (rdap != null) {
                    learned = true
                    out += "whois:" + listOf("Registrar", "Expires", "Status", "Nameservers").joinToString("|") { l -> "$l=" + (rdap.facts.firstOrNull { it.label == l }?.value ?: "") }
                } else out += carried("whois:")
                val latest = byId["DOMAIN-wayback"]?.facts?.firstOrNull { it.label == "Latest capture" }?.value?.substringBefore('\n')
                if (latest != null) { learned = true; out += "wayback:$latest" } else out += carried("wayback:")
                if (learned) out else null
            }
            else -> if (findings.any { it.id.endsWith("-error") }) null else findings.filter { it.scorable }.map { it.id }.toSet()
        }
    }

    /** A username/domain baseline recorded before typed tokens ([TYPED]) can't be compared: it is re-recorded silently. */
    fun usableBaseline(type: InputType, known: Set<String>?): Set<String>? =
        if (known != null && type in setOf(InputType.USERNAME, InputType.DOMAIN) && TYPED !in known) null else known

    /** One line for the notification, e.g. "2 new subdomains (api, dev) · registration (WHOIS) changed". */
    fun describe(type: InputType, fresh: Set<String>): String {
        val subs = fresh.filter { it.startsWith("sub:") }.map { it.removePrefix("sub:") }
        val accounts = fresh.filter { it.startsWith("account:") }.map { it.removePrefix("account:") }
        return buildList {
            if (subs.isNotEmpty()) add("${subs.size} new subdomain${if (subs.size == 1) "" else "s"} (${subs.sorted().take(3).joinToString(", ")}${if (subs.size > 3) ", …" else ""})")
            if (accounts.isNotEmpty()) add("new account${if (accounts.size == 1) "" else "s"} on ${accounts.sorted().take(3).joinToString(", ")}${if (accounts.size > 3) " +${accounts.size - 3}" else ""}")
            if (fresh.any { it.startsWith("whois:") }) add("registration (WHOIS) changed")
            if (fresh.any { it.startsWith("wayback:") }) add("new Wayback Machine capture")
            val other = fresh.count { ':' !in it }
            if (other > 0) add(if (type == InputType.EMAIL) "$other new breach${if (other == 1) "" else "es"}" else "$other new finding${if (other == 1) "" else "s"}")
        }.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }

    /** New identifiers since the last check. A first check (no baseline) records silently. */
    fun newItems(known: Set<String>?, now: Set<String>): Set<String> = if (known == null) emptySet() else now - known
}

@Singleton
class WatchRepository @Inject constructor(private val dao: WatchDao) {
    private val json = Json
    val items: Flow<List<WatchEntity>> = dao.all()

    suspend fun add(value: String, now: Long = System.currentTimeMillis(), type: InputType = InputType.EMAIL) {
        val e = value.trim().lowercase()
        // knownJson "null" = baseline not yet recorded.
        dao.upsert(WatchEntity(e, e, type.name, "null", 0L, now))
    }

    suspend fun remove(email: String) = dao.delete(email)
    suspend fun all() = dao.snapshot()
    suspend fun record(e: WatchEntity, signature: Set<String>, now: Long, changed: Boolean = WatchLogic.newItems(known(e), signature).isNotEmpty()) =
        dao.upsert(e.copy(knownJson = json.encodeToString(signature.sorted()), lastChecked = now, lastChange = if (changed) now else e.lastChange))

    fun known(e: WatchEntity): Set<String>? = runCatching { json.decodeFromString<List<String>?>(e.knownJson)?.toSet() }.getOrNull()
}

/** Re-runs breach checks for watched emails and returns the ones with something new. */
class WatchChecker(private val repo: WatchRepository, private val source: OsintSource, private val runner: vip.ringling.recon.domain.ScanRunner? = null) {
    suspend fun run(now: Long = System.currentTimeMillis()): Map<String, Set<String>> {
        val alerts = mutableMapOf<String, Set<String>>()
        for (w in repo.all()) {
            val type = runCatching { InputType.valueOf(w.type) }.getOrDefault(InputType.EMAIL)
            val input = ScanInput(w.raw, type)
            // Only the module that feeds each watch runs: breach for email, the username sweep, the domain module.
            val modules = when (type) { InputType.USERNAME -> setOf(ModuleId.IDENTITY); InputType.DOMAIN -> setOf(ModuleId.DOMAIN); else -> null }
            val findings = runCatching {
                if (type == InputType.EMAIL || runner == null) source.run(input).toList() else runner.session(input, modules).lastOrNull()?.findings.orEmpty()
            }.getOrNull() ?: continue
            val known = WatchLogic.usableBaseline(type, repo.known(w))
            val sig = WatchLogic.signatureFor(type, findings, known) ?: continue
            val fresh = WatchLogic.newItems(known, sig)
            if (fresh.isNotEmpty()) alerts[w.raw] = fresh
            repo.record(w, sig, now, changed = fresh.isNotEmpty())
        }
        return alerts
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WatchEntryPoint {
    fun repo(): WatchRepository
    fun breachSource(): vip.ringling.recon.data.sources.breach.BreachSource
    fun runner(): vip.ringling.recon.domain.ScanRunner
}

class WatchWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(ctx, WatchEntryPoint::class.java)
        val repo = ep.repo()
        val types = repo.all().associate { it.raw to (runCatching { InputType.valueOf(it.type) }.getOrDefault(InputType.EMAIL)) }
        val alerts = WatchChecker(repo, ep.breachSource(), ep.runner()).run()
        alerts.forEach { (value, fresh) -> val t = types[value] ?: InputType.EMAIL; Notifier.watch(ctx, value, t, WatchLogic.describe(t, fresh)) }
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
    fun watch(ctx: Context, value: String, type: InputType, what: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watchlist alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Notifications show on the lock screen: an email is masked; usernames and domains are what the user chose to watch.
        val who = if (type == InputType.EMAIL) "${value.substringBefore('@').take(2)}…@${value.substringAfter('@')}" else value
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Watched target changed: $who")
            .setContentText(what).setStyle(NotificationCompat.BigTextStyle().bigText(what))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open).setAutoCancel(true).build()
        nm.notify(value.hashCode(), n)
    }

    fun rescan(ctx: Context, summary: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watchlist alerts", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(0x5ca7, NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Case targets changed").setContentText(summary).setContentIntent(open).setAutoCancel(true).build())
    }
}
