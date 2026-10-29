package vip.ringling.recon.data

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Passphrase encryption for backup files: PBKDF2-HMAC-SHA256 (600k iterations, random 16-byte salt) derives an AES-256-GCM key; the
 * envelope's format, version and KDF parameters are bound in as associated data, so none of them can be altered without failing to open.
 */
object BackupCrypto {
    const val FORMAT = "recon-backup-encrypted"
    const val MIN_PASSPHRASE = 8
    private const val ITERATIONS = 600_000

    @Serializable private data class Envelope(val format: String, val v: Int, val kdf: String, val iter: Int, val salt: String, val iv: String, val ct: String)

    class WrongPassphrase : Exception("Wrong passphrase, or the file was changed.")

    private val json = Json { ignoreUnknownKeys = true }
    private val b64 = Base64.getEncoder(); private val unb64 = Base64.getDecoder()

    fun isSealed(text: String): Boolean = runCatching { json.decodeFromString<Envelope>(text).format == FORMAT }.getOrDefault(false)

    fun seal(plain: String, passphrase: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): String {
        require(passphrase.size >= MIN_PASSPHRASE) { "Use at least $MIN_PASSPHRASE characters." }
        val salt = ByteArray(16).also(random::nextBytes); val iv = ByteArray(12).also(random::nextBytes)
        val header = Envelope(FORMAT, 1, "PBKDF2WithHmacSHA256", iterations, b64.encodeToString(salt), b64.encodeToString(iv), "")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(128, iv)); updateAAD(aad(header)) }
        return json.encodeToString(Envelope.serializer(), header.copy(ct = b64.encodeToString(c.doFinal(plain.toByteArray(Charsets.UTF_8)))))
    }

    fun open(sealed: String, passphrase: CharArray): String {
        val e = json.decodeFromString<Envelope>(sealed)
        require(e.format == FORMAT && e.v == 1 && e.kdf == "PBKDF2WithHmacSHA256") { "Not a Recon encrypted backup this version can read." }
        require(e.iter in 100_000..10_000_000) { "Unsupported key-derivation settings." }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(passphrase, unb64.decode(e.salt), e.iter), GCMParameterSpec(128, unb64.decode(e.iv))); updateAAD(aad(e.copy(ct = "")))
        }
        return try { String(c.doFinal(unb64.decode(e.ct)), Charsets.UTF_8) } catch (x: AEADBadTagException) { throw WrongPassphrase() }
    }

    private fun aad(e: Envelope) = "${e.format}|${e.v}|${e.kdf}|${e.iter}|${e.salt}|${e.iv}".toByteArray()

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try { return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }
}
