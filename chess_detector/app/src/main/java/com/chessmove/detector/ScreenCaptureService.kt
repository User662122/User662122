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
    private var cachedUciCoordinates: Map<String, android.graphics.Point>? = null
    private var isCapturing = false
    private var captureCount = 0
    
    // Image processing queue
    private val imageQueue = ConcurrentLinkedQueue<CapturedFrame>()
    private val isProcessing = AtomicBoolean(false)
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var lastTwoStates = mutableListOf<Pair<BoardState, Long>>()
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 3
    
    // Backend integration
    private lateinit var backendClient: BackendClient
    private var gameStarted = false
    private var appColor: String? = null
    private var isAppTurn = false
    private var waitingForBackendMove = false

    companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "ScreenCaptureChannel"
        const val TAG = "ScreenCaptureService"
        const val MAX_CAPTURES = 1000
        const val CAPTURE_INTERVAL = 3000L
        const val MAX_QUEUE_SIZE = 5
        const val PROCESSING_TIMEOUT = 5000L
    }

    override fun onCreate() {
        super.onCreate()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
        
        backendClient = BackendClient(this)
        
        createNotificationChannel()
        startImageProcessor()
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
            val gameStatus = if (gameStarted) " | Playing as $appColor" else ""
            "$text (#$count - $cacheStatus) | Q:$queueSize$gameStatus"
        } else text
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("â™Ÿï¸ Chess Detector")
            .setContentText(displayText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
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

    private fun startImageProcessor() {
        processingJob = processingScope.launch {
            Log.d(TAG, "ðŸ”„ Image processor started")
            while (isActive) {
                try {
                    val frame = imageQueue.poll()
                    if (frame != null) {
                        isProcessing.set(true)
                        
                        withTimeout(PROCESSING_TIMEOUT) {
                            processFrameWithErrorHandling(frame)
                        }
                        
                        frame.bitmap.recycle()
                        isProcessing.set(false)
                    } else {
                        delay(100)
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.e(TAG, "â±ï¸ Processing timeout for frame, skipping...")
                    consecutiveErrors++
                    isProcessing.set(false)
                    
                    if (consecutiveErrors >= maxConsecutiveErrors) {
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
        cachedUciCoordinates = null
        consecutiveErrors = 0
        gameStarted = false
        appColor = null
        
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
                getBoardStateFromBitmapWithCachedCorners(
                    frame.bitmap, 
                    cachedBoardCorners!!, 
                    "Frame #${frame.captureNumber}",
                    cachedOrientation!!
                )
            } else {
                val state = getBoardStateFromBitmap(frame.bitmap, "Frame #${frame.captureNumber}")
                
                if (state?.boardCorners != null && state.whiteOnBottom != null) {
                    cachedBoardCorners = state.boardCorners
                    cachedOrientation = state.whiteOnBottom
                    cachedUciCoordinates = state.uciToScreenCoordinates
                    consecutiveErrors = 0
                    
                    Log.d(TAG, "âœ… Cached board corners, orientation AND ${cachedUciCoordinates?.size} UCI coordinates")
                    
                    // âœ… START GAME with backend
                    if (!gameStarted && backendClient.hasBackendUrl()) {
                        val bottomColor = if (cachedOrientation == true) "white" else "black"
                        startGameWithBackend(bottomColor)
                    }
                    
                    withContext(Dispatchers.Main) {
                        showToast("Board detected! Playing as app")
                    }
                }
                state
            }
            
            if (currentBoardState != null) {
                consecutiveErrors = 0
                Log.d(TAG, "âœ… Frame #${frame.captureNumber}: ${currentBoardState.white.size} white, ${currentBoardState.black.size} black")
                
                lastTwoStates.add(Pair(currentBoardState, frame.timestamp))
                
                if (lastTwoStates.size > 2) {
                    lastTwoStates.removeAt(0)
                }
                
                if (lastTwoStates.size == 2) {
                    val (oldState, oldTime) = lastTwoStates[0]
                    val (newState, newTime) = lastTwoStates[1]
                    
                    val moveDetected = detectAndProcessMoves(oldState, newState)
                    
                    if (!moveDetected) {
                        lastTwoStates.removeAt(0)
                    } else {
                        lastTwoStates.removeAt(0)
                    }
                }
                
                previousBoardState = currentBoardState
            } else {
                consecutiveErrors++
                Log.w(TAG, "âš ï¸ No board detected in frame #${frame.captureNumber} (Error count: $consecutiveErrors)")
                
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

    // âœ… Start game with backend
    private fun startGameWithBackend(bottomColor: String) {
        processingScope.launch {
            try {
                appColor = bottomColor
                Log.d(TAG, "ðŸŽ® App is playing as: $bottomColor")
                
                val result = backendClient.startGame(bottomColor)
                
                result.onSuccess { response ->
                    gameStarted = true
                    
                    if (response.isNotEmpty() && response != "Invalid" && response != "Game Over") {
                        // Backend made first move (app is white)
                        Log.d(TAG, "âœ… Executed: $response")
                        withContext(Dispatchers.Main) {
                            showToast("Backend played: $response")
                        }
                        executeBackendMove(response)
                        isAppTurn = false
                    } else {
                        // App is black, wait for enemy move
                        isAppTurn = false
                    }
                }.onFailure { e ->
                    Log.e(TAG, "âŒ Failed to start game", e)
                    withContext(Dispatchers.Main) {
                        showToast("Failed to start game: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "âŒ Error starting game", e)
            }
        }
    }
    // âœ… Start game with backend
    private fun startGameWithBackend(bottomColor: String) {
        processingScope.launch {
            try {
                appColor = bottomColor
                Log.d(TAG, "ðŸŽ® App is playing as: $bottomColor")
                
                val result = backendClient.startGame(bottomColor)
                
                result.onSuccess { response ->
                    gameStarted = true
                    
                    if (response.isNotEmpty() && response != "Invalid" && response != "Game Over") {
                        // Backend made first move (app is white)
                        Log.d(TAG, "âœ… Executed: $response")
                        withContext(Dispatchers.Main) {
                            showToast("Backend played: $response")
                        }
                        executeBackendMove(response)
                        isAppTurn = false
                    } else {
                        // App is black, wait for enemy move
                        isAppTurn = false
                    }
                }.onFailure { e ->
                    Log.e(TAG, "âŒ Failed to start game", e)
                    withContext(Dispatchers.Main) {
                        showToast("Failed to start game: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "âŒ Error starting game", e)
            }
        }
    }

    // âœ… FIXED: Detect only enemy moves, send clean UCI notation
    private suspend fun detectAndProcessMoves(oldState: BoardState, newState: BoardState): Boolean {
        val topColor = if (cachedOrientation == true) "black" else "white"
        
        // Get piece sets for top color (enemy)
        val topOldPieces = if (topColor == "white") oldState.white else oldState.black
        val topNewPieces = if (topColor == "white") newState.white else newState.black
        
        // Get piece sets for bottom color (app)
        val bottomOldPieces = if (topColor == "white") oldState.black else oldState.white
        val bottomNewPieces = if (topColor == "white") newState.black else newState.white
        
        // Detect move: enemy piece left one square and appeared on another
        val topMoved = topOldPieces - topNewPieces
        val topAppeared = topNewPieces - topOldPieces
        
        // Normal move or capture (both are just moves in UCI notation)
        if (topMoved.size == 1 && topAppeared.size == 1) {
            val from = topMoved.first()
            val to = topAppeared.first()
            val move = "$from$to"
            
            // âœ… ONLY 3 LOGS FROM HERE
            Log.d(TAG, "ðŸŽ¯ Enemy moved: $move")
            handler.post {
                showToast("Enemy: $move")
            }
            
            // Send to backend
            if (gameStarted) {
                sendMoveToBackend(move)
            }
            
            return true
        }
        
        return false
    }

    // âœ… Send move to backend
    private fun sendMoveToBackend(move: String) {
        processingScope.launch {
            try {
                waitingForBackendMove = true
                
                val result = backendClient.sendMove(move)
                
                result.onSuccess { response ->
                    when (response) {
                        "Invalid" -> {
                            // âœ… LOG 3a: Non-UCI response
                            Log.e(TAG, "âŒ Backend response: $response")
                            withContext(Dispatchers.Main) {
                                showToast("Backend rejected move: Invalid")
                            }
                        }
                        "Game Over" -> {
                            // âœ… LOG 3b: Non-UCI response
                            Log.d(TAG, "ðŸ Backend response: $response")
                            withContext(Dispatchers.Main) {
                                showToast("Game Over!")
                            }
                            gameStarted = false
                        }
                        else -> {
                            // âœ… LOG 2: Backend's move executed
                            Log.d(TAG, "âœ… Executed: $response")
                            withContext(Dispatchers.Main) {
                                showToast("Executing: $response")
                            }
                            executeBackendMove(response)
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "âŒ Failed to send move", e)
                    withContext(Dispatchers.Main) {
                        showToast("Backend error: ${e.message}")
                    }
                }
                
                waitingForBackendMove = false
            } catch (e: Exception) {
                Log.e(TAG, "âŒ Error sending move to backend", e)
                waitingForBackendMove = false
            }
        }
    }

    // âœ… Execute backend's move via accessibility service
    private suspend fun executeBackendMove(move: String) {
        withContext(Dispatchers.Main) {
            try {
                val executor = AutoTapExecutor.getInstance()
                
                if (executor == null) {
                    showToast("âš ï¸ Enable Accessibility Service to auto-execute moves")
                    Log.w(TAG, "Accessibility service not enabled")
                    return@withContext
                }
                
                if (cachedUciCoordinates == null) {
                    Log.e(TAG, "âŒ UCI coordinates not cached, cannot execute move")
                    return@withContext
                }
                
                val success = executor.executeMove(move, cachedUciCoordinates!!)
                
                if (!success) {
                    showToast("âŒ Failed to execute: $move")
                }
            } catch (e: Exception) {
                Log.e(TAG, "âŒ Error executing move", e)
                showToast("Error executing move: ${e.message}")
            }
        }
    }

    private fun clearCacheAndRetry() {
        Log.w(TAG, "ðŸ”„ Clearing cache due to errors, will retry full detection...")
        cachedBoardCorners = null
        cachedOrientation = null
        cachedUciCoordinates = null
        lastTwoStates.clear()
        previousBoardState = null
        consecutiveErrors = 0
        
        handler.post {
            showToast("Detection issues - resetting...")
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
        gameStarted = false
        
        Log.d(TAG, "ðŸ›‘ Service destroying, cleaning up...")
        
        processingJob?.cancel()
        processingScope.cancel()
        
        var clearedCount = 0
        while (imageQueue.isNotEmpty()) {
            imageQueue.poll()?.bitmap?.recycle()
            clearedCount++
        }
        Log.d(TAG, "ðŸ—‘ï¸ Cleared $clearedCount frames from queue")
        
        lastTwoStates.clear()
        cachedBoardCorners = null
        cachedOrientation = null
        cachedUciCoordinates = null
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