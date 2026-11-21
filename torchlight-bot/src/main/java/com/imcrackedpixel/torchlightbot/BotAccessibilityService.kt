package com.imcrackedpixel.torchlightbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

class BotAccessibilityService : AccessibilityService() {
    companion object {
        const val ACTION_SHOW = "com.imcrackedpixel.torchlightbot.SHOW"
        const val ACTION_STOP = "com.imcrackedpixel.torchlightbot.STOP"
        const val ACTION_FARM = "com.imcrackedpixel.torchlightbot.FARM"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val screenshotExecutor = java.util.concurrent.Executor { command -> handler.post(command) }
    private val prefs by lazy { getSharedPreferences("tap_positions", MODE_PRIVATE) }
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var statusView: TextView? = null
    private var toggleView: TextView? = null
    private val controls = mutableListOf<Pair<TapCircle, WindowManager.LayoutParams>>()
    private var running = false
    private var screenshotBusy = false
    private var lastCombatAt = 0L
    private var lastF3At = 0L
    private var lastF6At = 0L
    private var lastAttackAt = 0L
    private var lastStatusAt = 0L
    private var screenW = 1
    private var screenH = 1
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastAttackAt >= 1600L) {
                lastAttackAt = now
                tap("attack")
            }
            if (Build.VERSION.SDK_INT >= 30 && !screenshotBusy) readScreen()
            handler.postDelayed(this, 350L)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SHOW -> showControls()
                ACTION_STOP -> stopBot()
                ACTION_FARM -> startBot()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        refreshSize()
        val filter = IntentFilter().apply {
            addAction(ACTION_SHOW)
            addAction(ACTION_STOP)
            addAction(ACTION_FARM)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    private fun refreshSize() {
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels.coerceAtLeast(1)
        screenH = metrics.heightPixels.coerceAtLeast(1)
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
        if (panel != null) return
        refreshSize()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 7, 10, 7)
            setBackgroundColor(0xDD17212B.toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toggleView = TextView(this).apply {
            text = if (running) "  BOT ON  " else "  BOT OFF  "
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xFF276749.toInt())
            setOnClickListener { if (running) stopBot() else startBot() }
        }
        header.addView(toggleView)
        val close = TextView(this).apply {
            text = "  ×  "
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(10, 5, 10, 5)
            setOnClickListener { stopBot(); removeControls() }
        }
        header.addView(close)
        root.addView(header)
        statusView = TextView(this).apply {
            text = "Drag circles onto game buttons. Tap BOT ON to start."
            textSize = 10f
            setTextColor(Color.WHITE)
            setPadding(4, 5, 4, 2)
        }
        root.addView(statusView)
        val pp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 10; y = 70 }
        panel = root
        try {
            wm.addView(root, pp)
            addCircle("attack", "ATK", 0xFFE53935.toInt(), 66f, 0.84f, 0.82f)
            addCircle("f3", "F3", 0xFF28A86B.toInt(), 48f, 0.74f, 0.82f)
            addCircle("f6", "F6", 0xFFFFA726.toInt(), 48f, 0.92f, 0.70f)
        } catch (e: Exception) {
            setStatus("Overlay failed: enable accessibility and try again")
        }
    }

    private fun addCircle(key: String, label: String, color: Int, dp: Float, defaultX: Float, defaultY: Float) {
        val size = (dp * resources.displayMetrics.density).toInt()
        val nx = prefs.getFloat(key + "_x", defaultX).coerceIn(0.02f, 0.98f)
        val ny = prefs.getFloat(key + "_y", defaultY).coerceIn(0.02f, 0.98f)
        val view = TapCircle(this, label, color, size)
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (screenW * nx - size / 2f).toInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
            y = (screenH * ny - size / 2f).toInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f) moved = true
                    if (moved) {
                        params.x = (startX + dx).toInt().coerceIn(0, (screenW - size).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, (screenH - size).coerceAtLeast(0))
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.edit()
                            .putFloat(key + "_x", (params.x + size / 2f) / screenW)
                            .putFloat(key + "_y", (params.y + size / 2f) / screenH)
                            .apply()
                        setStatus(label + " position saved")
                    } else {
                        tapAt(params.x + size / 2f, params.y + size / 2f)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        try {
            wm.addView(view, params)
            controls.add(view to params)
        } catch (_: Exception) {}
    }

    private fun startBot() {
        if (panel == null) showControls()
        if (running) return
        running = true
        val now = System.currentTimeMillis()
        lastCombatAt = now
        lastF3At = 0L
        lastF6At = 0L
        lastAttackAt = 0L
        toggleView?.text = "  BOT ON  "
        setStatus("Running · attack every 1.6s · F3 after 5s idle · F6 below 50%")
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun stopBot() {
        running = false
        handler.removeCallbacks(tick)
        toggleView?.text = "  BOT OFF  "
        setStatus("Stopped")
    }

    private fun setStatus(message: String) {
        statusView?.text = message
        lastStatusAt = System.currentTimeMillis()
    }

    private fun tap(key: String) {
        val pair = controls.firstOrNull {
            when (key) {
                "attack" -> it.first.label == "ATK"
                "f3" -> it.first.label == "F3"
                else -> it.first.label == "F6"
            }
        } ?: return
        val p = pair.second
        val v = pair.first
        tapAt(p.x + v.width / 2f, p.y + v.height / 2f)
    }

    private fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.2f, y + 0.2f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 65L))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {}
            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (running) setStatus("Tap cancelled · move circles onto the game buttons")
            }
        }, null)
    }

    private fun readScreen() {
        screenshotBusy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                var bitmap: Bitmap? = null
                try {
                    bitmap = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    screenshot.hardwareBuffer.close()
                    if (bitmap != null && running) analyse(bitmap)
                } catch (_: Exception) {
                } finally {
                    bitmap?.recycle()
                    screenshotBusy = false
                }
            }
            override fun onFailure(errorCode: Int) {
                screenshotBusy = false
                if (running && System.currentTimeMillis() - lastStatusAt > 8000L) {
                    setStatus("Screen read unavailable (code " + errorCode + ") · attack taps continue")
                }
            }
        })
    }

    private fun analyse(b: Bitmap) {
        val w = b.width
        val h = b.height
        val now = System.currentTimeMillis()
        // Red enemy bars/effects in the central playfield are used as a simple combat cue.
        var red = 0
        val x0 = (w * 0.22f).toInt().coerceIn(0, w - 1)
        val x1 = (w * 0.78f).toInt().coerceIn(x0 + 1, w)
        val y0 = (h * 0.16f).toInt().coerceIn(0, h - 1)
        val y1 = (h * 0.76f).toInt().coerceIn(y0 + 1, h)
        val sx = max(2, w / 100)
        val sy = max(2, h / 100)
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = b.getPixel(x, y)
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                if (r > 160 && r > g * 1.45f && r > bl * 1.25f) red++
                x += sx
            }
            y += sy
        }
        if (red >= 14) {
            lastCombatAt = now
            if (now - lastStatusAt > 2500L) setStatus("Combat detected · attacking")
        } else if (now - lastCombatAt >= 5000L && now - lastF3At >= 10000L) {
            lastF3At = now
            tap("f3")
            setStatus("Idle 5s · F3 tapped")
        }

        // Locate a blue HP fill in the upper-left HUD. Only act when a clear bar is found.
        val bx0 = (w * 0.035f).toInt().coerceIn(0, w - 1)
        val bx1 = (w * 0.34f).toInt().coerceIn(bx0 + 1, w)
        val by0 = (h * 0.075f).toInt().coerceIn(0, h - 1)
        val by1 = (h * 0.22f).toInt().coerceIn(by0 + 1, h)
        var bestRatio = 1f
        var found = false
        for (yy in by0 until by1) {
            var blue = 0
            var total = 0
            for (xx in bx0 until bx1 step max(1, w / 240)) {
                val c = b.getPixel(xx, yy)
                val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
                if (bl > 75 && bl > r * 1.18f && bl > g * 0.92f) blue++
                total++
            }
            val ratio = blue.toFloat() / total.coerceAtLeast(1)
            if (blue >= 8 && ratio in 0.025f..0.85f) {
                found = true
                if (ratio < bestRatio) bestRatio = ratio
            }
        }
        // This is deliberately conservative: F6 only fires when a blue bar is clearly short.
        if (found && bestRatio < 0.15f && now - lastF6At >= 5000L) {
            lastF6At = now
            tap("f6")
            setStatus("Low blue HP bar estimate · F6 tapped")
        }
    }

    private fun removeControls() {
        controls.forEach { try { wm.removeView(it.first) } catch (_: Exception) {} }
        controls.clear()
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        statusView = null
        toggleView = null
    }

    private inner class TapCircle(
        context: Context,
        val label: String,
        private val fill: Int,
        private val diameter: Int
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            paint.color = 0xDD101820.toInt()
            canvas.drawCircle(cx, cy, diameter * 0.49f, paint)
            paint.color = fill
            canvas.drawCircle(cx, cy, diameter * 0.40f, paint)
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.BOLD)
            paint.textSize = diameter * 0.22f
            canvas.drawText(label, cx, cy + paint.textSize * 0.34f, paint)
        }
    }
}
