package com.imcrackedpixel.torchcontroller

import android.content.Context
import java.util.Locale

/**
 * Offline Torchlight: Infinite progression coach.
 * Knowledge snapshot: Season 14 Tartarus (Nov 2026). Advice is conditional on
 * the visible hero/trait and never spends currency or changes equipment itself.
 */
object TorchlightProgressAdvisor {
    private const val SEASON = "Season 14 · Tartarus"
    private data class Build(
        val hero: String, val trait: String, val skill: String, val rating: String,
        val route: String, val focus: String, val caveat: String
    )
    private val builds = listOf(
        Build("Youga 3", "The Ultimate One / Clockwork Core", "Homing Frost Bolt", "S+ early-season all-round standout",
            "Starter into Netherrealm and high-end scaling", "Core placement, cold/projectile spell scaling, matching support links, and enough defenses to stay mobile",
            "Popular and strong, but some late-game items are expensive; do not copy another Youga variant's gear."),
        Build("Erika 1", "Wind Stalker", "Thunder Spike", "S-tier established speed-mapping route",
            "Campaign to Deep Space, with a staged guide", "Attack/weapon scaling that matches Thunder Spike, attack uptime, mobility, then defenses",
            "A fast mapper still needs enough damage to kill elites; do not trade away all survivability."),
        Build("Thea 1", "Wisdom of the Gods", "Split Lightning Bolt", "Popular current-season starter guide",
            "League-start outline into early progression", "Lightning spell scaling, support links that match the skill tags, and the guide's blessing-focused trait choices",
            "The build guide is popular, but check its exact stage before buying expensive items."),
        Build("Gemma 2", "Frostbitten Heart", "Cold Tangle Chromatic Shot", "Current-season creator guide",
            "Campaign and early endgame progression", "Cold damage and projectile/skill-tag synergy; follow the guide's skill and support setup",
            "Do not mix support gems or gear from a different Chromatic Shot variant.")
    )

    fun advice(context: Context, visibleText: String): String {
        val text = visibleText.lowercase(Locale.ROOT)
        val build = builds.firstOrNull { b ->
            text.contains(b.hero.lowercase(Locale.ROOT)) ||
                (b.hero == "Erika 1" && text.contains("wind stalker")) ||
                (b.hero == "Thea 1" && text.contains("wisdom of the gods")) ||
                (b.hero == "Youga 3" && text.contains("clockwork core")) ||
                (b.hero == "Gemma 2" && text.contains("frostbitten heart"))
        }
        val stage = when {
            listOf("deep space", "timemark 8", "timemark 7", "ss20", "netherrealm").any { text.contains(it) } -> "Endgame / Netherrealm"
            listOf("chapter", "campaign", "act ", "quest", "level ").any { text.contains(it) } -> "Campaign / levelling"
            else -> "Stage not confidently recognised from the screen"
        }
        val lines = mutableListOf<String>()
        lines += "TORCHLIGHT INFINITE · $SEASON"
        lines += "Detected progression: $stage"
        if (build != null) {
            lines += "MATCHED BUILD: ${build.hero} · ${build.trait}"
            lines += "Main skill: ${build.skill}"
            lines += "Community/tier context: ${build.rating}"
            lines += "Progress route: ${build.route}"
            lines += "Priorities: ${build.focus}"
            lines += "Caution: ${build.caveat}"
        } else {
            lines += "Hero/trait not readable yet; do not assume or switch the character."
            lines += "Current well-documented options:"
            builds.forEach { lines += "• ${it.hero}: ${it.skill} — ${it.rating}" }
            lines += "Best general recommendation: identify the hero trait first, then follow ONE matching build from campaign to endgame."
        }
        lines += ""
        lines += "UPGRADE ORDER"
        lines += "1. Keep one main damage skill and use support gems that match its tags and damage type."
        lines += "2. Upgrade the weapon / main damage source when the replacement clearly improves the chosen skill."
        lines += "3. Fill the recommended talent panels in the build order; prioritise core damage mechanics, then reliable defenses."
        lines += "4. Keep life/energy shield, resistances, recovery and mobility adequate for the content. If dying often, fix defenses before chasing more damage."
        lines += "5. Replace gear by comparing affixes to the build's actual scaling (damage type, attack/spell, crit or damage-over-time); do not equip higher item level blindly."
        lines += "6. Treat Pactspirits, slates, expensive uniques and endgame min-maxing as later steps unless the guide's current stage explicitly needs them."
        lines += ""
        lines += "BOT RULES: observe character/skill/talent/gear screens; compare visible choices with the matched guide; explain the best next upgrade. Never spend currency, reset talents, equip or sell items automatically."
        lines += "Build data is a season snapshot, not live game telemetry. Verify the guide's season and stage before costly decisions."
        val report = lines.joinToString("\n")
        context.getSharedPreferences("torchlight_advisor", Context.MODE_PRIVATE).edit()
            .putString("last_advice", report).putString("last_hero", build?.hero ?: "")
            .putString("last_skill", build?.skill ?: "").putString("last_stage", stage).apply()
        return report
    }
}
