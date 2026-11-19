package com.imcrackedpixel.torchcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Persistent, screen-relative world model.
 *
 * Stores repeated object candidates, approximate screen sectors, OCR objective cues,
 * and recent scene transitions. Coordinates are screen coordinates, not world-space
 * coordinates; ML Kit labels are generic candidates and may be wrong.
 */
object GameWorldModel {
    private const val PREFS = "torch_world_model"
    private const val MAX_EVENTS = 240
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun obj(s: String?) = try { JSONObject(s ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun arr(s: String?) = try { JSONArray(s ?: "[]") } catch (_: Exception) { JSONArray() }

    fun observe(c: Context, lines: List<String>, objects: List<String>, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val entities = obj(p.getString("entities", "{}"))
        val sectors = obj(p.getString("sectors", "{}"))
        var obstacleCandidates = 0
        var targetCandidates = 0
        var interactCandidates = 0
        objects.take(60).forEach { detail ->
            val labelPart = detail.substringBefore("@").lowercase(Locale.ROOT)
            val coords = detail.substringAfter("@", "").split(",")
            val x = coords.getOrNull(0)?.toFloatOrNull() ?: return@forEach
            val y = coords.getOrNull(1)?.toFloatOrNull() ?: return@forEach
            val w = coords.getOrNull(2)?.toFloatOrNull() ?: 0f
            val h = coords.getOrNull(3)?.toFloatOrNull() ?: 0f
            val nx = (x / width).coerceIn(0f, 1f)
            val ny = (y / height).coerceIn(0f, 1f)
            val sx = (nx * 5f).toInt().coerceIn(0, 4)
            val sy = (ny * 5f).toInt().coerceIn(0, 4)
            val key = labelPart.take(90).ifBlank { "unlabelled" }
            val category = classify(key)
            when (category) {
                "obstacle candidate" -> obstacleCandidates++
                "target candidate" -> targetCandidates++
                "interaction candidate" -> interactCandidates++
            }
            val entity = entities.optJSONObject(key) ?: JSONObject()
            entity.put("category", category)
            entity.put("seen", entity.optInt("seen", 0) + 1)
            entity.put("last_seen", now)
            entity.put("x", nx.toDouble())
            entity.put("y", ny.toDouble())
            entity.put("width", (w / width).toDouble())
            entity.put("height", (h / height).toDouble())
            entity.put("sector", "$sx,$sy")
            entities.put(key, entity)

            val sector = sectors.optJSONObject("$sx,$sy") ?: JSONObject()
            sector.put("seen", sector.optInt("seen", 0) + 1)
            sector.put("last_seen", now)
            sector.put("last_label", key)
            sector.put("last_category", category)
            sectors.put("$sx,$sy", sector)
        }
        val text = lines.joinToString(" ").take(700)
        val oldText = p.getString("last_objective_text", "") ?: ""
        val objective = lines.firstOrNull { line ->
            val lower = line.lowercase(Locale.ROOT)
            listOf("quest", "objective", "defeat", "collect", "find", "talk to", "return to", "reach", "activate", "deliver").any { lower.contains(it) }
        }?.take(220).orEmpty()
        val events = arr(p.getString("events", "[]"))
        if (objective.isNotBlank() && objective != oldText) {
            events.put(JSONObject().apply {
                put("time", now)
                put("type", "objective candidate")
                put("text", objective)
                put("verified", false)
            })
        }
        if (obstacleCandidates + targetCandidates + interactCandidates > 0) {
            events.put(JSONObject().apply {
                put("time", now)
                put("type", "visual candidates")
                put("obstacles", obstacleCandidates)
                put("targets", targetCandidates)
                put("interactions", interactCandidates)
            })
        }
        while (events.length() > MAX_EVENTS) events.remove(0)
        p.edit().putString("entities", entities.toString())
            .putString("sectors", sectors.toString())
            .putString("events", events.toString())
            .putString("last_objective_text", if (objective.isNotBlank()) objective else oldText)
            .putString("last_screen_text", text)
            .putInt("observations", p.getInt("observations", 0) + 1)
            .putLong("last_seen", now)
            .apply()
    }

    private fun classify(label: String): String = when {
        listOf("wall", "barrier", "fence", "rock", "boulder", "tree", "building", "obstacle").any { label.contains(it) } -> "obstacle candidate"
        listOf("enemy", "monster", "person", "animal", "creature").any { label.contains(it) } -> "target candidate"
        listOf("door", "portal", "chest", "npc", "button", "sign").any { label.contains(it) } -> "interaction candidate"
        else -> "unclassified visual"
    }

    fun report(c: Context): String {
        val p = prefs(c)
        val entities = obj(p.getString("entities", "{}"))
        val sectors = obj(p.getString("sectors", "{}"))
        val events = arr(p.getString("events", "[]"))
        var obstacles = 0
        var targets = 0
        var interactions = 0
        val keys = entities.keys()
        while (keys.hasNext()) {
            val item = entities.optJSONObject(keys.next()) ?: continue
            when (item.optString("category")) {
                "obstacle candidate" -> obstacles++
                "target candidate" -> targets++
                "interaction candidate" -> interactions++
            }
        }
        return "SCREEN WORLD MODEL\nTracked visual labels: ${entities.length()} · occupied screen sectors: ${sectors.length()}/25\n" +
            "Obstacle candidates: $obstacles · target candidates: $targets · interaction candidates: $interactions\n" +
            "World events remembered: ${events.length()} · observations: ${p.getInt("observations", 0)}\n" +
            "Important: this is a persistent screen-relative model. Generic object labels are uncertain and do not yet provide true 3D positions or guaranteed collision detection."
    }
}
