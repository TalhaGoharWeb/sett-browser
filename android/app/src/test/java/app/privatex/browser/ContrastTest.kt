package app.privatex.browser

import app.privatex.browser.ui.SettTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redesign Phase 9: WCAG contrast guard. Every text-bearing token
 * must reach 4.5:1 against both the page background and card
 * surface, in BOTH palettes. (Phase 9 found LIGHT faint at 2.84:1;
 * this test keeps it from regressing.)
 */
class ContrastTest {

    private fun luminance(argb: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92
            else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun ratio(fg: Int, bg: Int): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun bothPalettesMeetContrastForTextTokens() {
        listOf(SettTheme.DARK, SettTheme.LIGHT).forEach { p ->
            listOf(p.text, p.dim, p.faint).forEach { fg ->
                assertTrue(
                    "text token on bg: ${ratio(fg, p.bg)}",
                    ratio(fg, p.bg) >= 4.5,
                )
                assertTrue(
                    "text token on surface: ${ratio(fg, p.surface)}",
                    ratio(fg, p.surface) >= 4.5,
                )
            }
            listOf(p.accent, p.accent2, p.warn, p.error).forEach { fg ->
                assertTrue(
                    "signal token on bg: ${ratio(fg, p.bg)}",
                    ratio(fg, p.bg) >= 4.5,
                )
            }
        }
    }
}
