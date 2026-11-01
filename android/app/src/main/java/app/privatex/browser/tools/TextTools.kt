package app.privatex.browser.tools

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text and data tools: hashes, Base64, URL coding, JSON formatting,
 * text statistics, unit conversion, a small expression calculator,
 * and the Have-I-Been-Pwned k-anonymity split/parse (the password is
 * SHA-1 hashed on device; only the first 5 hex chars ever leave it,
 * and only when the user explicitly runs the check).
 * All pure JVM logic — unit-tested.
 */
object TextTools {

    // ------------------------------------------------------------ hashes

    enum class HashAlgorithm(val jcaName: String, val label: String) {
        SHA_256("SHA-256", "SHA-256"),
        SHA_512("SHA-512", "SHA-512"),
        SHA_1("SHA-1", "SHA-1 (legacy — checksums, not security)"),
        MD5("MD5", "MD5 (legacy — checksums only)"),
    }

    fun hashBytes(bytes: ByteArray, algorithm: HashAlgorithm): String =
        MessageDigest.getInstance(algorithm.jcaName).digest(bytes).toHex()

    fun hashText(text: String, algorithm: HashAlgorithm): String =
        hashBytes(text.toByteArray(Charsets.UTF_8), algorithm)

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ pwned (k-anonymity)

    /** Splits a password's SHA-1 into (5-char prefix sent, suffix kept local). */
    fun pwnedSplit(password: String): Pair<String, String> {
        val hash = hashText(password, HashAlgorithm.SHA_1).uppercase()
        return hash.substring(0, 5) to hash.substring(5)
    }

