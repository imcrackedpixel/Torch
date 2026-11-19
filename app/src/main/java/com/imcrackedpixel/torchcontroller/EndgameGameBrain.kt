package com.imcrackedpixel.torchcontroller

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Offline game decision layer combining general game heuristics with local experience. */
object EndgameGameBrain {
    private const val PREFS = "torch_endgame_brain"
    private const val MAX_EXPERIENCES = 240
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun arr(v: String?) = try { JSONArray(v ?: "[]") } catch (_: Exception) { JSONArray() }
    private fun obj(v: String?) = try { JSONObject(v ?: "{}") } catch (_: Exception) { JSONObject() }

    fun observe(c: Context, lines: List<String>, cues: List<String>, objects: List<String>, width: Int, height: Int) {
        val p = prefs(c)
        val text = (lines.joinToString(" ") + " " + cues.joinToString(" ")).lowercase(Locale.ROOT)
        val profile = profileFor(text)
        val phase = when {
            listOf("game over", "you died", "defeat", "respawn").any(text::contains) -> "RECOVERY"
            listOf("victory", "stage clear", "mission complete", "quest complete", "level complete").any(text::contains) -> "POSSIBLE COMPLETION"
            listOf("inventory", "settings", "skill tree", "equipment", "paused", "main menu", "options").any(text::contains) -> "MENU"
            listOf("boss", "enemy", "monster", "elite").any(text::contains) || cues.any { it.contains("enemy", true) || it.contains("combat", true) } -> "POSSIBLE COMBAT"
            else -> "EXPLORATION / UNKNOWN"
        }
        val objective = lines.firstOrNull { line ->
            val l = line.lowercase(Locale.ROOT)
            listOf("quest", "objective", "defeat", "kill", "collect", "find", "talk to", "speak to", "return to", "reach", "deliver", "activate", "rescue", "investigate").any(l::contains)
        }?.trim()?.take(220).orEmpty()
        val key = (profile + phase + objective + text.take(300)).hashCode().toString()
        val history = arr(p.getString("scene_memory", "[]"))
        if (p.getString("last_scene_key", "") != key) {
            history.put(JSONObject().apply { put("time", System.currentTimeMillis()); put("profile", profile); put("phase", phase); put("objective", objective); put("object_count", objects.size); put("screen", text.take(300)) })
            while (history.length() > 180) history.remove(0)
        }
        val objectives = arr(p.getString("objective_memory", "[]"))
        if (objective.isNotBlank() && (objectives.length() == 0 || objectives.optJSONObject(objectives.length() - 1)?.optString("text") != objective)) {
            objectives.put(JSONObject().apply { put("time", System.currentTimeMillis()); put("text", objective); put("status", "unverified candidate") })
            while (objectives.length() > 80) objectives.remove(0)
        }
        p.edit().putString("last_scene_key", key).putString("profile", profile).putString("phase", phase)
            .putString("objective", objective.ifBlank { p.getString("objective", "") ?: "" })
            .putString("scene_memory", history.toString()).putString("objective_memory", objectives.toString())
            .putInt("observations", p.getInt("observations", 0) + 1).putLong("last_observation", System.currentTimeMillis()).apply()
    }

