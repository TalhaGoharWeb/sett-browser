package app.privatex.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spaces: named groups of tabs. A tab belongs to exactly one space;
 * the tab tray shows the current space's tabs. Spaces are an
 * ORGANISATION feature — honestly stated wherever they appear:
 * GeckoView gives an app a single browsing profile, so spaces do
 * NOT isolate cookies, storage or logins between each other. (Private
 * tabs and Fire are Sett's isolation tools.)
 *
 * State lives in [SettingsStore]: the space list, the current space,
 * and the tabId → spaceId map. Untagged tabs are claimed by the
 * current space (BrowserActivity's store observer), which covers
 * tabs created by links, extensions and restores alike.
 */
object Spaces {
    data class Space(val id: String, val name: String)

    fun list(settings: SettingsStore): List<Space> {
        val arr = JSONArray(settings.spacesJson)
        val out = mutableListOf<Space>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            out.add(Space(obj.getString("id"), obj.getString("name")))
        }
        if (out.isEmpty()) out.add(Space("main", "Main"))
        return out
    }

    fun save(settings: SettingsStore, spaces: List<Space>) {
        val arr = JSONArray()
        spaces.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
        settings.spacesJson = arr.toString()
    }

    fun current(settings: SettingsStore): Space {
        val spaces = list(settings)
        return spaces.firstOrNull { it.id == settings.currentSpaceId } ?: spaces.first()
    }

    fun tabMap(settings: SettingsStore): MutableMap<String, String> {
        val obj = JSONObject(settings.tabSpacesJson)
        val out = mutableMapOf<String, String>()
        obj.keys().forEach { out[it] = obj.getString(it) }
        return out
    }

    fun saveTabMap(settings: SettingsStore, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        settings.tabSpacesJson = obj.toString()
    }

    fun tabsInSpace(settings: SettingsStore, tabIds: List<String>, spaceId: String): List<String> {
        val map = tabMap(settings)
        return tabIds.filter { (map[it] ?: settings.currentSpaceId) == spaceId }
    }

    fun create(settings: SettingsStore, name: String): Space {
        val spaces = list(settings).toMutableList()
        val space = Space("space-${System.currentTimeMillis()}", name.trim())
        spaces.add(space)
        save(settings, spaces)
        return space
    }

    /** Deleting a space moves its tabs to the first remaining space. */
    fun delete(settings: SettingsStore, spaceId: String) {
        val spaces = list(settings).filter { it.id != spaceId }
        if (spaces.isEmpty()) return
        save(settings, spaces)
        val fallback = spaces.first().id
        val map = tabMap(settings)
        map.keys.filter { map[it] == spaceId }.forEach { map[it] = fallback }
        saveTabMap(settings, map)
        if (settings.currentSpaceId == spaceId) settings.currentSpaceId = fallback
    }
}
