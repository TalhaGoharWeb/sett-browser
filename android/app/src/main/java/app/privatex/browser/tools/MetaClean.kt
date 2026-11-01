package app.privatex.browser.tools

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * MetaClean — metadata inspection and removal for JPEG / PNG / WebP,
 * ported faithfully from the PRIVACY X suite's byte-level Dart engine.
 *
 * Only the metadata carriers named here are handled: EXIF/GPS/XMP/
 * comment segments and text chunks. Image data is copied verbatim,
 * never re-encoded, so a cleaned file keeps identical pixels.
 * [clean] re-inspects its own output and throws if any metadata
 * survives — verified stripping, enforced in code.
 */
class MetaCleanException(message: String) : Exception(message)

data class MetadataField(val name: String, val value: String) {
    override fun toString(): String = "$name: $value"
}

data class MetadataReport(
    val format: String,
    val fields: List<MetadataField>,
    val metadataCarriers: List<String>,
) {
    val hasMetadata: Boolean get() = fields.isNotEmpty() || metadataCarriers.isNotEmpty()
    val hasLocation: Boolean get() = fields.any { it.name.startsWith("GPS") }
}

data class CleanResult(
    val bytes: ByteArray,
    val before: MetadataReport,
    val removedCarriers: List<String>,
)

object MetaClean {

    enum class Format { JPEG, PNG, WEBP }

