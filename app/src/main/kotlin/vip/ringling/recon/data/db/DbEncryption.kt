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
object DbEncryption {
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
        if (file.exists() && isPlaintext(file) && !migrate(file, pass)) null else pass.toByteArray(Charsets.US_ASCII)
    } catch (t: Throwable) {
        null
    }

    internal fun isPlaintext(file: File): Boolean = runCatching {
        file.inputStream().use { s -> ByteArray(16).also { s.read(it) }.contentEquals(PLAIN_HEADER) }
    }.getOrDefault(false)

    private fun deleteDbFiles(file: File) {
        listOf(file, File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
    }

    private val noHook: SQLiteDatabaseHook? = null

    /** Copies [file] (plaintext) into an encrypted twin, verifies it, then swaps it in. Returns false (leaving the original untouched) on any problem. */
    private fun migrate(file: File, pass: String): Boolean {
        val tmp = File(file.parentFile, "recon-enc.tmp")
        val bak = File(file.parentFile, "recon-plain.bak")
        deleteDbFiles(tmp); bak.delete()
        try {
            val src = SQLiteDatabase.openDatabase(file.path, "", null, SQLiteDatabase.OPEN_READWRITE, noHook)
            val version: Int; val tables: Long
            try {
                version = src.version
                tables = count(src)
                src.rawExecSQL("ATTACH DATABASE '${tmp.path.replace("'", "''")}' AS encrypted KEY '$pass'")
                src.rawExecSQL("SELECT sqlcipher_export('encrypted')")
                src.rawExecSQL("PRAGMA encrypted.user_version = $version")
                src.rawExecSQL("DETACH DATABASE encrypted")
            } finally { src.close() }
            val check = SQLiteDatabase.openDatabase(tmp.path, pass.toByteArray(Charsets.US_ASCII), null, SQLiteDatabase.OPEN_READWRITE, noHook)
            val ok = try { check.version == version && count(check) == tables } finally { check.close() }
            if (!ok) { deleteDbFiles(tmp); return false }
            // The plaintext WAL/SHM belong to the old file; remove them before the swap so the new file is read on its own.
            if (!file.renameTo(bak)) { deleteDbFiles(tmp); return false }
            if (!tmp.renameTo(file)) { bak.renameTo(file); deleteDbFiles(tmp); return false }
            listOf(File(file.path + "-wal"), File(file.path + "-shm"), File(file.path + "-journal")).forEach { it.delete() }
            overwriteAndDelete(bak)
            return true
        } catch (t: Throwable) {
            deleteDbFiles(tmp)
            if (!file.exists() && bak.exists()) bak.renameTo(file)
            return false
        }
    }

    private fun count(db: SQLiteDatabase): Long = db.rawQuery("SELECT count(*) FROM sqlite_master", arrayOf<String>()).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }

    /** Best effort to leave no readable copy behind (flash storage may still keep remnants). */
    private fun overwriteAndDelete(f: File) {
        runCatching { java.io.RandomAccessFile(f, "rw").use { r -> val n = r.length(); r.seek(0); val z = ByteArray(8192); var left = n; while (left > 0) { val w = minOf(left, z.size.toLong()).toInt(); r.write(z, 0, w); left -= w } } }
        f.delete()
    }
}

/** The database passphrase, stored as AES-GCM ciphertext under a Keystore key. */
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
