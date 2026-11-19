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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.app.AlertDialog
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
        val nameInput = EditText(this).apply { setText("Farm route ${System.currentTimeMillis() % 10000}") }
        AlertDialog.Builder(this)
            .setTitle("Save recorded route")
            .setView(nameInput)
            .setPositiveButton("SAVE") { _, _ ->
                val routes = loadRoutes()
                routes.put(JSONObject().apply {
                    put("id", System.currentTimeMillis().toString())
                    put("name", nameInput.text.toString().ifBlank { "Farm route" })
                    put("createdAt", System.currentTimeMillis())
                    put("runs", 0)
                    put("wins", 0)
                    put("actions", routeActions)
                })
                saveRoutes(routes)
                setStatus("saved ${routeActions.length()} actions")
            }
            .setNegativeButton("CANCEL") { _, _ -> setStatus("recording paused") }
            .show()
    }

    private fun manualMove(direction: String) {
        if (active) return
        val duration = 650L
        val action = JSONObject().put("type", "move").put("direction", direction).put("duration", duration)
        if (recording) routeActions.put(action)
        executeMove(direction, duration) { setStatus(if (recording) "recording · ${routeActions.length()} actions" else "manual move") }
    }

    private fun manualSkill(slot: Int) {
        if (active) return
        if (recording) routeActions.put(JSONObject().put("type", "skill").put("slot", slot))
        tapSkill(slot)
        setStatus(if (recording) "recording · ${routeActions.length()} actions" else "skill $slot")
    }

    private fun playLastRoute() {
        val routes = loadRoutes()
        if (routes.length() == 0) { setStatus("no saved routes"); return }
        val r = routes.getJSONObject(routes.length() - 1)
        startPlayback(r)
    }

    private fun playRandomRoute() {
        val routes = loadRoutes()
        if (routes.length() == 0) { showControls(); setStatus("record a route first"); return }
        val candidates = (0 until routes.length()).map { routes.getJSONObject(it) }
        val chosen = candidates[Random.nextInt(candidates.size)]
        startPlayback(chosen)
    }

    private fun startPlayback(route: JSONObject) {
        if (active) stopBot()
        recording = false
        routeName = route.optString("name", "Route")
        playRoute = route.optJSONArray("actions") ?: JSONArray()
        if (playRoute.length() == 0) { setStatus("empty route"); return }
        active = true
        playIndex = 0
        setStatus("playing $routeName")
        handler.post(playStep)
    }

    private val playStep = object : Runnable {
        override fun run() {
            if (!active) return
            if (playIndex >= playRoute.length()) {
                active = false
                updateRouteRun(routeName)
                setStatus("route finished · $routeName")
                return
            }
            val a = playRoute.optJSONObject(playIndex) ?: run { playIndex++; handler.post(this); return }
            playIndex++
            when (a.optString("type")) {
                "move" -> executeMove(a.optString("direction", "UP"), a.optLong("duration", 650L).coerceIn(250L, 2500L)) {
                    handler.postDelayed(this, 250L)
                }
                "skill" -> { tapSkill(a.optInt("slot", 1)); handler.postDelayed(this, 500L) }
                else -> handler.post(this)
            }
            setStatus("playing $routeName · $playIndex/${playRoute.length()}")
        }
    }

    private fun updateRouteRun(name: String) {
        val routes = loadRoutes()
        for (i in 0 until routes.length()) {
            val r = routes.optJSONObject(i) ?: continue
            if (r.optString("name") == name) {
                r.put("runs", r.optInt("runs") + 1)
                break
            }
        }
        saveRoutes(routes)
    }

    private fun stopBot() {
        active = false
        handler.removeCallbacks(playStep)
        recording = false
        setStatus("stopped")
    }

    private fun executeMove(direction: String, duration: Long, done: () -> Unit) {
        val prefs = getSharedPreferences("bot_config", MODE_PRIVATE)
        val xPct = (prefs.getString("joy_x", "15")?.toFloatOrNull() ?: 15f).coerceIn(0f, 100f)
        val yPct = (prefs.getString("joy_y", "82")?.toFloatOrNull() ?: 82f).coerceIn(0f, 100f)
        val dm = resources.displayMetrics
        val cx = dm.widthPixels * xPct / 100f
        val cy = dm.heightPixels * yPct / 100f
        val radius = dm.widthPixels * 0.075f
        val vector = when (direction) {
            "LEFT" -> Pair(-1f, 0f); "RIGHT" -> Pair(1f, 0f)
            "DOWN" -> Pair(0f, 1f); else -> Pair(0f, -1f)
        }
        val path = Path().apply { moveTo(cx, cy); lineTo(cx + vector.first * radius, cy + vector.second * radius) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, duration)
        ).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done() }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (active) handler.postDelayed(done, 700L)
            }
        }, null)
    }

    private fun tapSkill(slot: Int) {
        val prefs = getSharedPreferences("bot_config", MODE_PRIVATE)
        val xKey = if (slot == 1) "s1_x" else "s2_x"
        val yKey = if (slot == 1) "s1_y" else "s2_y"
        val defaultX = if (slot == 1) 78f else 88f
        val defaultY = if (slot == 1) 82f else 72f
        val dm = resources.displayMetrics
        val x = dm.widthPixels * ((prefs.getString(xKey, defaultX.toString())?.toFloatOrNull() ?: defaultX).coerceIn(0f, 100f)) / 100f
        val y = dm.heightPixels * ((prefs.getString(yKey, defaultY.toString())?.toFloatOrNull() ?: defaultY).coerceIn(0f, 100f)) / 100f
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        dispatchGesture(gesture, null, null)
    }

    private fun loadRoutes(): JSONArray = try {
        JSONArray(getSharedPreferences("route_library", MODE_PRIVATE).getString("routes", "[]"))
    } catch (_: Exception) { JSONArray() }

    private fun saveRoutes(routes: JSONArray) {
        getSharedPreferences("route_library", MODE_PRIVATE).edit().putString("routes", routes.toString()).apply()
    }

    companion object {
        const val ACTION_SHOW = "com.imcrackedpixel.torchlightbot.SHOW"
        const val ACTION_RANDOM_PLAY = "com.imcrackedpixel.torchlightbot.RANDOM_PLAY"
        const val ACTION_STOP = "com.imcrackedpixel.torchlightbot.STOP"
    }
}