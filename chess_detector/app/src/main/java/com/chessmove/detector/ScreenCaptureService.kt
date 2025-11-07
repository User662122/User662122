package com.chessmove.detector

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "ScreenCaptureChannel"
        const val TAG = "ScreenCaptureService"
    }

    override fun onCreate() {
        super.onCreate()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        createNotificationChannel()
        Log.d(TAG, "âœ… Service created. Screen: ${screenWidth}x${screenHeight}, Density: $screenDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ðŸ“± onStartCommand called")
        
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")

            Log.d(TAG, "ResultCode: $resultCode (RESULT_OK=-1), Data exists: ${data != null}")

            if (resultCode == Activity.RESULT_OK && data != null) {
                startForeground(NOTIFICATION_ID, createNotification("Starting..."))
                
                // Setup media projection immediately
                try {
                    setupMediaProjection(resultCode, data)
                    
                    // Wait 10 seconds in coroutine, then capture
                    CoroutineScope(Dispatchers.Main).launch {
                        for (i in 10 downTo 1) {
                            Log.d(TAG, "â° Countdown: $i seconds...")
                            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            notificationManager.notify(NOTIFICATION_ID, createNotification("Capturing in $i seconds..."))
                            delay(1000)
                        }
                        Log.d(TAG, "ðŸŽ¬ Starting capture now!")
                        captureScreen()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "âŒ Error setting up media projection", e)
                    showToast("Setup failed: ${e.message}")
                    stopSelf()
                }
            } else {
                Log.e(TAG, "âŒ Invalid result code or data is null")
                showToast("Invalid screen capture permission")
                stopSelf()
            }
        } else {
            Log.e(TAG, "âŒ Intent is null")
            stopSelf()
        }
        
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Captures screen for chess detection"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        Log.d(TAG, "âœ… Notification channel created")
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("â™Ÿï¸ Chess Detector")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(false)
            .build()
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        Log.d(TAG, "ðŸ”§ Setting up media projection...")
        
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "âŒ MediaProjection is null!")
            throw Exception("MediaProjection is null")
        }
        Log.d(TAG, "âœ… MediaProjection created")

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        Log.d(TAG, "âœ… ImageReader created: ${screenWidth}x${screenHeight}")

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessScreenCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        
        if (virtualDisplay == null) {
            Log.e(TAG, "âŒ VirtualDisplay is null!")
            throw Exception("VirtualDisplay creation failed")
        }
        
        Log.d(TAG, "âœ… Media projection setup complete!")
    }

    private fun captureScreen() {
        Log.d(TAG, "ðŸ“¸ Attempting to capture screen...")
        
        try {
            // Update notification
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification("Capturing..."))
            
            // Give a moment for the display to stabilize
            Thread.sleep(500)
            Log.d(TAG, "â±ï¸ Stabilization delay complete")
            
            val image = imageReader?.acquireLatestImage()
            
            if (image != null) {
                Log.d(TAG, "âœ… Image captured! Size: ${image.width}x${image.height}, Format: ${image.format}")
                val bitmap = imageToBitmap(image)
                image.close()
                Log.d(TAG, "âœ… Converted to bitmap: ${bitmap.width}x${bitmap.height}")
                
                notificationManager.notify(NOTIFICATION_ID, createNotification("Processing..."))
                
                // Process the bitmap
                processChessBoard(bitmap)
            } else {
                Log.e(TAG, "âŒ Failed to acquire image - image is null")
                
                // Try one more time after a delay
                Log.d(TAG, "ðŸ”„ Retrying after 1 second...")
                Thread.sleep(1000)
                val retryImage = imageReader?.acquireLatestImage()
                
                if (retryImage != null) {
                    Log.d(TAG, "âœ… Retry successful!")
                    val bitmap = imageToBitmap(retryImage)
                    retryImage.close()
                    processChessBoard(bitmap)
                } else {
                    Log.e(TAG, "âŒ Retry failed - still no image")
                    showToast("Screen capture failed - no image available")
                    stopSelf()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "âŒ Error capturing screen", e)
            showToast("Capture error: ${e.message}")
            stopSelf()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        Log.d(TAG, "ðŸ–¼ï¸ Converting Image to Bitmap...")
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * screenWidth

        val bitmap = Bitmap.createBitmap(
            screenWidth + rowPadding / pixelStride,
            screenHeight,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        val finalBitmap = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
        Log.d(TAG, "âœ… Bitmap conversion complete")
        return finalBitmap
    }

    private fun processChessBoard(bitmap: Bitmap) {
        Log.d(TAG, "â™Ÿï¸ Starting chess board processing...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val boardState = getBoardStateFromBitmap(bitmap, "Screen Capture")
                
                if (boardState != null) {
                    val whiteList = boardState.white.sorted().joinToString(", ")
                    val blackList = boardState.black.sorted().joinToString(", ")
                    
                    Log.d(TAG, "âœ… Detection successful!")
                    Log.d(TAG, "âšª White pieces (${boardState.white.size}): $whiteList")
                    Log.d(TAG, "âš« Black pieces (${boardState.black.size}): $blackList")
                    
                    val message = "White pieces at: $whiteList\nBlack pieces at: $blackList"
                    showToast(message)
                } else {
                    Log.e(TAG, "âŒ No chess board found in captured image")
                    showToast("No chess board found in screen")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "âŒ Processing error", e)
                showToast("Processing error: ${e.message}")
            } finally {
                Log.d(TAG, "ðŸ›‘ Stopping service...")
                handler.postDelayed({
                    stopSelf()
                }, 2000)
            }
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            Log.d(TAG, "ðŸ’¬ Toast: $message")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            Log.d(TAG, "âœ… Service destroyed and resources released")
        } catch (e: Exception) {
            Log.e(TAG, "âŒ Error during cleanup", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}