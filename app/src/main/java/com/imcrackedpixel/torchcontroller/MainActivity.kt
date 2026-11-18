package com.imcrackedpixel.torchcontroller
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
class MainActivity:Activity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(28,28,28,28)}
  root.addView(TextView(this).apply{text="TAP CONTROLLER\n\n1. Allow display over other apps.\n2. Enable Accessibility gesture access.\n3. Show the floating controller.\n4. Drag T1–T4 to target positions. Tap bubbles to toggle. Set cooldowns, START ALL, STOP ALL.";textSize=17f})
  root.addView(Button(this).apply{text="1 · Allow overlay";setOnClickListener{startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+packageName)))}})
  root.addView(Button(this).apply{text="2 · Enable gesture access";setOnClickListener{startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)}})
  root.addView(Button(this).apply{text="3 · Show floating controller";setOnClickListener{if(Settings.canDrawOverlays(this@MainActivity))startForegroundService(Intent(this@MainActivity,OverlayService::class.java))else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+packageName)))}})
  root.addView(Button(this).apply{text="Stop floating controller";setOnClickListener{stopService(Intent(this@MainActivity,OverlayService::class.java))}})
  setContentView(root)
 }
}
