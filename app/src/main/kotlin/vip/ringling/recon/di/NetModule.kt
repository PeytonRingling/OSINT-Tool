package vip.ringling.recon.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import vip.ringling.recon.data.db.HistoryDb
import vip.ringling.recon.data.db.ScanDao
import vip.ringling.recon.data.net.Endpoints
import vip.ringling.recon.data.net.Http

@Module
@InstallIn(SingletonComponent::class)
object NetModule {
    @Provides @Singleton fun http(): Http = Http()
    @Provides @Singleton fun endpoints(): Endpoints = Endpoints()
    @Provides @Singleton fun db(@ApplicationContext c: Context): HistoryDb {
        // Encrypted at rest (SQLCipher) when available; null keeps it plain so the app can never lose data over this (see DbEncryption).
        val pass = try { vip.ringling.recon.data.db.DbEncryption.prepare(c) } catch (e: vip.ringling.recon.data.db.DbKeyUnreadable) {
            // The real database stays on disk untouched; this session works in memory and System → Data says why.
            return Room.inMemoryDatabaseBuilder(c, HistoryDb::class.java).build()
        }
        val builder = Room.databaseBuilder(c, HistoryDb::class.java, "recon.db").addMigrations(HistoryDb.MIGRATION_1_2, HistoryDb.MIGRATION_2_3, HistoryDb.MIGRATION_3_4, HistoryDb.MIGRATION_4_5)
        pass?.let { builder.openHelperFactory(net.zetetic.database.sqlcipher.SupportOpenHelperFactory(it)) }
        return builder.build()
    }
    @Provides fun scanDao(db: HistoryDb): ScanDao = db.scans()
    @Provides fun caseDao(db: HistoryDb) = db.cases()
    @Provides fun watchDao(db: HistoryDb) = db.watches()
    @Provides fun snapshotDao(db: HistoryDb) = db.snapshots()
    @Provides fun caseFileDao(db: HistoryDb) = db.caseFiles()
}
