package com.imcrackedpixel.torchcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Game-agnostic local learning memory.
 * It records observable text/state transitions as evidence, never as proof that
 * an objective or level has been completed.
 */
object GameLearningEngine {
    data class Snapshot(
        val phase: String,
        val objective: String,
        val interactionHint: String,
        val changed: Boolean
    )

    private val objectiveWords = listOf(
        "quest", "objective", "talk to", "speak to", "defeat", "kill", "find",
        "collect", "travel to", "enter", "rescue", "investigate", "activate",
        "complete", "deliver", "follow", "reach", "survive", "escape", "destroy",
        "open", "use", "equip", "upgrade", "win", "finish", "checkpoint"
    )
    private val interactionWords = listOf(
        "interact", "talk", "speak", "open", "continue", "next", "claim",
        "accept", "enter", "use", "revive", "respawn", "confirm", "collect"
    )

    fun observe(context: Context, lines: List<String>, cues: List<String>): Snapshot {
        val prefs = context.getSharedPreferences("game_learning_brain", Context.MODE_PRIVATE)
        val normalised = lines.map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }
        val joined = normalised.joinToString(" | ")
        val lower = joined.lowercase()
        val objectiveLines = normalised.filter { line ->
            val value = line.lowercase()
            objectiveWords.any { value.contains(it) }
        }.distinct().take(6)
        val objective = objectiveLines.joinToString(" · ").take(700)
        val interaction = interactionWords.firstOrNull { lower.contains(it) } ?: ""
        val phase = when {
            listOf("game over", "you died", "defeated", "respawn").any { lower.contains(it) } -> "RECOVERY OR DEFEAT SCREEN"
            listOf("victory", "level complete", "stage clear", "mission complete", "quest complete").any { lower.contains(it) } -> "POSSIBLE COMPLETION"
            listOf("pause", "settings", "inventory", "equipment", "skill tree", "map", "menu").any { lower.contains(it) } -> "MENU OR MAP"
            cues.any { it.contains("combat", true) || it.contains("enemy", true) } ||
                listOf("boss", "enemy", "monster", "attack", "critical").any { lower.contains(it) } -> "POSSIBLE COMBAT"
            objective.isNotBlank() -> "OBJECTIVE VISIBLE"
            interaction.isNotBlank() -> "INTERACTION PROMPT POSSIBLE"
            else -> "EXPLORATION OR UNCLASSIFIED"
        }
        val oldScreen = prefs.getString("last_screen_text", "") ?: ""
        val oldObjective = prefs.getString("active_objective", "") ?: ""
        val changed = joined.isNotBlank() && joined != oldScreen
        val objectiveChanged = objective.isNotBlank() && objective != oldObjective
        val history = try { JSONArray(prefs.getString("observation_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        if (changed || objectiveChanged) {
            history.put(JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("phase", phase)
                put("objective", objective)
                put("screen_text", joined.take(500))
                put("evidence", if (objectiveChanged) "objective text changed" else "visible text changed")
            })
            while (history.length() > 160) history.remove(0)
        }
        val edit = prefs.edit()
            .putString("last_screen_text", joined.take(1200))
            .putString("last_phase", phase)
            .putString("last_interaction_hint", interaction)
            .putString("observation_history", history.toString())
            .putLong("last_observed_at", System.currentTimeMillis())
        if (objective.isNotBlank()) edit.putString("active_objective", objective)
        edit.apply()
        return Snapshot(phase, objective.ifBlank { oldObjective }, interaction, changed)
    }

    fun learningSummary(context: Context): String {
        val prefs = context.getSharedPreferences("game_learning_brain", Context.MODE_PRIVATE)
        val phase = prefs.getString("last_phase", "Waiting for gameplay") ?: "Waiting for gameplay"
        val objective = prefs.getString("active_objective", "") ?: ""
        val interaction = prefs.getString("last_interaction_hint", "") ?: ""
        val count = try { JSONArray(prefs.getString("observation_history", "[]") ?: "[]").length() } catch (_: Exception) { 0 }
        return "Game state: $phase. " +
            (if (objective.isNotBlank()) "Candidate objective: $objective. " else "No readable objective yet. ") +
            (if (interaction.isNotBlank()) "Possible interaction prompt: $interaction. " else "") +
            "Saved visual observations: $count. Text changes are clues, not verified progress."
    }
}
