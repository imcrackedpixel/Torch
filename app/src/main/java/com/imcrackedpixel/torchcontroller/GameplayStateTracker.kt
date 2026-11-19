package com.imcrackedpixel.torchcontroller

import android.content.Context

/**
 * Small on-device evidence tracker. It labels visible cues conservatively and
 * keeps a short timeline so experiments can compare what the screen showed
 * before and after a controller action. It never claims an action succeeded.
 */
object GameplayStateTracker {
    fun observe(context: Context, lines: List<String>, cues: List<String>, objectCount: Int): String {
        val prefs = context.getSharedPreferences("live_teaching", Context.MODE_PRIVATE)
        val text = lines.joinToString(" ").lowercase()
        val cueText = cues.joinToString(" · ")
        // OCR-only quest hints; these are candidate objectives, not verified quest state.
        val questHints = lines.filter { line ->
            val value = line.lowercase()
            listOf("quest", "talk to", "speak to", "defeat", "kill", "find", "collect", "travel to", "enter", "rescue", "investigate", "activate", "complete", "deliver", "follow").any { value.contains(it) }
        }.distinct().take(5)
        if (questHints.isNotEmpty()) {
            val candidate = questHints.joinToString(" | ").take(500)
            val previousQuest = prefs.getString("quest_objective_text", "") ?: ""
            if (candidate != previousQuest) {
                val history = prefs.getString("quest_observation_history", "") ?: ""
                val entry = System.currentTimeMillis().toString() + " | " + candidate
                prefs.edit().putString("quest_objective_text", candidate)
                    .putString("quest_observation_history", (history.split("\n").filter { it.isNotBlank() } + entry).takeLast(80).joinToString("\n"))
                    .putLong("quest_objective_seen_at", System.currentTimeMillis()).apply()
            } else {
                prefs.edit().putLong("quest_objective_seen_at", System.currentTimeMillis()).apply()
            }
        }
        val state: String
        val confidence: String
        val next: String

        when {
            cues.any { it.contains("loot text detected", true) || it.contains("loot labels", true) } -> {
                state = "LOOT VISIBLE"
                confidence = "medium"
                next = "Check whether the pickup prompt appears, then confirm the pickup manually"
            }
            cues.any { it.contains("enemy/combat", true) || it.contains("enemy health bars", true) } ||
                listOf("boss", "enemy", "elite", "monster").any { text.contains(it) } -> {
                state = "POSSIBLE COMBAT"
                confidence = if (cues.any { it.contains("enemy/combat", true) }) "medium" else "low"
                next = "Watch the same health-bar area before and after one skill"
            }
            listOf("inventory", "settings", "quest", "map", "equipment", "select skill").any { text.contains(it) } -> {
                state = "MENU OR QUEST"
                confidence = "medium"
                next = "Pause automatic taps while a menu or quest screen is open"
            }
            objectCount > 0 -> {
                state = "OBJECTS ON SCREEN"
                confidence = "low"
                next = "Ask the player what the highlighted object represents"
            }
            else -> {
                state = "UNCLASSIFIED GAMEPLAY"
                confidence = "low"
                next = "Keep observing; ask the player to label the current activity"
            }
        }

        val previous = prefs.getString("last_state", "") ?: ""
        val changed = previous.isNotBlank() && previous != state
        val evidence = cueText.ifBlank { "No strong HUD cue" }
        val gameSnapshot = GameLearningEngine.observe(context, lines, cues)
        val quest = (prefs.getString("quest_objective_text", "") ?: "").takeIf { it.isNotBlank() }?.let { " · Quest text: $it" } ?: ""
        val generic = " · Game brain: ${gameSnapshot.phase}" +
            (if (gameSnapshot.objective.isNotBlank()) " · Candidate objective: ${gameSnapshot.objective}" else "") +
            (if (gameSnapshot.interactionHint.isNotBlank()) " · Possible interaction: ${gameSnapshot.interactionHint}" else "")
        val summary = "$state · confidence $confidence · objects detected: $objectCount$quest$generic"
        val detail = "$summary\nEvidence: $evidence\nNext: $next" + if (changed) "\nState change: $previous → $state" else ""
        val raw = lines.joinToString(" | ").take(180)
        prefs.edit()
            .putString("last_state", state)
            .putString("last_observation", "$summary | $evidence | Screen: $raw".take(240))
            .putString("brain_summary", detail.take(700))
            .apply()

        if (changed) {
            val old = prefs.getString("state_history", "") ?: ""
            val entry = System.currentTimeMillis().toString() + " | " + previous + " -> " + state
            prefs.edit().putString("state_history", (old.split("\n").filter { it.isNotBlank() } + entry).takeLast(80).joinToString("\n")).apply()
        }
        return detail
    }
}
