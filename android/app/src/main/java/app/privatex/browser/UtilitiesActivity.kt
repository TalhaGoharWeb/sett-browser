package app.privatex.browser

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import app.privatex.browser.tools.MetaClean
import app.privatex.browser.tools.PasswordStrength
import app.privatex.browser.tools.PdfTools
import app.privatex.browser.tools.QrTool
import app.privatex.browser.tools.TextTools
import app.privatex.browser.vault.PasswordGenerator
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Utilities hub (Phase 5): offline tools ported from the PRIVACY X
 * suite's tested engines — MetaClean, password tools, hashes — plus
 * QR, text tools, a converter/calculator and PDF tools. Everything
 * runs on-device; the single network feature (breach check) is an
 * explicit button whose privacy model is printed next to it.
 */
class UtilitiesActivity : AppCompatActivity() {

    private var pendingPick: ((Uri) -> Unit)? = null
    private val ui by lazy { app.privatex.browser.ui.SettUi(this, SettingsStore(this)) }
    private val BG get() = ui.palette.bg
    private val TEXT get() = ui.palette.text
    private val DIM get() = ui.palette.dim
    private val TEAL get() = ui.palette.accent
    private var pendingMultiPick: ((List<Uri>) -> Unit)? = null
    private var pendingSave: (() -> ByteArray)? = null
    private var saveSuggestedName: String = "sett-output"

