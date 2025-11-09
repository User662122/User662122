package com.chessmove.detector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput

class NotificationReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "NotificationReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ChessDetectorService.ACTION_START -> {
                Log.d(TAG, "🔔 Start button clicked from notification")
                
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("auto_start_capture", true)
                }
                context.startActivity(mainIntent)
                
                Toast.makeText(context, "Opening app to start capture...", Toast.LENGTH_SHORT).show()
            }
            
            ChessDetectorService.ACTION_SET_URL -> {
                Log.d(TAG, "🔗 Set URL button clicked from notification")
                
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("show_url_dialog", true)
                }
                context.startActivity(mainIntent)
                
                Toast.makeText(context, "Opening URL settings...", Toast.LENGTH_SHORT).show()
            }
            
            ChessDetectorService.ACTION_SEND_MOVE -> {
                Log.d(TAG, "📤 Send Move action triggered from notification")
                
                // Extract the move from RemoteInput
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                if (remoteInput != null) {
                    val move = remoteInput.getCharSequence(ChessDetectorService.KEY_MOVE_INPUT)?.toString()
                    
                    if (!move.isNullOrBlank()) {
                        val cleanMove = move.trim().lowercase()
                        Log.d(TAG, "📨 Manual move from notification: $cleanMove")
                        
                        // Send move to ScreenCaptureService
                        val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                            action = "MANUAL_MOVE"
                            putExtra("move", cleanMove)
                        }
                        context.startService(serviceIntent)
                        
                        Toast.makeText(context, "Sending move: $cleanMove", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Move cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}