    fun detectFormat(b: ByteArray): Format {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) return Format.JPEG
        if (b.size >= 8 &&
            b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() &&
            b[3] == 0x47.toByte() && b[4] == 0x0D.toByte() && b[5] == 0x0A.toByte() &&
            b[6] == 0x1A.toByte() && b[7] == 0x0A.toByte()
        ) return Format.PNG
        if (b.size >= 12 && ascii(b, 0, 4) == "RIFF" && ascii(b, 8, 4) == "WEBP") return Format.WEBP
        throw MetaCleanException("Unsupported file: MetaClean handles JPEG, PNG and WebP images.")
    }

    fun inspect(bytes: ByteArray): MetadataReport = when (detectFormat(bytes)) {
        Format.JPEG -> jpegInspect(bytes)
        Format.PNG -> pngInspect(bytes)
        Format.WEBP -> webpInspect(bytes)
    }

    fun clean(bytes: ByteArray): CleanResult {
        val before = inspect(bytes)
        val (cleaned, removed) = when (detectFormat(bytes)) {
            Format.JPEG -> jpegStrip(bytes)
            Format.PNG -> pngStrip(bytes)
            Format.WEBP -> webpStrip(bytes)
        }
        val after = inspect(cleaned)
        if (after.hasMetadata) {
            throw MetaCleanException(
                "Verification failed: metadata remains after cleaning " +
                    "(${after.metadataCarriers.joinToString(", ")}). File not written.",
            )
        }
        return CleanResult(cleaned, before, removed)
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String =
        String(b, off, len, Charsets.US_ASCII)

    // ------------------------------------------------------------ TIFF / EXIF

    private val typeSizes = mapOf(1 to 1, 2 to 1, 3 to 2, 4 to 4, 5 to 8, 6 to 1, 7 to 1, 8 to 2, 9 to 4, 10 to 8)

    internal fun parseTiff(tiff: ByteArray): List<MetadataField> {
        val fields = mutableListOf<MetadataField>()
        if (tiff.size < 8) return fields
        val orderMark = ascii(tiff, 0, 2)
        val little = orderMark == "II"
        if (!little && orderMark != "MM") return fields
        val buf = ByteBuffer.wrap(tiff).order(if (little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        fun u16(o: Int): Int = buf.getShort(o).toInt() and 0xFFFF
        fun u32(o: Int): Long = buf.getInt(o).toLong() and 0xFFFFFFFFL
        if (u16(2) != 42) return fields

        fun valueOf(type: Int, count: Int, valueOffsetField: Int, fieldOffset: Int): Any? {
            val size = (typeSizes[type] ?: 1) * count
            val dataOffset = if (size <= 4) fieldOffset + 8 else u32(valueOffsetField).toInt()
            if (dataOffset < 0 || dataOffset + size > tiff.size) return null
            return when (type) {
                2 -> {
                    val raw = tiff.copyOfRange(dataOffset, dataOffset + count)
                    val nul = raw.indexOf(0)
                    String(if (nul >= 0) raw.copyOf(nul) else raw, Charsets.UTF_8)
                }
                3 -> if (count == 1) u16(dataOffset) else null
                4 -> if (count == 1) u32(dataOffset) else null
                5 -> if (count >= 1) {
                    val num = u32(dataOffset)
                    val den = u32(dataOffset + 4)
                    if (den == 0L) 0.0 else num.toDouble() / den.toDouble()
                } else null
                else -> null
            }
        }

        fun rationals(type: Int, count: Int, vof: Int): List<Double>? {
            if (type != 5 || count < 1) return null
            val dataOffset = u32(vof).toInt()
            val out = mutableListOf<Double>()
            for (i in 0 until count) {
                val o = dataOffset + i * 8
                if (o + 8 > tiff.size) return null
                val den = u32(o + 4)
                out.add(if (den == 0L) 0.0 else u32(o).toDouble() / den.toDouble())
            }
            return out
        }

        val ifd0Names = mapOf(
            0x010F to "Camera make", 0x0110 to "Camera model", 0x0131 to "Software",
            0x0132 to "Date/time", 0x013B to "Artist", 0x8298 to "Copyright",
            0x0112 to "Orientation",
        )

        fun walkIfd(
            offset: Int,
            names: Map<Int, String>,
            extra: ((tag: Int, type: Int, count: Int, vof: Int, fo: Int) -> Unit)? = null,
        ) {
            if (offset + 2 > tiff.size) return
            val count = u16(offset)
            for (i in 0 until count) {
                val fo = offset + 2 + i * 12
                if (fo + 12 > tiff.size) return
                val tag = u16(fo)
                val type = u16(fo + 2)
                val cnt = u32(fo + 4).toInt()
                val name = names[tag]
                if (name != null) {
                    val v = valueOf(type, cnt, fo + 8, fo)
                    if (v != null) fields.add(MetadataField(name, v.toString()))
                }
                extra?.invoke(tag, type, cnt, fo + 8, fo)
            }
        }

        var exifPtr: Int? = null
        var gpsPtr: Int? = null
        walkIfd(u32(4).toInt(), ifd0Names) { tag, _, _, vof, _ ->
            if (tag == 0x8769) exifPtr = u32(vof).toInt()
            if (tag == 0x8825) gpsPtr = u32(vof).toInt()
        }
        exifPtr?.let {
            walkIfd(it, mapOf(
                0x9003 to "Date/time original", 0x9004 to "Date/time digitised",
                0xA434 to "Lens model", 0x9286 to "User comment",
            ))
        }
        gpsPtr?.let { ptr ->
            var latRef: String? = null
            var lonRef: String? = null
            var lat: List<Double>? = null
            var lon: List<Double>? = null
            walkIfd(ptr, emptyMap()) { tag, type, count, vof, fo ->
                when (tag) {
                    0x0001 -> latRef = valueOf(type, count, vof, fo)?.toString()
                    0x0002 -> lat = rationals(type, count, vof)
                    0x0003 -> lonRef = valueOf(type, count, vof, fo)?.toString()
                    0x0004 -> lon = rationals(type, count, vof)
                    0x0006 -> valueOf(type, count, vof, fo)?.let {
                        fields.add(MetadataField("GPS altitude", "$it m"))
                    }
                }
            }
            fun toDecimal(parts: List<Double>?, ref: String?): Double? {
                if (parts == null || parts.size < 3) return null
                var v = parts[0] + parts[1] / 60 + parts[2] / 3600
                if (ref == "S" || ref == "W") v = -v
                return v
            }
            val la = toDecimal(lat, latRef)
            val lo = toDecimal(lon, lonRef)
            if (la != null && lo != null) {
                fields.add(0, MetadataField("GPS location", "%.6f, %.6f".format(la, lo)))
            }
        }
        return fields
    }

    // ------------------------------------------------------------ JPEG

    private class JpegSegment(val marker: Int, val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    private fun jpegSegments(b: ByteArray): List<JpegSegment> {
        val segments = mutableListOf<JpegSegment>()
        var pos = 2
        while (pos + 1 < b.size) {
            if (b[pos] != 0xFF.toByte()) throw MetaCleanException("Malformed JPEG at offset $pos")
            val marker = b[pos + 1].toInt() and 0xFF
            when {
                marker == 0xDA -> {
                    segments.add(JpegSegment(marker, pos, b.size))
                    return segments
                }
                marker == 0xD9 -> {
                    segments.add(JpegSegment(marker, pos, pos + 2))
                    return segments
                }
                marker == 0x01 || marker in 0xD0..0xD7 -> {
                    segments.add(JpegSegment(marker, pos, pos + 2))
                    pos += 2
                    continue
                }
                else -> {
                    if (pos + 4 > b.size) throw MetaCleanException("Truncated JPEG segment")
                    val len = ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
                    if (len < 2 || pos + 2 + len > b.size) {
                        throw MetaCleanException("Invalid JPEG segment length")
                    }
                    segments.add(JpegSegment(marker, pos, pos + 2 + len))
                    pos += 2 + len
                }
            }
        }
        return segments
    }

    private fun isJpegMetadataSegment(s: JpegSegment): Boolean =
        s.marker == 0xE1 || s.marker == 0xED || s.marker == 0xFE

    private fun jpegCarrierName(b: ByteArray, s: JpegSegment): String {
        if (s.marker == 0xFE) return "JPEG COM comment segment, ${s.length} bytes"
        if (s.marker == 0xED) return "JPEG APP13 (Photoshop) segment, ${s.length} bytes"
        val dataStart = s.start + 4
        if (s.length > 10 && ascii(b, dataStart, 4) == "Exif") {
            return "JPEG APP1 (EXIF) segment, ${s.length} bytes"
        }
        if (s.length > 24 && ascii(b, dataStart, 20).contains("xap")) {
            return "JPEG APP1 (XMP) segment, ${s.length} bytes"
        }
        return "JPEG APP1 segment, ${s.length} bytes"
    }

    private fun jpegInspect(b: ByteArray): MetadataReport {
        val fields = mutableListOf<MetadataField>()
        val carriers = mutableListOf<String>()
        for (s in jpegSegments(b)) {
            if (!isJpegMetadataSegment(s)) continue
            carriers.add(jpegCarrierName(b, s))
            val dataStart = s.start + 4
            when {
                s.marker == 0xE1 && s.length > 10 && ascii(b, dataStart, 4) == "Exif" ->
                    fields.addAll(parseTiff(b.copyOfRange(dataStart + 6, s.end)))
                s.marker == 0xFE ->
                    fields.add(MetadataField("Comment", String(b, dataStart, s.end - dataStart, Charsets.UTF_8)))
                s.marker == 0xE1 ->
                    fields.add(MetadataField("XMP packet", "present"))
            }
        }
        return MetadataReport("JPEG", fields, carriers)
    }

    private fun jpegStrip(b: ByteArray): Pair<ByteArray, List<String>> {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        val removed = mutableListOf<String>()
        for (s in jpegSegments(b)) {
            if (isJpegMetadataSegment(s)) {
                removed.add(jpegCarrierName(b, s))
                continue
            }
            out.write(b, s.start, s.length)
        }
        return out.toByteArray() to removed
    }

    // ------------------------------------------------------------ PNG

    private val pngMetadataChunks = setOf("tEXt", "zTXt", "iTXt", "eXIf", "tIME")

    private class PngChunk(val type: String, val start: Int, val end: Int, val dataStart: Int, val dataEnd: Int)

    private fun pngChunks(b: ByteArray): List<PngChunk> {
        val chunks = mutableListOf<PngChunk>()
        var pos = 8
        while (pos + 12 <= b.size) {
            val len = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
                ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
            val type = ascii(b, pos + 4, 4)
            val end = pos + 12 + len
            if (end > b.size) throw MetaCleanException("Truncated PNG chunk $type")
            chunks.add(PngChunk(type, pos, end, pos + 8, pos + 8 + len))
            pos = end
            if (type == "IEND") break
        }
        return chunks
    }

    private fun pngInspect(b: ByteArray): MetadataReport {
        val fields = mutableListOf<MetadataField>()
        val carriers = mutableListOf<String>()
        for (c in pngChunks(b)) {
            if (c.type !in pngMetadataChunks) continue
            carriers.add("PNG ${c.type} chunk, ${c.end - c.start} bytes")
            val data = b.copyOfRange(c.dataStart, c.dataEnd)
            when (c.type) {
                "tEXt" -> {
                    val nul = data.indexOf(0)
                    if (nul > 0) {
                        fields.add(MetadataField(
                            "Text: ${String(data, 0, nul, Charsets.ISO_8859_1)}",
                            String(data, nul + 1, data.size - nul - 1, Charsets.ISO_8859_1),
                        ))
                    }
                }
                "zTXt" -> {
                    val nul = data.indexOf(0)
                    if (nul > 0 && nul + 1 < data.size && data[nul + 1] == 0.toByte()) {
                        val keyword = String(data, 0, nul, Charsets.ISO_8859_1)
                        val text = runCatching {
                            val inflater = Inflater()
                            inflater.setInput(data, nul + 2, data.size - nul - 2)
                            val buf = ByteArray(8192)
                            val sb = ByteArrayOutputStream()
                            while (!inflater.finished()) {
                                val n = inflater.inflate(buf)
                                if (n == 0) break
                                sb.write(buf, 0, n)
                            }
                            inflater.end()
                            String(sb.toByteArray(), Charsets.UTF_8)
                        }.getOrElse { "(compressed)" }
                        fields.add(MetadataField("Text: $keyword", text))
                    }
                }
                "iTXt" -> fields.add(MetadataField("International text chunk", "present"))
                "eXIf" -> fields.addAll(parseTiff(data))
                "tIME" -> if (data.size >= 7) {
                    val year = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                    fields.add(MetadataField(
                        "PNG modification time",
                        "$year-${data[2]}-${data[3]} ${data[4]}:${data[5]}:${data[6]} UTC",
                    ))
                }
            }
        }
        return MetadataReport("PNG", fields, carriers)
    }

    private fun pngStrip(b: ByteArray): Pair<ByteArray, List<String>> {
        val out = ByteArrayOutputStream()
        out.write(b, 0, 8)
        val removed = mutableListOf<String>()
        for (c in pngChunks(b)) {
            if (c.type in pngMetadataChunks) {
                removed.add("PNG ${c.type} chunk, ${c.end - c.start} bytes")
                continue
            }
            out.write(b, c.start, c.end - c.start)
        }
        return out.toByteArray() to removed
    }

    // ------------------------------------------------------------ WebP

    private class WebpChunk(val type: String, val start: Int, val end: Int, val dataStart: Int, val dataEnd: Int)

    private fun webpChunks(b: ByteArray): List<WebpChunk> {
        val chunks = mutableListOf<WebpChunk>()
        var pos = 12
        while (pos + 8 <= b.size) {
            val type = ascii(b, pos, 4)
            val size = (b[pos + 4].toInt() and 0xFF) or ((b[pos + 5].toInt() and 0xFF) shl 8) or
                ((b[pos + 6].toInt() and 0xFF) shl 16) or ((b[pos + 7].toInt() and 0xFF) shl 24)
            val dataEnd = pos + 8 + size
            if (dataEnd > b.size) throw MetaCleanException("Truncated WebP chunk $type")
            val end = dataEnd + if (size % 2 == 1) 1 else 0
            chunks.add(WebpChunk(type, pos, end, pos + 8, dataEnd))
            pos = end
        }
        return chunks
    }

    private fun webpInspect(b: ByteArray): MetadataReport {
        val fields = mutableListOf<MetadataField>()
        val carriers = mutableListOf<String>()
        for (c in webpChunks(b)) {
            when (c.type) {
                "EXIF" -> {
                    carriers.add("WebP EXIF chunk, ${c.end - c.start} bytes")
                    fields.addAll(parseTiff(b.copyOfRange(c.dataStart, c.dataEnd)))
                }
                "XMP " -> {
                    carriers.add("WebP XMP chunk, ${c.end - c.start} bytes")
                    fields.add(MetadataField("XMP packet", "present"))
                }
            }
        }
        return MetadataReport("WebP", fields, carriers)
    }

    private fun webpStrip(b: ByteArray): Pair<ByteArray, List<String>> {
        val removed = mutableListOf<String>()
        val out = ByteArrayOutputStream()
        out.write(b, 0, 12)
        for (c in webpChunks(b)) {
            when {
                c.type == "EXIF" || c.type == "XMP " -> {
                    removed.add("WebP ${c.type.trim()} chunk, ${c.end - c.start} bytes")
                }
                c.type == "VP8X" && c.dataEnd - c.dataStart >= 1 -> {
                    // Clear the EXIF (0x08) and XMP (0x04) feature flags.
                    val copy = b.copyOfRange(c.start, c.end)
                    copy[8] = (copy[8].toInt() and 0x0C.inv()).toByte()
                    out.write(copy)
                }
                else -> out.write(b, c.start, c.end - c.start)
            }
        }
        val bytes = out.toByteArray()
        // Patch the RIFF size: total file size - 8, little-endian.
        val size = bytes.size - 8
        bytes[4] = (size and 0xFF).toByte()
        bytes[5] = ((size shr 8) and 0xFF).toByte()
        bytes[6] = ((size shr 16) and 0xFF).toByte()
        bytes[7] = ((size shr 24) and 0xFF).toByte()
        return bytes to removed
    }
}
