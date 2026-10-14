package vip.ringling.recon.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
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

    @Query("DELETE FROM scans")
    suspend fun clear()
}

@Database(entities = [ScanEntity::class], version = 1, exportSchema = false)
abstract class HistoryDb : RoomDatabase() {
    abstract fun scans(): ScanDao
}

private val json = Json { ignoreUnknownKeys = true }

fun ScanRecord.toEntity() = ScanEntity(id, input.raw, input.type.name, timestampMillis, json.encodeToString(findings))

fun ScanEntity.toRecord() = ScanRecord(
    id = id,
    input = ScanInput(query, InputType.valueOf(type)),
    timestampMillis = timestamp,
    findings = runCatching { json.decodeFromString<List<Finding>>(findingsJson) }.getOrDefault(emptyList()),
)
