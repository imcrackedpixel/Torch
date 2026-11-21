package com.imcrackedpixel.torchlightbot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("route_library", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "RAGNAROK CHROME FARMER"
            textSize = 26f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "For your Ragnarok browser server: open the game in Chrome fullscreen, enable Accessibility, then open the floating controls and press START RO FARM. It taps the crossed-swords attack control, checks the minimap for movement, taps the F3 Fly Wing after five seconds without visible minimap change, and uses the red potion when the HP bar appears low."
            textSize = 14f
            setPadding(0, 12, 0, 16)
        })
        root.addView(Button(this).apply {
            text = "1 · ENABLE ACCESSIBILITY"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2 · OPEN FLOATING CONTROLS"
            setOnClickListener {
                sendBroadcast(Intent(BotAccessibilityService.ACTION_SHOW).setPackage(packageName))
                status.text = "Floating controls requested. Switch to Torchlight and use the on-screen panel."
            }
        })
        root.addView(Button(this).apply {
            text = "OPEN FARM CONTROLS"
            setOnClickListener {
                sendBroadcast(Intent(BotAccessibilityService.ACTION_RANDOM_PLAY).setPackage(packageName))
                status.text = "Floating controls requested. Switch to Chrome and press START RO FARM."
            }
        })
        root.addView(Button(this).apply {
            text = "STOP BOT"
            setOnClickListener {
                sendBroadcast(Intent(BotAccessibilityService.ACTION_STOP).setPackage(packageName))
                status.text = "Stop requested."
            }
        })
        root.addView(Button(this).apply {
            text = "EXPORT ROUTE LIBRARY"
            setOnClickListener {
                val data = prefs.getString("routes", "[]") ?: "[]"
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "torch-route-library.json")
                }
                exportData = data
                @Suppress("DEPRECATION")
                startActivityForResult(i, 7401)
            }
        })
        root.addView(Button(this).apply {
            text = "IMPORT ROUTE LIBRARY"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(i, 7402)
            }
        })
        status = TextView(this).apply {
            text = "Ready. Enable Accessibility, open Chrome fullscreen with the Ragnarok game, open floating controls, then press START RO FARM. Keep the game visible and supervise it."
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "This is an experimental screen-coordinate bot calibrated to the supplied screenshot. Attack, potion and F3 coordinates may need adjustment if the browser layout or zoom changes. Minimap difference is only a rough stuck detector; it does not identify monsters or guarantee safe movement. Keep the game visible and supervise it."
            textSize = 13f
            setPadding(0, 12, 0, 12)
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private var exportData = "[]"

    @Deprecated("Compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        if (requestCode == 7401) {
            contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use { it.write(exportData) }
            status.text = "Route library exported."
        } else if (requestCode == 7402) {
            val imported = contentResolver.openInputStream(data.data!!)?.bufferedReader()?.use { it.readText() } ?: return
            try {
                val array = org.json.JSONArray(imported)
                getSharedPreferences("route_library", MODE_PRIVATE).edit().putString("routes", array.toString()).apply()
                status.text = "Imported ${array.length()} routes."
            } catch (_: Exception) {
                status.text = "Import failed: file is not a valid route library."
            }
        }
    }
}