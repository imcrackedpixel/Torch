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
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val captureRequest = 4102
    private val exportRequest = 4103
    private val importRequest = 4104

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(VisualRecognitionService.EXTRA_STATUS)
                ?: "Screen recognition running."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(22, 28, 22, 24)
        }
        content.addView(TextView(this).apply {
            text = "TORCH AI"
            textSize = 30f
            setTextColor(0xFF17212B.toInt())
            gravity = Gravity.CENTER
        })
        content.addView(TextView(this).apply {
            text = "Your game-learning assistant"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 22)
        })
        content.addView(TextView(this).apply {
            text = "QUICK START"
            textSize = 13f
            setPadding(0, 8, 0, 8)
            setTextColor(0xFF526170.toInt())
        })
        content.addView(Button(this).apply {
            text = "1 · SHOW AI ON / OFF BUTTON"
            textSize = 15f
            setOnClickListener {
                if (Settings.canDrawOverlays(this@MainActivity)) {
                    startForegroundService(Intent(this@MainActivity, OverlayService::class.java))
                    status.text = "Floating AI switch shown. Open your game and use the switch."
                } else {
                    status.text = "Allow display over other apps, then tap this again."
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            text = "2 · START SCREEN RECOGNITION"
            textSize = 15f
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(manager.createScreenCaptureIntent(), captureRequest)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        status = TextView(this).apply {
            text = "Not started yet. Allow the permissions below once, then use the floating AI switch."
            textSize = 14f
            setPadding(4, 18, 4, 18)
        }
        content.addView(status)
        content.addView(TextView(this).apply {
            text = "ONE-TIME PERMISSIONS"
            textSize = 13f
            setTextColor(0xFF526170.toInt())
            setPadding(0, 8, 0, 8)
        })
        content.addView(Button(this).apply {
            text = "Allow floating button"
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            text = "Enable gesture accessibility"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            text = "Open AI chat"
            setOnClickListener { startActivity(Intent(this@MainActivity, ChatActivity::class.java)) }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            text = "View what AI has learned"
            setOnClickListener {
                val report = GameUnderstandingEngine.report(this@MainActivity) + "\\n\\n" +
                    MinimapNavigation.report(this@MainActivity) + "\\n\\n" +
                    RoutineController.report(this@MainActivity) + "\\n\\n" +
                    TorchGameAgent.report(this@MainActivity)
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Torch AI · Game knowledge")
                    .setMessage(report)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = "LEARNING MEMORY"
            textSize = 13f
            setTextColor(0xFF526170.toInt())
            setPadding(0, 18, 0, 8)
        })
        content.addView(Button(this).apply {
            text = "Export AI learning backup"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, "torch-ai-memory-backup.json")
                }
                startActivityForResult(intent, exportRequest)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(Button(this).apply {
            text = "Import AI learning backup"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                }
                startActivityForResult(intent, importRequest)
            }
        }, LinearLayout.LayoutParams(-1, -2))
        content.addView(TextView(this).apply {
            text = "After setup, use the floating AI ON switch. Drag M onto the game joystick, I onto its start/interact control, and T1–T4 onto skills. The AI switch is always the emergency OFF control. Keep the game visible and the phone awake."
            textSize = 13f
            setPadding(4, 18, 4, 8)
        })
        setContentView(ScrollView(this).apply { addView(content) })
        // Once overlay permission has been granted, show the AI switch automatically on app launch.
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java))
        }

        val filter = IntentFilter(VisualRecognitionService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == exportRequest || requestCode == importRequest) && resultCode == RESULT_OK && data?.data != null) {
            try {
                val uri = data.data!!
                if (requestCode == exportRequest) {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(TorchMemoryTransfer.export(this))
                    } ?: throw IllegalStateException("Could not open the selected backup file.")
                    status.text = "Learning backup exported. Keep this file safe; you can import it into a later Torch AI install."
                } else {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw IllegalStateException("Could not read the selected backup file.")
                    val restored = TorchMemoryTransfer.import(this, json)
                    status.text = "Learning backup imported ($restored stored values). Restart Torch AI to refresh its current learning view."
                }
            } catch (e: Exception) {
                status.text = "Memory transfer failed: ${e.message ?: "unknown error"}"
            }
            return
        }
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val service = Intent(this, VisualRecognitionService::class.java).apply {
                    action = VisualRecognitionService.ACTION_START
                    putExtra(VisualRecognitionService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(VisualRecognitionService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(service)
                status.text = "Screen recognition started. Now open your game and use the floating AI switch."
            } else status.text = "Screen capture permission was cancelled."
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
}
