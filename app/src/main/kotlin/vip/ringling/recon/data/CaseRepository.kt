package vip.ringling.recon.data

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vip.ringling.recon.data.db.CaseDao
import vip.ringling.recon.data.db.CaseEntity
import vip.ringling.recon.domain.Export
import vip.ringling.recon.domain.Finding

data class CaseItem(val key: String, val finding: Finding, val note: String, val addedAt: Long)

/** The user's pinned findings. On-device only; registry records are refused outright. */
@Singleton
class CaseRepository @Inject constructor(private val dao: CaseDao) {
    private val json = Json { ignoreUnknownKeys = true }

    val items: Flow<List<CaseItem>> = dao.all().map { l ->
        l.mapNotNull { e -> runCatching { CaseItem(e.id, json.decodeFromString<Finding>(e.findingJson), e.note, e.addedAt) }.getOrNull() }
    }

    /** Returns false when the finding can't be saved (registry data). */
    suspend fun add(f: Finding, now: Long = System.currentTimeMillis()): Boolean {
        if (!Export.exportable(f)) return false
        dao.upsert(CaseEntity(keyOf(f), json.encodeToString(f), "", now))
        return true
    }

    suspend fun setNote(key: String, note: String) = dao.setNote(key, note)
    suspend fun remove(key: String) = dao.delete(key)
    suspend fun clear() = dao.clear()

    companion object {
        fun keyOf(f: Finding): String =
            MessageDigest.getInstance("SHA-256").digest("${f.module}|${f.id}|${f.title}|${f.detail}".toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
