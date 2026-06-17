package com.deckkey.ime.layout

import android.content.Context
import com.deckkey.core.model.Key
import com.deckkey.core.model.KeyType
import com.deckkey.core.model.KeyboardLayout
import com.deckkey.core.model.Modifier
import com.deckkey.core.model.Row
import org.json.JSONObject

/**
 * Loads keyboard layouts from JSON files under `assets/layouts/` and caches them.
 *
 * Layouts are data, not code, so new pages can be added without touching Kotlin.
 * Parsing is forgiving: unknown fields are ignored, missing fields fall back to defaults.
 */
class LayoutManager(private val context: Context) {

    private val cache = mutableMapOf<String, KeyboardLayout>()

    /** Layout shown when the IME first opens. */
    val defaultLayoutId = "qwerty"

    fun load(id: String): KeyboardLayout =
        cache.getOrPut(id) { parse(readAsset("layouts/$id.json")) }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    private fun parse(json: String): KeyboardLayout {
        val root = JSONObject(json)
        val rowsJson = root.getJSONArray("rows")
        val rows = ArrayList<Row>(rowsJson.length())
        for (i in 0 until rowsJson.length()) {
            val rowObj = rowsJson.getJSONObject(i)
            val keysJson = rowObj.getJSONArray("keys")
            val keys = ArrayList<Key>(keysJson.length())
            for (j in 0 until keysJson.length()) {
                keys.add(parseKey(keysJson.getJSONObject(j)))
            }
            rows.add(
                Row(
                    keys = keys,
                    heightWeight = rowObj.optDouble("heightWeight", 1.0).toFloat(),
                )
            )
        }
        return KeyboardLayout(
            id = root.getString("id"),
            label = root.optString("label", root.getString("id")),
            rows = rows,
        )
    }

    private fun parseKey(o: JSONObject): Key {
        val longPress = o.optJSONArray("longPress")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        return Key(
            label = o.getString("label"),
            shiftLabel = o.optString("shiftLabel").ifBlank { null },
            hint = o.optString("hint").ifBlank { null },
            type = parseType(o.optString("type", "CHAR")),
            output = o.optString("output").ifBlank { null },
            keyCode = o.optInt("keyCode", 0),
            modifier = o.optString("modifier").ifBlank { null }?.let { Modifier.valueOf(it) },
            switchTo = o.optString("switchTo").ifBlank { null },
            widthWeight = o.optDouble("widthWeight", 1.0).toFloat(),
            repeatable = o.optBoolean("repeatable", false),
            longPress = longPress,
        )
    }

    private fun parseType(raw: String): KeyType =
        runCatching { KeyType.valueOf(raw) }.getOrDefault(KeyType.CHAR)
}
