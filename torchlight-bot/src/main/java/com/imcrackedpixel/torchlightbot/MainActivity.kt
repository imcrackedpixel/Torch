package com.imcrackedpixel.torchlightbot
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
class MainActivity : Activity() {
 private lateinit var status: TextView
 private lateinit var fields: MutableMap<String,EditText>
 private var exportText="[]"
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  val prefs=getSharedPreferences("bot_config",MODE_PRIVATE)
  val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(22,24,22,24) }
  root.addView(TextView(this).apply { text="TORCHLIGHT BOT"; textSize=27f; gravity=Gravity.CENTER })
  root.addView(TextView(this).apply { text="Separate calibrated game controller. Enter each control position as a percentage of screen width/height. Example: X=15, Y=82 is 15% from the left and 82% from the top. Keep Torch AI running separately to observe the session."; textSize=14f; setPadding(0,12,0,12) })
  fields=mutableMapOf()
  fun field(label:String,key:String,default:String) {
   root.addView(TextView(this).apply { text=label; textSize=13f })
   val e=EditText(this).apply { setSingleLine(true); setText(prefs.getString(key,default)?:default); textSize=16f; hint="0–100" }
   root.addView(e); fields[key]=e
  }
  field("Joystick centre X %","joy_x","15"); field("Joystick centre Y %","joy_y","82")
  field("Main skill X %","s1_x","78"); field("Main skill Y %","s1_y","82")
  field("Second skill X %","s2_x","88"); field("Second skill Y %","s2_y","72")
  field("Movement hold milliseconds (300–1400)","move_ms","900")
  field("Tap a skill every N movement cycles","skill_every","1")
  root.addView(Button(this).apply { text="1 · ENABLE BOT ACCESSIBILITY"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
  root.addView(Button(this).apply { text="2 · SAVE CALIBRATION"; setOnClickListener { save(); status.text="Calibration saved." } })
  root.addView(Button(this).apply { text="START ROUTINE"; setOnClickListener { save(); sendBroadcast(Intent(BotAccessibilityService.ACTION_START).setPackage(packageName)); status.text="Start requested. Switch to Torchlight: Infinite. Return here and press EMERGENCY STOP to stop." } })
  root.addView(Button(this).apply { text="EMERGENCY STOP"; setOnClickListener { sendBroadcast(Intent(BotAccessibilityService.ACTION_STOP).setPackage(packageName)); status.text="Stop requested." } })
  root.addView(Button(this).apply { text="EXPORT ACTION LOG"; setOnClickListener {
   exportText=prefs.getString("action_log","[]")?:"[]"
   val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type="application/json"; putExtra(Intent.EXTRA_TITLE,"torchlight-bot-session.json") }
   @Suppress("DEPRECATION") startActivityForResult(i,7301)
  } })
  status=TextView(this).apply { text="Bot idle. Version 0.1 uses a fixed movement/skill routine. It does not yet visually recognise enemies, loot or walls."; textSize=14f; setPadding(0,14,0,8) }
  root.addView(status)
  setContentView(ScrollView(this).apply { addView(root) })
 }
 private fun save() { val e=getSharedPreferences("bot_config",MODE_PRIVATE).edit(); fields.forEach { (k,v)->e.putString(k,v.text.toString()) }; e.apply() }
 @Deprecated("Compatibility")
 override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode==7301 && resultCode==RESULT_OK && data?.data!=null) contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use { it.write(exportText) } }
}