    fun plan(c: Context, phase: String, state: String, text: String, enemyBars: Int, skills: List<Int>): TorchGameAgent.Decision {
        val p = prefs(c)
        val lower = (phase + " " + state + " " + text).lowercase(Locale.ROOT)
        val profile = p.getString("profile", profileFor(lower)) ?: profileFor(lower)
        val mode = when {
            phase.contains("RECOVERY", true) || listOf("game over", "you died", "respawn", "connection lost").any(lower::contains) -> "RECOVERY"
            phase.contains("MENU", true) || listOf("inventory", "settings", "skill tree", "equipment", "paused", "main menu").any(lower::contains) -> "MENU"
            phase.contains("COMPLETION", true) || listOf("victory", "stage clear", "mission complete", "quest complete", "level complete").any(lower::contains) -> "POSSIBLE COMPLETION"
            enemyBars > 0 || phase.contains("COMBAT", true) || listOf("boss", "enemy", "monster", "elite").any(lower::contains) -> "COMBAT"
            else -> "EXPLORATION"
        }
        val direction = TorchGameAgent.chooseDirection(c)
        if (mode == "RECOVERY" || mode == "MENU" || mode == "POSSIBLE COMPLETION")
            return decide(c, "WAIT", mode + " detected · wait for a safe gameplay screen", direction, -1)

        val noProgress = p.getInt("no_progress", 0)
        if (listOf("low health", "critical health", "health critical", "almost dead", "retreat", "danger").any(lower::contains)) {
            val recent = arr(c.getSharedPreferences("torch_ai_brain", Context.MODE_PRIVATE).getString("recent_move_directions", "[]"))
            val last = recent.optInt(recent.length() - 1, -1)
            val retreat = if (last in 0..7) (last + 4) % 8 else direction
            return decide(c, "MOVE", "Possible danger cue · disengage and reassess", retreat, -1)
        }

        val interaction = listOf("tap to collect", "press to collect", "talk to npc", "speak to npc", "open chest", "accept quest", "claim reward", "enter portal", "tap to interact", "press to interact", "interact").any(lower::contains)
        if (interaction && p.getInt("steps_since_interaction", 0) >= 2) {
            p.edit().putInt("steps_since_interaction", 0).apply()
            return decide(c, "INTERACT", "Specific interaction cue · test once, then verify the result", direction, -1)
        }

        val count = p.getInt("decision_count", 0)
        if (mode == "COMBAT" && skills.isNotEmpty() && (count % 3 == 2 || noProgress >= 5)) {
            val selected = chooseSkill(c, skills)
            p.edit().putInt("decision_count", count + 1).apply()
            return decide(c, "SKILL", "Combat detected · test a learned or under-tested skill, then inspect the result", direction, selected)
        }

        p.edit().putInt("decision_count", count + 1).putInt("steps_since_interaction", p.getInt("steps_since_interaction", 0) + 1).apply()
        val mapDirection = MinimapNavigation.suggestedDirection(c)
        val chosen = mapDirection ?: direction
        val reason = when {
            mapDirection != null -> "Follow minimap target bearing · verify movement after the gesture"
            noProgress >= 5 -> "Repeated no-progress evidence · test a different route"
            mode == "COMBAT" -> "Combat detected · reposition between skill tests"
            else -> profile + " · explore a less-tested route and watch for objective changes"
        }
        return decide(c, "MOVE", reason, chosen, -1)
    }

    private fun decide(c: Context, action: String, reason: String, direction: Int, skill: Int): TorchGameAgent.Decision {
        prefs(c).edit().putString("last_decision", action).putString("last_reason", reason).putLong("decision_at", System.currentTimeMillis()).apply()
        return TorchGameAgent.Decision(action, reason, direction, skill)
    }

    private fun chooseSkill(c: Context, skills: List<Int>): Int {
        val scores = obj(prefs(c).getString("skill_scores", "{}"))
        val untested = skills.filter { (scores.optJSONObject((it + 1).toString())?.optInt("trials", 0) ?: 0) < 2 }
        if (untested.isNotEmpty()) return untested.minByOrNull { scores.optJSONObject((it + 1).toString())?.optInt("trials", 0) ?: 0 } ?: untested.first()
        return skills.maxByOrNull { index ->
            val item = scores.optJSONObject((index + 1).toString()) ?: JSONObject()
            val trials = item.optInt("trials", 0).coerceAtLeast(1)
            item.optDouble("reward_sum", 0.0) / trials - item.optInt("recent_failures", 0) * 0.12
        } ?: skills.first()
    }

