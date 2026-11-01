package app.privatex.browser

import app.privatex.browser.ui.SettTheme
import org.junit.Assert.assertSame
import org.junit.Test

/** Redesign Phase 2: theme token selection. */
class SettThemeTest {

    @Test
    fun modeWinsOverSystem() {
        assertSame(SettTheme.LIGHT, SettTheme.palette(SettTheme.MODE_LIGHT, systemDark = true))
        assertSame(SettTheme.DARK, SettTheme.palette(SettTheme.MODE_DARK, systemDark = false))
    }

    @Test
    fun systemDefersToOs() {
        assertSame(SettTheme.DARK, SettTheme.palette(SettTheme.MODE_SYSTEM, systemDark = true))
        assertSame(SettTheme.LIGHT, SettTheme.palette(SettTheme.MODE_SYSTEM, systemDark = false))
        // Unknown values fall back to system behaviour rather than crashing.
        assertSame(SettTheme.DARK, SettTheme.palette("bogus", systemDark = true))
    }
}
