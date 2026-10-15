package vip.ringling.recon.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch

/** Runs every source that supports the input type in parallel, streaming events as they happen. */
@Singleton
class ScanRunner @Inject constructor(private val sources: Set<@JvmSuppressWildcards OsintSource>) {

    fun modulesFor(type: InputType): List<ModuleId> =
        sources.filter { type in it.supportedInputTypes }.map { it.id }.sortedBy { it.ordinal }

    fun events(input: ScanInput, enabled: Set<ModuleId>? = null): Flow<ScanEvent> = channelFlow {
        val targets = sources.filter { input.type in it.supportedInputTypes && (enabled == null || it.id in enabled) }
        targets.forEach { send(ScanEvent.Started(it.id)) }
        targets.forEach { source ->
            launch {
                try {
                    source.run(input).collect { send(ScanEvent.Found(it)) }
                    send(ScanEvent.Finished(source.id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    send(ScanEvent.Failed(source.id, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /** Folds [events] into progressively complete [ScanSession] snapshots. */
    fun session(input: ScanInput, enabled: Set<ModuleId>? = null): Flow<ScanSession> =
        events(input, enabled).runningFold(ScanSession(input)) { s, e -> s.apply(e) }
}

fun ScanSession.apply(e: ScanEvent): ScanSession = when (e) {
    is ScanEvent.Started -> copy(statuses = statuses + (e.module to ModuleStatus.RUNNING))
    is ScanEvent.Found -> copy(findings = dropMislocated(findings + e.finding, e.finding))
    is ScanEvent.Finished -> copy(statuses = statuses + (e.module to ModuleStatus.DONE))
    is ScanEvent.Failed -> copy(
        statuses = statuses + (e.module to ModuleStatus.FAILED),
        errors = errors + (e.module to e.message),
    )
}

/** A registry record whose coordinates are far from all the others in the same search was geocoded to the wrong place: keep the record, drop the bad pin. */
private fun dropMislocated(all: List<Finding>, added: Finding): List<Finding> {
    if (added.module != ModuleId.REGISTRY || added.geo == null) return all
    val bad = Geo.outliers(all.filter { it.module == ModuleId.REGISTRY }.mapNotNull { it.geo })
    if (bad.isEmpty()) return all
    return all.map { if (it.module == ModuleId.REGISTRY && it.geo in bad) it.copy(geo = null, tags = it.tags + "Location unverified") else it }
}
