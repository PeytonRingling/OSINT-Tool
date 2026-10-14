package vip.ringling.recon.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import vip.ringling.recon.data.mock.MockData
import vip.ringling.recon.domain.ScanRecord

interface HistoryRepository {
    val records: Flow<List<ScanRecord>>
    suspend fun add(record: ScanRecord)
    suspend fun delete(id: String)
    suspend fun clear()
    suspend fun find(id: String): ScanRecord?
}

/** Phase 1 stand-in, seeded with mock records. Phase 2 swaps in Room behind the same interface. */
@Singleton
class InMemoryHistoryRepository @Inject constructor() : HistoryRepository {
    private val state = MutableStateFlow(MockData.history(System.currentTimeMillis()))
    override val records: Flow<List<ScanRecord>> = state.map { l -> l.sortedByDescending { it.timestampMillis } }
    override suspend fun add(record: ScanRecord) = state.update { listOf(record) + it }
    override suspend fun delete(id: String) = state.update { l -> l.filterNot { it.id == id } }
    override suspend fun clear() = state.update { emptyList() }
    override suspend fun find(id: String) = state.value.firstOrNull { it.id == id }
}
