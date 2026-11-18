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
import kotlin.math.roundToInt

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var panel: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
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
            svc.tapAt(p.x+b.width/2f,p.y+b.height/2f)
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
    private fun updateStatus(){ status?.text=if(running) "Running · "+enabled.count{it}+" active · STOP ALL" else "Tap bubbles to enable · drag to move" }
    private fun stopAutomation(message:String){running=false;for(i in 0..3)handler.removeCallbacks(loops[i]);handler.removeCallbacks(safetyStop);status?.text=message}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_STICKY
    override fun onDestroy(){stopAutomation("Controller closed");for(b in taps)if(b?.isAttachedToWindow==true)wm.removeView(b);if(::panel.isInitialized&&panel.isAttachedToWindow)wm.removeView(panel);super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
}
