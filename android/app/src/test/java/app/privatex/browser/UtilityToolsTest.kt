package app.privatex.browser

import app.privatex.browser.tools.MetaClean
import app.privatex.browser.tools.PasswordStrength
import app.privatex.browser.tools.QrTool
import app.privatex.browser.tools.TextTools
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 5 utility tests: MetaClean against synthetic JPEG/PNG/WebP
 * fixtures built byte-by-byte (EXIF with GPS, comments, text chunks),
 * strength-estimator vectors, text tools, and a QR round-trip through
 * the real ZXing encoder/decoder.
 */
class UtilityToolsTest {

    // ------------------------------------------------------------ fixtures

    /** Minimal JPEG: SOI, APP0, APP1-EXIF (make + GPS), COM, SOS + scan bytes, EOI bytes in scan. */
    private fun syntheticJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        // APP0 JFIF
        out.write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))
        out.write("JFIF\u0000".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(9))
        // APP1 EXIF: "Exif\0\0" + TIFF (little-endian)
        val tiff = buildTiff()
        val app1 = ByteArrayOutputStream()
        app1.write("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII))
        app1.write(tiff)
        val app1Bytes = app1.toByteArray()
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte()))
        out.write((app1Bytes.size + 2).let { byteArrayOf((it shr 8).toByte(), it.toByte()) })
        out.write(app1Bytes)
        // COM comment
        val comment = "shot by talha".toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
        out.write((comment.size + 2).let { byteArrayOf((it shr 8).toByte(), it.toByte()) })
        out.write(comment)
        // SOS + fake scan data
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00))
        out.write(byteArrayOf(0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte()))
        return out.toByteArray()
    }

    /** TIFF with IFD0 (Make=Canon, GPS ptr) and a GPS IFD (Lahore coords). */
    private fun buildTiff(): ByteArray {
        val buf = ByteArrayOutputStream()
        fun u16(v: Int) = buf.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        fun u32(v: Int) = buf.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        buf.write("II".toByteArray(Charsets.US_ASCII))
        u16(42)
        u32(8) // IFD0 at offset 8
        // IFD0: 2 entries (Make ASCII "Canon\0" inline-ish via offset, GPS ptr)
        u16(2)
        // Entry 1: tag 0x010F Make, type 2 (ASCII), count 6, value offset -> after IFDs
        u16(0x010F); u16(2); u32(6); u32(8 + 2 + 24 + 4) // offset to string data
        // Entry 2: tag 0x8825 GPS ptr, type 4, count 1, value = GPS IFD offset
        val gpsOffset = 8 + 2 + 24 + 4 + 6 // header+ifd0+string
        u16(0x8825); u16(4); u32(1); u32(gpsOffset)
        u32(0) // next IFD
        buf.write("Canon\u0000".toByteArray(Charsets.US_ASCII))
        // GPS IFD at gpsOffset: 4 entries: latRef "N", lat 31/1 32/1 36/1, lonRef "E", lon 74/1 21/1 0/1
        val rationalsOffset = gpsOffset + 2 + 48 + 4
        u16(4)
        u16(0x0001); u16(2); u32(2); buf.write(byteArrayOf('N'.code.toByte(), 0, 0, 0))
        u16(0x0002); u16(5); u32(3); u32(rationalsOffset)
        u16(0x0003); u16(2); u32(2); buf.write(byteArrayOf('E'.code.toByte(), 0, 0, 0))
        u16(0x0004); u16(5); u32(3); u32(rationalsOffset + 24)
        u32(0)
        // rationals: lat (31,1)(32,1)(36,1), lon (74,1)(21,1)(0,1)
        listOf(31, 32, 36, 74, 21, 0).forEach { n -> u32(n); u32(1) }
        return buf.toByteArray()
    }

    private fun syntheticPng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) {
            out.write(byteArrayOf(
                (data.size shr 24).toByte(), (data.size shr 16).toByte(),
                (data.size shr 8).toByte(), data.size.toByte(),
            ))
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(data)
            out.write(byteArrayOf(0, 0, 0, 0)) // CRC placeholder — parser doesn't verify
        }
        chunk("IHDR", ByteArray(13))
        val text = ByteArrayOutputStream()
        text.write("Author".toByteArray(Charsets.ISO_8859_1))
        text.write(0)
        text.write("Talha".toByteArray(Charsets.ISO_8859_1))
        chunk("tEXt", text.toByteArray())
        chunk("IDAT", byteArrayOf(0x42, 0x42))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun syntheticWebp(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0, 0, 0, 0)) // patched by strip; inspect ignores
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        fun chunk(type: String, data: ByteArray) {
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(byteArrayOf(data.size.toByte(), (data.size shr 8).toByte(), (data.size shr 16).toByte(), (data.size shr 24).toByte()))
            out.write(data)
            if (data.size % 2 == 1) out.write(0)
        }
        chunk("VP8 ", byteArrayOf(0x01, 0x02, 0x03))
        chunk("EXIF", buildTiff())
        chunk("XMP ", "<x:xmpmeta/>".toByteArray(Charsets.UTF_8))
        return out.toByteArray()
    }

    // ------------------------------------------------------------ MetaClean

    @Test
    fun jpegInspectFindsExifGpsAndComment() {
        val report = MetaClean.inspect(syntheticJpeg())
        assertEquals("JPEG", report.format)
        assertTrue(report.hasMetadata)
        assertTrue(report.hasLocation)
        val gps = report.fields.firstOrNull { it.name == "GPS location" }
        assertEquals("31.543333, 74.350000", gps?.value)
        assertTrue(report.fields.any { it.name == "Camera make" && it.value == "Canon" })
        assertTrue(report.fields.any { it.name == "Comment" && it.value == "shot by talha" })
        assertTrue(report.metadataCarriers.any { it.contains("EXIF") })
    }

    @Test
    fun jpegCleanStripsAndVerifies() {
        val result = MetaClean.clean(syntheticJpeg())
        assertTrue(result.removedCarriers.size >= 2)
        val after = MetaClean.inspect(result.bytes)
        assertFalse(after.hasMetadata)
        // Pixel data preserved: APP0 and the scan bytes survive.
        assertTrue(result.bytes.toList().windowed(2).any { it == listOf(0xFF.toByte(), 0xE0.toByte()) })
    }

    @Test
    fun pngTextChunkFoundAndStripped() {
        val report = MetaClean.inspect(syntheticPng())
        assertTrue(report.fields.any { it.name == "Text: Author" && it.value == "Talha" })
        val cleaned = MetaClean.clean(syntheticPng())
        assertFalse(MetaClean.inspect(cleaned.bytes).hasMetadata)
        assertTrue(String(cleaned.bytes, Charsets.US_ASCII).contains("IDAT"))
    }

    @Test
    fun webpExifAndXmpStripped() {
        val report = MetaClean.inspect(syntheticWebp())
        assertTrue(report.hasLocation)
        val cleaned = MetaClean.clean(syntheticWebp())
        val after = MetaClean.inspect(cleaned.bytes)
        assertFalse(after.hasMetadata)
        // RIFF size patched to file size - 8.
        val size = (cleaned.bytes[4].toInt() and 0xFF) or
            ((cleaned.bytes[5].toInt() and 0xFF) shl 8) or
            ((cleaned.bytes[6].toInt() and 0xFF) shl 16) or
            ((cleaned.bytes[7].toInt() and 0xFF) shl 24)
        assertEquals(cleaned.bytes.size - 8, size)
    }

    @Test
    fun unsupportedFormatThrows() {
        try {
            MetaClean.inspect(byteArrayOf(0x25, 0x50, 0x44, 0x46)) // "%PDF"
            fail("PDF bytes must be rejected")
        } catch (expected: Exception) {
            // as designed
        }
    }

    // ------------------------------------------------------------ strength

    @Test
    fun strengthVectors() {
        assertEquals(PasswordStrength.Band.VERY_WEAK, PasswordStrength.estimate("password").band)
        assertEquals(PasswordStrength.Band.VERY_WEAK, PasswordStrength.estimate("").band)
        val strong = PasswordStrength.estimate("Xq9!mZ2#vL8&pR4w")
        assertTrue(strong.band == PasswordStrength.Band.STRONG || strong.band == PasswordStrength.Band.EXCELLENT)
        assertTrue(PasswordStrength.estimate("aaaaaaaaaaaa").warnings.any { it.contains("Repeated") })
        assertTrue(PasswordStrength.estimate("abc123456").warnings.any { it.contains("Sequential") })
    }

    // ------------------------------------------------------------ text tools

    @Test
    fun textToolsRoundTrips() {
        assertEquals("aGVsbG8=", TextTools.base64Encode("hello"))
        assertEquals("hello", TextTools.base64Decode("aGVsbG8="))
        assertEquals("a%20b%26c", TextTools.urlEncode("a b&c"))
        assertEquals("a b&c", TextTools.urlDecode("a%20b%26c"))
        // Note: org.json writes single-entry objects on one line even
        // when pretty-printing (documented library behaviour), so the
        // pretty case uses two entries.
        assertTrue(TextTools.jsonPretty("{\"a\":1,\"b\":2}").contains("\n"))
        assertEquals("{\"a\":1}", TextTools.jsonMinify("{ \"a\" : 1 }"))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            TextTools.hashText("abc", TextTools.HashAlgorithm.SHA_256),
        )
        val stats = TextTools.textStats("One two. Three four five!")
        assertEquals(5, stats.words)
        assertEquals(2, stats.sentences)
    }

    @Test
    fun pwnedSplitAndParse() {
        // SHA-1("password") = 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8
        val (prefix, suffix) = TextTools.pwnedSplit("password")
        assertEquals("5BAA6", prefix)
        assertEquals("1E4C9B93F3F0682250B6CF8331B7EE68FD8", suffix)
        val parsed = TextTools.parseRangeResponse("0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n1E4C9B93F3F0682250B6CF8331B7EE68FD8:9545824\n")
        assertEquals(9545824, parsed["1E4C9B93F3F0682250B6CF8331B7EE68FD8"])
    }

    @Test
    fun calculatorAndConverter() {
        assertEquals(14.0, TextTools.evaluate("2 + 3 * 4"), 1e-9)
        assertEquals(20.0, TextTools.evaluate("(2 + 3) * 4"), 1e-9)
        assertEquals(8.0, TextTools.evaluate("2^3"), 1e-9)
        assertEquals(3.0, TextTools.evaluate("sqrt(9)"), 1e-9)
        assertEquals(-5.0, TextTools.evaluate("-(2+3)"), 1e-9)
        try {
            TextTools.evaluate("1/0")
            fail("division by zero must throw")
        } catch (expected: IllegalArgumentException) {
            // as designed
        }
        assertEquals(100.0, TextTools.convertTemperature(212.0, "°F", "°C"), 1e-9)
        assertEquals(1000.0, TextTools.convertLinear(1.0, 1000.0, 1.0), 1e-9)
    }

    // ------------------------------------------------------------ QR

    @Test
    fun qrRoundTrip() {
        val matrix = QrTool.encode("https://sett.example/hello", 256)
        val width = matrix[0].size
        val height = matrix.size
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (matrix[y][x]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertEquals("https://sett.example/hello", QrTool.decode(pixels, width, height))
    }
}
