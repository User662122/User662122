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
import kotlinx.coroutines.*
import org.opencv.core.Point
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

data class CapturedFrame(
    val bitmap: Bitmap,
    val captureNumber: Int,
    val timestamp: Long
)

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    private var previousBoardState: BoardState? = null
    private var cachedBoardCorners: Array<Point>? = null
    private var cachedOrientation: Boolean? = null
    private var isCapturing = false
    private var captureCount = 0
    
    // NEW: Image processing queue and worker
    private val imageQueue = ConcurrentLinkedQueue<CapturedFrame>()
    private val isProcessing = AtomicBoolean(false)
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // NEW: Store last two board states for comparison
    private var lastTwoStates = mutableListOf<Pair<BoardState, Long>>()
    
    // NEW: Error tracking
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 3

    companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "ScreenCaptureChannel"
        const val TAG = "ScreenCaptureService"
        const val MAX_CAPTURES = 100
        const val CAPTURE_INTERVAL = 3000L
        const val MAX_QUEUE_SIZE = 5  // Limit queue to prevent memory overflow
        const val PROCESSING_TIMEOUT = 5000L  // 5 seconds timeout per image
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
        startImageProcessor()  // NEW: Start background processor
        Log.d(TAG, "âœ… Service created. Screen: ${screenWidth}x${screenHeight}, Density: $screenDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ðŸ“± onStartCommand called")
        
        if (intent?.action == "STOP_CAPTURE") {
            Log.d(TAG, "ðŸ›‘ Stop capture requested")
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")

            Log.d(TAG, "ResultCode: $resultCode (RESULT_OK=-1), Data exists: ${data != null}")

            if (resultCode == Activity.RESULT_OK && data != null) {
                startForeground(NOTIFICATION_ID, createNotification("Starting...", 0, 0))
                
                try {
                    setupMediaProjection(resultCode, data)
                    
                    CoroutineScope(Dispatchers.Main).launch {
                        for (i in 10 downTo 1) {
                            Log.d(TAG, "â° Countdown: $i seconds...")
                            updateNotification("Starting in $i seconds...", 0, 0)
                            delay(1000)
                        }
                        Log.d(TAG, "ðŸŽ¬ Starting continuous capture!")
                        startContinuousCapture()
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

    private fun createNotification(text: String, count: Int, queueSize: Int): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "STOP_CAPTURE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val displayText = if (count > 0) {
            val cacheStatus = when {
                cachedBoardCorners != null && cachedOrientation != null -> "Fully Optimized"
                cachedBoardCorners != null -> "Optimized"
                else -> "Initializing"
            }
            "$text (#$count - $cacheStatus) | Queue: $queueSize"
        } else text
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("â™Ÿï¸ Chess Detector")
            .setContentText(displayText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setAutoCancel(false)
            .build()
    }
    
    private fun updateNotification(text: String, count: Int, queueSize: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text, count, queueSize))
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

    // NEW: Background image processor that runs continuously
    private fun startImageProcessor() {
        processingJob = processingScope.launch {
            Log.d(TAG, "ðŸ”„ Image processor started")
            while (isActive) {
                try {
                    val frame = imageQueue.poll()
                    if (frame != null) {
                        isProcessing.set(true)
                        
                        // Process with timeout
                        withTimeout(PROCESSING_TIMEOUT) {
                            processFrameWithErrorHandling(frame)
                        }
                        
                        // Clean up old bitmap
                        frame.bitmap.recycle()
                        
                        isProcessing.set(false)
                    } else {
                        // No frames to process, wait a bit
                        delay(100)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "â±ï¸ Processing timeout for frame, skipping...")
                    consecutiveErrors++
                    isProcessing.set(false)
                    
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        Log.e(TAG, "âŒ Too many consecutive errors, clearing cache...")
                        clearCacheAndRetry()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "âŒ Error in image processor", e)
                    consecutiveErrors++
                    isProcessing.set(false)
                    
                    if (consecutiveErrors >= maxConsecutiveErrors) {
                        clearCacheAndRetry()
                    }
                }
            }
            Log.d(TAG, "ðŸ›‘ Image processor stopped")
        }
    }

    private fun startContinuousCapture() {
        isCapturing = true
        captureCount = 0
        previousBoardState = null
        cachedBoardCorners = null
        cachedOrientation = null
        consecutiveErrors = 0
        
        CoroutineScope(Dispatchers.IO).launch {
            while (isCapturing && captureCount < MAX_CAPTURES) {
                captureCount++
                
                val statusMessage = when {
                    cachedBoardCorners != null && cachedOrientation != null -> "ðŸš€ Fully optimized"
                    cachedBoardCorners != null -> "ðŸš€ Fast capture"
                    else -> "ðŸ“¸ Initial capture"
                }
                Log.d(TAG, "$statusMessage #$captureCount")
                
                updateNotification("Monitoring...", captureCount, imageQueue.size)
                
                // Check queue size to prevent memory overflow
                if (imageQueue.size >= MAX_QUEUE_SIZE) {
                    Log.w(TAG, "âš ï¸ Queue full (${imageQueue.size}), skipping capture #$captureCount")
                    delay(CAPTURE_INTERVAL)
                    continue
                }
                
                captureAndEnqueue()
                
                delay(CAPTURE_INTERVAL)
            }
            
            if (captureCount >= MAX_CAPTURES) {
                showToast("Stopped after $MAX_CAPTURES captures")
            }
            
            // Wait for queue to clear before stopping
            while (imageQueue.isNotEmpty()) {
                Log.d(TAG, "â³ Waiting for ${imageQueue.size} frames to process...")
                delay(500)
            }
            
            stopSelf()
        }
    }

    private fun captureAndEnqueue() {
        try {
            Thread.sleep(200)
            
            val image = imageReader?.acquireLatestImage()
            
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                
                val frame = CapturedFrame(bitmap, captureCount, System.currentTimeMillis())
                imageQueue.offer(frame)
                Log.d(TAG, "ðŸ“¥ Frame #$captureCount enqueued (Queue size: ${imageQueue.size})")
            } else {
                Log.w(TAG, "âš ï¸ No image available on capture #$captureCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "âŒ Error capturing frame #$captureCount", e)
            consecutiveErrors++
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
        return Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
    }

    private suspend fun processFrameWithErrorHandling(frame: CapturedFrame) {
        try {
            val currentBoardState = if (cachedBoardCorners != null && cachedOrientation != null) {
                // FULLY OPTIMIZED: Use cached corners AND orientation
                Log.d(TAG, "ðŸš€ Using cached corners AND orientation for frame #${frame.captureNumber}")
                getBoardStateFromBitmapWithCachedCorners(
                    frame.bitmap, 
                    cachedBoardCorners!!, 
                    "Frame #${frame.captureNumber}",
                    cachedOrientation!!
                )
            } else {
                // First capture - detect board, cache corners AND orientation
                Log.d(TAG, "ðŸ” First detection - finding board and orientation for frame #${frame.captureNumber}...")
                val state = getBoardStateFromBitmap(frame.bitmap, "Frame #${frame.captureNumber}")
                
                // Cache BOTH corners and orientation for future use
                if (state?.boardCorners != null && state.whiteOnBottom != null) {
                    cachedBoardCorners = state.boardCorners
                    cachedOrientation = state.whiteOnBottom
                    consecutiveErrors = 0  // Reset error counter on success
                    Log.d(TAG, "âœ… Board corners AND orientation cached!")
                    Log.d(TAG, "   Orientation: ${if (cachedOrientation == true) "White on bottom" else "Black on bottom"}")
                    withContext(Dispatchers.Main) {
                        showToast("Board detected! Now fully optimized")
                    }
                }
                state
            }
            
            if (currentBoardState != null) {
                consecutiveErrors = 0  // Reset on successful detection
                Log.d(TAG, "âœ… Frame #${frame.captureNumber}: ${currentBoardState.white.size} white, ${currentBoardState.black.size} black")
                
                // Store state with timestamp
                lastTwoStates.add(Pair(currentBoardState, frame.timestamp))
                
                // Keep only last 2 states
                if (lastTwoStates.size > 2) {
                    lastTwoStates.removeAt(0)
                }
                
                // Compare if we have 2 states
                if (lastTwoStates.size == 2) {
                    val (oldState, oldTime) = lastTwoStates[0]
                    val (newState, newTime) = lastTwoStates[1]
                    
                    val moveDetected = detectAndShowMoves(oldState, newState)
                    
                    if (!moveDetected) {
                        // No move detected - states are duplicate, remove oldest
                        Log.d(TAG, "ðŸ—‘ï¸ No move detected, removing oldest state")
                        lastTwoStates.removeAt(0)
                    } else {
                        // Move detected - keep newest, remove oldest
                        Log.d(TAG, "â™Ÿï¸ Move detected! Keeping current state for next comparison")
                        lastTwoStates.removeAt(0)
                    }
                }
                
                previousBoardState = currentBoardState
            } else {
                consecutiveErrors++
                Log.w(TAG, "âš ï¸ No board detected in frame #${frame.captureNumber} (Error count: $consecutiveErrors)")
                
                // If detection fails multiple times, clear cache to retry full detection
                if (consecutiveErrors >= maxConsecutiveErrors) {
                    clearCacheAndRetry()
                }
            }
        } catch (e: Exception) {
            consecutiveErrors++
            Log.e(TAG, "âŒ Error processing frame #${frame.captureNumber}", e)
            
            if (consecutiveErrors >= maxConsecutiveErrors) {
                clearCacheAndRetry()
            }
        }
    }

    private fun clearCacheAndRetry() {
        Log.w(TAG, "ðŸ”„ Clearing cache due to errors, will retry full detection...")
        cachedBoardCorners = null
        cachedOrientation = null
        lastTwoStates.clear()
        previousBoardState = null
        consecutiveErrors = 0
        
        handler.post {
            showToast("Detection issues - resetting...")
        }
    }

    private fun detectAndShowMoves(oldState: BoardState, newState: BoardState): Boolean {
        val whiteMoved = oldState.white - newState.white
        val whiteAppeared = newState.white - oldState.white
        val blackMoved = oldState.black - newState.black
        val blackAppeared = newState.black - oldState.black
        
        val moves = mutableListOf<String>()
        
        // Detect white move
        if (whiteMoved.size == 1 && whiteAppeared.size == 1) {
            val from = whiteMoved.first()
            val to = whiteAppeared.first()
            moves.add("White moved: $from$to")
            Log.d(TAG, "âšª White: $from â†’ $to")
        } else if (whiteMoved.size == 1 && whiteAppeared.size == 0 && blackMoved.size == 1) {
            val from = whiteMoved.first()
            val to = blackMoved.first()
            moves.add("White captured: $from$to")
            Log.d(TAG, "âšª White captured: $from â†’ $to")
        }
        
        // Detect black move
        if (blackMoved.size == 1 && blackAppeared.size == 1) {
            val from = blackMoved.first()
            val to = blackAppeared.first()
            moves.add("Black moved: $from$to")
            Log.d(TAG, "âš« Black: $from â†’ $to")
        } else if (blackMoved.size == 1 && blackAppeared.size == 0 && whiteMoved.size == 1) {
            val from = blackMoved.first()
            val to = whiteMoved.first()
            moves.add("Black captured: $from$to")
            Log.d(TAG, "âš« Black captured: $from â†’ $to")
        }
        
        // Detect castling
        if (whiteMoved.size == 2 && whiteAppeared.size == 2) {
            val sorted = whiteMoved.sorted()
            if (sorted.any { it.startsWith("e") }) {
                moves.add("White castled")
                Log.d(TAG, "âšª White castled")
            }
        }
        if (blackMoved.size == 2 && blackAppeared.size == 2) {
            val sorted = blackMoved.sorted()
            if (sorted.any { it.startsWith("e") }) {
                moves.add("Black castled")
                Log.d(TAG, "âš« Black castled")
            }
        }
        
        if (moves.isNotEmpty()) {
            val message = moves.joinToString("\n")
            handler.post {
                showToast(message)
            }
            Log.d(TAG, "ðŸŽ¯ Moves detected: $message")
            return true
        } else {
            if (whiteMoved.isNotEmpty() || whiteAppeared.isNotEmpty() || 
                blackMoved.isNotEmpty() || blackAppeared.isNotEmpty()) {
                Log.d(TAG, "âš ï¸ Board changed but no clear move pattern")
                Log.d(TAG, "  White: moved=$whiteMoved, appeared=$whiteAppeared")
                Log.d(TAG, "  Black: moved=$blackMoved, appeared=$blackAppeared")
                return false
            }
            return false
        }
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCapturing = false
        
        Log.d(TAG, "ðŸ›‘ Service destroying, cleaning up...")
        
        // Cancel processing job
        processingJob?.cancel()
        processingScope.cancel()
        
        // Clear queue and recycle bitmaps
        var clearedCount = 0
        while (imageQueue.isNotEmpty()) {
            imageQueue.poll()?.bitmap?.recycle()
            clearedCount++
        }
        Log.d(TAG, "ðŸ—‘ï¸ Cleared $clearedCount frames from queue")
        
        // Clear states
        lastTwoStates.clear()
        cachedBoardCorners = null
        cachedOrientation = null
        previousBoardState = null
        
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