package app.privatex.browser.tools

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/**
 * Password strength estimation — ported from the PRIVACY X suite's
 * password_tools. An honest heuristic: pool-based entropy with
 * penalties for common passwords, repeats and sequences. It is an
 * estimate, labelled as such wherever shown.
 */
object PasswordStrength {

    enum class Band(val label: String) {
        VERY_WEAK("Very weak"), WEAK("Weak"), FAIR("Fair"),
        STRONG("Strong"), EXCELLENT("Excellent"),
    }

    data class Result(val entropyBits: Double, val band: Band, val warnings: List<String>)

    private val commonPasswords = setOf(
        "password", "password1", "123456", "12345678", "123456789", "qwerty",
        "letmein", "admin", "welcome", "monkey", "dragon", "football", "iloveyou",
        "sunshine", "master", "shadow", "superman", "trustno1", "pakistan",
    )

    private val sequencePattern =
        Regex("(012|123|234|345|456|567|678|789|abc|bcd|cde|def|qwerty|asdf)")
    private val repeatPattern = Regex("(.)\\1{2,}")

    fun estimate(password: String): Result {
        val warnings = mutableListOf<String>()
        if (password.isEmpty()) {
            return Result(0.0, Band.VERY_WEAK, listOf("Empty password"))
        }
        var pool = 0
        if (password.any { it in 'a'..'z' }) pool += 26
        if (password.any { it in 'A'..'Z' }) pool += 26
        if (password.any { it in '0'..'9' }) pool += 10
        if (password.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' }) pool += 32
        var entropy = password.length * log2(max(pool, 2).toDouble())

        if (password.lowercase() in commonPasswords) {
            warnings.add("This is a commonly used password")
            entropy = min(entropy, 10.0)
        }
        if (repeatPattern.containsMatchIn(password)) {
            warnings.add("Repeated characters lower the strength")
            entropy -= 8
        }
        if (sequencePattern.containsMatchIn(password.lowercase())) {
            warnings.add("Sequential characters or keyboard patterns detected")
            entropy -= 8
        }
        if (password.length < 8) warnings.add("Shorter than 8 characters")
        if (password.length < 12) warnings.add("12+ characters is recommended")
        entropy = max(entropy, 0.0)

        val band = when {
            entropy < 28 -> Band.VERY_WEAK
            entropy < 36 -> Band.WEAK
            entropy < 60 -> Band.FAIR
            entropy < 100 -> Band.STRONG
            else -> Band.EXCELLENT
        }
        return Result(Math.round(entropy).toDouble(), band, warnings)
    }
}
