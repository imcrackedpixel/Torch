package com.imcrackedpixel.torchcontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
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
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.Gravity
import android.view.View
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean


private class ScreenHighlightOverlay(context: Context) : View(context) {
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22FFD54F
        style = Paint.Style.FILL
    }
    private var boxes: List<Rect> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var screenWidth = 1
    private var screenHeight = 1

    fun setBoxes(value: List<Rect>, srcW: Int, srcH: Int, screenW: Int, screenH: Int) {
        boxes = value
        sourceWidth = srcW.coerceAtLeast(1)
        sourceHeight = srcH.coerceAtLeast(1)
        screenWidth = screenW.coerceAtLeast(1)
        screenHeight = screenH.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = screenWidth.toFloat() / sourceWidth
        val sy = screenHeight.toFloat() / sourceHeight
        boxes.forEach { b ->
            val r = RectF(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy)
            canvas.drawRoundRect(r, 7f, 7f, fill)
            canvas.drawRoundRect(r, 7f, 7f, outline)
        }
    }
}

class VisualRecognitionService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val processing = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val learnedPrefs by lazy { getSharedPreferences("live_teaching", MODE_PRIVATE) }
    private val askedThisSession = mutableSetOf<String>()
    private var teachPill: TextView? = null
    private var teachPanel: LinearLayout? = null
    private var teachQuestion: TextView? = null
    private var overlayManager: WindowManager? = null
    private var activeQuestionKey: String? = null
    private var answerField: EditText? = null
    private var highlightOverlay: ScreenHighlightOverlay? = null
    private var lastScreenText: String = ""
    private var lastGeneralQuestionAt: Long = 0L
    private var generalQuestionNumber: Int = 0
    private var currentObservation: String = ""
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
            sendStatus("Watching screen. Live Teach AI is ready. Nothing is uploaded.")
            mainHandler.post { buildTeachOverlay() }
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
            observeGeneralGameplay(text)
            updateHighlights(ocrLines, bitmap.width, bitmap.height)
            val teachCandidate = findTeachCandidate(bitmap, ocrLines.map { it.text })
            if (teachCandidate != null) askTeachQuestion(teachCandidate.first, teachCandidate.second)
            // Keep detailed OCR/object data internal; never dump raw screen text into the app UI.
            sendStatus("Watching screen · learning locally")
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


    private fun findTeachCandidate(bitmap: Bitmap, recognisedLines: List<String>): Pair<String, String>? {
        val joined = recognisedLines.joinToString(" ").lowercase()
        val lootNames = listOf("bud rod", "rogue outerwear", "herbalist's footguards", "tortoiseshell amulet")
        val foundLoot = lootNames.filter { joined.contains(it) }
        if (foundLoot.isNotEmpty()) return "loot" to "I can read item names (${foundLoot.joinToString(", ")}). Are these dropped loot items?"
        val enemyNames = listOf("agito vanguard", "lean the chosen")
        val foundEnemies = enemyNames.filter { joined.contains(it) }
        if (foundEnemies.isNotEmpty()) return "enemy" to "I can read ${foundEnemies.joinToString(", ")} during combat. Is this an enemy?"
        val redBars = countColourRuns(bitmap, 0.22f, 0.78f, 0.12f, 0.78f) { r, g, b ->
            r > 145 && r > g * 1.45 && r > b * 1.25 && g < 150
        }
        if (redBars >= 2) return "enemy" to "I see red health-bar shapes in the play area. Do these indicate enemies?"
        val purplePixels = countMatchingPixels(bitmap, 0.76f, 0.98f, 0.55f, 0.86f) { r, g, b ->
            r > 75 && b > 125 && b > g * 1.25 && r > g * 1.15
        }
        if (purplePixels > 35) return "skill" to "I see a purple icon in the lower-right action area. Is this one of your skill buttons?"
        return null
    }


    private fun countMatchingPixels(
        bitmap: Bitmap, left: Float, right: Float, top: Float, bottom: Float,
        match: (Int, Int, Int) -> Boolean
    ): Int {
        val x0 = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val x1 = (bitmap.width * right).toInt().coerceIn(x0 + 1, bitmap.width)
        val y0 = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val y1 = (bitmap.height * bottom).toInt().coerceIn(y0 + 1, bitmap.height)
        val sx = maxOf(1, bitmap.width / 360)
        val sy = maxOf(1, bitmap.height / 220)
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val c = bitmap.getPixel(x, y)
                if (match(Color.red(c), Color.green(c), Color.blue(c))) count++
                x += sx
            }
            y += sy
        }
        return count
    }


    // Transparent, non-touchable rectangles mark the OCR text currently being read.
    private fun updateHighlights(lines: List<Text.Line>, sourceWidth: Int, sourceHeight: Int) {
        val screen = Point()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(screen)
        val boxes = lines.mapNotNull { line ->
            val bounds = line.boundingBox ?: return@mapNotNull null
            Rect(bounds)
        }.take(10)
        mainHandler.post {
            if (!Settings.canDrawOverlays(this)) return@post
            try {
                val wm = overlayManager ?: (getSystemService(WINDOW_SERVICE) as WindowManager).also { overlayManager = it }
                var view = highlightOverlay
                if (view == null) {
                    view = ScreenHighlightOverlay(this)
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = Gravity.TOP or Gravity.LEFT }
                    wm.addView(view, params)
                    highlightOverlay = view
                }
                view?.setBoxes(boxes, sourceWidth, sourceHeight, screen.x, screen.y)
            } catch (_: Exception) { }
        }
    }

    private fun askTeachQuestion(key: String, question: String) {
        mainHandler.post {
            if (learnedPrefs.contains(key) || askedThisSession.contains(key)) return@post
            askedThisSession.add(key)
            activeQuestionKey = key
            if (teachQuestion != null) {
                teachQuestion?.text = question
                teachPanel?.visibility = android.view.View.VISIBLE
            } else sendStatus("Teach AI: $question (allow display-over-other-apps to answer while playing)")
        }
    }

    private fun buildTeachOverlay() {
        if (!Settings.canDrawOverlays(this) || teachPill != null) return
        try {
            overlayManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val pill = TextView(this).apply {
                text = "TEACH AI"; textSize = 13f; setTextColor(Color.WHITE)
                setPadding(18, 12, 18, 12); setBackgroundColor(0xEE263746.toInt())
                setOnClickListener {
                    teachPanel?.let { p -> p.visibility = if (p.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
                }
            }
            val pillParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 90 }
            overlayManager?.addView(pill, pillParams); teachPill = pill

            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14, 12, 14, 12); setBackgroundColor(0xF0222932.toInt()) }
            panel.addView(TextView(this).apply { text = "Torch live learning"; textSize = 16f; setTextColor(Color.WHITE) })
            val question = TextView(this).apply {
                text = "Watching gameplay. I'll ask a short question when I notice a change."
                textSize = 14f
                setTextColor(Color.WHITE)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 8, 0, 8)
            }
            teachQuestion = question
            panel.addView(question)
            val answer = EditText(this).apply {
                hint = "What were you doing? What should I learn?"
                setTextColor(Color.WHITE)
                setHintTextColor(0xFFBBBBBB.toInt())
                minLines = 2
                maxLines = 3
            }
            answerField = answer
            panel.addView(answer)
            val quickAnswers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            listOf("Moving", "Fighting", "Looting", "Menu / quest", "Other").forEach { choice ->
                quickAnswers.addView(Button(this).apply {
                    text = choice
                    textSize = 10f
                    minWidth = 0
                    setPadding(3, 4, 3, 4)
                    setOnClickListener { saveQuickExplanation(choice) }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            panel.addView(quickAnswers)
            panel.addView(Button(this).apply {
                text = "SAVE MY EXPLANATION"
                setOnClickListener { saveGeneralExplanation() }
            })
            val answers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            answers.addView(Button(this).apply { text = "YES"; setOnClickListener { answerTeachQuestion(true) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            answers.addView(Button(this).apply { text = "NO"; setOnClickListener { answerTeachQuestion(false) } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            answers.addView(Button(this).apply { text = "LATER"; setOnClickListener { teachPanel?.visibility = android.view.View.GONE } }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            panel.addView(answers)
            val panelParams = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.94f).toInt(), (resources.displayMetrics.density * 300f).toInt(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (resources.displayMetrics.density * 72f).toInt() }
            overlayManager?.addView(panel, panelParams); teachPanel = panel
            panel.visibility = android.view.View.GONE
        } catch (e: Exception) { sendStatus("Teach AI overlay could not open: ${e.message}") }
    }


    // General-purpose observation: compare successive OCR snapshots, not just known game categories.
    private fun observeGeneralGameplay(lines: List<String>) {
        val current = lines.joinToString(" | ").take(900)
        if (current.isBlank()) return
        val now = System.currentTimeMillis()
        val changed = current != lastScreenText
        if (changed && now - lastGeneralQuestionAt > 15000L) {
            val before = lastScreenText
            // Keep OCR details in local notes, but never show a raw transcript in the gameplay panel.
            currentObservation = if (before.isBlank()) {
                "First gameplay screen observed."
            } else {
                "Gameplay screen changed since the previous observation."
            }
            saveGameplayNote("OBSERVATION: " + currentObservation)
            generalQuestionNumber += 1
            val questionKey = "general_gameplay_" + generalQuestionNumber
            if (activeQuestionKey == null) {
                activeQuestionKey = questionKey
                askedThisSession.add(questionKey)
                mainHandler.post {
                    teachQuestion?.text = "I noticed the screen changed. What were you doing just now?"
                    answerField?.setText("")
                    teachPanel?.visibility = android.view.View.VISIBLE
                }
            }
            lastGeneralQuestionAt = now
        }
        lastScreenText = current
    }

    private fun saveGameplayNote(note: String) {
        val prefsKey = "whole_gameplay_notes"
        val old = learnedPrefs.getString(prefsKey, "") ?: ""
        val entry = System.currentTimeMillis().toString() + " | " + note.replace("\\n", " ").take(1200)
        val notes = (old.split("\\n").filter { it.isNotBlank() } + entry).takeLast(150)
        learnedPrefs.edit().putString(prefsKey, notes.joinToString("\\n")).apply()
    }

    private fun saveQuickExplanation(choice: String) {
        answerField?.setText(choice)
        saveGeneralExplanation()
    }

    private fun saveGeneralExplanation() {
        val explanation = answerField?.text?.toString()?.trim().orEmpty()
        if (explanation.isBlank()) {
            sendStatus("Type a short explanation so I can learn what happened.")
            return
        }
        val note = "PLAYER EXPLANATION: " + explanation + " | CONTEXT: " + currentObservation
        saveGameplayNote(note)
        activeQuestionKey?.let { learnedPrefs.edit().putString("learned_" + it, note).apply() }
        activeQuestionKey = null
        currentObservation = ""
        teachPanel?.visibility = android.view.View.GONE
        sendStatus("Saved your gameplay explanation locally on this phone.")
    }

    private fun answerTeachQuestion(isYes: Boolean) {
        val key = activeQuestionKey ?: return
        learnedPrefs.edit().putBoolean(key, isYes).apply()
        teachPanel?.visibility = android.view.View.GONE
        sendStatus("Learned: $key = ${if (isYes) "YES" else "NO"}. Saved on this device.")
        activeQuestionKey = null
    }

    private fun removeTeachOverlay() {
        try { highlightOverlay?.let { overlayManager?.removeView(it) } } catch (_: Exception) {}
        highlightOverlay = null
        try { teachPill?.let { overlayManager?.removeView(it) } } catch (_: Exception) {}
        try { teachPanel?.let { overlayManager?.removeView(it) } } catch (_: Exception) {}
        teachPill = null; teachPanel = null; teachQuestion = null; overlayManager = null
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
        mainHandler.post { removeTeachOverlay() }
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
