package app.privatex.browser.vault

import java.security.SecureRandom

/**
 * Password generator: unbiased sampling (SecureRandom.nextInt over the
 * full charset) with one guaranteed character from each enabled class,
 * shuffled. Pure JVM-testable logic.
 */
object PasswordGenerator {
    private const val LOWER = "abcdefghijkmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{}<>?"

    fun generate(
        length: Int = 16,
        symbols: Boolean = true,
        random: SecureRandom = SecureRandom(),
    ): String {
        require(length >= 8) { "Passwords are at least 8 characters" }
        val classes = mutableListOf(LOWER, UPPER, DIGITS)
        if (symbols) classes.add(SYMBOLS)
        val all = classes.joinToString("")
        val chars = CharArray(length)
        // One guaranteed char per class, then fill from the union.
        classes.forEachIndexed { i, cls -> chars[i] = cls[random.nextInt(cls.length)] }
        for (i in classes.size until length) {
            chars[i] = all[random.nextInt(all.length)]
        }
        // Fisher–Yates shuffle so the guaranteed chars are not first.
        for (i in length - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = chars[i]; chars[i] = chars[j]; chars[j] = tmp
        }
        return String(chars)
    }
}
