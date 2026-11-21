package com.imcrackedpixel.torchlightbot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "RAGNAROK TAP BOT"
            textSize = 25f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "A simple screen overlay for your browser game. Enable the accessibility service, open the game in Chrome fullscreen, then show the three circles and drag each onto the matching game button."
            textSize = 15f
            setPadding(0, 18, 0, 16)
        })
        root.addView(Button(this).apply {
            text = "1 · ENABLE ACCESSIBILITY"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2 · SHOW ATTACK / F3 / F6 CIRCLES"
            setOnClickListener {
                sendBroadcast(Intent(BotAccessibilityService.ACTION_SHOW).setPackage(packageName))
            }
        })
        root.addView(Button(this).apply {
            text = "3 · START BOT"
            setOnClickListener {
                sendBroadcast(Intent(BotAccessibilityService.ACTION_FARM).setPackage(packageName))
            }
        })
        root.addView(Button(this).apply {
            text = "STOP BOT"
            setOnClickListener {
                sendBroadcast(Intent(BotAccessibilityService.ACTION_STOP).setPackage(packageName))
            }
        })
        root.addView(TextView(this).apply {
            text = "How it behaves\n• ATK: taps the attack button about every 1.6 seconds.\n• F3: taps after 5 seconds without a red combat cue (10-second cooldown).\n• F6: checks for a low blue HP bar in the upper-left HUD and taps with a 5-second cooldown.\n\nThe circles are draggable and remember their positions. Use + SKILL on the overlay to add up to eight extra S1–S8 tap buttons for future skills; drag each onto the matching game skill and tap it to test. Keep Chrome and the game visible while it runs. Screen recognition is approximate and must be checked in your game before leaving it unattended."
            textSize = 14f
            setPadding(0, 20, 0, 12)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
