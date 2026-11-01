package app.privatex.browser

/**
 * Start-page addresses — pure Kotlin (no Android types) so the matching
 * rules are unit-testable on the JVM.
 *
 * LEGACY is the pre-rename address (v0.1.0–v0.2.2). Session storage
 * restores tab URLs verbatim across app updates, so a tab saved before
 * the rename still points at LEGACY after it. It must keep serving the
 * start page forever; dropping it is what blanked the home tab in
 * v0.3.0 (see docs/SETT_040_PLAN.md, Phase 3a).
 */
object HomeUrls {
    const val CURRENT = "sett://home"
    const val LEGACY = "privatex://home"

    /** True for either start-page URL, with or without a trailing slash. */
    fun isHome(uri: String): Boolean =
        uri == CURRENT || uri == LEGACY || uri == "$CURRENT/" || uri == "$LEGACY/"

    /** True when raw address-bar text means "take me home". */
    fun isHomeInput(text: String): Boolean {
        val t = text.trim().lowercase()
        return t == "home" || t == "sett:home" || isHome(t)
    }
}
