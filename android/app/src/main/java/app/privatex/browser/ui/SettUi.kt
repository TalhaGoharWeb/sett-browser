package app.privatex.browser.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import app.privatex.browser.R
import app.privatex.browser.SettingsStore

/**
 * Shared screen components (redesign Phase 2) — replaces the
 * page/section/body/field helpers that were copy-pasted into a
 * dozen activities with one token-driven implementation.
 *
 * Usage: `private val ui by lazy { SettUi(this, components.settings) }`
 * then build with ui.page("Title"), ui.section(...), ui.body(...),
 * ui.field(...), ui.actionButton(...), ui.card().
 */
class SettUi(
    private val activity: Activity,
    settings: SettingsStore,
) {
    val palette: SettTheme.Palette
    val dark: Boolean
    val compact: Boolean

    init {
        val systemDark =
            (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        palette = SettTheme.palette(settings.themeMode, systemDark)
        dark = SettTheme.isDark(settings.themeMode, systemDark)
        compact = settings.density == SettingsStore.DENSITY_COMPACT
    }

    /** Call first in onCreate: aligns the platform theme (dialogs, menus) with the palette. */
    fun applyPlatformTheme() {
        activity.setTheme(if (dark) R.style.Theme_PrivateX else R.style.Theme_PrivateX_Light)
    }

    fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun padVertical(): Int = if (compact) 10 else 14

    /** Density (Phase 9): Compact tightens spacing only — never touch targets. */
    private fun pagePad(): Int = if (compact) 12 else SettTheme.SPACE_PAGE
    private fun sectionPad(): Int = if (compact) 10 else SettTheme.SPACE_SECTION
    private fun cardPadV(): Int = if (compact) 9 else 12

    fun page(titleText: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.bg)
        setPadding(dp(SettTheme.SPACE_PAGE), dp(pagePad()), dp(SettTheme.SPACE_PAGE), dp(pagePad()))
        addView(TextView(activity).apply {
            text = titleText
            textSize = SettTheme.TEXT_TITLE
            setTextColor(palette.text)
            setPadding(0, 0, 0, dp(10))
        })
    }

    fun section(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = SettTheme.TEXT_SECTION
        setTextColor(palette.text)
        setPadding(0, dp(sectionPad()), 0, dp(6))
    }

    fun body(text: String): TextView = TextView(activity).apply {
        this.text = text
        textSize = SettTheme.TEXT_SMALL
        setTextColor(palette.dim)
        setPadding(0, dp(4), 0, dp(8))
    }

    fun field(hint: String): EditText = EditText(activity).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(palette.text)
        setHintTextColor(palette.faint)
        setPadding(0, dp(padVertical() - 4), 0, dp(padVertical() - 4))
    }

    fun actionButton(text: String, onClick: (View) -> Unit): Button = Button(activity).apply {
        this.text = text
        textSize = SettTheme.TEXT_BODY
        minHeight = dp(SettTheme.TOUCH_MIN)
        setOnClickListener(onClick)
    }

    fun primaryButton(text: String, onClick: (View) -> Unit): Button =
        actionButton(text, onClick).apply {
            setTextColor(palette.onAccent)
            background = GradientDrawable().apply {
                setColor(palette.accent)
                cornerRadius = dp(SettTheme.RADIUS_PILL).toFloat()
            }
        }

    fun card(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(palette.surface)
            cornerRadius = dp(SettTheme.RADIUS_CARD).toFloat()
        }
        setPadding(dp(14), dp(cardPadV()), dp(14), dp(cardPadV()))
    }

    fun emptyState(message: String): TextView = TextView(activity).apply {
        text = message
        textSize = SettTheme.TEXT_BODY
        setTextColor(palette.dim)
        setPadding(dp(4), dp(18), dp(4), dp(18))
    }
}
