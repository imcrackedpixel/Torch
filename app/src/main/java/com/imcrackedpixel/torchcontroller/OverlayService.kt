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
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// Supervised experiments are bounded and user-controlled.
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var panel: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var recording = false
    private var replaying = false
    private var experimenting = false
    private var aiPlaying = false
    private var aiPlayIndex = 0
    private var aiWaitingForResult = false
    private val aiPlayLoop = object : Runnable {
        override fun run() {
            if (!aiPlaying || aiWaitingForResult) return
            val state = getSharedPreferences("live_teaching", MODE_PRIVATE).getString("last_state", "") ?: ""
            if (!state.contains("POSSIBLE COMBAT", true)) {
                status?.text = "AI running · waiting for possible combat"
                handler.postDelayed(this, 1800L)
                return
            }
            val available = (0..3).filter { enabled[it] }
            if (available.isEmpty()) { stopAiPlay("AI stopped: turn ON at least one T button"); return }
            val index = chooseLearningButton(available)
            val b = taps[index]; val p = params[index]; val svc = TouchService.instance
            if (b == null || p == null || svc == null) { stopAiPlay("AI stopped: controller or Accessibility unavailable"); return }
            val x = p.x + b.width / 2f
            val y = p.y + b.height / 2f
            val beforeBars = getSharedPreferences("live_teaching", MODE_PRIVATE).getInt("enemy_bar_count", -1)
            val beforeObservation = getSharedPreferences("live_teaching", MODE_PRIVATE).getString("last_observation", "") ?: ""
            svc.tapAt(x, y)
            recordAction(index, x, y)
            aiWaitingForResult = true
            status?.text = "AI learning · tried T" + (index + 1) + " · checking screen evidence"
            handler.postDelayed({
                if (!aiPlaying) { aiWaitingForResult = false; return@postDelayed }
                val live = getSharedPreferences("live_teaching", MODE_PRIVATE)
                val afterBars = live.getInt("enemy_bar_count", -1)
                val afterObservation = live.getString("last_observation", "") ?: ""
                recordAiOutcome(index, beforeBars, afterBars, beforeObservation, afterObservation)
                aiWaitingForResult = false
                handler.postDelayed(this, 900L)
            }, 2400L)
        }
    }
    private var experimentIndex = 0
    private var experimentStartedAt = 0L
    private val replayTasks = mutableListOf<Runnable>()
    private val experimentLoop = object : Runnable {
        override fun run() {
            if (!experimenting) return
            if (System.currentTimeMillis() - experimentStartedAt >= 60000L) {
                stopExperiment("Experiment finished after 60 seconds")
                return
            }
            val available = (0..3).filter { enabled[it] }
            if (available.isEmpty()) {
                stopExperiment("Experiment stopped: enable at least one T button")
                return
            }
            val index = available[experimentIndex % available.size]
            experimentIndex++
            val button = taps[index]
            val lp = params[index]
            val service = TouchService.instance
            if (button == null || lp == null || service == null) {
                stopExperiment("Experiment stopped: controller unavailable")
                return
            }
            val x = lp.x + button.width / 2f
            val y = lp.y + button.height / 2f
            val before = getSharedPreferences("live_teaching", MODE_PRIVATE)
                .getString("last_observation", "") ?: ""
            service.tapAt(x, y)
            saveExperimentTrial(index, x, y, before)
            status?.text = "Experiment: testing T" + (index + 1) + " · " + ((60000L - (System.currentTimeMillis() - experimentStartedAt)) / 1000L) + "s left"
            handler.postDelayed(this, 3000L)
        }
    }
    private val memoryPrefs by lazy { getSharedPreferences("game_action_memory", MODE_PRIVATE) }
    private var status: TextView? = null
    private val taps = arrayOfNulls<Button>(4)
    private val params = arrayOfNulls<WindowManager.LayoutParams>(4)
    private val cooldowns = longArrayOf(500, 1000, 1500, 2000)
    private val enabled = BooleanArray(4)
    private val cooldownChoices = longArrayOf(250, 500, 750, 1000, 1500, 2000, 3000, 5000)
    private val prefs by lazy { getSharedPreferences("tap_positions", MODE_PRIVATE) }
    private val safetyStop = Runnable { if (running) stopAutomation("5-minute safety limit reached") }
    private val loops = Array(4) { index -> object : Runnable {
        override fun run() {
            if (!running || !enabled[index]) return
            val b=taps[index] ?: return
            val p=params[index] ?: return
            val svc=TouchService.instance
            if(svc==null){ stopAutomation("Accessibility disconnected"); return }
            val tapX = p.x + b.width / 2f
            val tapY = p.y + b.height / 2f
            svc.tapAt(tapX, tapY)
            recordAction(index, tapX, tapY)
            handler.postDelayed(this,cooldowns[index])
        }
    }}
    override fun onCreate() {
        super.onCreate()
        startForeground(7,notification())
        wm=getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
    }
    private fun notification():Notification {
        val id="torch_controller"
        if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(id,"Tap Controller",NotificationManager.IMPORTANCE_LOW))
        return if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,id).setContentTitle("Tap Controller active").setSmallIcon(android.R.drawable.ic_media_play).build()
        else @Suppress("DEPRECATION") Notification.Builder(this).setContentTitle("Tap Controller active").setSmallIcon(android.R.drawable.ic_media_play).build()
    }
    private fun buildOverlay() {
        val density=resources.displayMetrics.density
        for(i in 0..3) {
            val button=Button(this).apply { text="T"+(i+1)+"\nOFF"; textSize=12f; setTextColor(Color.WHITE); setBackgroundColor(0xCC334455.toInt()); isAllCaps=false }
            val lp=WindowManager.LayoutParams((64*density).roundToInt(),(64*density).roundToInt(),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
                gravity=Gravity.TOP or Gravity.LEFT
                x=prefs.getInt("x"+i,(70+i*72)*density.roundToInt())
                y=prefs.getInt("y"+i,(220+i*12)*density.roundToInt())
            }
            params[i]=lp; taps[i]=button
            var downX=0f; var downY=0f; var startX=0; var startY=0; var dragged=false
            button.setOnTouchListener { v,e ->
                when(e.action) {
                    MotionEvent.ACTION_DOWN -> { downX=e.rawX; downY=e.rawY; startX=lp.x; startY=lp.y; dragged=false; true }
                    MotionEvent.ACTION_MOVE -> {
                        if(kotlin.math.abs(e.rawX-downX)>8*density || kotlin.math.abs(e.rawY-downY)>8*density) dragged=true
                        if(dragged){lp.x=startX+(e.rawX-downX).roundToInt(); lp.y=startY+(e.rawY-downY).roundToInt(); wm.updateViewLayout(v,lp)}
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if(dragged) prefs.edit().putInt("x"+i,lp.x).putInt("y"+i,lp.y).apply()
                        else { enabled[i]=!enabled[i]; button.text="T"+(i+1)+"\n"+(if(enabled[i]) "ON" else "OFF"); button.setBackgroundColor(if(enabled[i]) 0xCC2E7D32.toInt() else 0xCC334455.toInt()); updateStatus() }
                        true
                    }
                    else -> true
                }
            }
            wm.addView(button,lp)
        }
        panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(8,8,8,8); setBackgroundColor(0xDD20252B.toInt()) }
        status=TextView(this).apply { text="Tap bubbles to enable · drag to move"; setTextColor(Color.WHITE); textSize=12f }
        panel.addView(status)
        panel.addView(Button(this).apply {
            text = "SHOW LEARNING BRAIN"
            setOnClickListener {
                val brain = getSharedPreferences("live_teaching", MODE_PRIVATE)
                    .getString("brain_summary", "") ?: ""
                status?.text = if (brain.isBlank()) {
                    "Learning brain is waiting for live screen recognition"
                } else brain
            }
        })
        panel.addView(Button(this).apply {
            text = "START ACTION RECORDING"
            setOnClickListener {
                recording = !recording
                if (recording) memoryPrefs.edit().putString("events", "[]").apply()
                text = if (recording) "STOP RECORDING" else "START ACTION RECORDING"
                status?.text = if (recording) "Recording controller taps with screen context" else "Recording saved on this phone"
            }
        })
        panel.addView(Button(this).apply {
            text = "GO AI (LEARN & PLAY)"
            setOnClickListener { startAiPlay() }
        })
        panel.addView(Button(this).apply {
            text = "STOP AI"
            setOnClickListener { stopAiPlay("AI play stopped by you") }
        })
        panel.addView(Button(this).apply {
            text = "REPLAY LAST RECORDING"
            setOnClickListener { replayLastRecording() }
        })
        panel.addView(Button(this).apply {
            text = "CLEAR ACTION MEMORY"
            setOnClickListener {
                stopReplay()
                memoryPrefs.edit().remove("events").apply()
                status?.text = "Action memory cleared"
            }
        })
        for(i in 0..3) panel.addView(Button(this).apply {
            text="T"+(i+1)+" cooldown: "+cooldowns[i]+"ms"
            setOnClickListener {
                val index=cooldownChoices.indexOf(cooldowns[i]).let{if(it<0)0 else it}
                cooldowns[i]=cooldownChoices[(index+1)%cooldownChoices.size]
                text="T"+(i+1)+" cooldown: "+cooldowns[i]+"ms"
            }
        })
        panel.addView(Button(this).apply { text="START ALL"; setOnClickListener {
            if(TouchService.instance==null){status?.text="Enable Accessibility first";return@setOnClickListener}
            if(!running){running=true; for(i in 0..3) if(enabled[i]) handler.post(loops[i]); handler.removeCallbacks(safetyStop); handler.postDelayed(safetyStop,300000); updateStatus()}
        }})
        panel.addView(Button(this).apply { text="STOP ALL"; setOnClickListener { stopAutomation("Stopped") } })
        panel.addView(Button(this).apply { text="CLOSE"; setOnClickListener { stopSelf() } })
        val panelParams=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply { gravity=Gravity.TOP or Gravity.RIGHT; x=(8*density).roundToInt(); y=(48*density).roundToInt() }
        wm.addView(panel,panelParams)
    }
    // Learns a cautious per-button preference from repeated, local screen observations.
    // A falling count of candidate red bars is weak evidence only, not proof of damage.
    private fun chooseLearningButton(available: List<Int>): Int {
        val scores = try { JSONObject(memoryPrefs.getString("skill_learning", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val unexplored = available.filter { index ->
            val item = scores.optJSONObject((index + 1).toString())
            (item?.optInt("trials", 0) ?: 0) < 2
        }
        if (unexplored.isNotEmpty()) return unexplored.minByOrNull { index ->
            scores.optJSONObject((index + 1).toString())?.optInt("trials", 0) ?: 0
        } ?: unexplored.first()
        return available.maxByOrNull { index ->
            val item = scores.optJSONObject((index + 1).toString()) ?: JSONObject()
            val trials = item.optInt("trials", 0).coerceAtLeast(1)
            (item.optInt("positive", 0).toDouble() - item.optInt("negative", 0).toDouble() * 0.5) / trials
        } ?: available.first()
    }

    private fun recordAiOutcome(index: Int, beforeBars: Int, afterBars: Int, beforeText: String, afterText: String) {
        try {
            val key = (index + 1).toString()
            val scores = JSONObject(memoryPrefs.getString("skill_learning", "{}") ?: "{}")
            val item = scores.optJSONObject(key) ?: JSONObject()
            val positive = beforeBars > 0 && afterBars >= 0 && afterBars < beforeBars
            val negative = beforeBars > 0 && afterBars > beforeBars
            item.put("trials", item.optInt("trials", 0) + 1)
            item.put("positive", item.optInt("positive", 0) + if (positive) 1 else 0)
            item.put("negative", item.optInt("negative", 0) + if (negative) 1 else 0)
            item.put("last_evidence", when {
                positive -> "candidate bar count decreased after tap; uncertain correlation"
                negative -> "candidate bar count increased; inconclusive"
                beforeBars < 0 || afterBars < 0 -> "bar count unavailable"
                else -> "no clear bar-count decrease"
            })
            scores.put(key, item)
            memoryPrefs.edit().putString("skill_learning", scores.toString()).apply()

            val history = JSONArray(memoryPrefs.getString("ai_learning_history", "[]") ?: "[]")
            history.put(JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("button", index + 1)
                put("before_bars", beforeBars)
                put("after_bars", afterBars)
                put("evidence", item.optString("last_evidence"))
                put("screen_changed", beforeText != afterText)
            })
            while (history.length() > 100) history.remove(0)
            memoryPrefs.edit().putString("ai_learning_history", history.toString()).apply()
            status?.text = "AI learned from T" + (index + 1) + " · " + item.optInt("trials") + " trials · evidence: " + item.optString("last_evidence")
        } catch (_: Exception) {
            status?.text = "AI is playing · couldn't save this learning result"
        }
    }

    private fun startAiPlay() {
        if (aiPlaying) { status?.text = "AI play is already running"; return }
        if (TouchService.instance == null) { status?.text = "Enable Accessibility before AI play"; return }
        if ((0..3).none { enabled[it] }) { status?.text = "Turn ON the skill buttons you want AI to try first"; return }
        stopAutomation("Pausing other automation for AI play")
        aiPlaying = true; aiPlayIndex = 0; aiWaitingForResult = false
        status?.text = "AI PLAY ready · waiting for possible combat · 60-second limit"
        handler.post(aiPlayLoop)
    }

    private fun stopAiPlay(message: String) {
        aiPlaying = false
        aiWaitingForResult = false
        handler.removeCallbacks(aiPlayLoop)
        status?.text = message
    }

    private fun startExperiment() {
        if (experimenting) {
            status?.text = "Experiment is already running"
            return
        }
        if (TouchService.instance == null) {
            status?.text = "Enable Accessibility before experimenting"
            return
        }
        if ((0..3).none { enabled[it] }) {
            status?.text = "Turn ON at least one T1–T4 button first"
            return
        }
        stopAutomation("Automation paused for experiment")
        experimenting = true
        experimentIndex = 0
        experimentStartedAt = System.currentTimeMillis()
        status?.text = "Experiment started · one enabled skill every 3 seconds · 60-second limit"
        handler.post(experimentLoop)
    }

    private fun stopExperiment(message: String) {
        experimenting = false
        handler.removeCallbacks(experimentLoop)
        status?.text = message
    }

    private fun saveExperimentTrial(index: Int, x: Float, y: Float, before: String) {
        try {
            val trialTime = System.currentTimeMillis()
            val events = JSONArray(memoryPrefs.getString("experiments", "[]") ?: "[]")
            events.put(JSONObject().apply {
                put("time", trialTime)
                put("button", index + 1)
                put("x", x.toDouble())
                put("y", y.toDouble())
                put("before", before.take(180))
                put("result", "pending_observation")
            })
            while (events.length() > 80) events.remove(0)
            memoryPrefs.edit().putString("experiments", events.toString()).apply()
            handler.postDelayed({
                try {
                    val after = getSharedPreferences("live_teaching", MODE_PRIVATE)
                        .getString("last_observation", "") ?: ""
                    val latest = JSONArray(memoryPrefs.getString("experiments", "[]") ?: "[]")
                    for (i in latest.length() - 1 downTo 0) {
                        val trial = latest.optJSONObject(i) ?: continue
                        if (trial.optLong("time") == trialTime) {
                            trial.put("after", after.take(180))
                            trial.put("result", if (after.isNotBlank() && after != before) "screen_text_changed" else "no_text_change_detected")
                            break
                        }
                    }
                    memoryPrefs.edit().putString("experiments", latest.toString()).apply()
                } catch (_: Exception) { }
            }, 1800L)
        } catch (_: Exception) { }
    }

    private fun recordAction(index: Int, x: Float, y: Float) {
        if (!recording) return
        try {
            val events = JSONArray(memoryPrefs.getString("events", "[]") ?: "[]")
            val context = getSharedPreferences("live_teaching", MODE_PRIVATE)
                .getString("last_observation", "") ?: ""
            events.put(JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("button", index + 1)
                put("x", x.toDouble())
                put("y", y.toDouble())
                put("context", context.take(160))
            })
            while (events.length() > 120) events.remove(0)
            memoryPrefs.edit().putString("events", events.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun replayLastRecording() {
        if (replaying) {
            stopReplay()
            status?.text = "Replay stopped"
            return
        }
        if (TouchService.instance == null) {
            status?.text = "Enable Accessibility before replay"
            return
        }
        val events = try { JSONArray(memoryPrefs.getString("events", "[]") ?: "[]") }
            catch (_: Exception) { JSONArray() }
        if (events.length() < 2) {
            status?.text = "Record at least two controller taps first"
            return
        }
        stopAutomation("Automation paused for replay")
        val startTime = events.optJSONObject(0)?.optLong("time") ?: 0L
        replaying = true
        status?.text = "Replaying " + events.length() + " recorded taps · tap STOP ALL to cancel"
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val offset = (event.optLong("time") - startTime).coerceIn(0L, 45000L)
            val x = event.optDouble("x").toFloat()
            val y = event.optDouble("y").toFloat()
            val task = Runnable {
                if (replaying) TouchService.instance?.tapAt(x, y)
                if (i == events.length() - 1) {
                    replaying = false
                    status?.text = "Replay finished"
                }
            }
            replayTasks += task
            handler.postDelayed(task, offset)
        }
    }

    private fun stopReplay() {
        replayTasks.forEach { handler.removeCallbacks(it) }
        replayTasks.clear()
        replaying = false
    }

    private fun updateStatus(){ status?.text=if(running) "Running · "+enabled.count{it}+" active · STOP ALL" else "Tap bubbles to enable · drag to move" }
    private fun stopAutomation(message:String){stopAiPlay(message);running=false;for(i in 0..3)handler.removeCallbacks(loops[i]);handler.removeCallbacks(safetyStop);stopReplay();stopExperiment(message);status?.text=message}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_STICKY
    override fun onDestroy(){recording=false;stopAutomation("Controller closed");for(b in taps)if(b?.isAttachedToWindow==true)wm.removeView(b);if(::panel.isInitialized&&panel.isAttachedToWindow)wm.removeView(panel);super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
