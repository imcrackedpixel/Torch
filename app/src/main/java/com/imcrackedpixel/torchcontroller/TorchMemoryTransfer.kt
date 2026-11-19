package com.imcrackedpixel.torchcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable backup for Torch's local learning stores. Export/import is user-driven
 * through Android's document picker; imported data is merged into the new app.
 */
object TorchMemoryTransfer {
    private val stores = listOf(
        "torch_endgame_brain",
        "torch_game_agent",
        "torch_ai_brain",
        "torch_minimap_memory",
        "torch_world_model",
        "game_action_memory",
        "tap_positions",
        "torch_ai_learning",
        "torchlight_progress"
    )

    fun export(context: Context): String {
        val root = JSONObject()
        root.put("format", "torch-ai-memory")
        root.put("schema", 1)
        root.put("exported_at", System.currentTimeMillis())
        val data = JSONObject()
        stores.forEach { name ->
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val store = JSONObject()
            values.forEach { (key, value) ->
                when (value) {
                    is String -> store.put(key, JSONObject().put("type", "string").put("value", value))
                    is Boolean -> store.put(key, JSONObject().put("type", "boolean").put("value", value))
                    is Int -> store.put(key, JSONObject().put("type", "int").put("value", value))
                    is Long -> store.put(key, JSONObject().put("type", "long").put("value", value))
                    is Float -> store.put(key, JSONObject().put("type", "float").put("value", value.toDouble()))
                    is Set<*> -> {
                        val items = JSONArray()
                        value.filterIsInstance<String>().forEach { items.put(it) }
                        store.put(key, JSONObject().put("type", "string_set").put("value", items))
                    }
                }
            }
            data.put(name, store)
        }
        root.put("stores", data)
        return root.toString(2)
    }

    fun import(context: Context, json: String): Int {
        val root = JSONObject(json)
        require(root.optString("format") == "torch-ai-memory") { "This is not a Torch AI memory backup." }
        require(root.optInt("schema", 0) in 1..1) { "This backup version is not supported." }
        val data = root.optJSONObject("stores") ?: throw IllegalArgumentException("Backup contains no memory stores.")
        var restored = 0
        stores.forEach { name ->
            val store = data.optJSONObject(name) ?: return@forEach
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            val keys = store.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = store.optJSONObject(key) ?: continue
                val value = entry.opt("value")
                when (entry.optString("type")) {
                    "string" -> if (value is String) editor.putString(key, value)
                    "boolean" -> editor.putBoolean(key, value as? Boolean ?: false)
                    "int" -> editor.putInt(key, (value as? Number)?.toInt() ?: 0)
                    "long" -> editor.putLong(key, (value as? Number)?.toLong() ?: 0L)
                    "float" -> editor.putFloat(key, (value as? Number)?.toFloat() ?: 0f)
                    "string_set" -> {
                        val set = mutableSetOf<String>()
                        if (value is JSONArray) for (i in 0 until value.length()) {
                            value.optString(i).takeIf { it.isNotEmpty() }?.let(set::add)
                        }
                        editor.putStringSet(key, set)
                    }
                }
                restored++
            }
            editor.apply()
        }
        return restored
    }
}
