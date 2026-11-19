package com.imcrackedpixel.torchlightbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class BotAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executor { command -> handler.post(command) }
    private var recording = false
    private var playing = false
    private var eliteMode = false
    private var routeActions = JSONArray()
    private val eliteSamples = JSONArray()
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null
    private lateinit var wm: WindowManager
    private var playIndex = 0
    private var playRoute = JSONArray()
    private var routeName = "Map"
    private var lastActionAt = 0L
    private var sampleIndex = 0
    private val routePrefs by lazy { getSharedPreferences("route_library", MODE_PRIVATE) }

    companion object {
        const val ACTION_SHOW = "com.imcrackedpixel.torchlightbot.SHOW"
        const val ACTION_RANDOM_PLAY = "com.imcrackedpixel.torchlightbot.RANDOM_PLAY"
        const val ACTION_STOP = "com.imcrackedpixel.torchlightbot.STOP"
        private const val MOVE_MS = 650L
    }

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
            addAction(ACTION_SHOW); addAction(ACTION_RANDOM_PLAY); addAction(ACTION_STOP)
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
            setPadding(10, 10, 10, 10)
            setBackgroundColor(0xEE18212B.toInt())
        }
        statusView = TextView(this).apply {
            text = "TORCH MAP FARMER · READY"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 12f
        }
        panel.addView(statusView)
        val top = LinearLayout(this)
        top.addView(makeButton("RECORD MAP") { beginRecording() })
        top.addView(makeButton("SAVE MAP") { saveRecording() })
        panel.addView(top)
        val second = LinearLayout(this)
        second.addView(makeButton("PLAY MAP") { playLastRoute() })
        second.addView(makeButton("RANDOM MAP") { playRandomRoute() })
        panel.addView(second)
        val eliteRow = LinearLayout(this)
        eliteRow.addView(makeButton("ELITE SPOTTING: OFF") {
            eliteMode = !eliteMode
            it.text = if (eliteMode) "ELITE SPOTTING: ON" else "ELITE SPOTTING: OFF"
            setStatus(if (eliteMode) "elite spotting on · tag an elite when visible" else "elite spotting off")
        })
        eliteRow.addView(makeButton("TAG ELITE") { captureEliteSample() })
        panel.addView(eliteRow)
        panel.addView(TextView(this).apply {
            text = "Use these direction buttons to record. TAG ELITE saves a visual example."
            setTextColor(0xFFCFD8E3.toInt()); textSize = 10f
        })
        val up = LinearLayout(this).apply { gravity = Gravity.CENTER }
        up.addView(makeButton("↑") { manualMove("UP") })
        panel.addView(up)
        val middle = LinearLayout(this).apply { gravity = Gravity.CENTER }
        middle.addView(makeButton("←") { manualMove("LEFT") })
        middle.addView(makeButton("↓") { manualMove("DOWN") })
        middle.addView(makeButton("→") { manualMove("RIGHT") })
        panel.addView(middle)
        val bottom = LinearLayout(this)
        bottom.addView(makeButton("STOP") { stopBot() })
        bottom.addView(makeButton("HIDE") { removeControls() })
        panel.addView(bottom)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 8; y = 90 }
        overlay = panel
        try { wm.addView(panel, params) } catch (_: Exception) { overlay = null }
    }

    private fun makeButton(label: String, action: (Button) -> Unit): Button =
        Button(this).apply { text = label; textSize = 10f; setPadding(5, 2, 5, 2); setOnClickListener { action(this) } }

    private fun removeControls() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun setStatus(s: String) { statusView?.text = "TORCH MAP FARMER · " + s }

    private fun beginRecording() {
        stopBot()
        routeActions = JSONArray()
        while (eliteSamples.length() > 0) eliteSamples.remove(0)
        recording = true
        lastActionAt = System.currentTimeMillis()
        setStatus("recording map · use floating directions")
    }

    private fun saveRecording() {
        recording = false
        if (routeActions.length() == 0) { setStatus("no movement recorded"); return }
        val routes = loadRoutes()
        val name = "Map " + (routes.length() + 1)
        val route = JSONObject().apply {
            put("id", System.currentTimeMillis().toString())
            put("name", name)
            put("createdAt", System.currentTimeMillis())
            put("runs", 0); put("wins", 0)
            put("actions", routeActions)
            put("eliteSamples", eliteSamples)
            put("visualRecording", true)
        }
        routes.put(route)
        saveRoutes(routes)
        setStatus("saved " + name + " · " + routeActions.length() + " moves · " + eliteSamples.length() + " elite samples")
    }

    private fun manualMove(direction: String) {
        if (playing) return
        if (recording) {
            val now = System.currentTimeMillis()
            routeActions.put(JSONObject().apply {
                put("type", "move"); put("direction", direction)
                put("durationMs", MOVE_MS); put("gapMs", max(0L, now - lastActionAt - MOVE_MS))
            })
            lastActionAt = now
            captureRouteFrame(routeActions.length() - 1)
        }
        performMove(direction, MOVE_MS) {
            setStatus(if (recording) "recording · " + routeActions.length() + " moves" else "manual move " + direction)
        }
    }

    private fun performMove(direction: String, duration: Long, done: () -> Unit = {}) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val cx = w * 0.135f
        val cy = h * 0.805f
        val radius = min(w, h) * 0.075f
        val delta = when (direction) {
            "UP" -> 0f to -radius
            "DOWN" -> 0f to radius
            "LEFT" -> -radius to 0f
            else -> radius to 0f
        }
        val path = Path().apply { moveTo(cx, cy); lineTo(cx + delta.first, cy + delta.second) }
        val stroke = GestureDescription.StrokeDescription(path, 0, max(150L, duration))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { done() }
                override fun onCancelled(gestureDescription: GestureDescription?) { setStatus("movement cancelled") }
            }, null)
    }

    private fun playLastRoute() {
        val routes = loadRoutes()
        if (routes.length() == 0) { setStatus("record a map first"); return }
        startRoute(routes.getJSONObject(routes.length() - 1))
    }

    private fun playRandomRoute() {
        val routes = loadRoutes()
        if (routes.length() == 0) { setStatus("no saved maps yet"); return }
        startRoute(routes.getJSONObject(Random.nextInt(routes.length())))
    }

    private fun startRoute(route: JSONObject) {
        stopBot()
        routeName = route.optString("name", "Saved map")
        playRoute = route.optJSONArray("actions") ?: JSONArray()
        if (playRoute.length() == 0) { setStatus("map has no recorded moves"); return }
        playIndex = 0
        playing = true
        setStatus("running " + routeName)
        playNext()
    }

    private fun playNext() {
        if (!playing) return
        if (playIndex >= playRoute.length()) {
            playing = false
            setStatus("route finished · return to town manually")
            updateRunCount(routeName)
            return
        }
        val action = playRoute.optJSONObject(playIndex++) ?: run { playNext(); return }
        val gap = action.optLong("gapMs", 0L).coerceIn(0L, 2500L)
        handler.postDelayed({
            if (!playing) return@postDelayed
            val direction = action.optString("direction", "UP")
            val duration = action.optLong("durationMs", MOVE_MS).coerceIn(150L, 2500L)
            performMove(direction, duration) { handler.postDelayed({ playNext() }, 180L) }
        }, gap)
    }

    private fun stopBot() {
        recording = false
        playing = false
        handler.removeCallbacksAndMessages(null)
        setStatus("stopped")
    }

    private fun loadRoutes(): JSONArray = try {
        JSONArray(routePrefs.getString("routes", "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }

    private fun saveRoutes(routes: JSONArray) {
        routePrefs.edit().putString("routes", routes.toString()).apply()
    }

    private fun updateRunCount(name: String) {
        val routes = loadRoutes()
        for (i in 0 until routes.length()) {
            val item = routes.optJSONObject(i) ?: continue
            if (item.optString("name") == name) {
                item.put("runs", item.optInt("runs") + 1)
                break
            }
        }
        saveRoutes(routes)
    }

    private fun captureRouteFrame(actionIndex: Int) {
        if (Build.VERSION.SDK_INT < 30) return
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bitmap == null) return
                    val dir = File(filesDir, "route-frames").apply { mkdirs() }
                    val file = File(dir, "frame-" + System.currentTimeMillis() + ".jpg")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
                    bitmap.recycle()
                    if (recording && actionIndex < routeActions.length()) {
                        routeActions.optJSONObject(actionIndex)?.put("visualFrame", file.name)
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(errorCode: Int) { }
        })
    }

    private fun captureEliteSample() {
        if (Build.VERSION.SDK_INT < 30) { setStatus("visual samples need Android 11+"); return }
        if (!eliteMode) { setStatus("turn elite spotting on first"); return }
        setStatus("capturing elite example")
        takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                try {
                    val bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bitmap == null) { setStatus("could not read screenshot"); return }
                    val dir = File(filesDir, "elite-samples").apply { mkdirs() }
                    val file = File(dir, "elite-" + System.currentTimeMillis() + "-" + sampleIndex++ + ".png")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    eliteSamples.put(JSONObject().apply {
                        put("file", file.name); put("tag", "elite"); put("capturedAt", System.currentTimeMillis())
                    })
                    setStatus("elite example saved · " + eliteSamples.length() + " this recording")
                } catch (e: Exception) {
                    setStatus("sample failed: " + (e.message ?: "unknown error"))
                }
            }
            override fun onFailure(errorCode: Int) { setStatus("screenshot failed · code " + errorCode) }
        })
    }
}
