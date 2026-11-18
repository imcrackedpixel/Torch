package com.imcrackedpixel.torchcontroller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class TouchService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onServiceConnected() { instance=this }
    override fun onDestroy() { if (instance===this) instance=null; super.onDestroy() }

    fun joystickDrag(x: Float, y: Float, dx: Float, dy: Float, holdMs: Long=1200L) {
        val endX=x+dx
        val endY=y+dy
        val approach=Path().apply { moveTo(x,y); lineTo(endX,endY) }
        val first=GestureDescription.StrokeDescription(approach,0,220,true)
        val hold=Path().apply { moveTo(endX,endY); lineTo(endX,endY) }
        val continued=first.continueStroke(hold,220,holdMs,false)
        val gesture=GestureDescription.Builder().addStroke(first).addStroke(continued).build()
        dispatchGesture(gesture,null,null)
    }
    companion object { @Volatile var instance: TouchService?=null }
}