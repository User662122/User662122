package com.chessmove.detector

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

class NotificationReceiver : BroadcastReceiver() {
    
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val TAG = "NotificationReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ChessDetectorService.ACTION_START -> {
                Log.d(TAG, "Start button clicked")
                
                try {
                    // Get saved screen capture permission
                    val sharedPrefs = context.getSharedPreferences("chess_detector", Context.MODE_PRIVATE)
                    val resultCode = sharedPrefs.getInt(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                    val dataString = sharedPrefs.getString(EXTRA_DATA, null)
                    
                    Log.d(TAG, "Retrieved resultCode: $resultCode")
                    Log.d(TAG, "Retrieved dataString: ${dataString != null}")
                    
                    if (resultCode == Activity.RESULT_OK && dataString != null) {
                        try {
                            // Parse the saved intent
                            val data = Intent.parseUri(dataString, 0)
                            
                            // Start screen capture service
                            val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                                putExtra("resultCode", resultCode)
                                putExtra("data", data)
                            }
                            
                            ContextCompat.startForegroundService(context, serviceIntent)
                            Toast.makeText(context, "Screen capture starting in 10 seconds...", Toast.LENGTH_SHORT).show()
                            
                            Log.d(TAG, "Service started successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing intent or starting service", e)
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Log.w(TAG, "Permission not granted or saved")
                        Toast.makeText(context, "Please grant screen capture permission first", Toast.LENGTH_LONG).show()
                        
                        // Open MainActivity to request permission
                        val mainIntent = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            putExtra("request_capture", true)
                        }
                        context.startActivity(mainIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error in onReceive", e)
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}