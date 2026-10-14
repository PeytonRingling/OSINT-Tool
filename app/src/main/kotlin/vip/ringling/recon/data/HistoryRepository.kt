package vip.ringling.recon.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import vip.ringling.recon.data.db.ScanDao
import vip.ringling.recon.data.db.toEntity
import vip.ringling.recon.data.db.toRecord
import vip.ringling.recon.data.mock.MockData
import vip.ringling.recon.domain.ScanRecord

interface HistoryRepository {
    val records: Flow<List<ScanRecord>>
    suspend fun add(record: ScanRecord)
    suspend fun delete(id: String)
    suspend fun clear()
    suspend fun find(id: String): ScanRecord?
}

/** Room-backed history. Scans are only ever stored on this device. */
@Singleton
class RoomHistoryRepository @Inject constructor(private val dao: ScanDao) : HistoryRepository {
    override val records: Flow<List<ScanRecord>> = dao.all().map { l -> l.map { it.toRecord() } }
    override suspend fun add(record: ScanRecord) = dao.insert(record.toEntity())
    override suspend fun delete(id: String) = dao.delete(id)
    override suspend fun clear() = dao.clear()
    override suspend fun find(id: String) = dao.find(id)?.toRecord()
}

/** Test/preview stand-in, seeded with mock records. Phase 2 swaps in Room behind the same interface. */
@Singleton
class InMemoryHistoryRepository @Inject constructor() : HistoryRepository {
    private val state = MutableStateFlow(emptyList<ScanRecord>())
    override val records: Flow<List<ScanRecord>> = state.map { l -> l.sortedByDescending { it.timestampMillis } }
    override suspend fun add(record: ScanRecord) = state.update { listOf(record) + it }
    override suspend fun delete(id: String) = state.update { l -> l.filterNot { it.id == id } }
    override suspend fun clear() = state.update { emptyList() }
    override suspend fun find(id: String) = state.value.firstOrNull { it.id == id }
}
