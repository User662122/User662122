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
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Accessibility service connected")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not needed for gesture execution
    }
    
    override fun onInterrupt() {
        // Handle interruptions if needed
    }
    
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
    
    suspend fun executeMove(move: String, boardCorners: Array<org.opencv.core.Point>): Boolean {
        return suspendCancellableCoroutine { continuation ->
            try {
                // Parse UCI move (e.g., "e2e4")
                val from = move.substring(0, 2)
                val to = move.substring(2, 4)
                
                val fromPoint = uciToScreenCoordinates(from, boardCorners)
                val toPoint = uciToScreenCoordinates(to, boardCorners)
                
                if (fromPoint == null || toPoint == null) {
                    Log.e(TAG, "❌ Invalid coordinates for move: $move")
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
    
    private fun uciToScreenCoordinates(
        uci: String, 
        boardCorners: Array<org.opencv.core.Point>
    ): Point? {
        try {
            val file = uci[0] - 'a'  // 0-7
            val rank = uci[1] - '1'  // 0-7
            
            // Assuming board is detected in landscape/proper orientation
            // boardCorners = [topLeft, topRight, bottomRight, bottomLeft]
            val topLeft = boardCorners[0]
            val topRight = boardCorners[1]
            val bottomLeft = boardCorners[3]
            
            val boardWidth = topRight.x - topLeft.x
            val boardHeight = bottomLeft.y - topLeft.y
            
            val cellWidth = boardWidth / 8.0
            val cellHeight = boardHeight / 8.0
            
            // Calculate center of square
            val x = topLeft.x + (file + 0.5) * cellWidth
            val y = topLeft.y + (rank + 0.5) * cellHeight
            
            return Point(x.toInt(), y.toInt())
        } catch (e: Exception) {
            Log.e(TAG, "Error converting UCI to coordinates", e)
            return null
        }
    }
}