package vip.ringling.recon.data.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook

/**
 * Encrypts the Room database at rest (SQLCipher). The passphrase is random, generated on first run, and kept only as AES-GCM ciphertext
 * under a non-exportable Android Keystore key. An existing plaintext database is converted in place on first launch (copy, verify, swap).
 *
 * Everything here is best-effort and fail-safe: if the native library or Keystore is unavailable (or the conversion fails), [prepare]
 * returns null and the database simply stays as it is, so the app never loses data or refuses to start.
 */
/** Whether the Room database is encrypted right now, and (when not) why. Shown in System → Data. */
data class DbStatus(val encrypted: Boolean, val reason: String = "")

object DbEncryption {
    /** Set by [prepare]; `null` until the database has been opened. */
    @Volatile var status: DbStatus? = null
        private set

    private const val DB = "recon.db"
    private val PLAIN_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    /** The passphrase bytes to open the database with, or null to open it unencrypted. */
    fun prepare(context: Context): ByteArray? = try {
        System.loadLibrary("sqlcipher")
        val file = context.getDatabasePath(DB)
        var pass = DbKey.load(context)
        if (pass == null) {
            // An encrypted file with no readable key (Keystore reset) can never be opened again: start fresh rather than crash-loop.
            if (file.exists() && !isPlaintext(file)) deleteDbFiles(file)
            pass = DbKey.create(context)
        }
        val failure = if (file.exists() && isPlaintext(file)) migrate(file, pass) else null
        if (failure != null) {
            status = DbStatus(false, "Converting the existing database failed (${failure.ifBlank { "unknown error" }}), so it was left unchanged.")
            null
        } else { status = DbStatus(true); pass.toByteArray(Charsets.US_ASCII) }
    } catch (t: Throwable) {
        status = DbStatus(false, "Encryption isn't available on this device (${t.javaClass.simpleName}${t.message?.let { ": " + it.take(120) } ?: ""}).")
        null
    }

    internal fun isPlaintext(file: File): Boolean = runCatching {
        file.inputStream().use { s -> ByteArray(16).also { s.read(it) }.contentEquals(PLAIN_HEADER) }
    }.getOrDefault(false)

    private fun deleteDbFiles(file: File) {
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
    }

    private val noHook: SQLiteDatabaseHook? = null

    /**
     * Copies [file] (plaintext) into an encrypted twin, verifies it, then swaps it in. Returns null on success, otherwise a short description of
     * what failed (the original is left untouched).
     */
    private fun migrate(file: File, pass: String): String? {
        val a = migrateOnce(file, pass, reverse = false) ?: return null
        val b = migrateOnce(file, pass, reverse = true) ?: return null
        return "$a; second method: $b"
    }

    /**
     * [reverse] = false: open the plaintext file and attach the encrypted twin (SQLCipher's documented route).
     * [reverse] = true: create the encrypted twin, attach the plaintext file to it and export from there.
     */
    private fun migrateOnce(file: File, pass: String, reverse: Boolean): String? {
        val tmp = File(file.parentFile, "recon-enc.tmp")
        val bak = File(file.parentFile, "recon-plain.bak")
        deleteDbFiles(tmp); bak.delete()
        var stage = "opening the existing database"
        try {
            val version: Int; val tables: Long
            run {
                val plain = SQLiteDatabase.openDatabase(file.path, "", null, SQLiteDatabase.OPEN_READWRITE, noHook)
                try { stage = "reading the existing database"; version = plain.version; tables = count(plain) } finally { plain.close() }
            }
            if (!reverse) {
                val src = SQLiteDatabase.openDatabase(file.path, "", null, SQLiteDatabase.OPEN_READWRITE, noHook)
                try {
                    stage = "attaching the encrypted copy"
                    src.rawExecSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS encrypted KEY '$pass'")
                    stage = "copying the data"
                    src.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                    src.rawExecSQL("DETACH DATABASE encrypted")
                } finally { src.close() }
            } else {
                val dst = SQLiteDatabase.openDatabase(tmp.path, pass.toByteArray(Charsets.US_ASCII), null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, noHook)
                try {
                    stage = "attaching the existing database"
                    dst.rawExecSQL("ATTACH DATABASE '${file.path.replace("'", "''")}' AS plaintext KEY ''")
                    stage = "copying the data"
                    dst.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
                    dst.rawExecSQL("DETACH DATABASE plaintext")
                } finally { dst.close() }
            }
            stage = "verifying the encrypted copy (file ${if (tmp.exists()) "${tmp.length()} bytes" else "missing"})"
            val check = SQLiteDatabase.openDatabase(tmp.path, pass.toByteArray(Charsets.US_ASCII), null, SQLiteDatabase.OPEN_READWRITE, noHook)
            val problem = try {
                // sqlcipher_export does not carry user_version over; set it through the API (Room reads it to decide whether to migrate).
                check.version = version
                val v = check.version; val t = count(check)
                if (v != version) "schema version $v != $version" else if (t != tables) "$t tables != $tables" else null
            } finally { check.close() }
            if (problem != null) { deleteDbFiles(tmp); return "verifying the copy failed ($problem)" }
            stage = "swapping the files"
            // The plaintext WAL/SHM belong to the old file; remove them before the swap so the new file is read on its own.
            if (!file.renameTo(bak)) { deleteDbFiles(tmp); return "couldn't move the old file aside" }
            if (!tmp.renameTo(file)) { bak.renameTo(file); deleteDbFiles(tmp); return "couldn't move the new file into place" }
            listOf(File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
            overwriteAndDelete(bak)
            return null
        } catch (t: Throwable) {
            deleteDbFiles(tmp)
            if (!file.exists() && bak.exists()) bak.renameTo(file)
            return "$stage: ${t.javaClass.simpleName}${t.message?.let { ": " + it.take(120) } ?: ""}"
        }
    }

    /** Number of real tables (ignores SQLite internals and Android's locale table, which the two sides treat differently). */
    private fun count(db: SQLiteDatabase): Long = db.rawQuery("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'", arrayOf<String>()).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    /** Best effort to leave no readable copy behind (flash storage may still keep remnants). */
    private fun overwriteAndDelete(f: File) {
        runCatching { java.io.RandomAccessFile(f, "rw").use { r -> val n = r.length(); r.seek(0); val z = ByteArray(8192); var left = n; while (left > 0) { val w = minOf(left, z.size.toLong()).toInt(); r.write(z, 0, w); left -= w } } }
        f.delete()
    }
}

/** The database passphrase, stored as AES-GCM ciphertext under a Keystore key. */
@android.annotation.SuppressLint("ApplySharedPref") // the key must be on disk before the database is created with it
internal object DbKey {
    private const val ALIAS = "recon_db_key_v1"
    private const val PREFS = "recon_db"
    private const val ENTRY = "k"

    fun load(context: Context): String? = runCatching {
        val blob = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENTRY, null) ?: return null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12)) }
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.US_ASCII)
    }.getOrNull()

    fun create(context: Context): String {
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = Base64.encodeToString(c.iv + c.doFinal(pass.toByteArray(Charsets.US_ASCII)), Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ENTRY, blob).commit()
        return pass
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }
}
