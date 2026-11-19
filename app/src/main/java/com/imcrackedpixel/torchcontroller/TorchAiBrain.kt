package com.imcrackedpixel.torchcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Local, game-agnostic decision memory for Torch.
 *
 * It stores screen observations, generic ML Kit labels, candidate objectives and
 * action outcomes. Screen changes are weak evidence only; they are never treated
 * as proof of movement, damage, quest completion or success.
 */
object TorchAiBrain {
    private const val PREFS = "torch_ai_brain"
    private const val MAX_OBSERVATIONS = 300
    private const val MAX_ACTIONS = 240

    data class Observation(
        val phase: String,
        val objective: String,
        val sceneKey: String,
        val objectCount: Int
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun observe(
        context: Context,
        lines: List<String>,
        cues: List<String>,
        objectDetails: List<String>,
        screenWidth: Int,
        screenHeight: Int
    ): Observation {
        val p = prefs(context)
        val cleanLines = lines.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }
        val text = cleanLines.joinToString(" | ").take(1200)
        val lower = text.lowercase()
        val objectiveWords = listOf("quest", "objective", "talk to", "speak to", "defeat", "kill", "find", "collect", "travel to", "enter", "rescue", "investigate", "activate", "complete", "deliver", "follow", "reach", "survive", "escape", "destroy", "open", "use", "equip", "upgrade", "checkpoint")
        val objective = cleanLines.filter { line -> objectiveWords.any { line.lowercase().contains(it) } }
            .distinct().take(5).joinToString(" · ").take(600)
        val phase = when {
            listOf("game over", "you died", "defeat", "respawn").any { lower.contains(it) } -> "RECOVERY"
            listOf("victory", "level complete", "stage clear", "mission complete", "quest complete").any { lower.contains(it) } -> "POSSIBLE COMPLETION"
            listOf("inventory", "settings", "equipment", "skill tree", "pause", "map", "menu").any { lower.contains(it) } -> "MENU"
            cues.any { it.contains("combat", true) || it.contains("enemy", true) } || listOf("boss", "enemy", "monster").any { lower.contains(it) } -> "POSSIBLE COMBAT"
            objective.isNotBlank() -> "OBJECTIVE VISIBLE"
            else -> "EXPLORATION / UNKNOWN"
        }
        val sceneKey = digest((text + "|" + cues.sorted().joinToString("|") + "|" + objectDetails.sorted().joinToString("|")).take(1800))
        val history = try { JSONArray(p.getString("observations", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val oldKey = p.getString("last_scene_key", "") ?: ""
        if (sceneKey != oldKey || history.length() == 0) {
            history.put(JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("phase", phase)
                put("objective", objective)
                put("scene", sceneKey)
                put("screen", text.take(500))
                put("object_count", objectDetails.size)
                put("objects", JSONArray(objectDetails.take(24)))
                put("screen_width", screenWidth)
                put("screen_height", screenHeight)
            })
            while (history.length() > MAX_OBSERVATIONS) history.remove(0)
        }

        val objectMemory = try { JSONObject(p.getString("object_memory", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        objectDetails.take(40).forEach { detail ->
            val label = detail.substringBefore("@").substringBefore(":").trim().lowercase().take(80)
            if (label.isNotBlank()) {
                val item = objectMemory.optJSONObject(label) ?: JSONObject()
                item.put("seen", item.optInt("seen", 0) + 1)
                item.put("last_seen", System.currentTimeMillis())
                item.put("last_screen_position", detail.substringAfter("@", "").take(100))
                objectMemory.put(label, item)
            }
        }

        val previousObjective = p.getString("active_objective", "") ?: ""
        p.edit()
            .putString("last_scene_key", sceneKey)
            .putString("last_phase", phase)
            .putString("active_objective", objective.ifBlank { previousObjective })
            .putString("observations", history.toString())
            .putString("object_memory", objectMemory.toString())
            .putLong("last_observed_at", System.currentTimeMillis())
            .putInt("total_observations", p.getInt("total_observations", 0) + 1)
            .apply()
        return Observation(phase, objective.ifBlank { previousObjective }, sceneKey, objectDetails.size)
    }

    fun chooseSkill(context: Context, available: List<Int>): Int {
        if (available.isEmpty()) return -1
        val scores = actionScores(context, "skill")
        val unexplored = available.filter { scores.optJSONObject((it + 1).toString())?.optInt("trials", 0) ?: 0 < 3 }
        if (unexplored.isNotEmpty()) return unexplored.minByOrNull { scores.optJSONObject((it + 1).toString())?.optInt("trials", 0) ?: 0 } ?: unexplored.first()
        return available.maxByOrNull { index ->
            val item = scores.optJSONObject((index + 1).toString()) ?: JSONObject()
            val trials = item.optInt("trials", 0).coerceAtLeast(1)
            (item.optInt("positive", 0) - item.optInt("negative", 0) * 0.6).toDouble() / trials
        } ?: available.first()
    }

    fun chooseMovementDirection(context: Context): Int {
        val scores = actionScores(context, "move")
        val unexplored = (0..7).filter { scores.optJSONObject(it.toString())?.optInt("trials", 0) ?: 0 < 3 }
        if (unexplored.isNotEmpty()) return unexplored.minByOrNull { scores.optJSONObject(it.toString())?.optInt("trials", 0) ?: 0 } ?: 0
        return (0..7).maxByOrNull { direction ->
            val item = scores.optJSONObject(direction.toString()) ?: JSONObject()
            val trials = item.optInt("trials", 0).coerceAtLeast(1)
            (item.optInt("positive", 0) - item.optInt("negative", 0) * 0.7 + 0.2).toDouble() / (trials + 0.5)
        } ?: 0
    }

    fun recordOutcome(context: Context, kind: String, key: String, before: String, after: String) {
        val p = prefs(context)
        val scores = actionScores(context, kind)
        val item = scores.optJSONObject(key) ?: JSONObject()
        val changed = before.isNotBlank() && after.isNotBlank() && before != after
        item.put("trials", item.optInt("trials", 0) + 1)
        // A change is weak exploratory feedback, not proof the action worked.
        item.put("positive", item.optInt("positive", 0) + if (changed) 1 else 0)
        item.put("negative", item.optInt("negative", 0) + if (!changed) 1 else 0)
        item.put("last_evidence", if (changed) "screen text/state changed after action; correlation uncertain" else "no readable screen change detected")
        item.put("last_time", System.currentTimeMillis())
        scores.put(key, item)
        p.edit().putString("scores_$kind", scores.toString()).apply()

        val history = try { JSONArray(p.getString("action_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        history.put(JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("kind", kind)
            put("key", key)
            put("changed", changed)
            put("before", before.take(180))
            put("after", after.take(180))
            put("evidence", item.optString("last_evidence"))
        })
        while (history.length() > MAX_ACTIONS) history.remove(0)
        p.edit().putString("action_history", history.toString()).apply()
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        val observations = try { JSONArray(p.getString("observations", "[]") ?: "[]").length() } catch (_: Exception) { 0 }
        val objects = try { JSONObject(p.getString("object_memory", "{}") ?: "{}").length() } catch (_: Exception) { 0 }
        val actions = try { JSONArray(p.getString("action_history", "[]") ?: "[]").length() } catch (_: Exception) { 0 }
        val objective = p.getString("active_objective", "") ?: ""
        val phase = p.getString("last_phase", "Waiting for screen observations") ?: "Waiting for screen observations"
        return "Torch AI brain · $phase · observations $observations · object labels remembered $objects · experiments $actions" +
            (if (objective.isNotBlank()) " · candidate objective: $objective" else " · no readable objective yet") +
            ". Screen changes are clues, not proof of success. Map memory currently records observed scenes and screen positions, not a full navigable world map."
    }

    private fun actionScores(context: Context, kind: String): JSONObject =
        try { JSONObject(prefs(context).getString("scores_$kind", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
}
