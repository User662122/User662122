package com.chesschat.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service for automated touch gestures
 * This service enables the app to dispatch touch events for auto-play functionality
 */
class ChessAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "ChessA11yService"
        private var instance: ChessAccessibilityService? = null
        
        fun getInstance(): ChessAccessibilityService? = instance
        
        fun isServiceEnabled(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process accessibility events
        // We only use this service for gesture dispatch
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    /**
     * Dispatch a touch gesture
     * @param gesture The gesture description to dispatch
     * @param callback Callback for gesture completion
     * @return true if gesture was successfully queued, false otherwise
     */
    fun dispatchCustomGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback?
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val result = dispatchGesture(gesture, callback, null)
            if (!result) {
                Log.w(TAG, "Failed to dispatch gesture - queue may be full or service not ready")
            }
            result
        } else {
            Log.w(TAG, "Gesture dispatch requires Android N or higher")
            false
        }
    }

    /**
     * Perform a simple touch at coordinates
     */
    fun performTouch(x: Float, y: Float, durationMs: Long = 100): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val path = Path()
        path.moveTo(x, y)

        val gestureBuilder = GestureDescription.Builder()
        val gesture = gestureBuilder
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchCustomGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Touch gesture completed at ($x, $y)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Touch gesture cancelled at ($x, $y)")
            }
        })
    }

    /**
     * Perform a drag gesture from one point to another
     */
    fun performDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val path = Path()
        path.moveTo(fromX, fromY)
        path.lineTo(toX, toY)

        val gestureBuilder = GestureDescription.Builder()
        val gesture = gestureBuilder
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchCustomGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Drag gesture completed from ($fromX, $fromY) to ($toX, $toY)")
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Drag gesture cancelled")
            }
        })
    }
}
