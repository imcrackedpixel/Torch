package com.imcrackedpixel.torchcontroller
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.view.accessibility.AccessibilityEvent
class TouchService : AccessibilityService() {
    override fun onAccessibilityEvent(event:AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onServiceConnected(){instance=this}
    override fun onDestroy(){if(instance===this)instance=null;super.onDestroy()}
    fun tapAt(x:Float,y:Float){val path=Path().apply{moveTo(x,y)};val stroke=GestureDescription.StrokeDescription(path,0,80);dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),null,null)}
    // Holds a virtual joystick away from its centre, then releases it.
    fun moveJoystick(centerX: Float, centerY: Float, directionX: Float, directionY: Float, holdMs: Long = 650L) {
        val path = Path().apply {
            moveTo(centerX, centerY)
            lineTo(centerX + directionX, centerY + directionY)
        }
        val duration = holdMs.coerceIn(250L, 1400L)
        val stroke = GestureDescription.StrokeDescription(path, 0L, duration)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
    companion object{@Volatile var instance:TouchService?=null}
}
