package com.imcrackedpixel.torchcontroller

import android.content.Context
import java.util.Locale

/**
 * Offline Torchlight: Infinite guide snapshot for SS14 Tartarus (Nov 2026).
 * Recommendations are conditional on OCR-visible hero, trait and progression.
 * This advisor never spends currency, resets talents, equips or sells items.
 */
object TorchlightProgressAdvisor {
    private const val SEASON = "SS14 · Tartarus"
    private val iris2Terms = listOf("iris", "vigilant breeze", "erosion magus", "summon erosion magus", "spirit magus")

    fun advice(context: Context, visibleText: String): String {
        val text = visibleText.lowercase(Locale.ROOT)
        val iris2 = iris2Terms.any { text.contains(it) }
        val stage = when {
            listOf("profound", "deep space", "timemark 8", "timemark 7", "timemark 6", "netherrealm", "t8", "t7").any { text.contains(it) } -> "Mapping / endgame"
            listOf("timemark 5", "t5", "chapter", "campaign", "act ", "quest", "level ").any { text.contains(it) } -> "Campaign / early mapping"
            else -> "Not confidently recognised"
        }
        val lines = mutableListOf<String>()
        lines += "TORCHLIGHT INFINITE · $SEASON"
        lines += "Detected stage: $stage"
        lines += ""
        if (iris2) {
            lines += "MATCHED CHARACTER: THE FORSAKEN · IRIS 2"
            lines += "Hero trait: Vigilant Breeze"
            lines += "Core loop: summon Spirit Magi → merge with a Magus → build Breeze stacks → use the Magus skills/Ultimate while keeping the summons alive."
            lines += "Important mechanic: while merged, Magi build Breeze over time, but consume Life when not Reconjuring. Life regeneration, Life regain and Magus Growth are build requirements, not optional luxury stats."
            lines += ""
            lines += "RECOMMENDED SS14 ROUTE"
            lines += "1. Campaign: start with the Fire or Frost Magus you can support with available skills and gear. Learn the merge / unmerge timing and keep your own defenses and recovery functional."
            lines += "2. Early maps: do not force an expensive endgame setup. Improve Spirit Magus levels, minion damage, Magus survival, Growth and Life recovery first."
            lines += "3. Around Timemark 5: Rock Magus is a documented alternative transition if your gear and preferred guide support it. Do not swap just because a screen mentions Rock Magus."
            lines += "4. Timemark 7 onward: the SS14 Erosion Magus route becomes a strong long-term option. Its main summon is Summon Erosion Magus; the Magus uses Scattered Mud, Withering Payback, Bleak Grass and World of Thorns."
            lines += ""
            lines += "EROSION MAGUS SETUP (GUIDE SNAPSHOT)"
            lines += "• Main active: Summon Erosion Magus."
            lines += "• T7 five-link shown in the guide: Harvest Time · Savage Growth · Friend of Spirit Magi · Servant Damage · Elemental Duo. Check actual support compatibility and available links before copying."
            lines += "• Utility: Dazzling Bloom to reposition Magi and focus a high-rarity target; Blink for movement; Life Tonic for emergency recovery."
            lines += "• Aura direction: Radical Order / Precise Radical Order when reservation permits."
            lines += "• Talent direction: God of Machines — Boss / Isomorphic Arms; Alchemist — Source; Machinist — sustain and Magus support; Lich — Off The Beaten Track. The published endgame example uses a 107-point allocation, so do not copy point totals before reaching that level."
            lines += "• Isomorphic Arms makes a well-crafted Wand or Tin Staff especially relevant to minion damage. Compare the actual affixes; do not assume every higher-level weapon is better."
            lines += "• Sustain: Nourishment of Life, Life regeneration / regain, Magus survival and Growth. Check mana reservation before adding another summon or aura."
            lines += "• Advanced Pactspirit: Scale of Original Sin is a requirement for the guide's finished Ultimate-focused version, not a campaign requirement. Its Scale of Judgment bonus needs any two of Strength, Dexterity and Intelligence to be equal; the example balances Strength = Dexterity while stacking Intelligence."
            lines += "• Advanced upgrades: Hero Memories, Magus Growth, Enhanced Skill chance, Ultimate damage and specialised Pactspirits come after the core summon, recovery and five-link work."
            lines += ""
            lines += "BUILD RATING / TRADE-OFF"
            lines += "Iris 2 Erosion Magus is listed A-tier in the SS14 build snapshot. It offers comfortable summon-led mapping, but the advanced version depends on Life recovery, Growth, specialised memories and the Scale of Original Sin attribute condition. It is not a free instant endgame setup."
            lines += ""
            lines += "WHAT THE BOT SHOULD CHECK"
            lines += "• Is the active hero trait actually Vigilant Breeze?"
            lines += "• Which Magus is summoned, and is it alive / being reconjured?"
            lines += "• Is the character losing Life during merge? If yes, prioritise sustain before more damage."
            lines += "• Does the main skill have the correct supports and enough links?"
            lines += "• Are mana reservation and support compatibility valid?"
            lines += "• Does the weapon improve the chosen minion scaling?"
            lines += "• Are Strength and Dexterity still equal if Scale of Original Sin is equipped?"
            lines += "• Is the current stage appropriate for the proposed item or talent?"
            lines += ""
            lines += "ALTERNATIVES"
            lines += "Fire / Frost Magus: accessible campaign starters. Rock Magus: an alternative mid-progression swap around TM5 in a dedicated guide. Erosion Magus: recommended mature SS14 route toward Profound. Choose one guide path at a time; do not mix support gems, talents or gear from unrelated variants."
        } else {
            lines += "HERO NOT CONFIRMED"
            lines += "Open the hero / trait screen so OCR can identify the character. Do not assume Iris 2 or change the selected hero."
            lines += "Known SS14 guide options:"
            lines += "• Iris 2 · Vigilant Breeze · Erosion Magus — A-tier summoner route, campaign to Profound."
            lines += "• Youga 3 · The Ultimate One · Homing Frost Bolt — S+ tier in one current-season tier list; endgame investment can be expensive."
            lines += "• Erika 1 · Wind Stalker · Thunder Spike — S-tier speed-mapping route."
            lines += "• Gemma 2 · Frostbitten Heart · Chromatic Shot / Cold Tangle — A-tier option."
            lines += "• Thea 1 · Wisdom of the Gods · Split Lightning Bolt — current-season starter route."
        }
        lines += ""
        lines += "GENERAL PROGRESSION RULES"
        lines += "1. Keep one main damage plan. Match supports to the skill's tags and damage type."
        lines += "2. Upgrade the main damage source only when the affixes support the chosen build."
        lines += "3. Follow the selected guide's major talents in its stage order; do not blindly respec to an endgame tree while levelling."
        lines += "4. Balance damage with Life / Energy Shield, resistances, recovery and mobility. If dying, repair defenses before chasing DPS."
        lines += "5. Compare gear affixes, links, reservation and build synergy—not item level alone."
        lines += "6. Delay expensive Pactspirits, Hero Memories, slates and perfect crafting until the guide stage actually needs them."
        lines += ""
        lines += "AUTOPLAY SAFETY: observe and suggest; do not spend currency, reset talents, equip or sell items automatically. OCR can misread icons and stats, so ask for a clearer screen rather than guessing."
        lines += "Knowledge is an offline SS14 snapshot, not live market data. Verify patch/guide version before expensive decisions."
        val report = lines.joinToString("\n")
        context.getSharedPreferences("torchlight_advisor", Context.MODE_PRIVATE).edit()
            .putString("last_advice", report)
            .putString("last_hero", if (iris2) "Iris 2 · Vigilant Breeze" else "")
            .putString("last_skill", if (iris2) "Summon Erosion Magus" else "")
            .putString("last_stage", stage).apply()
        return report
    }
}
