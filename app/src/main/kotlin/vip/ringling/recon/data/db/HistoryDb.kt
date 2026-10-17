package vip.ringling.recon.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.ScanRecord

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey val id: String,
    val query: String,
    val type: String,
    val timestamp: Long,
    val findingsJson: String,
    /** RiskScore.VERSION the row was saved under; 0 for rows from builds before versioning. */
    @androidx.room.ColumnInfo(defaultValue = "0") val scoreVersion: Int = 0,
)

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY timestamp DESC")
    fun all(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun find(id: String): ScanEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: ScanEntity)

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM scans WHERE timestamp < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("DELETE FROM scans")
    suspend fun clear()
}

/** A finding the user pinned to the case, plus their note. Registry findings are never added (see [vip.ringling.recon.domain.Export.exportable]). */
@Entity(tableName = "case_items")
data class CaseEntity(
    @PrimaryKey val id: String,
    val findingJson: String,
    val note: String,
    val addedAt: Long,
)

/** A watched email: the background worker alerts when a new breach shows up. [knownJson] is the list of breach names already seen. */
@Entity(tableName = "watch")
data class WatchEntity(
    @PrimaryKey val id: String,
    val raw: String,
    val type: String,
    val knownJson: String,
    val lastChecked: Long,
    val lastChange: Long,
)

@Dao
interface CaseDao {
    @Query("SELECT * FROM case_items ORDER BY addedAt DESC")
    fun all(): Flow<List<CaseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: CaseEntity)

    @Query("UPDATE case_items SET note = :note WHERE id = :id")
    suspend fun setNote(id: String, note: String)

    @Query("DELETE FROM case_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM case_items")
    suspend fun clear()
}

/** One finished scan of one target: the numbers needed for the Log, sparklines and the change timeline. ZIP scans store the record COUNT in [infoCount] (never the records). */
@Entity(tableName = "scan_snapshot")
data class SnapshotEntity(
    @PrimaryKey val id: String,
    val targetType: String,
    val targetValue: String,
    val ts: Long,
    val risk: Int,
    val exposedCount: Int,
    val infoCount: Int,
    /** Comma-separated finding ids (empty for ZIP scans). */
    val findingIds: String,
)

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM scan_snapshot ORDER BY ts DESC")
    fun all(): Flow<List<SnapshotEntity>>

    @Query("SELECT * FROM scan_snapshot")
    suspend fun snapshot(): List<SnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: SnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(e: List<SnapshotEntity>)

    @Query("DELETE FROM scan_snapshot WHERE ts < :cutoff")
    suspend fun deleteBefore(cutoff: Long)

    @Query("DELETE FROM scan_snapshot")
    suspend fun clear()

    @Query("DELETE FROM scan_snapshot WHERE targetType = :type AND targetValue = :value")
    suspend fun deleteTarget(type: String, value: String)
}

@Entity(tableName = "cases")
data class CaseFileEntity(@PrimaryKey val id: String, val name: String, val createdAt: Long, @androidx.room.ColumnInfo(defaultValue = "") val tags: String = "")

@Entity(tableName = "case_targets", primaryKeys = ["caseId", "type", "value"])
data class CaseTargetEntity(val caseId: String, val type: String, val value: String, val addedAt: Long)

@Entity(tableName = "case_notes")
data class CaseNoteEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val caseId: String, val text: String, val ts: Long)

@Dao
interface CaseFileDao {
    @Query("SELECT * FROM cases ORDER BY createdAt ASC")
    fun cases(): Flow<List<CaseFileEntity>>

    @Query("SELECT * FROM case_targets ORDER BY addedAt ASC")
    fun targets(): Flow<List<CaseTargetEntity>>

    @Query("SELECT * FROM case_notes ORDER BY ts DESC")
    fun notes(): Flow<List<CaseNoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCase(e: CaseFileEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTarget(e: CaseTargetEntity)

    @Query("DELETE FROM case_targets WHERE caseId = :caseId AND type = :type AND value = :value")
    suspend fun removeTarget(caseId: String, type: String, value: String)

    @Insert
    suspend fun addNote(e: CaseNoteEntity)

    @Query("DELETE FROM case_notes WHERE id = :id")
    suspend fun deleteNote(id: Long)

    @Query("UPDATE cases SET tags = :tags WHERE id = :id")
    suspend fun setTags(id: String, tags: String)

    @Query("UPDATE cases SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM cases WHERE id = :id")
    suspend fun deleteCase(id: String)

    @Query("DELETE FROM case_targets WHERE caseId = :id")
    suspend fun deleteTargets(id: String)

    @Query("DELETE FROM case_notes WHERE caseId = :id")
    suspend fun deleteNotes(id: String)
}

@Dao
interface WatchDao {
    @Query("SELECT * FROM watch ORDER BY lastChecked DESC")
    fun all(): Flow<List<WatchEntity>>

    @Query("SELECT * FROM watch")
    suspend fun snapshot(): List<WatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: WatchEntity)

    @Query("DELETE FROM watch WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(entities = [ScanEntity::class, CaseEntity::class, WatchEntity::class, SnapshotEntity::class, CaseFileEntity::class, CaseTargetEntity::class, CaseNoteEntity::class], version = 5, exportSchema = false)
abstract class HistoryDb : RoomDatabase() {
    abstract fun scans(): ScanDao
    abstract fun cases(): CaseDao
    abstract fun watches(): WatchDao
    abstract fun snapshots(): SnapshotDao
    abstract fun caseFiles(): CaseFileDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE `cases` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''") }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `scan_snapshot` (`id` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetValue` TEXT NOT NULL, `ts` INTEGER NOT NULL, `risk` INTEGER NOT NULL, `exposedCount` INTEGER NOT NULL, `infoCount` INTEGER NOT NULL, `findingIds` TEXT NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `cases` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `case_targets` (`caseId` TEXT NOT NULL, `type` TEXT NOT NULL, `value` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`caseId`, `type`, `value`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `case_notes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `caseId` TEXT NOT NULL, `text` TEXT NOT NULL, `ts` INTEGER NOT NULL)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `scans` ADD COLUMN `scoreVersion` INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `case_items` (`id` TEXT NOT NULL, `findingJson` TEXT NOT NULL, `note` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `watch` (`id` TEXT NOT NULL, `raw` TEXT NOT NULL, `type` TEXT NOT NULL, `knownJson` TEXT NOT NULL, `lastChecked` INTEGER NOT NULL, `lastChange` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            }
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

fun ScanRecord.toEntity() = ScanEntity(id, input.raw, input.type.name, timestampMillis, json.encodeToString(findings), scoreVersion)

fun ScanEntity.toRecord(): ScanRecord {
    val decoded = runCatching { json.decodeFromString<List<Finding>>(findingsJson) }.getOrDefault(emptyList())
    return ScanRecord(
        id = id,
        input = ScanInput(query, InputType.valueOf(type)),
        timestampMillis = timestamp,
        // Rows saved before the current scoring rules are re-scored on load (and labelled in History) so old and new scans compare fairly.
        findings = if (scoreVersion < vip.ringling.recon.domain.RiskScore.VERSION) decoded.map(vip.ringling.recon.domain.Rescore::finding) else decoded,
        scoreVersion = scoreVersion,
    )
}
