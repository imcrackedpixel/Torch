package com.imcrackedpixel.torchcontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var panel: LinearLayout
    private lateinit var target: TextView
    private lateinit var targetParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var centerX = 0f
    private var centerY = 0f
    private var step = 0
    private var patternIndex = 0
    private var paceIndex = 1
    private var status: TextView? = null
    private var patternButton: Button? = null
    private var paceButton: Button? = null
    private var routineEnabled = false
    private var routineButton: Button? = null

    private val patterns: Array<Array<Pair<Float, Float>>> = arrayOf(
        arrayOf(Pair(0f, -58f), Pair(58f, 0f), Pair(0f, 58f), Pair(-58f, 0f)),
        arrayOf(Pair(-58f, 0f), Pair(58f, 0f)),
        arrayOf(Pair(0f, -58f), Pair(0f, 58f)),
        arrayOf(Pair(-41f, -41f), Pair(41f, -41f), Pair(41f, 41f), Pair(-41f, 41f))
    )
    private val patternNames = arrayOf("SQUARE", "HORIZONTAL", "VERTICAL", "DIAGONAL")
    private val holdTimes = longArrayOf(900L, 1300L, 1700L)
    private val pauseTimes = longArrayOf(1800L, 1200L, 800L)
    private val paceNames = arrayOf("GENTLE", "BALANCED", "QUICK")

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val svc = TouchService.instance
            if (svc == null) {
                stopAutomation("Accessibility disconnected")
                return
            }
            val direction = patterns[patternIndex][step % patterns[patternIndex].size]
            val density = resources.displayMetrics.density
            svc.joystickDrag(centerX, centerY, direction.first * density, direction.second * density, holdTimes[paceIndex])
            step++
            if (routineEnabled && step >= patterns[patternIndex].size) {
                step = 0
                patternIndex = (patternIndex + 1) % patterns.size
                patternButton?.text = "PATTERN: ${patternNames[patternIndex]}"
                status?.text = "Routine switched to ${patternNames[patternIndex]}"
            } else {
                status?.text = "${patternNames[patternIndex]} · ${paceNames[paceIndex]} · step ${step}"
            }
            handler.postDelayed(this, holdTimes[paceIndex] + pauseTimes[paceIndex])
        }
    }

    private val safetyStop = Runnable {
        if (running) stopAutomation("5-minute safety limit reached")
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(7, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
    }

    private fun notification(): Notification {
        val channelId = "torch_controller"
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(channelId, "Torch Controller", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId).setContentTitle("Torch Controller active").setSmallIcon(android.R.drawable.ic_media_play).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setContentTitle("Torch Controller active").setSmallIcon(android.R.drawable.ic_media_play).build()
        }
    }

    private fun buildOverlay() {
        val density = resources.displayMetrics.density
        target = TextView(this).apply {
            text = "✥"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(0xCC66FF66.toInt())
        }
        targetParams = WindowManager.LayoutParams((58*density).roundToInt(), (58*density).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (210*density).roundToInt()
            y = (160*density).roundToInt()
        }
        target.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX=e.rawX; downY=e.rawY
                        startX=targetParams.x; startY=targetParams.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        targetParams.x = startX + (e.rawX-downX).roundToInt()
                        targetParams.y = startY + (e.rawY-downY).roundToInt()
                        wm.updateViewLayout(target, targetParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        centerX = targetParams.x + target.width/2f
                        centerY = targetParams.y + target.height/2f
                        status?.text = "Joystick anchor set"
                        return true
                    }
                }
                return true
            }
        })

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8,8,8,8)
            setBackgroundColor(0xDD20252B.toInt())
        }
        status = TextView(this).apply {
            text = "Drag green target to joystick"
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        panel.addView(status)
        patternButton = Button(this).apply {
            text = "PATTERN: ${patternNames[patternIndex]}"
            setOnClickListener {
                if (running) {
                    status?.text = "Stop AUTO before changing pattern"
                } else {
                    patternIndex = (patternIndex + 1) % patterns.size
                    step = 0
                    text = "PATTERN: ${patternNames[patternIndex]}"
                    status?.text = "Pattern changed"
                }
            }
        }
        panel.addView(patternButton)
        paceButton = Button(this).apply {
            text = "PACE: ${paceNames[paceIndex]}"
            setOnClickListener {
                if (running) {
                    status?.text = "Stop AUTO before changing pace"
                } else {
                    paceIndex = (paceIndex + 1) % paceNames.size
                    text = "PACE: ${paceNames[paceIndex]}"
                    status?.text = "Pause between moves: ${pauseTimes[paceIndex] / 1000.0}s"
                }
            }
        }
        panel.addView(paceButton)
        routineButton = Button(this).apply {
            text = "ROUTINE: OFF"
            setOnClickListener {
                if (running) {
                    status?.text = "Stop AUTO before changing routine"
                } else {
                    routineEnabled = !routineEnabled
                    text = if (routineEnabled) "ROUTINE: ON · ROTATE" else "ROUTINE: OFF"
                    status?.text = if (routineEnabled) "Patterns will rotate automatically" else "Single pattern mode"
                }
            }
        }
        panel.addView(routineButton)
        panel.addView(Button(this).apply {
            text = "AUTO · 5 MIN MAX"
            setOnClickListener {
                if (TouchService.instance == null) {
                    status?.text = "Enable Accessibility first"
                    return@setOnClickListener
                }
                if (!running) {
                    running = true
                    step = 0
                    targetParams.flags = targetParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    wm.updateViewLayout(target,targetParams)
                    status?.text = "AUTO running · STOP to halt"
                    handler.removeCallbacks(safetyStop)
                    handler.postDelayed(safetyStop, 5 * 60 * 1000L)
                    handler.post(loop)
                }
            }
        })
        panel.addView(Button(this).apply {
            text = "STOP"
            setOnClickListener { stopAutomation("Stopped") }
        })
        panel.addView(Button(this).apply {
            text = "CLOSE"
            setOnClickListener { stopSelf() }
        })
        panelParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.RIGHT
            x=(8*density).roundToInt()
            y=(48*density).roundToInt()
        }
        wm.addView(target, targetParams)
        wm.addView(panel, panelParams)
        centerX = targetParams.x + targetParams.width/2f
        centerY = targetParams.y + targetParams.height/2f
    }

    private fun stopAutomation(message: String) {
        running = false
        handler.removeCallbacks(loop)
        handler.removeCallbacks(safetyStop)
        if (::targetParams.isInitialized && ::target.isInitialized && target.isAttachedToWindow) {
            targetParams.flags = targetParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            wm.updateViewLayout(target, targetParams)
        }
        status?.text = message
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopAutomation("Controller closed")
        if (::wm.isInitialized) {
            if (::target.isInitialized && target.isAttachedToWindow) wm.removeView(target)
            if (::panel.isInitialized && panel.isAttachedToWindow) wm.removeView(panel)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
