package com.imcrackedpixel.torchcontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

class VisualRecognitionService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val processing = AtomicBoolean(false)
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val objectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableClassification()
            .enableMultipleObjects()
            .build()
        ObjectDetection.getClient(options)
    }
    private val captureLoop = object : Runnable {
        override fun run() {
            captureFrame()
            handler?.postDelayed(this, 1600L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START && projection == null) {
            startForeground(NOTIFICATION_ID, buildNotification())
            beginCapture(intent)
        }
        return START_NOT_STICKY
    }

    private fun beginCapture(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") (intent.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent)
        }
        if (data == null) {
            sendStatus("Missing screen-capture permission data.")
            stopSelf()
            return
        }
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels.coerceAtMost(1440)
            val height = (metrics.heightPixels * (width.toFloat() / metrics.widthPixels)).toInt()
            val density = metrics.densityDpi

            worker = HandlerThread("TorchVisualRecognition").also { it.start() }
            handler = Handler(worker!!.looper)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "Torch visual recognition", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader?.surface, null, handler
            )
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            }, handler)
            sendStatus("Watching screen. On-device text and general-object detection are active. Nothing is uploaded.")
            handler?.post(captureLoop)
        } catch (e: Exception) {
            sendStatus("Could not start capture: ${e.message}")
            stopCapture()
            stopSelf()
        }
    }

    private fun captureFrame() {
        val image = try { reader?.acquireLatestImage() } catch (_: Exception) { null } ?: return
        if (!processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        val bitmap = try { imageToBitmap(image) } catch (_: Exception) { null } finally { image.close() }
        if (bitmap == null) {
            processing.set(false)
            return
        }

        val input = InputImage.fromBitmap(bitmap, 0)
        val textTask = recognizer.process(input)
        val objectTask = objectDetector.process(input)
        Tasks.whenAllComplete(textTask, objectTask).addOnCompleteListener {
            val ocrLines = if (textTask.isSuccessful) textTask.result.textBlocks
                .flatMap { block -> block.lines }
                .filter { line -> line.text.trim().isNotEmpty() } else emptyList()
            val text = ocrLines.map { it.text.trim() }.take(16)
            val objects = if (objectTask.isSuccessful) objectTask.result else emptyList()
            val torchSignals = analyseTorchlight(bitmap, ocrLines.map { it.text })
            val objectLines = objects.take(8).mapIndexed { index, item ->
                val label = item.labels.maxByOrNull { it.confidence }?.let {
                    "${it.text} ${(it.confidence * 100).toInt()}%"
                } ?: "Unclassified object"
                val box = item.boundingBox
                "${index + 1}. $label at ${box.centerX()},${box.centerY()}"
            }
            val report = buildString {
                append("Screen analysis\n")
                append("Text: ")
                append(if (text.isEmpty()) "none detected" else text.joinToString(" | "))
                append("\nTorchlight cues: ")
                append(torchSignals.joinToString(" | "))
                append("\nGeneral objects: ")
                append(if (objectLines.isEmpty()) "none classified" else objectLines.joinToString("; "))
                append("\nExperimental visual cues; verify on live gameplay before enabling automation.")
            }
            sendStatus(report)
            bitmap.recycle()
            processing.set(false)
        }
    }


    /**
     * First-pass Torchlight: Infinite HUD heuristics calibrated from the supplied
     * 1536x864 screenshots. Uses OCR text and screen-region colour patterns only;
     * it does not identify every monster or item and does not trigger taps.
     */
    private fun analyseTorchlight(bitmap: Bitmap, recognisedLines: List<String>): List<String> {
        val cues = mutableListOf<String>()
        val joined = recognisedLines.joinToString(" ").lowercase()
        val lootNames = listOf("bud rod", "rogue outerwear", "herbalist's footguards", "tortoiseshell amulet")
        val foundLoot = lootNames.filter { joined.contains(it) }
        if (foundLoot.isNotEmpty()) cues += "Loot text detected: ${foundLoot.joinToString(", ")}"

        val enemyNames = listOf("agito vanguard", "lean the chosen")
        val foundEnemies = enemyNames.filter { joined.contains(it) }
        val redBars = countColourRuns(bitmap, 0.22f, 0.78f, 0.12f, 0.78f) { r, g, b ->
            r > 145 && r > g * 1.45 && r > b * 1.25 && g < 150
        }
        if (foundEnemies.isNotEmpty() || redBars >= 2) {
            val detail = foundEnemies.joinToString(", ").ifEmpty { "red combat health-bar cues" }
            cues += "Possible enemy/combat detected: $detail"
        } else cues += "No clear enemy cue"

        val goldRuns = countColourRuns(bitmap, 0.56f, 0.91f, 0.34f, 0.82f) { r, g, b ->
            r > 155 && g > 85 && g < 205 && b < 105 && r > b * 1.7
        }
        if (goldRuns >= 2 && foundLoot.isNotEmpty()) cues += "Likely loot pickup prompt visible"
        else if (foundLoot.isNotEmpty()) cues += "Loot labels visible; pickup prompt not confidently confirmed"
        else cues += "No clear loot pickup cue"
        return cues
    }

    private fun countColourRuns(
        bitmap: Bitmap, left: Float, right: Float, top: Float, bottom: Float,
        match: (Int, Int, Int) -> Boolean
    ): Int {
        val x0 = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val x1 = (bitmap.width * right).toInt().coerceIn(x0 + 1, bitmap.width)
        val y0 = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val y1 = (bitmap.height * bottom).toInt().coerceIn(y0 + 1, bitmap.height)
        val strideX = maxOf(1, bitmap.width / 480)
        val strideY = maxOf(1, bitmap.height / 270)
        var matchingRows = 0
        var y = y0
        while (y < y1) {
            var run = 0
            var longest = 0
            var x = x0
            while (x < x1) {
                val pixel = bitmap.getPixel(x, y)
                if (match(android.graphics.Color.red(pixel), android.graphics.Color.green(pixel), android.graphics.Color.blue(pixel))) {
                    run++
                    if (run > longest) longest = run
                } else run = 0
                x += strideX
            }
            if (longest >= maxOf(5, (x1 - x0) / 110)) matchingRows++
            y += strideY
        }
        return matchingRows
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun sendStatus(message: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, message))
    }

    private fun buildNotification(): Notification {
        val channelId = "torch_screen_capture"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Torch screen recognition", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setContentTitle("Torch visual recognition")
            .setContentText("Screen capture is active. Tap Stop in Torch Controller to end it.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    private fun stopCapture() {
        handler?.removeCallbacksAndMessages(null)
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        try { recognizer.close() } catch (_: Exception) {}
        try { objectDetector.close() } catch (_: Exception) {}
        worker?.quitSafely()
        worker = null
        handler = null
        processing.set(false)
        sendStatus("Recognition stopped.")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.imcrackedpixel.torchcontroller.START_VISUAL"
        const val ACTION_STOP = "com.imcrackedpixel.torchcontroller.STOP_VISUAL"
        const val ACTION_STATUS = "com.imcrackedpixel.torchcontroller.VISUAL_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIFICATION_ID = 4103
    }
}
