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
        val summary = "$state · confidence $confidence · objects detected: $objectCount"
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
