package app.privatex.browser.vault

import org.json.JSONObject

/** One login entry inside the vault (the plaintext of a "login" record). */
data class VaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("title", title)
        .put("username", username)
        .put("password", password)
        .put("url", url)
        .put("notes", notes)
        .toString()
        .toByteArray(Charsets.UTF_8)

    companion object {
        fun fromBytes(id: String, bytes: ByteArray): VaultEntry {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            return VaultEntry(
                id = id,
                title = json.optString("title"),
                username = json.optString("username"),
                password = json.optString("password"),
                url = json.optString("url"),
                notes = json.optString("notes"),
            )
        }
    }
}
