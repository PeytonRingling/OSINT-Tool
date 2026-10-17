package vip.ringling.recon.data.sources.image

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import vip.ringling.recon.domain.Confidence
import vip.ringling.recon.domain.Fact
import vip.ringling.recon.domain.Finding
import vip.ringling.recon.domain.GeoPoint
import vip.ringling.recon.domain.InputType
import vip.ringling.recon.domain.Link
import vip.ringling.recon.domain.ModuleId
import vip.ringling.recon.domain.OsintSource
import vip.ringling.recon.domain.ScanInput
import vip.ringling.recon.domain.Severity

/** Reads a photo the user picked, entirely on-device: EXIF metadata + hashes. The image is never uploaded; reverse-image links open the engines' own upload pages. */
class ImageSource @Inject constructor(@ApplicationContext private val context: Context) : OsintSource {
    override val id = ModuleId.IMAGE
    override val supportedInputTypes = setOf(InputType.IMAGE)

    override suspend fun run(input: ScanInput): Flow<Finding> = flow {
        val uri = Uri.parse(input.raw)
        val bytes = context.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_BYTES) } ?: throw IllegalStateException("Could not open the photo")
        emit(ImageAnalyzer.analyze(uri.lastPathSegment ?: "photo", bytes.inputStream(), bytes))
    }.flowOn(Dispatchers.IO)

    companion object {
        const val MAX_BYTES = 40 * 1024 * 1024

        /** Reads at most [max] bytes. (InputStream.readNBytes needs API 33; this app supports API 29.) */
        fun readCapped(input: java.io.InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var left = max
            while (left > 0) { val n = input.read(buf, 0, minOf(buf.size, left)); if (n < 0) break; out.write(buf, 0, n); left -= n }
            return out.toByteArray()
        }
    }
}

object ImageAnalyzer {
    private fun hex(algo: String, b: ByteArray) = MessageDigest.getInstance(algo).digest(b).joinToString("") { "%02x".format(it) }

    fun analyze(name: String, stream: InputStream, bytes: ByteArray): Finding {
        val exif = ExifInterface(stream)
        fun tag(t: String) = exif.getAttribute(t)?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        val ll = exif.latLong
        val geo = ll?.let { GeoPoint(it[0], it[1]) }
        val make = tag(ExifInterface.TAG_MAKE); val model = tag(ExifInterface.TAG_MODEL)
        val camera = listOfNotNull(make, model).joinToString(" ").ifEmpty { null }
        val taken = tag(ExifInterface.TAG_DATETIME_ORIGINAL) ?: tag(ExifInterface.TAG_DATETIME)
        val serial = tag(ExifInterface.TAG_BODY_SERIAL_NUMBER)
        val owner = tag(ExifInterface.TAG_CAMERA_OWNER_NAME) ?: tag(ExifInterface.TAG_ARTIST)
        val facts = buildList {
            camera?.let { add(Fact("Camera", it)) }
            tag(ExifInterface.TAG_LENS_MODEL)?.let { add(Fact("Lens", it)) }
            taken?.let { add(Fact("Taken", it.replaceFirst(':', '-').replaceFirst(':', '-'))) }
            tag(ExifInterface.TAG_SOFTWARE)?.let { add(Fact("Software", it)) }
            owner?.let { add(Fact("Owner / artist", it)) }
            serial?.let { add(Fact("Camera serial", it)) }
            tag(ExifInterface.TAG_COPYRIGHT)?.let { add(Fact("Copyright", it)) }
            tag(ExifInterface.TAG_USER_COMMENT)?.let { add(Fact("User comment", it)) }
            tag(ExifInterface.TAG_IMAGE_DESCRIPTION)?.let { add(Fact("Description", it)) }
            val w = tag(ExifInterface.TAG_IMAGE_WIDTH); val h = tag(ExifInterface.TAG_IMAGE_LENGTH)
            if (w != null && h != null) add(Fact("Dimensions", "$w × $h"))
            geo?.let { add(Fact("GPS", "%.6f, %.6f".format(it.lat, it.lng))) }
            exif.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { add(Fact("Altitude", "%.0f m".format(it))) }
            tag(ExifInterface.TAG_GPS_DATESTAMP)?.let { add(Fact("GPS date", it)) }
            add(Fact("File size", "${bytes.size / 1024} KB"))
            add(Fact("SHA-256", hex("SHA-256", bytes)))
            add(Fact("MD5", hex("MD5", bytes)))
        }
        val sensitive = geo != null || serial != null || owner != null
        val detail = when {
            geo != null -> "This photo embeds the exact location it was taken at."
            sensitive -> "This photo embeds identifying details (owner or camera serial)."
            camera != null || taken != null -> "Camera and capture details found; no location embedded."
            else -> "No EXIF metadata found (stripped, screenshot or re-encoded)."
        }
        return Finding(
            id = "IMAGE-exif", module = ModuleId.IMAGE, title = if (geo != null) "Photo · GPS location embedded" else "Photo metadata",
            detail = detail, severity = if (sensitive) Severity.EXPOSED else Severity.INFO, confidence = Confidence.HIGH,
            geo = geo, facts = facts,
            tags = listOfNotNull(if (geo != null) "GPS" else null, if (serial != null) "Serial" else null),
            links = listOf(
                Link("Google Lens (upload the photo)", "https://lens.google.com/"),
                Link("TinEye (upload the photo)", "https://tineye.com/"),
                Link("Yandex Images (upload the photo)", "https://yandex.com/images/"),
                Link("Bing Visual Search (upload the photo)", "https://www.bing.com/visualsearch"),
                Link("PimEyes (faces, paid)", "https://pimeyes.com/en"),
            ),
            raw = "",
        )
    }
}
