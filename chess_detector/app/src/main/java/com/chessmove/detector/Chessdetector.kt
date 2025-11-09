package com.chessmove.detector

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class ChessDetectorService : Service() {

    companion object {
        const val CHANNEL_ID = "ChessDetectorChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.chessmove.detector.ACTION_START"
        const val ACTION_SET_URL = "com.chessmove.detector.ACTION_SET_URL"
        const val ACTION_SEND_MOVE = "com.chessmove.detector.ACTION_SEND_MOVE"
        const val KEY_MOVE_INPUT = "key_move_input"
        
        fun startService(context: Context) {
            val intent = Intent(context, ChessDetectorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, ChessDetectorService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chess Detector Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Chess piece detection service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val startIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_START
        }
        val startPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            startIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Set URL action
        val setUrlIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_SET_URL
        }
        val setUrlPendingIntent = PendingIntent.getBroadcast(
            this,
            2,
            setUrlIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // NEW: Send Move action with RemoteInput
        val sendMoveIntent = Intent(this, NotificationReceiver::class.java).apply {
            action = ACTION_SEND_MOVE
        }
        val sendMovePendingIntent = PendingIntent.getBroadcast(
            this,
            3,
            sendMoveIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val remoteInput = androidx.core.app.RemoteInput.Builder(KEY_MOVE_INPUT)
            .setLabel("Enter move (e.g., e2e4)")
            .build()
        
        val sendMoveAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Send Move",
            sendMovePendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("♟️ Chess Detector")
            .setContentText("Service is running")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(mainPendingIntent)
            .addAction(
                android.R.drawable.ic_media_play,
                "Start",
                startPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_edit,
                "Set URL",
                setUrlPendingIntent
            )
            .addAction(sendMoveAction)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(false)
            .build()
    }
}