package com.imcrackedpixel.torchcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * Startup perception and per-game knowledge bootstrap.
 *
 * This is deliberately a confidence-based heuristic layer, not a claim of
 * human-level vision. It combines OCR, generic object labels, HUD cues and
 * temporal screen changes into a compact game profile before the agent acts.
 */
object GameUnderstandingEngine {
    private const val PREFS = "torch_game_understanding"

    data class Report(
        val gameType: String,
        val phase: String,
        val objective: String,
        val confidence: Int,
        val observations: Int,
        val sceneChanges: Int,
        val ready: Boolean,
        val nextStep: String,
        val summary: String
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val genres = linkedMapOf(
        "ACTION RPG" to listOf("skill", "inventory", "equipment", "quest", "loot", "monster", "boss", "level"),
        "PLATFORMER" to listOf("jump", "checkpoint", "platform", "level", "run", "double jump"),
        "SHOOTER" to listOf("ammo", "reload", "weapon", "headshot", "scope", "grenade", "round"),
        "STRATEGY" to listOf("build", "upgrade", "unit", "base", "research", "resource", "wave"),
        "RACING" to listOf("lap", "race", "speed", "drift", "finish", "checkpoint", "nitro"),
        "PUZZLE" to listOf("match", "rotate", "puzzle", "solve", "piece", "move")
    )

    fun observe(
        context: Context,
        lines: List<String>,
        cues: List<String>,
        objects: List<String>,
        width: Int,
        height: Int
    ): Report {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val clean = lines.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }.distinct().take(40)
        val text = clean.joinToString(" | ").take(1800)
        val lower = (text + " " + cues.joinToString(" ") + " " + objects.joinToString(" "))
            .lowercase(Locale.ROOT)

        val genreScores = genres.mapValues { (_, words) -> words.count { lower.contains(it) } }
        val bestGenre = genreScores.maxByOrNull { it.value }
        val gameType = if (bestGenre != null && bestGenre.value >= 2) bestGenre.key else "UNIDENTIFIED GAME"

        val phase = when {
            listOf("game over", "you died", "defeat", "respawn", "revive").any { lower.contains(it) } -> "RECOVERY"
            listOf("victory", "stage clear", "mission complete", "quest complete", "level complete").any { lower.contains(it) } -> "POSSIBLE COMPLETION"
            listOf("main menu", "inventory", "settings", "skill tree", "equipment", "paused", "options").any { lower.contains(it) } -> "MENU"
            listOf("dialogue", "talk to", "speak to", "accept quest", "choose", "conversation").any { lower.contains(it) } -> "DIALOGUE OR INTERACTION"
            cues.any { it.contains("combat", true) || it.contains("enemy", true) } ||
                listOf("boss", "enemy", "monster", "attack", "reload").any { lower.contains(it) } -> "POSSIBLE COMBAT"
            else -> "EXPLORATION OR UNKNOWN"
        }

        val goalWords = listOf("quest", "objective", "defeat", "kill", "collect", "find", "talk to",
            "speak to", "return to", "reach", "deliver", "activate", "rescue", "investigate",
            "survive", "escape", "destroy", "checkpoint", "complete")
        val objective = clean.filter { line ->
            val l = line.lowercase(Locale.ROOT)
            goalWords.any { l.contains(it) } || Regex("\\b\\d+\\s*/\\s*\\d+\\b").containsMatchIn(l)
        }.distinct().take(5).joinToString(" · ").take(500)

        val fingerprint = digest(
            (text + "|" + cues.sorted().joinToString("|") + "|" +
                objects.sorted().joinToString("|") + "|$width:$height").take(2600)
        )
        val oldFingerprint = p.getString("last_fingerprint", "") ?: ""
        val history = try { JSONArray(p.getString("scene_history", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val changed = fingerprint != oldFingerprint
        if (changed || history.length() == 0) {
            history.put(JSONObject().apply {
                put("time", now)
                put("phase", phase)
                put("game_type", gameType)
                put("objective", objective)
                put("ocr", text.take(450))
                put("objects", JSONArray(objects.take(12)))
                put("cues", JSONArray(cues.take(12)))
            })
            while (history.length() > 160) history.remove(0)
        }

        val observations = p.getInt("observation_count", 0) + 1
        val started = p.getLong("learning_started_at", 0L).takeIf { it > 0L } ?: now
        val changes = history.length()
        val confidence = (
            (if (text.isNotBlank()) 25 else 0) +
            (if (objects.isNotEmpty()) 15 else 0) +
            (if (cues.isNotEmpty()) 20 else 0) +
            (if (objective.isNotBlank()) 20 else 0) +
            (if (gameType != "UNIDENTIFIED GAME") 20 else 0)
        ).coerceIn(0, 100)
        // Never hold the player indefinitely when a game exposes little readable UI.
        val ready = observations >= 5 || now - started >= 10000L

        val next = when {
            phase == "RECOVERY" -> "Wait for a safe respawn or player input"
            phase == "MENU" -> "Identify the menu and wait for a safe gameplay screen"
            phase == "POSSIBLE COMPLETION" -> "Pause and ask the player to verify completion"
            objective.isNotBlank() -> "Track the candidate objective and test one relevant action"
            phase == "POSSIBLE COMBAT" -> "Track threats and test one mapped skill, then verify"
            gameType == "UNIDENTIFIED GAME" -> "Continue observing; identify HUD, controls and game genre"
            else -> "Explore cautiously and compare screen changes after each action"
        }

        val summary = "Game understanding · $gameType · $phase · confidence $confidence% · " +
            "observations $observations · scene changes $changes" +
            (if (objective.isNotBlank()) " · candidate goal: $objective" else "")
        p.edit()
            .putLong("learning_started_at", started)
            .putInt("observation_count", observations)
            .putInt("scene_changes", changes)
            .putInt("confidence", confidence)
            .putString("game_type", gameType)
            .putString("phase", phase)
            .putString("candidate_objective", objective.ifBlank { p.getString("candidate_objective", "") ?: "" })
            .putString("next_step", next)
            .putString("last_fingerprint", fingerprint)
            .putString("scene_history", history.toString())
            .putString("summary", summary)
            .putLong("last_observed_at", now)
            .apply()

        return Report(gameType, phase, objective, confidence, observations, changes, ready, next, summary)
    }

    /** Returns a short preflight message, or null once startup observation is sufficient. */
    fun preflightMessage(context: Context): String? {
        val p = prefs(context)
        val count = p.getInt("observation_count", 0)
        val started = p.getLong("learning_started_at", 0L)
        if (started == 0L) return "Learning game layout · waiting for first screen observations"
        if (count < 5 && System.currentTimeMillis() - started < 10000L) {
            return "Understanding game · scanning interface ($count/5 observations)"
        }
        return null
    }

    fun report(context: Context): String {
        val p = prefs(context)
        return (p.getString("summary", "Waiting for game screen observations") ?: "Waiting for game screen observations") +
            "\\nNext: " + (p.getString("next_step", "Observe the game") ?: "Observe the game") +
            "\\nThis is an evidence-based estimate; unreadable or hidden mechanics still need testing."
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
}
