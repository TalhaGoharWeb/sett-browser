package app.privatex.browser.research

import app.privatex.browser.tools.ScholarTools
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Research collections store (Phase 8): named collections of saved
 * works with per-work notes, in one JSON file in app-private storage.
 * Plain java.io + org.json so it is JVM-testable like the vault core.
 * Export produces a whole collection as BibTeX or RIS text.
 */
class ResearchStore(private val file: File) {

    data class SavedWork(
        val work: ScholarTools.Work,
        val note: String,
        val savedAtMillis: Long,
    )

    data class Collection(
        val id: String,
        val name: String,
        val works: List<SavedWork>,
    )

    @Synchronized
    fun listCollections(): List<Collection> = load().collections

    @Synchronized
    fun createCollection(name: String): Collection {
        val data = load()
        val collection = Collection(
            id = "c${System.currentTimeMillis().toString(36)}${(Math.random() * 1e6).toInt().toString(36)}",
            name = name.trim().ifEmpty { "Untitled collection" },
            works = emptyList(),
        )
        save(data.copy(collections = data.collections + collection))
        return collection
    }

    @Synchronized
    fun deleteCollection(id: String) {
        val data = load()
        save(data.copy(collections = data.collections.filterNot { it.id == id }))
    }

    @Synchronized
    fun addWork(collectionId: String, work: ScholarTools.Work) {
        val data = load()
        val collections = data.collections.map { collection ->
            if (collection.id != collectionId) {
                collection
            } else {
                // De-duplicate by DOI, else title+year.
                val key = work.doi ?: "${work.title}|${work.year}"
                val exists = collection.works.any { saved ->
                    (saved.work.doi ?: "${saved.work.title}|${saved.work.year}") == key
                }
                if (exists) {
                    collection
                } else {
                    collection.copy(
                        works = collection.works + SavedWork(work, "", System.currentTimeMillis()),
                    )
                }
            }
        }
        save(data.copy(collections = collections))
    }

    @Synchronized
    fun removeWork(collectionId: String, index: Int) {
        val data = load()
        val collections = data.collections.map { collection ->
            if (collection.id == collectionId && index in collection.works.indices) {
                collection.copy(works = collection.works.filterIndexed { i, _ -> i != index })
            } else {
                collection
            }
        }
        save(data.copy(collections = collections))
    }

    @Synchronized
    fun setNote(collectionId: String, index: Int, note: String) {
        val data = load()
        val collections = data.collections.map { collection ->
            if (collection.id == collectionId && index in collection.works.indices) {
                collection.copy(
                    works = collection.works.mapIndexed { i, saved ->
                        if (i == index) saved.copy(note = note) else saved
                    },
                )
            } else {
                collection
            }
        }
        save(data.copy(collections = collections))
    }

    /** Whole collection as one BibTeX document. */
    @Synchronized
    fun exportBibTex(collectionId: String): String =
        listCollections().firstOrNull { it.id == collectionId }
            ?.works?.joinToString("\n\n") { ScholarTools.toBibTex(it.work) }
            ?: ""

    /** Whole collection as one RIS document. */
    @Synchronized
    fun exportRis(collectionId: String): String =
        listCollections().firstOrNull { it.id == collectionId }
            ?.works?.joinToString("\n") { ScholarTools.toRis(it.work) }
            ?: ""

    // ------------------------------------------------------------ storage

    private data class Data(val collections: List<Collection>)

    private fun load(): Data {
        if (!file.exists()) return Data(emptyList())
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("collections") ?: return Data(emptyList())
            Data(
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    val worksArr = obj.optJSONArray("works") ?: JSONArray()
                    Collection(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        works = (0 until worksArr.length()).map { j ->
                            val saved = worksArr.getJSONObject(j)
                            val w = saved.getJSONObject("work")
                            SavedWork(
                                work = ScholarTools.Work(
                                    title = w.getString("title"),
                                    authors = w.optJSONArray("authors")?.let { a ->
                                        (0 until a.length()).map { a.getString(it) }
                                    } ?: emptyList(),
                                    year = if (w.has("year") && !w.isNull("year")) {
                                        w.getInt("year")
                                    } else {
                                        null
                                    },
                                    doi = w.optString("doi").ifEmpty { null },
                                    source = w.optString("source").ifEmpty { null },
                                    url = w.optString("url").ifEmpty { null },
                                    openAccess = if (w.has("oa") && !w.isNull("oa")) {
                                        w.getBoolean("oa")
                                    } else {
                                        null
                                    },
                                    oaUrl = w.optString("oaUrl").ifEmpty { null },
                                    citedByCount = if (w.has("cited") && !w.isNull("cited")) {
                                        w.getInt("cited")
                                    } else {
                                        null
                                    },
                                    origin = w.optString("origin"),
                                ),
                                note = saved.optString("note"),
                                savedAtMillis = saved.optLong("savedAt"),
                            )
                        },
                    )
                },
            )
        } catch (e: Exception) {
            Data(emptyList())
        }
    }

    private fun save(data: Data) {
        val root = JSONObject()
        val collectionsArr = JSONArray()
        data.collections.forEach { collection ->
            val obj = JSONObject()
            obj.put("id", collection.id)
            obj.put("name", collection.name)
            val worksArr = JSONArray()
            collection.works.forEach { saved ->
                val entry = JSONObject()
                val w = JSONObject()
                w.put("title", saved.work.title)
                w.put("authors", JSONArray(saved.work.authors))
                saved.work.year?.let { w.put("year", it) }
                saved.work.doi?.let { w.put("doi", it) }
                saved.work.source?.let { w.put("source", it) }
                saved.work.url?.let { w.put("url", it) }
                saved.work.openAccess?.let { w.put("oa", it) }
                saved.work.oaUrl?.let { w.put("oaUrl", it) }
                saved.work.citedByCount?.let { w.put("cited", it) }
                w.put("origin", saved.work.origin)
                entry.put("work", w)
                entry.put("note", saved.note)
                entry.put("savedAt", saved.savedAtMillis)
                worksArr.put(entry)
            }
            obj.put("works", worksArr)
            collectionsArr.put(obj)
        }
        root.put("collections", collectionsArr)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(root.toString())
        tmp.renameTo(file)
    }
}
