package com.imcrackedpixel.torchcontroller

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class TouchService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun joystickDrag(startX: Float, startY: Float, dx: Float, dy: Float, totalMs: Long = 1800L) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(startX + dx, startY + dy)
            lineTo(startX + dx, startY + dy)
        }
        val moveMs = 220L
        val stroke = GestureDescription.StrokeDescription(path, 0, totalMs)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    companion object {
        @Volatile var instance: TouchService? = null
    }
}