    private val pickOne = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        val action = pendingPick
        pendingPick = null
        if (result.resultCode == Activity.RESULT_OK && uri != null && action != null) {
            action(uri)
        }
    }

    private val pickMany = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val action = pendingMultiPick
        pendingMultiPick = null
        if (result.resultCode != Activity.RESULT_OK || action == null) return@registerForActivityResult
        val uris = mutableListOf<Uri>()
        val data = result.data ?: return@registerForActivityResult
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        }
        data.data?.let { uris.add(it) }
        if (uris.isNotEmpty()) action(uris)
    }

    private val saveAs = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        val provider = pendingSave
        pendingSave = null
        if (result.resultCode == Activity.RESULT_OK && uri != null && provider != null) {
            bg {
                val bytes = provider()
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ui { toast("Saved") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui.applyPlatformTheme()
        PdfTools.init(this)
        showHub()
    }

    // ------------------------------------------------------------ plumbing

    private fun launchPick(mime: String, action: (Uri) -> Unit) {
        pendingPick = action
        pickOne.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        })
    }

    private fun launchPickMany(mime: String, action: (List<Uri>) -> Unit) {
        pendingMultiPick = action
        pickMany.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        })
    }

    private fun launchSave(suggestedName: String, mime: String, provider: () -> ByteArray) {
        saveSuggestedName = suggestedName
        pendingSave = provider
        saveAs.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        })
    }

    private fun readBytes(uri: Uri): ByteArray =
        contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not read the file")

    private fun copyToTemp(uri: Uri, suffix: String): File {
        val file = File(cacheDir, "sett-tool-${System.currentTimeMillis()}$suffix")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("Could not read the file")
        return file
    }

    private fun bg(work: suspend () -> Unit) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Default) { work() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast(e.message ?: "Something went wrong") }
            }
        }
    }

    private suspend fun ui(block: () -> Unit) = withContext(Dispatchers.Main) { block() }

    // ------------------------------------------------------------ hub

    private fun showHub() {
        val layout = page("🧰  Utilities")
        layout.addView(body("Offline tools. Nothing here uploads anything — the one exception (breach check) is clearly marked and only runs when you tap it."))
        fun tool(emoji: String, label: String, screen: () -> Unit) {
            layout.addView(Button(this).apply {
                text = "$emoji  $label"
                textSize = 16f
                setOnClickListener { screen() }
            })
        }
        tool("🔑", "Password tools") { showPasswordTools() }
        tool("🧼", "MetaClean — photo metadata") { showMetaClean() }
        tool("#️⃣", "File & text hashes") { showHashes() }
        tool("🔳", "QR code — make & read") { showQr() }
        tool("🔤", "Text tools — Base64, URL, JSON, stats") { showTextTools() }
        tool("🔄", "Converter & calculator") { showConvertCalc() }
        tool("📄", "PDF tools") { showPdfTools() }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ password tools

    private fun showPasswordTools() {
        val layout = page("🔑  Password tools")
        back(layout) { showHub() }
        layout.addView(section("Strength estimator (an honest heuristic)"))
        val pw = field("Type a password to estimate")
        val strengthOut = body("")
        layout.addView(pw)
        layout.addView(strengthOut)
        pw.addTextChangedListener {
            val result = PasswordStrength.estimate(it?.toString().orEmpty())
            strengthOut.text = "Band: ${result.band.label} · ≈${result.entropyBits.toInt()} bits" +
                if (result.warnings.isEmpty()) {
                    ""
                } else {
                    "\n" + result.warnings.joinToString("\n") { "• $it" }
                }
        }
        layout.addView(section("Breach check (Have I Been Pwned)"))
        layout.addView(body(
            "Privacy model: the password is SHA-1 hashed on this device; only the first " +
                "5 characters of the hash are sent; the match is checked locally against " +
                "the ~600 returned suffixes. The password and its full hash never leave " +
                "the phone. Runs only when you tap Check, and needs internet.",
        ))
        val breachOut = body("")
        layout.addView(Button(this).apply {
            text = "Check the password above for breaches"
            setOnClickListener {
                val password = pw.text.toString()
                if (password.isEmpty()) {
                    toast("Type a password first")
                } else {
                    bg {
                        val (prefix, suffix) = TextTools.pwnedSplit(password)
                        val connection = URL("https://api.pwnedpasswords.com/range/$prefix")
                            .openConnection() as HttpURLConnection
                        connection.setRequestProperty("Add-Padding", "true")
                        connection.connectTimeout = 15000
                        connection.readTimeout = 15000
                        val bodyText = connection.inputStream.bufferedReader().readText()
                        val entries = TextTools.parseRangeResponse(bodyText)
                        val count = entries[suffix] ?: 0
                        ui {
                            breachOut.text = if (count > 0) {
                                "⚠ This password appears in breach data $count times. Do not use it anywhere."
                            } else {
                                "Not found in the breach corpus. (That is not a strength guarantee — see the estimate above.)"
                            }
                        }
                    }
                }
            }
        })
        layout.addView(breachOut)
        layout.addView(section("Generator"))
        val length = field("Length (12–64)").apply { setText("16") }
        val symbols = CheckBox(this).apply {
            text = "Include symbols"
            isChecked = true
            setTextColor(TEXT)
        }
        val generated = field("Generated password appears here")
        layout.addView(length)
        layout.addView(symbols)
        layout.addView(generated)
        layout.addView(Button(this).apply {
            text = "Generate"
            setOnClickListener {
                val len = length.text.toString().toIntOrNull()?.coerceIn(12, 64) ?: 16
                generated.setText(PasswordGenerator.generate(len, symbols.isChecked))
            }
        })
        layout.addView(Button(this).apply {
            text = "Copy generated"
            setOnClickListener {
                val text = generated.text.toString()
                if (text.isNotBlank()) copy("password", text)
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ MetaClean

    private fun showMetaClean() {
        val layout = page("🧼  MetaClean")
        back(layout) { showHub() }
        layout.addView(body(
            "Inspect and strip photo metadata (EXIF, GPS, XMP, comments) from JPEG, PNG " +
                "and WebP — byte-level, no re-encoding, so pixels are untouched. Cleaning " +
                "verifies its own output and refuses to save if anything survives.",
        ))
        val report = body("No image chosen yet.")
        layout.addView(report)
        var currentBytes: ByteArray? = null
        layout.addView(Button(this).apply {
            text = "Choose an image"
            setOnClickListener {
                launchPick("image/*") { uri ->
                    bg {
                        val bytes = readBytes(uri)
                        val result = MetaClean.inspect(bytes)
                        currentBytes = bytes
                        ui {
                            report.text = buildString {
                                append("Format: ").append(result.format).append('\n')
                                if (result.hasLocation) {
                                    append("⚠ GPS LOCATION IS EMBEDDED in this image.\n")
                                }
                                if (!result.hasMetadata) {
                                    append("No metadata found.")
                                } else {
                                    append("Carriers:\n")
                                    result.metadataCarriers.forEach { append("• ").append(it).append('\n') }
                                    append("\nFields:\n")
                                    result.fields.forEach { append("• ").append(it.name).append(": ").append(it.value).append('\n') }
                                }
                            }
                        }
                    }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Clean & save a copy"
            setOnClickListener {
                val bytes = currentBytes
                if (bytes == null) {
                    toast("Choose an image first")
                } else {
                    bg {
                        val cleaned = MetaClean.clean(bytes)
                        ui {
                            launchSave("cleaned-image", "image/*") { cleaned.bytes }
                            toast("Removed: ${cleaned.removedCarriers.joinToString("; ").ifBlank { "nothing to remove" }}")
                        }
                    }
                }
            }
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ hashes

    private fun showHashes() {
        val layout = page("#️⃣  Hashes")
        back(layout) { showHub() }
        layout.addView(section("Text hash"))
        val input = field("Text to hash")
        val out = body("")
        layout.addView(input)
        layout.addView(out)
        input.addTextChangedListener {
            val text = it?.toString().orEmpty()
            out.text = if (text.isEmpty()) {
                ""
            } else {
                TextTools.HashAlgorithm.entries.joinToString("\n\n") { alg ->
                    "${alg.label}\n${TextTools.hashText(text, alg)}"
                }
            }
        }
        layout.addView(section("File hash (streamed — large files are fine)"))
        val fileOut = body("")
        layout.addView(Button(this).apply {
            text = "Choose a file"
            setOnClickListener {
                launchPick("*/*") { uri ->
                    bg {
                        val digests = TextTools.HashAlgorithm.entries.associateWith {
                            MessageDigest.getInstance(it.jcaName)
                        }
                        contentResolver.openInputStream(uri)?.use { stream ->
                            val buffer = ByteArray(1 shl 20)
                            while (true) {
                                val n = stream.read(buffer)
                                if (n < 0) break
                                digests.values.forEach { d -> d.update(buffer, 0, n) }
                            }
                        } ?: throw IllegalStateException("Could not read the file")
                        ui {
                            fileOut.text = digests.entries.joinToString("\n\n") { (alg, digest) ->
                                "${alg.label}\n${with(TextTools) { digest.digest().toHex() }}"
                            }
                        }
                    }
                }
            }
        })
        layout.addView(fileOut)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ QR

    private fun showQr() {
        val layout = page("🔳  QR codes")
        back(layout) { showHub() }
        layout.addView(section("Make a QR code"))
        val qrText = field("Text or link")
        layout.addView(qrText)
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240))
            setPadding(0, dp(10), 0, dp(10))
        }
        layout.addView(image)
        var qrBitmap: Bitmap? = null
        layout.addView(Button(this).apply {
            text = "Generate"
            setOnClickListener {
                bg {
                    val matrix = QrTool.encode(qrText.text.toString(), 512)
                    val size = matrix.size
                    val pixels = IntArray(size * size) { i ->
                        if (matrix[i / size][i % size]) Color.BLACK else Color.WHITE
                    }
                    val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
                    qrBitmap = bitmap
                    ui { image.setImageBitmap(bitmap) }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Save QR as PNG"
            setOnClickListener {
                val bitmap = qrBitmap
                if (bitmap == null) {
                    toast("Generate a QR first")
                } else {
                    launchSave("sett-qr.png", "image/png") {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                        stream.toByteArray()
                    }
                }
            }
        })
        layout.addView(section("Read a QR code from an image"))
        val readOut = body("")
        layout.addView(Button(this).apply {
            text = "Choose an image with a QR code"
            setOnClickListener {
                launchPick("image/*") { uri ->
                    bg {
                        val bytes = readBytes(uri)
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            ?: throw IllegalStateException("Not a readable image")
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val decoded = QrTool.decode(pixels, bitmap.width, bitmap.height)
                        ui {
                            readOut.text = decoded ?: "No QR code found in that image."
                            if (decoded != null) showQrResult(decoded)
                        }
                    }
                }
            }
        })
        layout.addView(readOut)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun showQrResult(decoded: String) {
        val isUrl = decoded.startsWith("http://") || decoded.startsWith("https://")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("QR content")
            .setMessage(decoded)
            .setPositiveButton("Copy") { _, _ -> copy("qr content", decoded) }
            .setNegativeButton("Close", null)
            .apply {
                if (isUrl) {
                    setNeutralButton("Open in Sett") { _, _ ->
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(decoded))
                                .setClass(this@UtilitiesActivity, BrowserActivity::class.java),
                        )
                        finish()
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------ text tools

    private fun showTextTools() {
        val layout = page("🔤  Text tools")
        back(layout) { showHub() }
        val input = field("Input").apply {
            minLines = 3
        }
        val output = field("Output (tap a result button, then copy if needed)").apply {
            minLines = 3
        }
        layout.addView(input)
        fun action(label: String, op: (String) -> String) {
            layout.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    runCatching { output.setText(op(input.text.toString())) }
                        .onFailure { toast(it.message ?: "Invalid input") }
                }
            })
        }
        action("Base64 encode") { TextTools.base64Encode(it) }
        action("Base64 decode") { TextTools.base64Decode(it) }
        action("URL encode") { TextTools.urlEncode(it) }
        action("URL decode") { TextTools.urlDecode(it) }
        action("JSON pretty-print") { TextTools.jsonPretty(it) }
        action("JSON minify") { TextTools.jsonMinify(it) }
        layout.addView(output)
        layout.addView(Button(this).apply {
            text = "Copy output"
            setOnClickListener {
                val text = output.text.toString()
                if (text.isNotBlank()) copy("output", text)
            }
        })
        val stats = body("")
        layout.addView(stats)
        input.addTextChangedListener {
            val s = TextTools.textStats(it?.toString().orEmpty())
            stats.text = "Characters: ${s.characters} · without spaces: ${s.charactersNoSpaces} · " +
                "Words: ${s.words} · Lines: ${s.lines} · Sentences: ${s.sentences} · " +
                "Reading time ≈ ${"%.1f".format(s.readingMinutes)} min"
        }
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ converter & calculator

    private fun showConvertCalc() {
        val layout = page("🔄  Converter & calculator")
        back(layout) { showHub() }
        layout.addView(section("Unit converter"))
        val families = TextTools.unitFamilies.keys.toList() + "Temperature"
        val familySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@UtilitiesActivity, android.R.layout.simple_spinner_dropdown_item, families)
        }
        layout.addView(familySpinner)
        val fromSpinner = Spinner(this)
        val toSpinner = Spinner(this)
        val value = field("Value").apply { setText("1") }
        val result = body("")
        fun unitsFor(family: String): List<String> =
            if (family == "Temperature") listOf("°C", "°F", "K")
            else TextTools.unitFamilies[family]!!.keys.toList()
        fun refreshUnits() {
            val units = unitsFor(families[familySpinner.selectedItemPosition])
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
            fromSpinner.adapter = adapter
            toSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
            if (units.size > 1) toSpinner.setSelection(1)
        }
        fun compute() {
            val number = value.text.toString().toDoubleOrNull()
            if (number == null) {
                result.text = ""
                return
            }
            val family = families[familySpinner.selectedItemPosition]
            val from = unitsFor(family)[fromSpinner.selectedItemPosition]
            val to = unitsFor(family)[toSpinner.selectedItemPosition]
            val converted = if (family == "Temperature") {
                TextTools.convertTemperature(number, from, to)
            } else {
                val map = TextTools.unitFamilies[family]!!
                TextTools.convertLinear(number, map[from]!!, map[to]!!)
            }
            result.text = "$number $from = ${formatNumber(converted)} $to"
        }
        familySpinner.onItemSelectedListener = simpleListener { refreshUnits(); compute() }
        fromSpinner.onItemSelectedListener = simpleListener { compute() }
        toSpinner.onItemSelectedListener = simpleListener { compute() }
        value.addTextChangedListener { compute() }
        layout.addView(fromSpinner)
        layout.addView(toSpinner)
        layout.addView(value)
        layout.addView(result)
        refreshUnits()

        layout.addView(section("Calculator"))
        layout.addView(body("Supports + − * / % ^ ( ), sqrt( ), abs( ). Example: (2+3)*4^2"))
        val expression = field("Expression")
        val calcOut = body("")
        layout.addView(expression)
        layout.addView(Button(this).apply {
            text = "Calculate"
            setOnClickListener {
                runCatching { TextTools.evaluate(expression.text.toString()) }
                    .onSuccess { calcOut.text = "= ${formatNumber(it)}" }
                    .onFailure { calcOut.text = it.message }
            }
        })
        layout.addView(calcOut)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun formatNumber(value: Double): String =
        if (value == Math.floor(value) && Math.abs(value) < 1e15) {
            value.toLong().toString()
        } else {
            "%.6g".format(value)
        }

    private fun simpleListener(action: () -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) = action()

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

    // ------------------------------------------------------------ PDF tools

    private fun showPdfTools() {
        val layout = page("📄  PDF tools")
        back(layout) { showHub() }
        layout.addView(body(
            "Merge, extract pages, rotate and pull text — on-device with PdfBox " +
                "(Apache-2.0). Scanned-image PDFs have no text layer, so text " +
                "extraction returns little or nothing for them (no OCR here).",
        ))
        val status = body("")
        layout.addView(Button(this).apply {
            text = "Merge PDFs (pick 2 or more)"
            setOnClickListener {
                launchPickMany("application/pdf") { uris ->
                    bg {
                        val files = uris.mapIndexed { i, uri -> copyToTemp(uri, "-$i.pdf") }
                        val out = File(cacheDir, "sett-merged.pdf")
                        PdfTools.merge(files, out)
                        val bytes = out.readBytes()
                        ui {
                            status.text = "Merged ${files.size} PDFs (${bytes.size / 1024} KB)."
                            launchSave("merged.pdf", "application/pdf") { bytes }
                        }
                    }
                }
            }
        })
        var pdfForPages: File? = null
        val pagesInfo = body("")
        layout.addView(Button(this).apply {
            text = "Pick a PDF for page tools"
            setOnClickListener {
                launchPick("application/pdf") { uri ->
                    bg {
                        val file = copyToTemp(uri, ".pdf")
                        pdfForPages = file
                        val count = PdfTools.pageCount(file)
                        ui { pagesInfo.text = "Loaded: $count page(s)." }
                    }
                }
            }
        })
        layout.addView(pagesInfo)
        val range = field("Page range, e.g. 2-5").apply { setText("1-1") }
        layout.addView(range)
        layout.addView(Button(this).apply {
            text = "Extract those pages as a new PDF"
            setOnClickListener {
                val file = pdfForPages
                if (file == null) {
                    toast("Pick a PDF first")
                } else {
                    bg {
                        val parts = range.text.toString().split("-")
                        val from = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                        val to = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: from
                        val out = File(cacheDir, "sett-pages.pdf")
                        PdfTools.extractPages(file, from..to, out)
                        val bytes = out.readBytes()
                        ui { launchSave("pages-$from-$to.pdf", "application/pdf") { bytes } }
                    }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Rotate all pages 90°"
            setOnClickListener {
                val file = pdfForPages
                if (file == null) {
                    toast("Pick a PDF first")
                } else {
                    bg {
                        val out = File(cacheDir, "sett-rotated.pdf")
                        PdfTools.rotate(file, 90, out)
                        val bytes = out.readBytes()
                        ui { launchSave("rotated.pdf", "application/pdf") { bytes } }
                    }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Extract text"
            setOnClickListener {
                val file = pdfForPages
                if (file == null) {
                    toast("Pick a PDF first")
                } else {
                    bg {
                        val text = PdfTools.extractText(file)
                        ui {
                            status.text = if (text.isBlank()) {
                                "No text layer found (likely a scanned document)."
                            } else {
                                text.take(4000)
                            }
                            if (text.isNotBlank()) copy("pdf text", text)
                        }
                    }
                }
            }
        })
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    // ------------------------------------------------------------ shared views

    private fun page(titleText: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(BG)
        setPadding(dp(18), dp(18), dp(18), dp(16))
        addView(TextView(this@UtilitiesActivity).apply {
            text = titleText
            textSize = 20f
            setTextColor(TEXT)
            setPadding(0, 0, 0, dp(10))
        })
    }

    private fun back(layout: LinearLayout, action: () -> Unit) {
        layout.addView(TextView(this).apply {
            text = "← All utilities"
            textSize = 14f
            setTextColor(TEAL)
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { action() }
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

    private fun copy(label: String, value: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, value))
        toast("$label copied")
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
    }
}
