package com.chessmove.detector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class NotificationReceiver : BroadcastReceiver() {
    
    companion object {
        const val TAG = "NotificationReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ChessDetectorService.ACTION_START -> {
                Log.d(TAG, "ðŸ”” Start button clicked from notification")
                
                // Open MainActivity to request fresh screen capture permission
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("auto_start_capture", true)
                }
                context.startActivity(mainIntent)
                
                Toast.makeText(context, "Opening app to start capture...", Toast.LENGTH_SHORT).show()
            }
        }
    }
}