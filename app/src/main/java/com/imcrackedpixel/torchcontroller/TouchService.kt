package com.imcrackedpixel.torchcontroller
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
class TouchService : AccessibilityService() {
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onServiceConnected(){instance=this}
    override fun onDestroy(){if(instance===this)instance=null;super.onDestroy()}
    fun tapAt(x:Float,y:Float){val path=Path().apply{moveTo(x,y)};val stroke=GestureDescription.StrokeDescription(path,0,80);dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),null,null)}
    companion object{@Volatile var instance:TouchService?=null}
}
