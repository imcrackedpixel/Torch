package com.imcrackedpixel.torchcontroller

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val captureRequest = 4102

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(VisualRecognitionService.EXTRA_STATUS)
                ?: "Screen recognition running."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "TORCH CONTROLLER\n\nLIVE TEACH AI\nStart screen capture, allow display-over-other-apps, then open Torchlight. A floating TEACH AI panel watches gameplay and asks questions when it spots possible enemies or loot. Your YES/NO answers are saved on this device. Nothing is uploaded and learning does not perform game taps."
            textSize = 17f
        })
        status = TextView(this).apply {
            text = "Recognition is stopped."
            textSize = 15f
            setPadding(0, 20, 0, 16)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Start Live Teach AI"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        })
        root.addView(Button(this).apply {
            text = "Allow display over other apps (needed for live questions)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        })
        root.addView(Button(this).apply {
            text = "Stop screen recognition"
            setOnClickListener {
                stopService(Intent(this@MainActivity, VisualRecognitionService::class.java))
                status.text = "Recognition stopped."
            }
        })
        root.addView(Button(this).apply {
            text = "Allow display over other apps"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "Enable gesture accessibility"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "Show four-button controller"
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                } else {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Stop floating controller"
            setOnClickListener { stopService(Intent(this@MainActivity, OverlayService::class.java)) }
        })
        setContentView(root)

        val filter = IntentFilter(VisualRecognitionService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val service = Intent(this, VisualRecognitionService::class.java).apply {
                    action = VisualRecognitionService.ACTION_START
                    putExtra(VisualRecognitionService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(VisualRecognitionService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(service)
                status.text = "Screen capture permission granted. Starting recognition…"
            } else {
                status.text = "Screen capture was cancelled."
            }
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
