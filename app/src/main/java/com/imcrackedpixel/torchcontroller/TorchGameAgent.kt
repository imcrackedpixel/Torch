package com.imcrackedpixel.torchcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Conservative on-device action-RPG starter agent.
 * It selects among mapped controls using visible cues and stored action evidence.
 * It does not claim to understand arbitrary game worlds or prove quest completion.
 */
object TorchGameAgent {
    data class Decision(val action: String, val reason: String, val direction: Int, val skillIndex: Int)

    private fun prefs(context: Context) = context.getSharedPreferences("torch_game_agent", Context.MODE_PRIVATE)

    fun observeScene(context: Context, lines: List<String>, objects: List<String>, width: Int, height: Int) {
        val p = prefs(context)
        val text = lines.joinToString(" ").lowercase(Locale.ROOT).take(900)
        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.distinct().sorted().take(24)
        val fingerprint = digest(tokens.joinToString("|"))
        val old = p.getString("game_fingerprint", "") ?: ""
        val now = System.currentTimeMillis()
        // Keep a bounded objective journal from readable HUD/quest text. These are
        // candidate objectives only; completion is never inferred from a screen change.
        val objectiveLine = lines.firstOrNull { line ->
            val l = line.lowercase(Locale.ROOT)
            listOf("quest", "objective", "defeat", "kill ", "collect", "find ", "talk to",
                "speak to", "return to", "reach ", "deliver", "activate", "rescue", "0/", "1/").any { l.contains(it) }
        }?.trim()?.take(180).orEmpty()
        val previousObjective = p.getString("candidate_objective", "") ?: ""
        val objectiveHistory = try { JSONArray(p.getString("objective_journal", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if (objectiveLine.isNotBlank() && objectiveLine != previousObjective) {
            objectiveHistory.put(JSONObject().apply {
                put("time", now); put("text", objectiveLine); put("status", "candidate")
            })
            while (objectiveHistory.length() > 60) objectiveHistory.remove(0)
        }
        val entities = try { JSONObject(p.getString("screen_entity_memory", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val sectors = try { JSONObject(p.getString("screen_sector_memory", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        objects.take(40).forEach { detail ->
            val coords = detail.substringAfter("@", "").split(",")
            val cx = coords.getOrNull(0)?.toFloatOrNull() ?: return@forEach
            val cy = coords.getOrNull(1)?.toFloatOrNull() ?: return@forEach
            val sx = (cx / width.coerceAtLeast(1) * 3f).toInt().coerceIn(0, 2)
            val sy = (cy / height.coerceAtLeast(1) * 3f).toInt().coerceIn(0, 2)
            val key = "$sx,$sy"
            val item = sectors.optJSONObject(key) ?: JSONObject()
            item.put("seen", item.optInt("seen", 0) + 1)
            item.put("last_seen", now)
            val label = detail.substringBefore("@").trim().take(80)
            item.put("last_label", label)
            sectors.put(key, item)
            if (label.isNotBlank()) {
                val entity = entities.optJSONObject(label) ?: JSONObject()
                entity.put("seen", entity.optInt("seen", 0) + 1)
                entity.put("last_seen", now)
                entity.put("last_x", cx)
                entity.put("last_y", cy)
                entity.put("sector", key)
                entities.put(label, entity)
            }
        }
        val history = try { JSONArray(p.getString("scene_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if (fingerprint != old || history.length() == 0) {
            history.put(JSONObject().apply {
                put("time", now); put("fingerprint", fingerprint)
                put("text", text.take(240)); put("object_count", objects.size)
            })
            while (history.length() > 180) history.remove(0)
        }
        val profile = tokens.take(8).joinToString("-").ifBlank { "unidentified-game" }.take(100)
        p.edit().putString("game_fingerprint", fingerprint)
            .putString("game_profile_hint", profile)
            .putString("screen_sector_memory", sectors.toString())
            .putString("screen_entity_memory", entities.toString())
            .putString("objective_journal", objectiveHistory.toString())
            .putString("candidate_objective", if (objectiveLine.isNotBlank()) objectiveLine else previousObjective)
            .putString("scene_history", history.toString())
            .putInt("scene_observations", p.getInt("scene_observations", 0) + 1)
            .putLong("last_scene_at", now).apply()
    }

    fun plan(context: Context, phase: String, state: String, text: String, enemyBars: Int, availableSkills: List<Int>): Decision {
        val lower = (state + " " + text).lowercase(Locale.ROOT)
        if (phase.contains("MENU", true) || phase.contains("RECOVERY", true) ||
            lower.contains("game over") || lower.contains("you died") || lower.contains("inventory") ||
            lower.contains("settings") || lower.contains("skill tree")) {
            return Decision("WAIT", "Menu or recovery state: wait for safe gameplay", chooseDirection(context), -1)
        }
        if (phase.contains("POSSIBLE COMPLETION", true) || listOf("victory", "stage clear", "mission complete", "quest complete").any { lower.contains(it) }) {
            return Decision("WAIT", "Possible completion: stop and ask the player to verify", chooseDirection(context), -1)
        }
        val interaction = state.contains("LOOT VISIBLE", true) ||
            listOf("pickup", "pick up", "claim reward", "accept quest", "talk to", "speak to",
                "interact", "open chest", "enter portal", "continue", "press to collect").any { lower.contains(it) }
        if (interaction) return Decision("INTERACT", "Visible loot or interaction text; test the mapped I control", chooseDirection(context), -1)
        val combat = enemyBars > 0 || phase.contains("COMBAT", true) ||
            listOf("boss", "enemy", "monster", "attack").any { lower.contains(it) }
        if (combat && availableSkills.isNotEmpty()) {
            val selected = TorchAiBrain.chooseSkill(context, availableSkills).takeIf { it >= 0 } ?: availableSkills.first()
            return Decision("SKILL", "Possible combat; use the best remembered mapped skill", chooseDirection(context), selected)
        }
        return Decision("MOVE", "No strong combat or interaction cue; explore a less-tested direction", chooseDirection(context), -1)
    }

    fun chooseDirection(context: Context): Int {
        val p = prefs(context)
        val brain = context.getSharedPreferences("torch_ai_brain", Context.MODE_PRIVATE)
        val recent = try { JSONArray(brain.getString("recent_move_directions", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val avoid = (0 until recent.length()).mapNotNull { recent.optInt(it, -1).takeIf { v -> v in 0..7 } }.toSet()
        val scores = try { JSONObject(brain.getString("scores_move", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val all = (0..7).toList()
        val candidates = all.filter { it !in avoid }.ifEmpty { all }
        val seed = p.getInt("direction_seed", 0)
        val selected = candidates.minByOrNull { direction ->
            val item = scores.optJSONObject(direction.toString()) ?: JSONObject()
            val trials = item.optInt("trials", 0)
            val failures = item.optInt("negative", 0)
            val successes = item.optInt("positive", 0)
            failures * 5.0 + trials * 0.18 - successes * 1.4 + ((direction - seed + 8) % 8) * 0.015
        } ?: seed
        p.edit().putInt("direction_seed", (selected + 3) % 8).apply()
        return selected
    }

    fun recordMovement(context: Context, direction: Int, changed: Boolean) {
        val p = prefs(context)
        val streak = if (changed) 0 else p.getInt("no_progress_streak", 0) + 1
        val history = try { JSONArray(p.getString("movement_results", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        history.put(JSONObject().apply {
            put("time", System.currentTimeMillis()); put("direction", direction)
            put("changed", changed); put("streak", streak)
        })
        while (history.length() > 100) history.remove(0)
        p.edit().putInt("no_progress_streak", streak)
            .putString("movement_results", history.toString())
            .putLong("last_action_at", System.currentTimeMillis()).apply()
    }

    fun movementDuration(context: Context): Long {
        val streak = prefs(context).getInt("no_progress_streak", 0)
        return when {
            streak >= 7 -> 1450L
            streak >= 3 -> 1050L
            else -> 700L
        }
    }

    fun report(context: Context): String {
        val p = prefs(context)
        val brain = context.getSharedPreferences("torch_ai_brain", Context.MODE_PRIVATE)
        val scenes = try { JSONArray(p.getString("scene_history", "[]") ?: "[]").length() } catch (_: Exception) { 0 }
        val sectors = try { JSONObject(p.getString("screen_sector_memory", "{}") ?: "{}").length() } catch (_: Exception) { 0 }
        val actions = try { JSONArray(brain.getString("action_history", "[]") ?: "[]").length() } catch (_: Exception) { 0 }
        val observations = brain.getInt("total_observations", 0)
        val labels = try { JSONObject(brain.getString("object_memory", "{}") ?: "{}").length() } catch (_: Exception) { 0 }
        val visualEntities = try { JSONObject(p.getString("screen_entity_memory", "{}") ?: "{}").length() } catch (_: Exception) { 0 }
        val objectiveEntries = try { JSONArray(p.getString("objective_journal", "[]") ?: "[]").length() } catch (_: Exception) { 0 }
        val objective = p.getString("candidate_objective", "") ?: ""
        val streak = p.getInt("no_progress_streak", 0)
        val profile = p.getString("game_profile_hint", "not identified") ?: "not identified"
        return "TORCH AI LEARNING REPORT\nProfile hint: $profile\nScreen observations: $observations\nScene changes remembered: $scenes\nScreen sectors with detected objects: $sectors/9\nObject labels remembered: $labels\nRecorded action experiments: $actions\nRecent no-progress movement streak: $streak\n\nEvidence is heuristic. Screen changes and detected objects are not proof of movement, damage, loot collection or quest completion. The 3x3 memory is screen-region memory, not a true world map."
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it) }
}
