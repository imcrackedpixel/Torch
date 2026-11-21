package com.imcrackedpixel.torchlightbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
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
    private var farming = false
    private var lastMinimap: Bitmap? = null
    private var lastMapChangeAt = 0L
    private var lastPotionAt = 0L
    private var teleporting = false
    private var eliteMode = false
    private var routeActions = JSONArray()
    private val eliteSamples = JSONArray()
    private val routePoints = mutableListOf<Pair<Float, Float>>()
    private var routePreview: RoutePreviewView? = null
    private var overlay: LinearLayout? = null
    private var statusView: TextView? = null
    private var farmToggle: Button? = null
    private var aimMarker: AimMarkerView? = null
    private var aimMarkerParams: WindowManager.LayoutParams? = null
    private val actionMarkers = mutableListOf<Pair<AimMarkerView, WindowManager.LayoutParams>>()
    private val aimPrefs by lazy { getSharedPreferences("ragnarok_aim", MODE_PRIVATE) }
    private val attackX: Float get() = aimPrefs.getFloat("x", 0.833f).coerceIn(0.02f, 0.98f)
    private val attackY: Float get() = aimPrefs.getFloat("y", 0.755f).coerceIn(0.02f, 0.98f)
    private val f3X: Float get() = aimPrefs.getFloat("f3x", 0.768f).coerceIn(0.02f, 0.98f)
    private val f3Y: Float get() = aimPrefs.getFloat("f3y", 0.775f).coerceIn(0.02f, 0.98f)
    private val f6X: Float get() = aimPrefs.getFloat("f6x", 0.326f).coerceIn(0.02f, 0.98f)
    private val f6Y: Float get() = aimPrefs.getFloat("f6y", 0.045f).coerceIn(0.02f, 0.98f)
    private var lastCombatAt = 0L
    private var lastF3At = 0L
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
        const val ACTION_FARM = "com.imcrackedpixel.torchlightbot.FARM"
        private const val MOVE_MS = 650L
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW -> showControls()
                ACTION_RANDOM_PLAY -> playRandomRoute()
                ACTION_STOP -> stopBot()
                ACTION_FARM -> startRagnarokFarm()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val f = IntentFilter().apply {
            addAction(ACTION_SHOW); addAction(ACTION_RANDOM_PLAY); addAction(ACTION_STOP); addAction(ACTION_FARM)
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
            setPadding(4, 4, 4, 4)
            setBackgroundColor(0xCC18212B.toInt())
        }
        val toggle = Button(this).apply {
            text = if (farming) "OFF" else "ON"
            textSize = 12f
            setPadding(8, 4, 8, 4)
            minWidth = 0
            minHeight = 0
            setOnClickListener {
                if (farming) {
                    stopBot()
                    text = "ON"
                } else {
                    startRagnarokFarm()
                    text = "OFF"
                }
            }
        }
        farmToggle = toggle
        panel.addView(toggle, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ))
        statusView = TextView(this).apply {
            text = if (farming) "Starting Ragnarok hunt…" else "Ready · press ON to start"
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(6, 4, 6, 4)
        }
        panel.addView(statusView, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 12; y = 100 }
        overlay = panel
        try {
            wm.addView(panel, params)
            showAimMarker()
        } catch (_: Exception) { overlay = null }
    }

    private fun makeButton(label: String, action: (Button) -> Unit): Button =
        Button(this).apply { text = label; textSize = 10f; setPadding(5, 2, 5, 2); setOnClickListener { action(this) } }

    private fun showAimMarker() {
        if (aimMarker != null) return
        addTapMarker("attack", attackX, attackY, "ATK", 0xFF00E5FF.toInt(), true)
        addTapMarker("f3", f3X, f3Y, "F3", 0xFF69F0AE.toInt(), false)
        addTapMarker("f6", f6X, f6Y, "F6", 0xFFFFB74D.toInt(), false)
    }

    private fun addTapMarker(
        key: String, nx: Float, ny: Float, label: String, color: Int, primary: Boolean
    ) {
        val size = (58f * resources.displayMetrics.density).toInt()
        val marker = AimMarkerView(this, label, color)
        val p = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (resources.displayMetrics.widthPixels * nx - size / 2f).toInt()
                .coerceIn(0, (resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
            y = (resources.displayMetrics.heightPixels * ny - size / 2f).toInt()
                .coerceIn(0, (resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
        }
        marker.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    marker.dragStartX = event.rawX; marker.dragStartY = event.rawY
                    marker.viewStartX = p.x; marker.viewStartY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = (marker.viewStartX + event.rawX - marker.dragStartX).toInt()
                        .coerceIn(0, (resources.displayMetrics.widthPixels - size).coerceAtLeast(0))
                    p.y = (marker.viewStartY + event.rawY - marker.dragStartY).toInt()
                        .coerceIn(0, (resources.displayMetrics.heightPixels - size).coerceAtLeast(0))
                    try { wm.updateViewLayout(marker, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val w = resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
                    val h = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
                    val px = ((p.x + size / 2f) / w).coerceIn(0.02f, 0.98f)
                    val py = ((p.y + size / 2f) / h).coerceIn(0.02f, 0.98f)
                    val edit = aimPrefs.edit()
                    if (key == "attack") edit.putFloat("x", px).putFloat("y", py)
                    else edit.putFloat(key + "x", px).putFloat(key + "y", py)
                    edit.apply()
                    setStatus("$label target saved")
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        try {
            wm.addView(marker, p)
            if (primary) { aimMarker = marker; aimMarkerParams = p }
            else actionMarkers.add(marker to p)
        } catch (_: Exception) { }
    }

    private fun removeControls() {
        aimMarker?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        aimMarker = null
        aimMarkerParams = null
        actionMarkers.forEach { (view, _) -> try { wm.removeView(view) } catch (_: Exception) {} }
        actionMarkers.clear()
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun setStatus(s: String) {
        statusView?.text = "TORCH MAP FARMER · " + s
        farmToggle?.text = if (farming) "OFF" else "ON"
    }

    private fun beginRecording() {
        stopBot()
        routeActions = JSONArray()
        routePoints.clear(); routePoints.add(0f to 0f); routePreview?.invalidate()
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
            put("pathPoints", JSONArray().apply { routePoints.forEach { p -> put(JSONObject().put("x", p.first).put("y", p.second)) } })
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
            val prev = routePoints.lastOrNull() ?: (0f to 0f)
            val scale = MOVE_MS.toFloat() / 650f
            val next = when (direction) {
                "UP" -> prev.first to (prev.second - scale)
                "DOWN" -> prev.first to (prev.second + scale)
                "LEFT" -> (prev.first - scale) to prev.second
                else -> (prev.first + scale) to prev.second
            }
            routePoints.add(next); routePreview?.invalidate()
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
        routePoints.clear()
        val savedPoints = route.optJSONArray("pathPoints")
        if (savedPoints != null) for (i in 0 until savedPoints.length()) {
            val p = savedPoints.optJSONObject(i) ?: continue
            routePoints.add(p.optDouble("x").toFloat() to p.optDouble("y").toFloat())
        }
        routePreview?.invalidate()
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


    private fun tapAt(nx: Float, ny: Float, duration: Long = 90L) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val x = (w * nx).coerceIn(2f, w - 2f)
        val y = (h * ny).coerceIn(2f, h - 2f)
        // A stationary press/release: lineTo at the same coordinate makes this a real tap,
        // not a directional swipe that can move the character.
        val p = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }
        val stroke = GestureDescription.StrokeDescription(p, 0, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun startRagnarokFarm() {
        stopBot()
        farming = true
        lastMapChangeAt = System.currentTimeMillis()
        lastCombatAt = System.currentTimeMillis()
        lastF3At = 0L
        lastPotionAt = 0L
        lastMinimap?.recycle()
        lastMinimap = null
        setStatus("Ragnarok hunt running · attack scan active")
        farmToggle?.text = "OFF"
        farmTick()
    }

    private fun farmTick() {
        if (!farming) return
        tapAt(attackX, attackY, 70L)
        if (Build.VERSION.SDK_INT >= 30) {
            takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var bitmap: Bitmap? = null
                    try {
                        bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        screenshot.hardwareBuffer.close()
                        if (bitmap != null && farming) analyseRagnarokFrame(bitmap)
                    } catch (_: Exception) { }
                    finally { bitmap?.recycle() }
                }
                override fun onFailure(errorCode: Int) {
                    if (farming) setStatus("screen read unavailable · attack continues")
                }
            })
        }
        handler.postDelayed({ farmTick() }, 1800L)
    }

    private fun analyseRagnarokFrame(frame: Bitmap) {
        val now = System.currentTimeMillis()
        val w = frame.width
        val h = frame.height

        // Rough combat cue: red enemy health bars/effects in the central play area.
        // UI edges are excluded to avoid counting the red attack/potion controls.
        var redSamples = 0
        val xStart = (w * 0.22f).toInt().coerceIn(0, w - 1)
        val xEnd = (w * 0.78f).toInt().coerceIn(xStart + 1, w)
        val yStart = (h * 0.16f).toInt().coerceIn(0, h - 1)
        val yEnd = (h * 0.76f).toInt().coerceIn(yStart + 1, h)
        val stepX = max(2, w / 100)
        val stepY = max(2, h / 100)
        var y = yStart
        while (y < yEnd) {
            var x = xStart
            while (x < xEnd) {
                val c = frame.getPixel(x, y)
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                if (r > 155 && r > g * 1.45f && r > b * 1.25f) redSamples++
                x += stepX
            }
            y += stepY
        }
        val inCombat = redSamples >= 14
        if (inCombat) {
            lastCombatAt = now
            setStatus("combat detected · attacking (red cue $redSamples)")
        } else if (now - lastCombatAt >= 4500L && now - lastF3At >= 12000L && !teleporting) {
            teleporting = true
            lastF3At = now
            setStatus("no combat detected · tapping F3")
            tapAt(f3X, f3Y, 85L)
            handler.postDelayed({
                teleporting = false
                if (farming) {
                    lastCombatAt = System.currentTimeMillis()
                    setStatus("F3 tapped · scanning for enemies")
                }
            }, 2200L)
        }

        // Estimate HP fill from the red HP bar in the upper-left HUD.
        // Drag the F6 marker onto the game's actual F6 button before starting.
        val hpY = (h * 0.151f).toInt().coerceIn(0, h - 1)
        val hpL = (w * 0.082f).toInt().coerceIn(0, w - 1)
        val hpR = (w * 0.245f).toInt().coerceIn(hpL + 1, w)
        var redHp = 0
        val total = (hpR - hpL).coerceAtLeast(1)
        for (x in hpL until hpR) {
            val color = frame.getPixel(x, hpY)
            val r = (color shr 16) and 255
            val g = (color shr 8) and 255
            val b = color and 255
            if (r > 105 && r > g * 1.25f && r > b * 1.20f) redHp++
        }
        val hpRatio = redHp.toFloat() / total
        if (hpRatio < 0.50f && now - lastPotionAt > 5000L) {
            tapAt(f6X, f6Y, 85L)
            lastPotionAt = now
            setStatus("HP estimate below 50% · F6 tapped")
        }
    }

    private fun stopBot() {
        recording = false
        playing = false
        farming = false
        teleporting = false
        lastMinimap?.recycle(); lastMinimap = null
        handler.removeCallbacksAndMessages(null)
        setStatus("stopped")
        farmToggle?.text = "ON"
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


    private inner class AimMarkerView(
        context: Context, private val markerLabel: String, markerColor: Int
    ) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = markerColor
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * resources.displayMetrics.density
        }
        private val centre = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFD54F.toInt()
            style = Paint.Style.FILL
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = markerColor
            textSize = 11f * resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        var dragStartX = 0f
        var dragStartY = 0f
        var viewStartX = 0
        var viewStartY = 0
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = width * 0.34f
            canvas.drawCircle(cx, cy, radius, ring)
            canvas.drawLine(cx - radius * 1.35f, cy, cx + radius * 1.35f, cy, ring)
            canvas.drawLine(cx, cy - radius * 1.35f, cx, cy + radius * 1.35f, ring)
            canvas.drawCircle(cx, cy, 3.5f * resources.displayMetrics.density, centre)
            canvas.drawText(markerLabel, cx, cy + radius + labelPaint.textSize, labelPaint)
        }
    }

    private inner class RoutePreviewView(context: Context) : View(context) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF42D7A0.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC857.toInt(); style = Paint.Style.FILL }
        private val bg = Paint().apply { color = 0xFF263442.toInt() }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f, bg)
            if (routePoints.isEmpty()) return
            val minX = routePoints.minOf { it.first }; val maxX = routePoints.maxOf { it.first }
            val minY = routePoints.minOf { it.second }; val maxY = routePoints.maxOf { it.second }
            val sx = (width - 24f) / max(1f, maxX - minX)
            val sy = (height - 24f) / max(1f, maxY - minY)
            val scale = min(sx, sy)
            val ox = (width - (maxX - minX) * scale) / 2f
            val oy = (height - (maxY - minY) * scale) / 2f
            val path = Path()
            routePoints.forEachIndexed { i, p ->
                val x = ox + (p.first - minX) * scale
                val y = oy + (p.second - minY) * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, line)
            val first = routePoints.first()
            val last = routePoints.last()
            canvas.drawCircle(ox + (first.first - minX) * scale, oy + (first.second - minY) * scale, 5f, dot)
            canvas.drawCircle(ox + (last.first - minX) * scale, oy + (last.second - minY) * scale, 5f, dot)
        }
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
