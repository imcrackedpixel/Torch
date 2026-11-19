package com.imcrackedpixel.torchcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * On-device game agent. Uses visible OCR/object cues and locally learned action
 * evidence. All inferred progress is explicitly treated as uncertain until the
 * game provides readable confirmation.
 */
object TorchGameAgent {
    data class Decision(val action: String, val reason: String, val direction: Int, val skillIndex: Int)

    private fun prefs(context: Context) = context.getSharedPreferences("torch_game_agent", Context.MODE_PRIVATE)

    fun observeScene(context: Context, lines: List<String>, objects: List<String>, width: Int, height: Int) {
        val p = prefs(context)
        val text = lines.joinToString(" ").lowercase(Locale.ROOT).take(1200)
        val tokens = text.split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.distinct().sorted().take(32)
        val fingerprint = digest(tokens.joinToString("|"))
        val old = p.getString("game_fingerprint", "") ?: ""
        val now = System.currentTimeMillis()

        val objectiveLine = lines.firstOrNull { line ->
            val l = line.lowercase(Locale.ROOT)
            listOf("quest", "objective", "defeat", "kill ", "collect", "find ", "talk to",
                "speak to", "return to", "reach ", "deliver", "activate", "rescue",
                "0/", "1/", "2/", "3/", "4/", "5/").any { l.contains(it) }
        }?.trim()?.take(180).orEmpty()
        val previousObjective = p.getString("candidate_objective", "") ?: ""
        val objectiveHistory = safeArray(p.getString("objective_journal", "[]"))
        if (objectiveLine.isNotBlank() && objectiveLine != previousObjective) {
            objectiveHistory.put(JSONObject().apply {
                put("time", now); put("text", objectiveLine); put("status", "candidate")
            })
            while (objectiveHistory.length() > 80) objectiveHistory.remove(0)
        }

        val entities = safeObject(p.getString("screen_entity_memory", "{}"))
        val sectors = safeObject(p.getString("screen_sector_memory", "{}"))
        objects.take(50).forEach { detail ->
            val coords = detail.substringAfter("@", "").split(",")
            val cx = coords.getOrNull(0)?.toFloatOrNull() ?: return@forEach
            val cy = coords.getOrNull(1)?.toFloatOrNull() ?: return@forEach
            val sx = (cx / width.coerceAtLeast(1) * 3f).toInt().coerceIn(0, 2)
            val sy = (cy / height.coerceAtLeast(1) * 3f).toInt().coerceIn(0, 2)
            val key = "$sx,$sy"
            val label = detail.substringBefore("@").trim().take(80)
            val sector = sectors.optJSONObject(key) ?: JSONObject()
            sector.put("seen", sector.optInt("seen", 0) + 1)
            sector.put("last_seen", now)
            sector.put("last_label", label)
            sectors.put(key, sector)
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

        val history = safeArray(p.getString("scene_history", "[]"))
        if (fingerprint != old || history.length() == 0) {
            history.put(JSONObject().apply {
                put("time", now); put("fingerprint", fingerprint)
                put("text", text.take(300)); put("object_count", objects.size)
            })
            while (history.length() > 240) history.remove(0)
        }
        val profile = tokens.take(10).joinToString("-").ifBlank { "unidentified-game" }.take(120)
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

    fun plan(context: Context, phase: String, state: String, text: String, enemyBars: Int, availableSkills: List<Int>): Decision =
        RoutineController.plan(context, phase, state, text, enemyBars, availableSkills)

    @Suppress("unused")
    private fun legacyPlan(context: Context, phase: String, state: String, text: String, enemyBars: Int, availableSkills: List<Int>): Decision {
        val lower = (state + " " + text).lowercase(Locale.ROOT)
        val direction = chooseDirection(context)
        val startPrompt = listOf("tap to start", "press start", "press to start", "start game",
            "continue game", "play now", "resume game", "tap to play").any { lower.contains(it) }
        if (startPrompt) {
            return Decision("INTERACT", "Start/continue prompt detected; test the mapped interaction button", direction, -1)
        }
        if (phase.contains("MENU", true) || phase.contains("RECOVERY", true) ||
            lower.contains("game over") || lower.contains("you died") || lower.contains("inventory") ||
            lower.contains("settings") || lower.contains("skill tree") || lower.contains("connection lost")) {
            return Decision("WAIT", "Menu, recovery or connection state: pause for safe player input", direction, -1)
        }
        if (phase.contains("POSSIBLE COMPLETION", true) ||
            listOf("victory", "stage clear", "mission complete", "quest complete", "level complete").any { lower.contains(it) }) {
            return Decision("WAIT", "Possible completion: stop and ask the player to verify", direction, -1)
        }

        val danger = listOf("low health", "health critical", "critical health", "almost dead", "danger", "retreat").any { lower.contains(it) }
        if (danger) {
            val retreat = oppositeRecentDirection(context)
            return Decision("MOVE", "Possible danger cue: retreat and reassess rather than continue attacking", retreat, -1)
        }

        // Movement is the default action. Stale OCR and broad interaction words
        // must never trap the agent in an interaction loop.
        val strongInteraction = listOf("tap to collect", "press to collect", "claim reward",
            "accept quest", "talk to npc", "speak to npc", "open chest", "enter portal")
            .any { lower.contains(it) }
        if (strongInteraction && prefs(context).getInt("move_steps_since_interact", 0) >= 3) {
            prefs(context).edit().putInt("move_steps_since_interact", 0).apply()
            return Decision("INTERACT", "Specific interaction prompt detected; test once, then resume navigation", direction, -1)
        }

        val combat = enemyBars > 0 || phase.contains("COMBAT", true) ||
            listOf("boss", "enemy", "monster", "attack").any { lower.contains(it) }
        val actionCycle = prefs(context).getInt("navigation_action_cycle", 0)
        // Two navigation actions for every skill trial, including during combat.
        if (combat && availableSkills.isNotEmpty() && actionCycle % 3 == 2) {
            val selected = TorchAiBrain.chooseSkill(context, availableSkills).takeIf { it >= 0 } ?: availableSkills.first()
            prefs(context).edit().putInt("navigation_action_cycle", actionCycle + 1).apply()
            return Decision("SKILL", "Combat cue: one skill trial between navigation moves", direction, selected)
        }
        prefs(context).edit().putInt("navigation_action_cycle", actionCycle + 1)
            .putInt("move_steps_since_interact", prefs(context).getInt("move_steps_since_interact", 0) + 1).apply()
        val mapDirection = MinimapNavigation.suggestedDirection(context)
        val chosen = mapDirection ?: direction
        return Decision("MOVE", if (mapDirection != null) "Follow minimap target bearing" else "Explore an under-tested route", chosen, -1)
    }

    fun chooseDirection(context: Context): Int {
        val p = prefs(context)
        val brain = context.getSharedPreferences("torch_ai_brain", Context.MODE_PRIVATE)
        val recent = safeArray(brain.getString("recent_move_directions", "[]"))
        val scores = safeObject(brain.getString("scores_move", "{}"))
        val results = safeArray(p.getString("movement_results", "[]"))
        val noProgress = p.getInt("no_progress_streak", 0)

        // Do not sweep clockwise through all eight joystick directions: that
        // produces the circular-looking movement reported by the player.
        // First test the four straight directions; use diagonals only after
        // straight movement has repeatedly failed.
        val pool = if (noProgress < 4) listOf(0, 2, 4, 6) else listOf(1, 3, 5, 7)
        val last = recent.optInt(recent.length() - 1, -1)
        val previous = recent.optInt(recent.length() - 2, -1)
        val candidates = pool.filter { it != last && it != previous }.ifEmpty { pool }

        val visits = IntArray(8)
        val failuresByDirection = IntArray(8)
        for (i in 0 until results.length()) {
            val entry = results.optJSONObject(i) ?: continue
            val d = entry.optInt("direction", -1)
            if (d in 0..7) {
                visits[d]++
                if (!entry.optBoolean("changed", false)) failuresByDirection[d]++
            }
        }
        val seed = p.getInt("direction_seed", 0).coerceIn(0, 7)
        val mapDirection = MinimapNavigation.suggestedDirection(context)
        val mapFailures = mapDirection?.let { d -> failuresByDirection[d] } ?: 0
        if (mapDirection != null && mapFailures < 2 && noProgress < 4) return mapDirection
        val selected = candidates.minByOrNull { direction ->
            val item = scores.optJSONObject(direction.toString()) ?: JSONObject()
            val trials = maxOf(visits[direction], item.optInt("trials", 0))
            val failures = maxOf(failuresByDirection[direction], item.optInt("negative", 0))
            val successes = item.optInt("positive", 0)
            // Explore the least-tested straight route; avoid repeating failed routes.
            trials * 1.8 + failures * 3.5 - successes * 0.4 +
                ((direction - seed + 8) % 8) * 0.01
        } ?: pool.first()
        // Advance through the candidate pool rather than rotating around the compass.
        p.edit().putInt("direction_seed", (selected + 2) % 8).apply()
        return selected
    }

    private fun oppositeRecentDirection(context: Context): Int {
        val recent = safeArray(context.getSharedPreferences("torch_ai_brain", Context.MODE_PRIVATE)
            .getString("recent_move_directions", "[]"))
        val last = recent.optInt(recent.length() - 1, -1)
        return if (last in 0..7) (last + 4) % 8 else chooseDirection(context)
    }

    fun recordMovement(context: Context, direction: Int, changed: Boolean) {
        val p = prefs(context)
        val streak = if (changed) 0 else (p.getInt("no_progress_streak", 0) + 1).coerceAtMost(99)
        val history = safeArray(p.getString("movement_results", "[]"))
        history.put(JSONObject().apply {
            put("time", System.currentTimeMillis()); put("direction", direction)
            put("changed", changed); put("streak", streak)
        })
        while (history.length() > 120) history.remove(0)
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
        val scenes = safeArray(p.getString("scene_history", "[]")).length()
        val sectors = safeObject(p.getString("screen_sector_memory", "{}")).length()
        val actions = safeArray(brain.getString("action_history", "[]"))
        var positive = 0
        var negative = 0
        for (i in 0 until actions.length()) {
            val item = actions.optJSONObject(i) ?: continue
            if (item.optBoolean("changed")) positive++ else negative++
        }
        val observations = brain.getInt("total_observations", 0)
        val labels = safeObject(brain.getString("object_memory", "{}")).length()
        val entities = safeObject(p.getString("screen_entity_memory", "{}")).length()
        val objectiveEntries = safeArray(p.getString("objective_journal", "[]")).length()
        val objective = p.getString("candidate_objective", "") ?: ""
        val streak = p.getInt("no_progress_streak", 0)
        val profile = p.getString("game_profile_hint", "not identified") ?: "not identified"
        val health = if (streak >= 5) "movement may be stuck; try repositioning M or manually moving" else "no severe movement stall recorded"
        return "TORCH AI LEARNING REPORT\n" +
            "Game profile hint: $profile\nScreen observations: $observations\nScene changes remembered: $scenes\n" +
            "Screen sectors with detected objects: $sectors/9\nVisual labels remembered: $labels\nTracked label entries: $entities\n" +
            MinimapNavigation.report(context) + "\n" +
            "Candidate objective records: $objectiveEntries\nAction experiments: ${actions.length()} (screen changes: $positive; no readable change: $negative)\n" +
            "No-progress movement streak: $streak · $health\n" +
            (if (objective.isNotBlank()) "Current OCR objective candidate: $objective\n" else "No readable objective candidate yet\n") +
            "\nIMPORTANT: screen changes are weak clues, not proof of movement, damage, loot, or quest completion. " +
            "The minimap memory is a coarse explored-position grid, not a full collision or world map. Generic labels may be wrong."
    }

    private fun safeArray(value: String?): JSONArray = try { JSONArray(value ?: "[]") } catch (_: Exception) { JSONArray() }
    private fun safeObject(value: String?): JSONObject = try { JSONObject(value ?: "{}") } catch (_: Exception) { JSONObject() }
    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it) }
}
