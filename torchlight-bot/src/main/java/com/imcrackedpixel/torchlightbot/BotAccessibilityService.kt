package com.imcrackedpixel.torchlightbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var recording = false
    private var routeActions = JSONArray()
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null
    private lateinit var wm: WindowManager
    private var playIndex = 0
    private var playRoute = JSONArray()
    private var routeName = "Route"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW -> showControls()
                ACTION_RANDOM_PLAY -> playRandomRoute()
                ACTION_STOP -> stopBot()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val f = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_RANDOM_PLAY)
            addAction(ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, f)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopBot() }
    override fun onDestroy() {
        stopBot()
        removeControls()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun showControls() {
        if (overlay != null) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
            setBackgroundColor(0xEE202630.toInt())
        }
        statusView = TextView(this).apply {
            text = "TORCH ROUTES · idle"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
        }
        panel.addView(statusView)
        val rowTop = LinearLayout(this)
        rowTop.addView(Button(this).apply { text = "RECORD"; setOnClickListener { beginRecording() } })
        rowTop.addView(Button(this).apply { text = "SAVE"; setOnClickListener { saveRecording() } })
        rowTop.addView(Button(this).apply { text = "PLAY"; setOnClickListener { playLastRoute() } })
        panel.addView(rowTop)

        val upRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        upRow.addView(Button(this).apply { text = "↑"; setOnClickListener { manualMove("UP") } })
        panel.addView(upRow)
        val middle = LinearLayout(this).apply { gravity = Gravity.CENTER }
        middle.addView(Button(this).apply { text = "←"; setOnClickListener { manualMove("LEFT") } })
        middle.addView(Button(this).apply { text = "↓"; setOnClickListener { manualMove("DOWN") } })
        middle.addView(Button(this).apply { text = "→"; setOnClickListener { manualMove("RIGHT") } })
        panel.addView(middle)

        val skillRow = LinearLayout(this)
        skillRow.addView(Button(this).apply { text = "SKILL 1"; setOnClickListener { manualSkill(1) } })
        skillRow.addView(Button(this).apply { text = "SKILL 2"; setOnClickListener { manualSkill(2) } })
        skillRow.addView(Button(this).apply { text = "STOP"; setOnClickListener { stopBot() } })
        panel.addView(skillRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 12; y = 140 }
        overlay = panel
        try { wm.addView(panel, params) } catch (_: Exception) { overlay = null }
    }

    private fun removeControls() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun setStatus(s: String) { statusView?.text = "TORCH ROUTES · $s" }

    private fun beginRecording() {
        stopBot()
        routeActions = JSONArray()
        recording = true
        setStatus("recording")
    }

    private fun saveRecording() {
        recording = false
        if (routeActions.length() == 0) { setStatus("nothing recorded"); return }
        val routes = loadRoutes()
        val name = "Farm route ${routes.length() + 1}"
        routes.put(JSONObject().apply {
            put("id", System.currentTimeMillis().toString())
            put("name", name)
            put("createdAt", System.currentTimeMillis())
            put("runs", 0)
            put("wins", 0)
            put("actions", routeActions)
        })
        saveRoutes(routes)
        setStatus("saved $name · ${routeActions.length()} actions")
    }
}