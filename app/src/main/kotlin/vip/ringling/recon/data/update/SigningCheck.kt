package vip.ringling.recon.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/**
 * Refuses an update whose signing certificate isn't the installed app's. Android would also refuse it at install time, but checking first
 * gives a clear message, never hands a foreign APK to the installer, and still holds if the release key ever leaked.
 */
object SigningCheck {
    fun sha256(cert: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }

    /** Same signer set (the usual case: one certificate each). An empty side never matches. */
    fun matches(installed: Set<String>, candidate: Set<String>): Boolean = installed.isNotEmpty() && installed == candidate

    fun installed(context: Context): Set<String> =
        certs(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))

    fun ofApk(context: Context, apk: File): Set<String> =
        context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.let(::certs).orEmpty()

    /** The certificates that signed the APK itself (current signers, not rotation history), from a [PackageInfo] read with GET_SIGNING_CERTIFICATES. */
    fun certs(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet()

    /** Throws if [apk] isn't signed by the same certificate as this app. */
    fun requireSameSigner(context: Context, apk: File) {
        val mine = runCatching { installed(context) }.getOrDefault(emptySet())
        val theirs = runCatching { ofApk(context, apk) }.getOrDefault(emptySet())
        if (!matches(mine, theirs)) throw UpdateException(
            if (theirs.isEmpty()) "The download isn't a readable, signed app, so it was discarded."
            else "This update is signed with a different key than the installed Recon, so it was refused. If the release key was changed on purpose, back up, uninstall Recon and install the new build once by hand.",
        )
    }
}
