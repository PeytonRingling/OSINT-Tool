package vip.ringling.recon.data.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/** How a downloaded update's signer compares with the installed app's. */
enum class SignerMatch { SAME, DIFFERENT, UNKNOWN }

/**
 * Refuses an update signed with a different certificate than the installed app. Checking before the installer gives a clear message and
 * never hands a foreign APK to PackageInstaller. When either side's certificates can't be read (Android 10 and some OEM builds return no
 * signing info for an APK file), the check steps aside: PackageInstaller enforces the same rule at install time, so a readable mismatch is
 * the only thing refused here and a genuine update is never blocked by this check.
 */
object SigningCheck {
    // GET_SIGNATURES alongside GET_SIGNING_CERTIFICATES: some Android 10 builds fill only the legacy field for an APK file.
    @Suppress("DEPRECATION")
    private const val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES

    fun sha256(cert: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }

    fun compare(installed: Set<String>, candidate: Set<String>): SignerMatch = when {
        installed.isEmpty() || candidate.isEmpty() -> SignerMatch.UNKNOWN
        installed == candidate -> SignerMatch.SAME
        else -> SignerMatch.DIFFERENT
    }

    // Lint's PackageManagerGetSignatures warns about trusting only the first of several signers; the whole signer set is compared here.
    @SuppressLint("PackageManagerGetSignatures")
    fun installed(context: Context): Set<String> = certs(context.packageManager.getPackageInfo(context.packageName, FLAGS))

    @SuppressLint("PackageManagerGetSignatures")
    fun ofApk(context: Context, apk: File): Set<String> = context.packageManager.getPackageArchiveInfo(apk.path, FLAGS)?.let(::certs).orEmpty()

    /** SHA-256 of each certificate that signed the APK (current signers, not rotation history), the same digest `apksigner verify --print-certs` shows. */
    fun certs(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val signers = info.signingInfo?.apkContentsSigners?.takeIf { it.isNotEmpty() } ?: info.signatures
        return signers.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }

    /** Throws when [apk] is readably signed by a certificate other than this app's. */
    fun requireSameSigner(context: Context, apk: File) {
        val mine = runCatching { installed(context) }.getOrDefault(emptySet())
        val theirs = runCatching { ofApk(context, apk) }.getOrDefault(emptySet())
        if (compare(mine, theirs) == SignerMatch.DIFFERENT) throw UpdateException(
            "This update is signed with a different key than the installed Recon, so it was refused. If the release key was changed on purpose, back up, uninstall Recon and install the new build once by hand.",
        )
    }
}
