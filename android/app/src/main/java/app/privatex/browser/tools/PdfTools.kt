package app.privatex.browser.tools

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF tools on PdfBox-Android (Apache-2.0): merge, extract a page
 * range as a new document, rotate pages, and extract text. All local
 * file work — the activity copies picked documents to temp files and
 * offers the result through the system save dialog.
 */
object PdfTools {

    @Volatile
    private var initialised = false

    fun init(context: Context) {
        if (!initialised) {
            synchronized(this) {
                if (!initialised) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    initialised = true
                }
            }
        }
    }

    fun pageCount(file: File): Int = PDDocument.load(file).use { it.numberOfPages }

    /** Merges [sources] in order into [out]. */
    fun merge(sources: List<File>, out: File) {
        require(sources.size >= 2) { "Pick at least two PDFs to merge" }
        val merger = PDFMergerUtility()
        sources.forEach { merger.addSource(it) }
        merger.destinationFileName = out.absolutePath
        merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
    }

    /** Writes pages [range] (1-based, inclusive) of [source] into [out]. */
    fun extractPages(source: File, range: IntRange, out: File) {
        PDDocument.load(source).use { doc ->
            require(range.first >= 1 && range.last <= doc.numberOfPages) {
                "Page range must be within 1–${doc.numberOfPages}"
            }
            PDDocument().use { result ->
                for (page in range) {
                    result.importPage(doc.getPage(page - 1))
                }
                result.save(out)
            }
        }
    }

    /** Rotates every page of [source] by [degrees] (multiples of 90) into [out]. */
    fun rotate(source: File, degrees: Int, out: File) {
        require(degrees % 90 == 0) { "Rotation must be a multiple of 90" }
        PDDocument.load(source).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                val page = doc.getPage(i)
                page.rotation = (page.rotation + degrees) % 360
            }
            doc.save(out)
        }
    }

    /** Extracts the document's text (empty string for scanned-image PDFs). */
    fun extractText(file: File): String =
        PDDocument.load(file).use { doc -> PDFTextStripper().getText(doc) }
}
