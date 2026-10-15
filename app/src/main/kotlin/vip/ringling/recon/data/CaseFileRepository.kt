package vip.ringling.recon.data

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import vip.ringling.recon.data.db.CaseFileDao
import vip.ringling.recon.data.db.CaseFileEntity
import vip.ringling.recon.data.db.CaseNoteEntity
import vip.ringling.recon.data.db.CaseTargetEntity
import vip.ringling.recon.domain.CaseFile
import vip.ringling.recon.domain.CaseNote
import vip.ringling.recon.domain.CaseTarget
import vip.ringling.recon.domain.InputType

/** Cases (dossiers): a name, a set of targets and notes. Room-backed, on-device only. Registry findings never enter a case: only the ZIP itself can. */
@Singleton
class CaseFileRepository @Inject constructor(private val dao: CaseFileDao) {
    val cases: Flow<List<CaseFile>> = combine(dao.cases(), dao.targets(), dao.notes()) { cs, ts, ns ->
        cs.mapIndexed { i, c ->
            CaseFile(
                c.id, c.name, c.createdAt, i + 1,
                ts.filter { it.caseId == c.id }.mapNotNull { t -> runCatching { CaseTarget(InputType.valueOf(t.type), t.value, t.addedAt) }.getOrNull() },
                ns.filter { it.caseId == c.id }.map { CaseNote(it.id, it.text, it.ts) },
            )
        }
    }

    suspend fun create(name: String, first: CaseTarget? = null, now: Long = System.currentTimeMillis()): String {
        val id = UUID.randomUUID().toString()
        dao.upsertCase(CaseFileEntity(id, name.trim().ifEmpty { "Untitled case" }, now))
        first?.let { addTarget(id, it.type, it.value, now) }
        return id
    }

    suspend fun addTarget(caseId: String, type: InputType, value: String, now: Long = System.currentTimeMillis()) {
        if (type == InputType.IMAGE) return // a local photo path is not a durable target
        dao.addTarget(CaseTargetEntity(caseId, type.name, value.trim(), now))
    }

    suspend fun removeTarget(caseId: String, type: InputType, value: String) = dao.removeTarget(caseId, type.name, value)
    suspend fun addNote(caseId: String, text: String, now: Long = System.currentTimeMillis()) { if (text.isNotBlank()) dao.addNote(CaseNoteEntity(caseId = caseId, text = text.trim(), ts = now)) }
    suspend fun deleteNote(id: Long) = dao.deleteNote(id)
    suspend fun rename(id: String, name: String) { if (name.isNotBlank()) dao.rename(id, name.trim()) }
    suspend fun delete(id: String) { dao.deleteTargets(id); dao.deleteNotes(id); dao.deleteCase(id) }
}
