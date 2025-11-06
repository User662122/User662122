package com.chessmove.detector

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

class NotificationReceiver : BroadcastReceiver() {
    
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ChessDetectorService.ACTION_START -> {
                Log.d("NotificationReceiver", "Start button clicked")
                
                // Get saved screen capture permission
                val sharedPrefs = context.getSharedPreferences("chess_detector", Context.MODE_PRIVATE)
                val resultCode = sharedPrefs.getInt(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val dataString = sharedPrefs.getString(EXTRA_DATA, null)
                
                if (resultCode == Activity.RESULT_OK && dataString != null) {
                    // Start screen capture service
                    val data = Intent.parseUri(dataString, 0)
                    val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                        putExtra("resultCode", resultCode)
                        putExtra("data", data)
                    }
                    
                    ContextCompat.startForegroundService(context, serviceIntent)
                    Toast.makeText(context, "Screen capture starting in 10 seconds...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Please grant screen capture permission first", Toast.LENGTH_LONG).show()
                    
                    // Open MainActivity to request permission
                    val mainIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra("request_capture", true)
                    }
                    context.startActivity(mainIntent)
                }
            }
        }
    }
}