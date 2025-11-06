package com.chessmove.detector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class NotificationReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ChessDetectorService.ACTION_START -> {
                Log.d("NotificationReceiver", "Start button clicked")
                Toast.makeText(context, "Start button clicked!", Toast.LENGTH_SHORT).show()
                
                // TODO: Add your functionality here
                // For example:
                // - Start camera capture
                // - Begin continuous detection
                // - Open a specific activity
            }
        }
    }
}