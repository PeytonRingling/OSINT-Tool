package vip.ringling.recon.data.watch

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.lastOrNull
import vip.ringling.recon.data.CaseFileRepository
import vip.ringling.recon.data.HistoryRepository
import vip.ringling.recon.data.SnapshotRepository
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.ScanDiff
import vip.ringling.recon.domain.ScanDiffs
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanRunner
import vip.ringling.recon.domain.ScanSnapshot

/** Decides what is worth a notification after a background re-scan. Pure, so it is unit-tested. */
object AutoRescanLogic {
    /** Only things that got worse or newly appeared are alerted; improvements and no-change are silent, and a target's first scan is a silent baseline. */
    fun alerts(diffs: List<ScanDiff>): List<ScanDiff> = diffs.filter { !it.failed && it.before != null && ((it.delta ?: 0) > 0 || it.newTitles.isNotEmpty()) }

    fun summary(alerts: List<ScanDiff>): String? {
        if (alerts.isEmpty()) return null
        val names = alerts.take(3).joinToString(", ") { it.input.raw.let { r -> if (r.length > 18) r.take(17) + "…" else r } }
        return "${alerts.size} target${if (alerts.size == 1) "" else "s"} changed: $names" + if (alerts.size > 3) " +${alerts.size - 3}" else ""
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AutoRescanEntryPoint {
    fun runner(): ScanRunner
    fun history(): HistoryRepository
    fun snapshots(): SnapshotRepository
    fun cases(): CaseFileRepository
}

/** Re-scans every case target once a day (not ZIP/photo targets) and notifies when something got worse or a new finding appeared. */
class AutoRescanWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ep = EntryPointAccessors.fromApplication(ctx, AutoRescanEntryPoint::class.java)
        val targets = ScanDiffs.batchable(ep.cases().cases.first().flatMap { c -> c.targets.map { ScanInput(it.value, it.type) } })
        if (targets.isEmpty()) return Result.success()
        val before = ep.snapshots().snapshots.first()
        val diffs = mutableListOf<ScanDiff>()
        for (input in targets) {
            val last = runCatching { ep.runner().session(input).lastOrNull() }.getOrNull() ?: continue
            // A run with a failed source would look like findings disappearing: skip it rather than record a false "improvement".
            if (last.findings.isEmpty() || last.findings.any { it.id.endsWith("-error") }) continue
            val id = UUID.randomUUID().toString(); val now = System.currentTimeMillis()
            ep.history().add(ScanRecord(id, input, now, last.findings.filter { it.module != ModuleId.REGISTRY && it.paid == null }))
            val snap = ScanSnapshot.of(id, input, last.findings, now)
            ep.snapshots().add(snap)
            diffs += ScanDiffs.compare(before.filter { it.type == input.type && it.value == input.raw }.maxByOrNull { it.ts }, snap, last.findings)
        }
        AutoRescanLogic.summary(AutoRescanLogic.alerts(diffs))?.let { Notifier.rescan(ctx, it) }
        return Result.success()
    }
}

object AutoRescanScheduler {
    private const val NAME = "recon-autorescan"
    fun sync(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!on) { wm.cancelUniqueWork(NAME); return }
        wm.enqueueUniquePeriodicWork(
            NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoRescanWorker>(24, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }
}
