package app.privatex.browser.ui


/**
 * SETT design tokens (redesign Phase 2). One source of truth for
 * colour, type and spacing across every screen — screens must take
 * colours from here, never from local constants. Dark is the
 * original Sett identity; Light is the warm off-white companion the
 * redesign brief requires. Both palettes keep the same semantic
 * slots so a screen migrates by swapping the palette, not its code.
 */
object SettTheme {

    data class Palette(
        val bg: Int,
        val surface: Int,
        val elevated: Int,
        val accent: Int,
        val accent2: Int,
        val onAccent: Int,
        val text: Int,
        val dim: Int,
        val faint: Int,
        val warn: Int,
        val error: Int,
    )

    val DARK = Palette(
        bg = 0xFF050B18.toInt(),
        surface = 0xFF0B1626.toInt(),
        elevated = 0xFF10233A.toInt(),
        accent = 0xFF2DD4BF.toInt(),
        accent2 = 0xFF38BDF8.toInt(),
        onAccent = 0xFF04241F.toInt(),
        text = 0xFFE6F1F5.toInt(),
        dim = 0xFF8FB3C7.toInt(),
        faint = 0xFF7E97A8.toInt(),
        warn = 0xFFFBBF24.toInt(),
        error = 0xFFF87171.toInt(),
    )

    val LIGHT = Palette(
        bg = 0xFFF7F4EE.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        elevated = 0xFFFFFFFF.toInt(),
        accent = 0xFF0F766E.toInt(),
        accent2 = 0xFF0369A1.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        text = 0xFF0B2436.toInt(),
        dim = 0xFF52606D.toInt(),
        faint = 0xFF8494A3.toInt(),
        warn = 0xFFB45309.toInt(),
        error = 0xFFB91C1C.toInt(),
    )

    const val MODE_SYSTEM = "system"
    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"

    /** Pure selection logic (JVM-tested): the user's mode wins; system defers to the OS. */
    fun palette(mode: String, systemDark: Boolean): Palette = when (mode) {
        MODE_LIGHT -> LIGHT
        MODE_DARK -> DARK
        else -> if (systemDark) DARK else LIGHT
    }

    fun isDark(mode: String, systemDark: Boolean): Boolean =
        palette(mode, systemDark) === DARK

    // Type scale (sp) and spacing (dp) — the only sizes screens should use.
    const val TEXT_TITLE = 20f
    const val TEXT_SECTION = 16f
    const val TEXT_BODY = 14f
    const val TEXT_SMALL = 13f
    const val TEXT_CAPTION = 12.5f

    const val SPACE_PAGE = 18
    const val SPACE_SECTION = 16
    const val RADIUS_CARD = 16
    const val RADIUS_PILL = 24
    const val TOUCH_MIN = 48
}
