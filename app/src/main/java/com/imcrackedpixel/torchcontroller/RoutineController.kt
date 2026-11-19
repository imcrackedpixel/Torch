package com.imcrackedpixel.torchcontroller

import android.content.Context
import java.util.Locale

/**
 * Stable, goal-directed routine layer. It deliberately holds an action long enough
 * to observe a result instead of choosing a fresh direction on every planner tick.
 * All decisions remain heuristic and are stored locally.
 */
object RoutineController {
    private const val PREFS = "torch_routine_controller"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun plan(
        c: Context,
        phase: String,
        state: String,
        text: String,
        enemyBars: Int,
        skills: List<Int>
    ): TorchGameAgent.Decision {
        val prefs = p(c)
        val lower = (phase + " " + state + " " + text).lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()

        val terminal = listOf("victory", "stage clear", "mission complete", "quest complete", "level complete")
        val recovery = listOf("game over", "you died", "respawn", "connection lost", "revive")
        val menus = listOf("main menu", "inventory", "settings", "skill tree", "equipment", "paused", "options")
        if (terminal.any(lower::contains) || phase.contains("COMPLETION", true))
            return save(c, "WAIT", "Possible completion detected; pause so the player can verify", 0, -1)
        if (recovery.any(lower::contains) || phase.contains("RECOVERY", true))
            return save(c, "WAIT", "Recovery screen detected; wait for a safe respawn or player input", 0, -1)
        if (menus.any(lower::contains) || phase.contains("MENU", true))
            return save(c, "WAIT", "Menu detected; pause instead of moving blindly", 0, -1)

        // Explicit tutorial and confirmation text has priority over movement.
        val prompt = listOf(
            "skip tutorial", "skip", "next", "continue", "tap to start", "press to start",
            "tap to play", "press to play", "accept quest", "claim reward", "tap to collect",
            "press to collect", "tap to interact", "press to interact", "talk to npc",
            "speak to npc", "open chest", "enter portal", "confirm", "got it"
        ).firstOrNull { lower.contains(it) }
        val lastPrompt = prefs.getString("last_prompt", "") ?: ""
        val promptAt = prefs.getLong("prompt_at", 0L)
        if (prompt != null && (prompt != lastPrompt || now - promptAt > 7000L)) {
            prefs.edit().putString("last_prompt", prompt).putLong("prompt_at", now)
                .putLong("action_until", now + 1800L).apply()
            return save(c, "INTERACT", "Visible '$prompt' prompt; try the mapped interaction control once, then inspect the screen", stableDirection(c), -1)
        }

        if (listOf("low health", "critical health", "health critical", "almost dead", "retreat", "danger").any(lower::contains)) {
            val d = (stableDirection(c) + 4) % 8
            prefs.edit().putInt("held_direction", d).putLong("action_until", now + 3500L).apply()
            return save(c, "MOVE", "Possible danger; retreat briefly, then reassess", d, -1)
        }

        val combat = enemyBars > 0 || phase.contains("COMBAT", true) ||
            listOf("boss", "enemy", "monster", "elite").any(lower::contains)
        if (combat && skills.isNotEmpty()) {
            val n = prefs.getInt("combat_ticks", 0) + 1
            prefs.edit().putInt("combat_ticks", n).apply()
            if (n % 3 == 0) {
                val skill = TorchAiBrain.chooseSkill(c, skills).takeIf { it >= 0 } ?: skills.first()
                return save(c, "SKILL", "Combat routine: use one tested skill, then check enemy evidence", stableDirection(c), skill)
            }
        } else {
            prefs.edit().putInt("combat_ticks", 0).apply()
        }

        val noProgress = c.getSharedPreferences("torch_endgame_brain", Context.MODE_PRIVATE).getInt("no_progress", 0)
        val until = prefs.getLong("action_until", 0L)
        var direction = prefs.getInt("held_direction", -1)
        val mapDirection = MinimapNavigation.suggestedDirection(c)
        val shouldChoose = direction !in 0..7 || now >= until || noProgress >= 2 ||
            (mapDirection != null && mapDirection != direction && now >= prefs.getLong("map_hold_until", 0L))
        if (shouldChoose) {
            direction = mapDirection ?: MinimapNavigation.explorationDirection(c) ?: TorchGameAgent.chooseDirection(c)
            val hold = when {
                noProgress >= 5 -> 2200L
                noProgress >= 2 -> 3800L
                else -> 7200L
            }
            prefs.edit().putInt("held_direction", direction).putLong("action_until", now + hold)
                .putLong("map_hold_until", now + 5000L).apply()
        }
        val reason = when {
            combat -> "Combat routine: reposition in a steady direction between skill tests"
            mapDirection != null -> "Following the current minimap bearing; hold course and verify movement"
            noProgress >= 2 -> "Progress stalled; change route and test a different direction"
            else -> "Exploration routine: hold this route, observe the result, then choose the next route"
        }
        return save(c, "MOVE", reason, direction, -1)
    }

    private fun stableDirection(c: Context): Int {
        val saved = p(c).getInt("held_direction", -1)
        return if (saved in 0..7) saved else TorchGameAgent.chooseDirection(c)
    }

    private fun save(c: Context, action: String, reason: String, direction: Int, skill: Int): TorchGameAgent.Decision {
        p(c).edit().putString("last_action", action).putString("last_reason", reason)
            .putLong("last_decision_at", System.currentTimeMillis()).apply()
        return TorchGameAgent.Decision(action, reason, direction, skill)
    }

    fun report(c: Context): String {
        val prefs = p(c)
        return "ROUTINE CONTROLLER\nCurrent action: " + (prefs.getString("last_action", "waiting") ?: "waiting") +
            "\nReason: " + (prefs.getString("last_reason", "No routine decision yet") ?: "No routine decision yet") +
            "\nHeld direction: " + prefs.getInt("held_direction", -1) +
            "\nThis is a stable heuristic routine, not a trained general intelligence."
    }
}