    /** Parses a range-API response body: lines of "SUFFIX:count". */
    fun parseRangeResponse(body: String): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (line in body.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val colon = trimmed.indexOf(':')
            if (colon < 0) continue
            val suffix = trimmed.substring(0, colon).uppercase()
            val count = trimmed.substring(colon + 1).toIntOrNull() ?: 0
            out[suffix] = count
        }
        return out
    }

    // ------------------------------------------------------------ Base64 / URL

    fun base64Encode(text: String): String =
        java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    fun base64Decode(text: String): String {
        val cleaned = text.trim().replace("\n", "").replace(" ", "")
        val bytes = try {
            java.util.Base64.getDecoder().decode(cleaned)
        } catch (e: IllegalArgumentException) {
            java.util.Base64.getMimeDecoder().decode(cleaned)
        }
        return bytes.toString(Charsets.UTF_8)
    }

    fun urlEncode(text: String): String =
        URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    fun urlDecode(text: String): String = URLDecoder.decode(text, "UTF-8")

    // ------------------------------------------------------------ JSON

    /** Pretty-prints JSON, or throws with the parser's complaint. */
    fun jsonPretty(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
            else -> throw IllegalArgumentException("JSON must start with { or [")
        }
    }

    fun jsonMinify(text: String): String {
        val trimmed = text.trim()
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString()
            trimmed.startsWith("[") -> JSONArray(trimmed).toString()
            else -> throw IllegalArgumentException("JSON must start with { or [")
        }
    }

    // ------------------------------------------------------------ text stats

    data class TextStats(
        val characters: Int,
        val charactersNoSpaces: Int,
        val words: Int,
        val lines: Int,
        val sentences: Int,
        val readingMinutes: Double,
    )

    fun textStats(text: String): TextStats {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val sentences = text.split(Regex("[.!?]+")).count { it.isNotBlank() }
        return TextStats(
            characters = text.length,
            charactersNoSpaces = text.count { !it.isWhitespace() },
            words = if (text.isBlank()) 0 else words,
            lines = if (text.isEmpty()) 0 else text.lines().size,
            sentences = sentences,
            readingMinutes = if (text.isBlank()) 0.0 else words / 200.0,
        )
    }

    // ------------------------------------------------------------ units

    /** Linear unit families: unit -> factor to the family's base unit. */
    val unitFamilies: Map<String, Map<String, Double>> = mapOf(
        "Length (base: metre)" to mapOf(
            "millimetre" to 0.001, "centimetre" to 0.01, "metre" to 1.0,
            "kilometre" to 1000.0, "inch" to 0.0254, "foot" to 0.3048,
            "yard" to 0.9144, "mile" to 1609.344,
        ),
        "Mass (base: kilogram)" to mapOf(
            "gram" to 0.001, "kilogram" to 1.0, "tonne" to 1000.0,
            "ounce" to 0.028349523125, "pound" to 0.45359237,
        ),
        "Data (base: byte)" to mapOf(
            "byte" to 1.0, "kilobyte" to 1000.0, "megabyte" to 1e6,
            "gigabyte" to 1e9, "terabyte" to 1e12,
            "kibibyte" to 1024.0, "mebibyte" to 1048576.0, "gibibyte" to 1073741824.0,
        ),
        "Time (base: second)" to mapOf(
            "second" to 1.0, "minute" to 60.0, "hour" to 3600.0,
            "day" to 86400.0, "week" to 604800.0,
        ),
    )

    fun convertLinear(value: Double, fromFactor: Double, toFactor: Double): Double =
        value * fromFactor / toFactor

    fun convertTemperature(value: Double, from: String, to: String): Double {
        val celsius = when (from) {
            "°C" -> value
            "°F" -> (value - 32) * 5 / 9
            "K" -> value - 273.15
            else -> throw IllegalArgumentException("Unknown unit $from")
        }
        return when (to) {
            "°C" -> celsius
            "°F" -> celsius * 9 / 5 + 32
            "K" -> celsius + 273.15
            else -> throw IllegalArgumentException("Unknown unit $to")
        }
    }

    // ------------------------------------------------------------ calculator

    /**
     * A small recursive-descent expression evaluator: + − * / % ^,
     * parentheses, unary minus, sqrt() and abs(). No code execution —
     * it parses numbers and operators only.
     */
    fun evaluate(expression: String): Double {
        val parser = ExpressionParser(expression)
        val value = parser.parseExpression()
        parser.skipSpaces()
        if (!parser.atEnd()) throw IllegalArgumentException("Unexpected trailing input at ${parser.position()}")
        return value
    }

    private class ExpressionParser(private val input: String) {
        private var pos = 0
        fun position(): Int = pos
        fun atEnd(): Boolean = pos >= input.length
        fun skipSpaces() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipSpaces()
            return if (pos < input.length) input[pos] else null
        }

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; value += parseTerm() }
                    '-' -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; value *= parseFactor() }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw IllegalArgumentException("Division by zero")
                        value /= divisor
                    }
                    '%' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw IllegalArgumentException("Division by zero")
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            val base = parseUnary()
            if (peek() == '^') {
                pos++
                val exponent = parseFactor()
                return Math.pow(base, exponent)
            }
            return base
        }

        private fun parseUnary(): Double {
            return when (peek()) {
                '-' -> { pos++; -parseUnary() }
                '+' -> { pos++; parseUnary() }
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): Double {
            val c = peek() ?: throw IllegalArgumentException("Unexpected end of expression")
            if (c == '(') {
                pos++
                val value = parseExpression()
                if (peek() != ')') throw IllegalArgumentException("Missing closing parenthesis")
                pos++
                return value
            }
            if (c.isLetter()) {
                val start = pos
                while (pos < input.length && input[pos].isLetter()) pos++
                val name = input.substring(start, pos)
                if (peek() != '(') throw IllegalArgumentException("Expected ( after $name")
                pos++
                val arg = parseExpression()
                if (peek() != ')') throw IllegalArgumentException("Missing closing parenthesis")
                pos++
                return when (name.lowercase()) {
                    "sqrt" -> {
                        if (arg < 0) throw IllegalArgumentException("sqrt of a negative number")
                        Math.sqrt(arg)
                    }
                    "abs" -> Math.abs(arg)
                    else -> throw IllegalArgumentException("Unknown function $name")
                }
            }
            val start = pos
            var seenDot = false
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) {
                if (input[pos] == '.') {
                    if (seenDot) break
                    seenDot = true
                }
                pos++
            }
            if (start == pos) throw IllegalArgumentException("Expected a number at position $pos")
            return input.substring(start, pos).toDouble()
        }
    }
}
