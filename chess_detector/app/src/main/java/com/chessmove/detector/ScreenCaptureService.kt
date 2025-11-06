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
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private var captureAttempts = 0

    companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "ScreenCaptureChannel"
        const val TAG = "ScreenCaptureService"
    }

    override fun onCreate() {
        super.onCreate()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        // Create background thread for image capture
        backgroundThread = HandlerThread("ScreenCapture").apply {
            start()
        }
        backgroundHandler = Handler(backgroundThread!!.looper)
        
        createNotificationChannel()
        Log.d(TAG, "Service created - Screen: ${screenWidth}x${screenHeight}, Density: $screenDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")

            if (resultCode == Activity.RESULT_OK && data != null) {
                startForeground(NOTIFICATION_ID, createNotification("Waiting 10 seconds..."))
                Log.d(TAG, "Starting screen capture setup...")
                
                setupMediaProjection(resultCode, data)
                
                // Wait 10 seconds before first capture attempt
                mainHandler.postDelayed({
                    Log.d(TAG, "Starting capture after 10 second delay")
                    updateNotification("Capturing screen...")
                    captureScreen()
                }, 10000)
            } else {
                Log.e(TAG, "Invalid result code or data")
                showToast("Screen capture permission invalid")
                stopSelf()
            }
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
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("♟️ Chess Detector")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(false)
            .build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            val mediaProjectionManager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
            
            Log.d(TAG, "MediaProjection created")

            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )
            
            Log.d(TAG, "ImageReader created")

            // Give a small delay before creating virtual display
            mainHandler.postDelayed({
                try {
                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "ChessScreenCapture",
                        screenWidth,
                        screenHeight,
                        screenDensity,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        imageReader?.surface,
                        null,
                        backgroundHandler
                    )
                    Log.d(TAG, "VirtualDisplay created successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error creating VirtualDisplay", e)
                    showToast("Error setting up screen capture: ${e.message}")
                    stopSelf()
                }
            }, 500)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in setupMediaProjection", e)
            showToast("Error: ${e.message}")
            stopSelf()
        }
    }

    private fun captureScreen() {
        captureAttempts++
        Log.d(TAG, "Capture attempt #$captureAttempts")
        
        try {
            // Wait a bit for the image to be available
            backgroundHandler?.postDelayed({
                try {
                    val image = imageReader?.acquireLatestImage()
                    
                    if (image != null) {
                        Log.d(TAG, "Image acquired successfully")
                        val bitmap = imageToBitmap(image)
                        image.close()
                        
                        updateNotification("Processing...")
                        processChessBoard(bitmap)
                    } else {
                        Log.w(TAG, "No image available, attempt #$captureAttempts")
                        
                        // Retry up to 3 times
                        if (captureAttempts < 3) {
                            showToast("Retrying capture... ($captureAttempts/3)")
                            mainHandler.postDelayed({
                                captureScreen()
                            }, 1000)
                        } else {
                            showToast("Failed to capture screen after 3 attempts")
                            stopSelf()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in capture", e)
                    showToast("Capture error: ${e.message}")
                    stopSelf()
                }
            }, 500) // Give 500ms for image to be ready
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting capture", e)
            showToast("Error: ${e.message}")
            stopSelf()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
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
        
        // Crop to exact screen size
        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private fun processChessBoard(bitmap: Bitmap) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Processing bitmap: ${bitmap.width}x${bitmap.height}")
                val boardState = getBoardStateFromBitmap(bitmap, "Screen Capture")
                
                if (boardState != null) {
                    val whiteList = boardState.white.sorted().joinToString(", ")
                    val blackList = boardState.black.sorted().joinToString(", ")
                    
                    Log.d(TAG, "✅ Detection successful!")
                    Log.d(TAG, "White pieces: $whiteList")
                    Log.d(TAG, "Black pieces: $blackList")
                    
                    val message = buildString {
                        appendLine("✅ Chess pieces detected!")
                        appendLine()
                        appendLine("White at: $whiteList")
                        appendLine()
                        appendLine("Black at: $blackList")
                    }
                    
                    showToast(message)
                    updateNotification("Detection complete!")
                } else {
                    Log.w(TAG, "No chess board found")
                    showToast("❌ No chess board found in screen")
                    updateNotification("No board found")
                }
                
                // Cleanup bitmap
                bitmap.recycle()
                
            } catch (e: Exception) {
                Log.e(TAG, "Processing error", e)
                showToast("Processing error: ${e.message}")
            } finally {
                // Stop service after processing
                mainHandler.postDelayed({
                    stopSelf()
                }, 2000)
            }
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroying...")
        
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            backgroundThread?.quitSafely()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
        
        Log.d(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}