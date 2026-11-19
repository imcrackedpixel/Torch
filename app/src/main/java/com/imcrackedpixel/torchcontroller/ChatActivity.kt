package com.imcrackedpixel.torchcontroller

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URLEncoder

class ChatActivity : Activity() {
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private val prefs by lazy { getSharedPreferences("torch_chat", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14) }
        root.addView(TextView(this).apply { text = "TORCH AI CHAT"; textSize = 22f; setTextColor(0xFF17212B.toInt()) })
        root.addView(TextView(this).apply {
            text = "Type questions or teach it something. Answers use local observations and saved notes. Skill research opens a live web search."
            textSize = 13f; setPadding(0, 6, 0, 10)
        })
        val scroll = ScrollView(this)
        transcript = TextView(this).apply {
            textSize = 15f; setTextColor(0xFF17212B.toInt()); setPadding(10, 10, 10, 10)
            text = prefs.getString("history", "").orEmpty().ifBlank { "Torch AI: Hi! Ask what I can see, ask about T1–T4, or tell me what to remember.\n\n" }
        }
        scroll.addView(transcript)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        input = EditText(this).apply { hint = "Type a message…"; minLines = 1; maxLines = 4; textSize = 16f }
        root.addView(input)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply { text = "SEND"; setOnClickListener { sendMessage() } }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply { text = "RESEARCH SKILLS"; setOnClickListener { openResearch() } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        root.addView(Button(this).apply { text = "CLEAR CHAT"; setOnClickListener {
            transcript.text = "Torch AI: Chat cleared. Saved teaching notes are kept.\n\n"; saveHistory()
        } })
        setContentView(root)
    }

    private fun sendMessage() {
        val message = input.text.toString().trim()
        if (message.isBlank()) return
        append("You: $message"); input.setText(""); append("Torch AI: " + answer(message))
    }

    private fun answer(message: String): String {
        val q = message.lowercase()
        val live = getSharedPreferences("live_teaching", MODE_PRIVATE)
        if (q.startsWith("remember ") || q.startsWith("learn ")) {
            val note = message.substringAfter(" ").trim()
            if (note.isNotBlank()) {
                val old = prefs.getString("player_notes", "").orEmpty()
                prefs.edit().putString("player_notes", (old + "\n• " + note).takeLast(5000)).apply()
                return "Saved on this phone: \"$note\". This is a teaching note; it does not yet change gameplay decisions automatically."
            }
        }
        if (q.contains("what do you see") || q.contains("what are you seeing") || q.contains("game state")) {
            return live.getString("brain_summary", null)
                ?: "I don't have a recent screen observation. Start Live Teach AI and allow screen capture first."
        }
        if (q.contains("cooldown") || q.contains("skill tree") || q.contains("support gem") || q.contains("talent")) {
            return "I don't have your character, skill levels, supports or tree imported yet. Tap RESEARCH SKILLS to search current Torchlight: Infinite skill and talent information. Verify the game version and each skill's own cooldown rules."
        }
        if (q.contains("play") || q.contains("attack") || q.contains("use t1") || q.contains("use t2")) {
            return "Open the floating controller, turn ON the skill buttons you want it to use, then press GO AI. It keeps running until you press STOP AI. It learns from limited visual evidence, but cannot reliably navigate or clear a map yet."
        }
        if (q.contains("stop")) return "Use STOP AI PLAY or STOP ALL on the floating controller to stop taps immediately."
        if (q.contains("notes")) return prefs.getString("player_notes", "").orEmpty().ifBlank { "No saved teaching notes yet. Start a message with 'remember …'." }
        val observation = live.getString("last_observation", "").orEmpty()
        return if (observation.isNotBlank()) "Recent screen note: $observation. I can discuss that evidence, but a full language-model connection is not configured yet. Use RESEARCH SKILLS for current online references."
        else "I'm ready to learn. Start screen recognition so I can use live game observations. Type 'remember …' to save a rule or tap RESEARCH SKILLS for online references."
    }

    private fun openResearch() {
        val query = "Torchlight Infinite current skill database skill cooldown support skills talent tree cooldown recovery speed"
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        append("Torch AI: Opened a live search for skill, support and talent information. Results are not automatically imported yet; check the game version.")
    }

    private fun append(line: String) { transcript.append(line + "\n\n"); saveHistory() }
    private fun saveHistory() { prefs.edit().putString("history", transcript.text.toString().takeLast(12000)).apply() }
}
