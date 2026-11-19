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
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// Continuous user-controlled learning mode; it runs until the user presses STOP AI.
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var panel: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var recording = false
    private var replaying = false
    private var aiPlaying = false
    private var aiWaitingForResult = false
    private var movementAnchor: Button? = null
    private var movementParams: WindowManager.LayoutParams? = null
    private var interactButton: Button? = null
    private var interactParams: WindowManager.LayoutParams? = null
    private var movementDirectionIndex = 0
    private val aiPlayLoop: Runnable = object : Runnable {
        override fun run() {
            if (!aiPlaying || aiWaitingForResult) return
            val state = getSharedPreferences("live_teaching", MODE_PRIVATE).getString("last_state", "") ?: ""
            val live = getSharedPreferences("live_teaching", MODE_PRIVATE)
            val combat = state.contains("POSSIBLE COMBAT", true)
            val available = (0..3).filter { enabled[it] }
            val svc = TouchService.instance
            if (svc == null) { stopAiPlay("Accessibility disconnected · press GO again when restored"); return }
            val beforeBars = live.getInt("enemy_bar_count", -1)
            val beforeObservation = live.getString("last_observation", "") ?: ""
            val beforeState = state
            val observedText = live.getString("last_observation", "") ?: ""
            val interactionCue = listOf("interact", "talk to", "speak to", "press", "continue", "claim", "accept", "open", "enter", "confirm", "use").any { observedText.contains(it, true) }
            val interactionPoint = interactParams
            if (interactionCue && interactionPoint != null) {
                val x = interactionPoint.x + 32f * resources.displayMetrics.density
                val y = interactionPoint.y + 32f * resources.displayMetrics.density
                svc.tapAt(x, y)
                aiWaitingForResult = true
                status?.text = "AI testing mapped interaction control"
                handler.postDelayed({
                    if (!aiPlaying) { aiWaitingForResult = false; return@postDelayed }
                    val afterText = getSharedPreferences("live_teaching", MODE_PRIVATE).getString("last_observation", "") ?: ""
                    val history = JSONArray(memoryPrefs.getString("interaction_learning", "[]") ?: "[]")
                    history.put(JSONObject().apply {
                        put("time", System.currentTimeMillis())
                        put("screen_changed", beforeObservation != afterText)
                        put("evidence", if (beforeObservation != afterText) "visible text changed after interaction tap; uncertain correlation" else "no visible text change")
                    })
                    while (history.length() > 80) history.remove(0)
                    memoryPrefs.edit().putString("interaction_learning", history.toString()).apply()
                    aiWaitingForResult = false
                    handler.postDelayed(aiPlayLoop, 650L)
                }, 1800L)
                return
            }
            val trySkill = combat && available.isNotEmpty() && (movementDirectionIndex % 4 != 3)
            if (trySkill) {
                val index = chooseLearningButton(available)
                val b = taps[index]; val p = params[index]
                if (b != null && p != null) {
                    val x = p.x + b.width / 2f
                    val y = p.y + b.height / 2f
                    svc.tapAt(x, y)
                    recordAction(index, x, y)
                    aiWaitingForResult = true
                    status?.text = "AI experimenting · skill T" + (index + 1)
                    handler.postDelayed({
                        if (!aiPlaying) { aiWaitingForResult = false; return@postDelayed }
                        val after = getSharedPreferences("live_teaching", MODE_PRIVATE)
                        recordAiOutcome(index, beforeBars, after.getInt("enemy_bar_count", -1), beforeObservation, after.getString("last_observation", "") ?: "")
                        aiWaitingForResult = false
                        handler.postDelayed(aiPlayLoop, 500L)
                    }, 2200L)
                    return
                }
            }
            val anchor = movementParams
            if (anchor == null) { status?.text = "AI needs a movement anchor: drag M onto the game's joystick"; handler.postDelayed(this, 1600L); return }
            val directions = arrayOf(
                Pair(0f, -42f), Pair(30f, -30f), Pair(42f, 0f), Pair(30f, 30f),
                Pair(0f, 42f), Pair(-30f, 30f), Pair(-42f, 0f), Pair(-30f, -30f)
            )
            val dirIndex = chooseMovementDirection()
            val direction = directions[dirIndex]
            svc.moveJoystick(anchor.x + 32f * resources.displayMetrics.density, anchor.y + 32f * resources.displayMetrics.density, direction.first * resources.displayMetrics.density, direction.second * resources.displayMetrics.density, 700L)
            aiWaitingForResult = true
            status?.text = "AI exploring · movement direction " + (dirIndex + 1) + "/8"
            handler.postDelayed({
                if (!aiPlaying) { aiWaitingForResult = false; return@postDelayed }
                val after = getSharedPreferences("live_teaching", MODE_PRIVATE)
                recordMovementOutcome(dirIndex, beforeState, after.getString("last_state", "") ?: "", beforeObservation, after.getString("last_observation", "") ?: "")
                aiWaitingForResult = false
                handler.postDelayed(aiPlayLoop, 450L)
            }, 1900L)
        }
    }
    private val replayTasks = mutableListOf<Runnable>()
    private val memoryPrefs by lazy { getSharedPreferences("game_action_memory", MODE_PRIVATE) }
    private var status: TextView? = null
    private val taps = arrayOfNulls<Button>(4)
    private val params = arrayOfNulls<WindowManager.LayoutParams>(4)
    private val cooldowns = longArrayOf(500, 1000, 1500, 2000)
    private val enabled = BooleanArray(4)
    private val cooldownChoices = longArrayOf(250, 500, 750, 1000, 1500, 2000, 3000, 5000)
    private val prefs by lazy { getSharedPreferences("tap_positions", MODE_PRIVATE) }
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
        val moveButton = Button(this).apply { text="M\nMOVE"; textSize=11f; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(0xCC6A4C93.toInt()) }
        val moveLp = WindowManager.LayoutParams((64*density).roundToInt(),(64*density).roundToInt(),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            x=prefs.getInt("move_x",(24*density).roundToInt())
            y=prefs.getInt("move_y",(resources.displayMetrics.heightPixels-190*density).roundToInt())
        }
        movementAnchor=moveButton; movementParams=moveLp
        var moveDownX=0f; var moveDownY=0f; var moveStartX=0; var moveStartY=0; var moveDragged=false
        moveButton.setOnTouchListener { v,e ->
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { moveDownX=e.rawX; moveDownY=e.rawY; moveStartX=moveLp.x; moveStartY=moveLp.y; moveDragged=false; true }
                MotionEvent.ACTION_MOVE -> {
                    if(kotlin.math.abs(e.rawX-moveDownX)>8*density || kotlin.math.abs(e.rawY-moveDownY)>8*density) moveDragged=true
                    if(moveDragged){moveLp.x=moveStartX+(e.rawX-moveDownX).roundToInt();moveLp.y=moveStartY+(e.rawY-moveDownY).roundToInt();wm.updateViewLayout(v,moveLp)}
                    true
                }
                MotionEvent.ACTION_UP -> { if(moveDragged) prefs.edit().putInt("move_x",moveLp.x).putInt("move_y",moveLp.y).apply(); true }
                else -> true
            }
        }
        wm.addView(moveButton,moveLp)

        val interact = Button(this).apply { text="I\nUSE"; textSize=11f; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(0xCCB36B24.toInt()) }
        val interactLp = WindowManager.LayoutParams((64*density).roundToInt(),(64*density).roundToInt(),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.LEFT
            x=prefs.getInt("interact_x",(resources.displayMetrics.widthPixels-90*density).roundToInt())
            y=prefs.getInt("interact_y",(resources.displayMetrics.heightPixels-190*density).roundToInt())
        }
        interactButton=interact; interactParams=interactLp
        var interactDownX=0f; var interactDownY=0f; var interactStartX=0; var interactStartY=0; var interactDragged=false
        interact.setOnTouchListener { v,e ->
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { interactDownX=e.rawX; interactDownY=e.rawY; interactStartX=interactLp.x; interactStartY=interactLp.y; interactDragged=false; true }
                MotionEvent.ACTION_MOVE -> {
                    if(kotlin.math.abs(e.rawX-interactDownX)>8*density || kotlin.math.abs(e.rawY-interactDownY)>8*density) interactDragged=true
                    if(interactDragged){interactLp.x=interactStartX+(e.rawX-interactDownX).roundToInt();interactLp.y=interactStartY+(e.rawY-interactDownY).roundToInt();wm.updateViewLayout(v,interactLp)}
                    true
                }
                MotionEvent.ACTION_UP -> { if(interactDragged) prefs.edit().putInt("interact_x",interactLp.x).putInt("interact_y",interactLp.y).apply(); true }
                else -> true
            }
        }
        wm.addView(interact,interactLp)
        panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(6,6,6,6); setBackgroundColor(0xEE20252B.toInt()) }
        val panelParams=WindowManager.LayoutParams((236*density).roundToInt(),WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.RIGHT
            x=prefs.getInt("menu_x",(8*density).roundToInt())
            y=prefs.getInt("menu_y",(48*density).roundToInt())
        }
        val content = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(2,4,2,4) }
        val header = LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF303943.toInt()); setPadding(6,2,2,2)
        }
        val title = TextView(this).apply { text="TORCH CONTROLLER"; setTextColor(Color.WHITE); textSize=12f; setPadding(4,8,4,8) }
        val collapse = Button(this).apply { text="−"; textSize=18f; isAllCaps=false; setTextColor(Color.WHITE); setBackgroundColor(0xFF46515D.toInt()); minWidth=(42*density).roundToInt() }
        header.addView(title, LinearLayout.LayoutParams(0,(42*density).roundToInt(),1f))
        header.addView(collapse, LinearLayout.LayoutParams((42*density).roundToInt(),(42*density).roundToInt()))
        panel.addView(header)
        val scroll = ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=true; overScrollMode=ScrollView.OVER_SCROLL_IF_CONTENT_SCROLLS }
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*0.52f).roundToInt()))
        collapse.setOnClickListener {
            val hidden = scroll.visibility == android.view.View.VISIBLE
            scroll.visibility = if (hidden) android.view.View.GONE else android.view.View.VISIBLE
            collapse.text = if (hidden) "+" else "−"
            panelParams.width = if (hidden) (58*density).roundToInt() else (236*density).roundToInt()
            title.text = if (hidden) "TORCH" else "TORCH CONTROLLER"
            wm.updateViewLayout(panel,panelParams)
        }
        var menuDownX=0f; var menuDownY=0f; var menuStartX=panelParams.x; var menuStartY=panelParams.y
        title.setOnTouchListener { _,e ->
            when(e.action) {
                MotionEvent.ACTION_DOWN -> { menuDownX=e.rawX; menuDownY=e.rawY; menuStartX=panelParams.x; menuStartY=panelParams.y; true }
                MotionEvent.ACTION_MOVE -> { panelParams.x=menuStartX+(menuDownX-e.rawX).roundToInt(); panelParams.y=menuStartY+(e.rawY-menuDownY).roundToInt(); wm.updateViewLayout(panel,panelParams); true }
                MotionEvent.ACTION_UP -> { prefs.edit().putInt("menu_x",panelParams.x).putInt("menu_y",panelParams.y).apply(); true }
                else -> true
            }
        }
        status=TextView(this).apply { text="Tap bubbles to enable · drag to move"; setTextColor(Color.WHITE); textSize=12f }
        content.addView(status)
        content.addView(Button(this).apply {
            text = "SHOW LEARNING BRAIN"
            setOnClickListener {
                val brain = getSharedPreferences("live_teaching", MODE_PRIVATE)
                    .getString("brain_summary", "") ?: ""
                status?.text = if (brain.isBlank()) {
                    "Learning brain is waiting for live screen recognition"
                } else brain
            }
        })
        content.addView(Button(this).apply {
            text = "START ACTION RECORDING"
            setOnClickListener {
                recording = !recording
                if (recording) memoryPrefs.edit().putString("events", "[]").apply()
                text = if (recording) "STOP RECORDING" else "START ACTION RECORDING"
                status?.text = if (recording) "Recording controller taps with screen context" else "Recording saved on this phone"
            }
        })
        content.addView(Button(this).apply {
            text = "GO AI (LEARN & PLAY)"
            setOnClickListener { startAiPlay() }
        })
        content.addView(Button(this).apply {
            text = "STOP AI"
            setOnClickListener { stopAiPlay("AI play stopped by you") }
        })
        content.addView(Button(this).apply {
            text = "REPLAY LAST RECORDING"
            setOnClickListener { replayLastRecording() }
        })
        content.addView(Button(this).apply {
            text = "CLEAR ACTION MEMORY"
            setOnClickListener {
                stopReplay()
                memoryPrefs.edit().remove("events").apply()
                status?.text = "Action memory cleared"
            }
        })
        for(i in 0..3) content.addView(Button(this).apply {
            text="T"+(i+1)+" cooldown: "+cooldowns[i]+"ms"
            setOnClickListener {
                val index=cooldownChoices.indexOf(cooldowns[i]).let{if(it<0)0 else it}
                cooldowns[i]=cooldownChoices[(index+1)%cooldownChoices.size]
                text="T"+(i+1)+" cooldown: "+cooldowns[i]+"ms"
            }
        })
        content.addView(Button(this).apply { text="START ALL"; setOnClickListener {
            if(TouchService.instance==null){status?.text="Enable Accessibility first";return@setOnClickListener}
            if(!running){running=true; for(i in 0..3) if(enabled[i]) handler.post(loops[i]); updateStatus()}
        }})
        content.addView(Button(this).apply { text="STOP ALL"; setOnClickListener { stopAutomation("Stopped") } })
        content.addView(Button(this).apply { text="CLOSE"; setOnClickListener { stopSelf() } })
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

    private fun chooseMovementDirection(): Int {
        val history = try { JSONArray(memoryPrefs.getString("movement_learning", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val trials = IntArray(8)
        val changes = IntArray(8)
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val d = item.optInt("direction", -1)
            if (d in 0..7) {
                trials[d]++
                if (item.optBoolean("screen_changed", false)) changes[d]++
            }
        }
        val unexplored = (0..7).filter { trials[it] < 3 }
        if (unexplored.isNotEmpty()) return unexplored.minByOrNull { trials[it] } ?: 0
        return (0..7).maxByOrNull { d ->
            (changes[d].toDouble() + 0.25) / (trials[d].toDouble() + 1.0)
        } ?: 0
    }

    private fun recordMovementOutcome(direction: Int, beforeState: String, afterState: String, beforeText: String, afterText: String) {
        try {
            val history = JSONArray(memoryPrefs.getString("movement_learning", "[]") ?: "[]")
            history.put(JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("direction", direction)
                put("before_state", beforeState)
                put("after_state", afterState)
                put("screen_changed", beforeText != afterText)
                put("evidence", if (beforeText != afterText) "screen OCR changed after movement" else "no OCR change detected")
            })
            while (history.length() > 160) history.remove(0)
            memoryPrefs.edit().putString("movement_learning", history.toString()).apply()
        } catch (_: Exception) { }
    }

    private fun startAiPlay() {
        if (aiPlaying) { status?.text = "AI play is already running"; return }
        if (TouchService.instance == null) { status?.text = "Enable Accessibility before AI play"; return }
        if (movementParams == null) { status?.text = "Movement anchor missing · restart controller"; return }
        stopAutomation("Pausing other automation for AI play")
        aiPlaying = true; aiWaitingForResult = false
        movementAnchor?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        interactButton?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        status?.text = "AI GO · learning continuously · STOP AI to end"
        handler.post(aiPlayLoop)
    }

    private fun stopAiPlay(message: String) {
        aiPlaying = false
        aiWaitingForResult = false
        movementAnchor?.let { if (!it.isAttachedToWindow) try { wm.addView(it, movementParams) } catch (_: Exception) {} }
        interactButton?.let { if (!it.isAttachedToWindow) try { wm.addView(it, interactParams) } catch (_: Exception) {} }
        handler.removeCallbacks(aiPlayLoop)
        status?.text = message
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
    private fun stopAutomation(message:String){stopAiPlay(message);running=false;for(i in 0..3)handler.removeCallbacks(loops[i]);stopReplay();status?.text=message}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_STICKY
    override fun onDestroy(){recording=false;stopAutomation("Controller closed");for(b in taps)if(b?.isAttachedToWindow==true)wm.removeView(b);movementAnchor?.let{if(it.isAttachedToWindow)wm.removeView(it)};interactButton?.let{if(it.isAttachedToWindow)wm.removeView(it)};if(::panel.isInitialized&&panel.isAttachedToWindow)wm.removeView(panel);super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
