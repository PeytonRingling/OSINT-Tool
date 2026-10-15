package vip.ringling.recon.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import vip.ringling.recon.data.db.SnapshotDao
import vip.ringling.recon.data.db.SnapshotEntity
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanRecord
import vip.ringling.recon.domain.ScanSnapshot

/** The `scanSnapshot` table: one row per finished scan, read by the Log, the radar and every case timeline. On-device only. */
@Singleton
class SnapshotRepository @Inject constructor(private val dao: SnapshotDao) {
    val snapshots: Flow<List<ScanSnapshot>> = dao.all().map { l -> l.map { it.toDomain() } }

    suspend fun add(s: ScanSnapshot) = dao.insert(s.toEntity())
    suspend fun clear() = dao.clear()
    suspend fun deleteTarget(type: InputType, value: String) = dao.deleteTarget(type.name, value)

    /** Adds a snapshot for every History row that has none (builds that predate the table, or a scan that finished before it existed). */
    suspend fun backfill(records: List<ScanRecord>) {
        val have = dao.snapshot().map { it.id }.toSet()
        val missing = records.filter { it.id !in have }
        if (missing.isNotEmpty()) dao.insertAll(missing.map { ScanSnapshot.fromRecord(it).toEntity() })
    }
}

private fun SnapshotEntity.toDomain() = ScanSnapshot(
    id, runCatching { InputType.valueOf(targetType) }.getOrDefault(InputType.USERNAME), targetValue, ts, risk, exposedCount, infoCount,
    findingIds.split(',').filter { it.isNotEmpty() },
)

private fun ScanSnapshot.toEntity() = SnapshotEntity(id, type.name, value, ts, risk, exposed, info, findingIds.joinToString(","))
