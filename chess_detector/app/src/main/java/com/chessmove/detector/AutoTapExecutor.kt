package com.chessmove.detector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AutoTapExecutor : AccessibilityService() {
    
    companion object {
        const val TAG = "AutoTapExecutor"
        private var instance: AutoTapExecutor? = null
        
        fun getInstance(): AutoTapExecutor? = instance
        
        fun isEnabled(): Boolean = instance != null
    }
    
    private var lastTouchTime = 0L
    private val PAUSE_DURATION = 2000L // 2 seconds pause on touch
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Accessibility service connected")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Detect touch events to pause screen capture
        if (event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            event?.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            event?.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            
            lastTouchTime = System.currentTimeMillis()
            Log.d(TAG, "👆 Touch detected - pausing capture for ${PAUSE_DURATION}ms")
        }
    }
    
    override fun onInterrupt() {
        // Handle interruptions if needed
    }
    
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
    
    fun shouldPauseCapture(): Boolean {
        val timeSinceTouch = System.currentTimeMillis() - lastTouchTime
        return timeSinceTouch < PAUSE_DURATION
    }
    
    suspend fun executeMove(move: String, uciCoordinates: Map<String, Point>): Boolean {
        return suspendCancellableCoroutine { continuation ->
            try {
                // Parse UCI move (e.g., "e2e4")
                val from = move.substring(0, 2)
                val to = move.substring(2, 4)
                
                val fromPoint = uciCoordinates[from]
                val toPoint = uciCoordinates[to]
                
                if (fromPoint == null || toPoint == null) {
                    Log.e(TAG, "❌ Invalid coordinates for move: $move (from=$from, to=$to)")
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                
                Log.d(TAG, "🎯 Executing move: $move ($fromPoint -> $toPoint)")
                
                // Create gesture path for "from" square
                val path = Path().apply {
                    moveTo(fromPoint.x.toFloat(), fromPoint.y.toFloat())
                }
                
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                    .build()
                
                // Tap "from" square
                dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        // Wait 300ms then tap "to" square
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val path2 = Path().apply {
                                moveTo(toPoint.x.toFloat(), toPoint.y.toFloat())
                            }
                            
                            val gesture2 = GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path2, 0, 100))
                                .build()
                            
                            dispatchGesture(gesture2, object : GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) {
                                    Log.d(TAG, "✅ Move executed successfully")
                                    continuation.resume(true)
                                }
                                
                                override fun onCancelled(gestureDescription: GestureDescription?) {
                                    Log.e(TAG, "❌ Gesture cancelled")
                                    continuation.resume(false)
                                }
                            }, null)
                        }, 300)
                    }
                    
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.e(TAG, "❌ Gesture cancelled")
                        continuation.resume(false)
                    }
                }, null)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error executing move", e)
                continuation.resume(false)
            }
        }
    }
}