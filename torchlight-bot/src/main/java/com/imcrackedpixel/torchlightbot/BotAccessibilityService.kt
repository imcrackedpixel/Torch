package com.imcrackedpixel.torchlightbot
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import org.json.JSONArray
import org.json.JSONObject
class BotAccessibilityService : AccessibilityService() {
 private val handler=Handler(Looper.getMainLooper())
 private var active=false
 private var cycle=0
 private val receiver=object:BroadcastReceiver() {
  override fun onReceive(context:Context?,intent:Intent?) { when(intent?.action) { ACTION_START->startRoutine(); ACTION_STOP->stopRoutine() } }
 }
 override fun onServiceConnected() {
  super.onServiceConnected()
  val f=IntentFilter().apply { addAction(ACTION_START); addAction(ACTION_STOP) }
  if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,f,RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(receiver,f)
 }
 override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
 override fun onInterrupt() { stopRoutine() }
 override fun onDestroy() { stopRoutine(); try { unregisterReceiver(receiver) } catch (_:Exception) {}; super.onDestroy() }
 private fun startRoutine() { if(active)return; active=true; cycle=0; log("START","Routine started"); handler.post(step) }
 private fun stopRoutine() { active=false; handler.removeCallbacks(step); log("STOP","Routine stopped") }
 private fun point(key:String,fallback:Int):Float {
  val p=getSharedPreferences("bot_config",MODE_PRIVATE)
  val pct=(p.getString(key,fallback.toString())?.toFloatOrNull()?:fallback.toFloat()).coerceIn(0f,100f)
  val dm=resources.displayMetrics
  return pct/100f*(if(key.endsWith("_x")) dm.widthPixels else dm.heightPixels)
 }
 private val step: Runnable = object:Runnable {
  override fun run() {
   if(!active)return
   val p=getSharedPreferences("bot_config",MODE_PRIVATE)
   val dm=resources.displayMetrics
   val cx=point("joy_x",15); val cy=point("joy_y",82)
   val hold=(p.getString("move_ms","900")?.toLongOrNull()?:900L).coerceIn(300L,1400L)
   val every=(p.getString("skill_every","1")?.toIntOrNull()?:1).coerceIn(1,10)
   val d=when((cycle/4)%4) { 0->Pair(0f,-1f); 1->Pair(1f,0f); 2->Pair(0f,1f); else->Pair(-1f,0f) }
   val radius=dm.widthPixels*0.075f
   val path=Path().apply { moveTo(cx,cy); lineTo(cx+d.first*radius,cy+d.second*radius) }
   val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,hold)).build()
   dispatchGesture(gesture,object:GestureResultCallback() {
    override fun onCompleted(g:GestureDescription?) {
     if(!active)return
     log("MOVE","dir="+d.first+","+d.second+" cycle="+cycle)
     cycle++
     if(cycle%every==0) {
      val key=if((cycle/every)%2==0) "s1_x" else "s2_x"
      val yKey=if(key=="s1_x") "s1_y" else "s2_y"
      tap(point(key,if(key=="s1_x")78 else 88),point(yKey,if(yKey=="s1_y")82 else 72))
      log("SKILL","tap "+key)
     }
     handler.postDelayed(step,500L)
    }
    override fun onCancelled(g:GestureDescription?) { if(active)handler.postDelayed(step,1000L) }
   },null)
  }
 }
 private fun tap(x:Float,y:Float) {
  val path=Path().apply { moveTo(x,y) }
  val g=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,70)).build()
  dispatchGesture(g,null,null)
 }
 private fun log(type:String,detail:String) {
  val p=getSharedPreferences("bot_config",MODE_PRIVATE)
  val a=try { JSONArray(p.getString("action_log","[]")) } catch(_:Exception) { JSONArray() }
  a.put(JSONObject().apply { put("time_ms",System.currentTimeMillis()); put("type",type); put("detail",detail) })
  while(a.length()>500)a.remove(0)
  p.edit().putString("action_log",a.toString()).apply()
 }
 companion object { const val ACTION_START="com.imcrackedpixel.torchlightbot.START"; const val ACTION_STOP="com.imcrackedpixel.torchlightbot.STOP" }
}