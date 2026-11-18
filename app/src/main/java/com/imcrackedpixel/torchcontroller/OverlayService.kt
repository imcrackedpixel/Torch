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
    private val directions = arrayOf(
        Pair(0f, -58f), Pair(58f, 0f), Pair(0f, 58f), Pair(-58f, 0f)
    )
    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            val svc = TouchService.instance
            if (svc == null) {
                status?.text = "Enable Accessibility first"
                running = false
                return
            }
            val d = directions[step % directions.size]
            val density = resources.displayMetrics.density
            svc.joystickDrag(centerX, centerY, d.first * density, d.second * density, 1600L)
            step++
            handler.postDelayed(this, 1900L)
        }
    }
    private var status: TextView? = null

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
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = (210*density).roundToInt(); y = (160*density).roundToInt() }
        target.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; startX=targetParams.x; startY=targetParams.y; return true }
                    MotionEvent.ACTION_MOVE -> {
                        targetParams.x = startX + (e.rawX-downX).roundToInt()
                        targetParams.y = startY + (e.rawY-downY).roundToInt()
                        wm.updateViewLayout(target, targetParams); return true
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
        status = TextView(this).apply { text = "Drag green target to joystick"; setTextColor(Color.WHITE); textSize=12f }
        panel.addView(status)
        panel.addView(Button(this).apply { text="AUTO"; setOnClickListener {
            if (TouchService.instance == null) { status?.text="Enable Accessibility first"; return@setOnClickListener }
            if (!running) { running=true; step=0; targetParams.flags = targetParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; wm.updateViewLayout(target,targetParams); status?.text="AUTO running · STOP to halt"; handler.post(loop) }
        }})
        panel.addView(Button(this).apply { text="STOP"; setOnClickListener {
            running=false; handler.removeCallbacks(loop); targetParams.flags = targetParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv(); if (target.isAttachedToWindow) wm.updateViewLayout(target,targetParams); status?.text="Stopped"
        }})
        panel.addView(Button(this).apply { text="CLOSE"; setOnClickListener { stopSelf() }})
        panelParams = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.RIGHT; x=(8*density).roundToInt(); y=(48*density).roundToInt() }
        wm.addView(target, targetParams)
        wm.addView(panel, panelParams)
        centerX = targetParams.x + targetParams.width/2f
        centerY = targetParams.y + targetParams.height/2f
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running=false; handler.removeCallbacks(loop)
        if (::wm.isInitialized) {
            if (::target.isInitialized && target.isAttachedToWindow) wm.removeView(target)
            if (::panel.isInitialized && panel.isAttachedToWindow) wm.removeView(panel)
        }
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