    fun recordResult(c: Context, action: String, key: String, beforeText: String, afterText: String, beforeBars: Int = -1, afterBars: Int = -1, minimapMoved: Boolean? = null) {
        val p = prefs(c)
        val scores = obj(p.getString("action_scores", "{}"))
        val scoreKey = action + ":" + key
        val item = scores.optJSONObject(scoreKey) ?: JSONObject()
        val changed = beforeText.isNotBlank() && afterText.isNotBlank() && beforeText != afterText
        val reward: Double? = when {
            action == "MOVE" && minimapMoved == true -> 1.0
            action == "MOVE" && minimapMoved == false -> -0.7
            action == "SKILL" && beforeBars > 0 && afterBars in 0 until beforeBars -> 1.0
            action == "SKILL" && beforeBars > 0 && afterBars > beforeBars -> -0.4
            action == "INTERACT" && changed -> 0.45
            changed -> 0.15
            else -> null
        }
        item.put("trials", item.optInt("trials", 0) + 1)
        if (reward != null) {
            item.put("reward_sum", item.optDouble("reward_sum", 0.0) + reward)
            item.put("last_reward", reward)
        }
        item.put("last_time", System.currentTimeMillis())
        item.put("last_evidence", when {
            minimapMoved == true -> "minimap marker candidate moved"
            minimapMoved == false -> "minimap marker candidate did not move"
            beforeBars > 0 && afterBars in 0 until beforeBars -> "candidate enemy bar count decreased"
            changed -> "visible text changed; weak correlation only"
            else -> "no reliable visible result"
        })
        scores.put(scoreKey, item)
        val replay = arr(p.getString("experience_replay", "[]"))
        replay.put(JSONObject().apply {
            put("time", System.currentTimeMillis()); put("action", action); put("key", key)
            put("reward", reward ?: JSONObject.NULL); put("before", beforeText.take(160)); put("after", afterText.take(160))
            put("before_bars", beforeBars); put("after_bars", afterBars); put("minimap_moved", minimapMoved ?: JSONObject.NULL)
        })
        while (replay.length() > MAX_EXPERIENCES) replay.remove(0)
        if (action == "MOVE") {
            val streak = if (minimapMoved == false) (p.getInt("no_progress", 0) + 1).coerceAtMost(30)
                else if (minimapMoved == true) 0 else p.getInt("no_progress", 0)
            p.edit().putInt("no_progress", streak).apply()
        }
        if (action == "SKILL") {
            val skillScores = obj(p.getString("skill_scores", "{}"))
            val skill = skillScores.optJSONObject(key) ?: JSONObject()
            skill.put("trials", skill.optInt("trials", 0) + 1)
            if (reward != null) skill.put("reward_sum", skill.optDouble("reward_sum", 0.0) + reward)
            if (reward != null && reward < 0) skill.put("recent_failures", (skill.optInt("recent_failures", 0) + 1).coerceAtMost(20))
            else if (reward != null && reward > 0) skill.put("recent_failures", (skill.optInt("recent_failures", 0) - 1).coerceAtLeast(0))
            skillScores.put(key, skill)
            p.edit().putString("skill_scores", skillScores.toString()).apply()
        }
        p.edit().putString("action_scores", scores.toString()).putString("experience_replay", replay.toString())
            .putInt("experiments", p.getInt("experiments", 0) + 1).apply()
    }

    fun report(c: Context): String {
        val p = prefs(c)
        return "ENDGAME GAME BRAIN\nProfile: " + (p.getString("profile", "not identified") ?: "not identified") +
            "\nObserved scenes: " + arr(p.getString("scene_memory", "[]")).length() +
            " · candidate objectives: " + arr(p.getString("objective_memory", "[]")).length() +
            "\nExperiments: " + p.getInt("experiments", 0) +
            " · recent experience samples: " + arr(p.getString("experience_replay", "[]")).length() +
            " · no-progress streak: " + p.getInt("no_progress", 0) +
            "\nLast decision: " + (p.getString("last_decision", "none") ?: "none") + " · " + (p.getString("last_reason", "waiting") ?: "waiting") +
            "\nRewards favour minimap movement and candidate enemy-bar changes over OCR text changes. This is an offline heuristic learner, not a trained general intelligence."
    }

    private fun profileFor(text: String): String = when {
        listOf("torchlight", "netherrealm", "timemark", "pactspirit", "magus", "iris").any(text::contains) -> "Torchlight-style action RPG"
        listOf("health potion", "boss", "inventory", "quest", "loot", "skill").count(text::contains) >= 3 -> "Action RPG"
        listOf("lap", "race", "speedometer", "nitro", "checkpoint").any(text::contains) -> "Racing game"
        listOf("jump", "platform", "checkpoint", "double jump").count(text::contains) >= 2 -> "Platform game"
        listOf("match", "swap", "combo", "level objective").count(text::contains) >= 2 -> "Puzzle / match game"
        else -> "Unclassified mobile game"
    }
}
