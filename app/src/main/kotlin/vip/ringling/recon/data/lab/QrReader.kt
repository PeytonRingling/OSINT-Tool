package vip.ringling.recon.data.lab

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** Reads a QR code / barcode from raw ARGB pixels entirely on the device (ZXing core, no camera or network). */
object QrReader {
    fun decode(width: Int, height: Int, pixels: IntArray): String? {
        val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX, BarcodeFormat.CODE_128, BarcodeFormat.EAN_13, BarcodeFormat.PDF_417))) }
        return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))).text }.getOrNull()
    }

    fun decode(bitmap: android.graphics.Bitmap): String? {
        // Big photos are scaled down: QR detection doesn't need more than ~1500px and it keeps memory bounded.
        val scale = minOf(1f, 1500f / maxOf(bitmap.width, bitmap.height))
        val b = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return decode(b.width, b.height, px)
    }
}
