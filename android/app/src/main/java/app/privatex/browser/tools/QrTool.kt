package app.privatex.browser.tools

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR generate/read on ZXing core (Apache-2.0). Kept free of Android
 * types — it works on boolean matrices and raw ARGB pixel arrays, so
 * both directions are JVM-testable; the activity converts Bitmaps.
 */
object QrTool {

    /** Encodes [text] as a QR matrix (true = dark module), quiet zone included. */
    fun encode(text: String, size: Int = 512): Array<BooleanArray> {
        require(text.isNotBlank()) { "Nothing to encode" }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        return Array(matrix.height) { y ->
            BooleanArray(matrix.width) { x -> matrix.get(x, y) }
        }
    }

    /** Decodes a QR code from ARGB pixels, or returns null if none is found. */
    fun decode(argbPixels: IntArray, width: Int, height: Int): String? {
        val source = RGBLuminanceSource(width, height, argbPixels)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            MultiFormatReader().decode(
                bitmap,
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).text
        } catch (e: Exception) {
            null
        }
    }
}
