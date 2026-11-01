package app.privatex.browser

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.privatex.browser.tools.SearchRouting

/**
 * SETT Search (Phase 7) — an honest search layer: category tabs
 * (web/images/news/videos/maps/academic) with a per-category choice
 * of provider, and a data-flow section stating exactly what each
 * provider receives. Sett runs no search server and sees nothing:
 * pressing Search opens the provider's own results page in the
 * browser. The web category honours the Settings search engine,
 * including a self-hosted SearXNG instance the user points at.
 */
class SearchActivity : AppCompatActivity() {

    private val components get() = (application as PrivateXApp).components
    private var selectedCategory: SearchRouting.Category = SearchRouting.Category.WEB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Home-page category chips (redesign Phase 3) preselect here.
        intent.getStringExtra(EXTRA_CATEGORY)?.let { key ->
            selectedCategory = SearchRouting.Category.entries
                .firstOrNull { it.name == key } ?: SearchRouting.Category.WEB
        }
        showSearch()
    }



    private fun providerKeyFor(category: SearchRouting.Category): String {
        if (category == SearchRouting.Category.WEB) {
            return components.settings.searchEngine
        }
        val stored = components.settings.searchCategoryProvider(category.name)
        val providers = SearchRouting.categoryProviders.getValue(category)
        return providers.firstOrNull { it.key == stored }?.key ?: providers.first().key
    }

    private fun showSearch() {
        val settings = components.settings
        val layout = page("🔎  SETT Search")
        layout.addView(body(
            "Sett has no search server and receives nothing. A search opens the " +
                "chosen provider's own results page — what that provider receives is " +
                "written under Data flow below, before you search.",
        ))
        val query = field("Search…")
        layout.addView(query)

        val providerSpinner = Spinner(this)
        val flowNote = body("")
        fun refreshProviders() {
            val providers = SearchRouting.categoryProviders.getValue(selectedCategory)
            providerSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                providers.map { it.name },
            )
            val current = providerKeyFor(selectedCategory)
            providerSpinner.setSelection(providers.indexOfFirst { it.key == current }.coerceAtLeast(0))
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipButtons = mutableListOf<Button>()
        fun refreshChips() {
            chipButtons.forEachIndexed { index, button ->
                val category = SearchRouting.Category.entries[index]
                button.setTextColor(if (category == selectedCategory) TEAL else DIM)
            }
        }
        SearchRouting.Category.entries.forEach { category ->
            val button = Button(this).apply {
                text = category.label
                textSize = 13f
                setOnClickListener {
                    selectedCategory = category
                    refreshChips()
                    refreshProviders()
                }
            }
            chipButtons.add(button)
            chips.addView(button)
        }
        layout.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        layout.addView(providerSpinner)
        layout.addView(flowNote)
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val provider = SearchRouting.categoryProviders
                    .getValue(selectedCategory)[position]
                if (selectedCategory == SearchRouting.Category.WEB) {
                    settings.searchEngine = provider.key
                } else {
                    settings.setSearchCategoryProvider(selectedCategory.name, provider.key)
                }
                flowNote.text = provider.dataFlow
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        layout.addView(Button(this).apply {
            text = "Search"
            setOnClickListener {
                val text = query.text.toString().trim()
                if (text.isEmpty()) {
                    toast("Type a search first")
                } else {
                    val url = SearchRouting.route(
                        selectedCategory,
                        providerKeyFor(selectedCategory),
                        text,
                        settings.searxngUrl,
                    )
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .setClass(this@SearchActivity, BrowserActivity::class.java),
                    )
                    finish()
                }
            }
        })
        refreshChips()
        refreshProviders()

        layout.addView(section("Your SearXNG instance (optional)"))
        layout.addView(body(
            "SearXNG is free software you run yourself — Sett cannot host one for " +
                "you (zero-spend, no servers). If you run an instance, put its base " +
                "URL here; choosing SearXNG as the web provider then sends queries " +
                "only to it. Blank = web search falls back to DuckDuckGo.",
        ))
        val searxngField = field("https://search.example.org").apply {
            setText(settings.searxngUrl)
        }
        layout.addView(searxngField)
        layout.addView(Button(this).apply {
            text = "Save instance URL"
            setOnClickListener {
                settings.searxngUrl = SearchRouting.normalizeSearxngBase(searxngField.text.toString())
                toast("Saved")
            }
        })

        layout.addView(section("Data flow — what each provider receives"))
        SearchRouting.categoryProviders.forEach { (category, providers) ->
            providers.distinctBy { it.key }.forEach { provider ->
                layout.addView(body("• ${category.label} · ${provider.name}: ${provider.dataFlow}"))
            }
        }
        layout.addView(body(
            "Search suggestions stay OFF: Sett never sends keystrokes anywhere while " +
                "you type. Your query leaves the device only when you press Search, " +
                "and only to the provider shown.",
        ))
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ plumbing

    private fun page(titleText: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(TextView(this@SearchActivity).apply {
            text = titleText
            textSize = 20f
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(TEXT)
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(DIM)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun field(hint: String) = EditText(this).apply {
        this.hint = hint
        textSize = 15f
        setTextColor(TEXT)
        setHintTextColor(DIM)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CATEGORY = "sett_search_category"

        val BG = Color.parseColor("#0B1626")
        val TEXT = Color.parseColor("#E6F1F5")
        val DIM = Color.parseColor("#8FB3C7")
        val TEAL = Color.parseColor("#2DD4BF")
    }
}
