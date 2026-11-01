package app.privatex.browser

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Spaces manager: create, switch and delete tab spaces. The honesty
 * rule is printed on this screen: spaces organise tabs; they do not
 * isolate cookies or storage (one engine profile). Switching selects
 * a tab of the target space, or opens its home tab if it has none.
 */
class SpacesActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private val settings get() = components.settings
    private val ui by lazy { app.privatex.browser.ui.SettUi(this, components.settings) }
    private val BG get() = ui.palette.bg
    private val TEXT get() = ui.palette.text
    private val DIM get() = ui.palette.dim
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.applyPlatformTheme()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(18), dp(18), dp(16))
        }
        root.addView(title("🗂  Spaces"))
        root.addView(body(
            "Spaces group your tabs — Work, Study, Personal, whatever you like. " +
                "Stated plainly: spaces do NOT separate cookies, logins or storage " +
                "between each other; the engine has one profile. For real separation " +
                "use private tabs, and Fire to wipe everything.",
        ))
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox)
        val name = EditText(this).apply {
            hint = "New space name"
            textSize = 15f
            setTextColor(TEXT)
            setHintTextColor(DIM)
        }
        root.addView(name)
        root.addView(Button(this).apply {
            text = "Create space"
            setOnClickListener {
                val text = name.text.toString().trim()
                if (text.isEmpty()) {
                    toast("Give the space a name")
                } else {
                    Spaces.create(settings, text)
                    name.text.clear()
                    render()
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        render()
    }

    private fun render() {
        listBox.removeAllViews()
        val spaces = Spaces.list(settings)
        val map = Spaces.tabMap(settings)
        val allTabs = components.store.state.tabs.map { it.id }
        val currentId = Spaces.current(settings).id
        spaces.forEach { space ->
            val count = allTabs.count { (map[it] ?: currentId) == space.id }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(10))
            }
            row.addView(TextView(this).apply {
                text = (if (space.id == currentId) "● " else "○ ") +
                    "${space.name}  ·  $count tab(s)"
                textSize = 16f
                setTextColor(TEXT)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { switchTo(space.id) }
            })
            if (spaces.size > 1) {
                row.addView(Button(this).apply {
                    text = "Delete"
                    textSize = 12f
                    setOnClickListener {
                        AlertDialog.Builder(this@SpacesActivity)
                            .setTitle("Delete space")
                            .setMessage(
                                "Delete “${space.name}”? Its tabs move to " +
                                    "“${spaces.first { it.id != space.id }.name}”.",
                            )
                            .setPositiveButton("Delete") { _, _ ->
                                Spaces.delete(settings, space.id)
                                render()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
            }
            listBox.addView(row)
        }
    }

    private fun switchTo(spaceId: String) {
        settings.currentSpaceId = spaceId
        val map = Spaces.tabMap(settings)
        val target = components.store.state.tabs.firstOrNull { map[it.id] == spaceId }
        if (target != null) {
            components.tabsUseCases.selectTab(target.id)
        } else {
            // No tab yet: open home; BrowserActivity's observer tags it
            // with the (new) current space.
            components.tabsUseCases.addTab(Components.HOME_URL, selectTab = true)
        }
        finish()
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTextColor(TEXT)
        setPadding(0, dp(14), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(DIM)
        setPadding(0, dp(2), 0, dp(8))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
