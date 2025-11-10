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
import java.util.concurrent.atomic.AtomicBoolean

// Data class to hold UCI positions only (no bitmap stored)
data class UciSnapshot(
    val allPieces: Set<String>,  // All piece positions in UCI format
    val whitePieces: Set<String>,
    val blackPieces: Set<String>,
    val captureNumber: Int,
    val timestamp: Long
) {
    fun totalPieceCount() = allPieces.size
}

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    
    // Store only UCI positions, not full BoardState
    private var lastValidUci: UciSnapshot? = null
    private var previousValidUci: UciSnapshot? = null  // For move detection
    
    private var cachedBoardCorners: Array<Point>? = null
    private var cachedOrientation: Boolean? = null
    private var cachedUciCoordinates: Map<String, android.graphics.Point>? = null
    private var isCapturing = false
    private var captureCount = 0
    
    private val isProcessing = AtomicBoolean(false)
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 3
    
    // Maximum allowed piece difference to consider valid (noise filtering)
    private val MAX_PIECE_DIFFERENCE = 3
    
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
        const val CAPTURE_INTERVAL = 1000L  // 1 second between captures
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
        Log.d(TAG, "✅ Service created. Screen: ${screenWidth}x${screenHeight}, Density: $screenDensity")
        Log.d(TAG, "🎯 Noise filter: max ${MAX_PIECE_DIFFERENCE} piece difference allowed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 onStartCommand called")
        
        if (intent?.action == "MANUAL_MOVE") {
            val move = intent.getStringExtra("move")
            if (!move.isNullOrBlank()) {
                Log.d(TAG, "📨 Manual move received: $move")
                processingScope.launch {
                    sendMoveToBackend(move)
                }
                return START_STICKY
            }
        }
        
        if (intent?.action == "STOP_CAPTURE") {
            Log.d(TAG, "🛑 Stop capture requested")
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
                            Log.d(TAG, "⏰ Countdown: $i seconds...")
                            updateNotification("Starting in $i seconds...", 0, 0)
                            delay(1000)
                        }
                        Log.d(TAG, "🎬 Starting continuous capture!")
                        startContinuousCapture()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error setting up media projection", e)
                    showToast("Setup failed: ${e.message}")
                    stopSelf()
                }
            } else {
                Log.e(TAG, "❌ Invalid result code or data is null")
                showToast("Invalid screen capture permission")
                stopSelf()
            }
        } else {
            Log.e(TAG, "❌ Intent is null")
            stopSelf()
        }
        
        return START_STICKY
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
        Log.d(TAG, "✅ Notification channel created")
    }

    private fun createNotification(text: String, count: Int, validUciCount: Int): Notification {
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
                cachedBoardCorners != null && cachedOrientation != null -> "Opt"
                cachedBoardCorners != null -> "Partial"
                else -> "Init"
            }
            val gameStatus = if (gameStarted) " | ${appColor?.take(1)?.uppercase()}" else ""
            val uciInfo = if (lastValidUci != null) {
                " | W:${lastValidUci!!.whitePieces.size} B:${lastValidUci!!.blackPieces.size}"
            } else ""
            "$text (#$count-$cacheStatus$gameStatus$uciInfo)"
        } else text
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("♟️ Chess Detector")
            .setContentText(displayText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }
    
    private fun updateNotification(text: String, count: Int, validUciCount: Int) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification(text, count, validUciCount))
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        Log.d(TAG, "🔧 Setting up media projection...")
        
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "❌ MediaProjection is null!")
            throw Exception("MediaProjection is null")
        }
        Log.d(TAG, "✅ MediaProjection created")

        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        Log.d(TAG, "✅ ImageReader created: ${screenWidth}x${screenHeight}")

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
            Log.e(TAG, "❌ VirtualDisplay is null!")
            throw Exception("VirtualDisplay creation failed")
        }
        
        Log.d(TAG, "✅ Media projection setup complete!")
    }

    private fun startImageProcessor() {
        // No longer needed - we process immediately after capture
        Log.d(TAG, "🔄 Direct processing mode - no queue processor needed")
    }

    private fun startContinuousCapture() {
        isCapturing = true
        captureCount = 0
        lastValidUci = null
        previousValidUci = null
        cachedBoardCorners = null
        cachedOrientation = null
        cachedUciCoordinates = null
        consecutiveErrors = 0
        gameStarted = false
        appColor = null
        
        CoroutineScope(Dispatchers.IO).launch {
            while (isCapturing && captureCount < MAX_CAPTURES) {
                // Check if accessibility service wants to pause capture due to touch
                val executor = AutoTapExecutor.getInstance()
                if (executor?.shouldPauseCapture() == true) {
                    Log.d(TAG, "⏸️ Capture paused due to recent touch")
                    updateNotification("Paused (touch detected)...", captureCount, lastValidUci?.totalPieceCount() ?: 0)
                    delay(CAPTURE_INTERVAL)
                    continue
                }
                
                // Skip if still processing previous frame
                if (isProcessing.get()) {
                    Log.w(TAG, "⚠️ Still processing, skipping capture #${captureCount + 1}")
                    delay(CAPTURE_INTERVAL)
                    continue
                }
                
                captureCount++
                
                val statusMessage = when {
                    cachedBoardCorners != null && cachedOrientation != null -> "🚀 Optimized"
                    cachedBoardCorners != null -> "🚀 Partial"
                    else -> "📸 Initial"
                }
                Log.d(TAG, "$statusMessage capture #$captureCount")
                
                updateNotification("Monitoring...", captureCount, lastValidUci?.totalPieceCount() ?: 0)
                
                // Capture and process immediately (no queueing)
                captureAndProcessImmediately()
                
                delay(CAPTURE_INTERVAL)
            }
            
            if (captureCount >= MAX_CAPTURES) {
                showToast("Stopped after $MAX_CAPTURES captures")
            }
            
            stopSelf()
        }
    }

    /**
     * Capture image and process immediately - no storage, instant RAM cleanup
     */
    private fun captureAndProcessImmediately() {
        var bitmap: Bitmap? = null
        try {
            isProcessing.set(true)
            
            Thread.sleep(200)  // Small delay for image stabilization
            
            val image = imageReader?.acquireLatestImage()
            
            if (image != null) {
                // Convert to bitmap (in RAM only)
                bitmap = imageToBitmap(image)
                image.close()
                
                Log.d(TAG, "📥 Frame #$captureCount captured, processing immediately...")
                
                // Process immediately using runBlocking (we're already in IO dispatcher)
                runBlocking {
                    processFrameAndExtractUci(bitmap!!, captureCount)
                }
                
                // Immediately recycle bitmap to free RAM
                bitmap.recycle()
                bitmap = null
                Log.d(TAG, "🗑️ Frame #$captureCount bitmap recycled")
                
            } else {
                Log.w(TAG, "⚠️ No image available on capture #$captureCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error capturing/processing frame #$captureCount", e)
            consecutiveErrors++
            
            // Clean up bitmap if error occurred
            bitmap?.recycle()
        } finally {
            isProcessing.set(false)
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

    /**
     * Process frame, extract UCI positions only, compare with previous, detect noise
     */
    private suspend fun processFrameAndExtractUci(bitmap: Bitmap, frameNumber: Int) {
        try {
            val currentBoardState = if (cachedBoardCorners != null && cachedOrientation != null) {
                getBoardStateFromBitmapWithCachedCorners(
                    bitmap, 
                    cachedBoardCorners!!, 
                    "Frame #$frameNumber",
                    cachedOrientation!!
                )
            } else {
                val state = getBoardStateFromBitmap(bitmap, "Frame #$frameNumber")
                
                if (state?.boardCorners != null && state.whiteOnBottom != null) {
                    cachedBoardCorners = state.boardCorners
                    cachedOrientation = state.whiteOnBottom
                    cachedUciCoordinates = state.uciToScreenCoordinates
                    consecutiveErrors = 0
                    
                    Log.d(TAG, "✅ Cached board corners, orientation AND ${cachedUciCoordinates?.size} UCI coordinates")
                    
                    if (!gameStarted && backendClient.hasBackendUrl()) {
                        val bottomColor = if (cachedOrientation == true) "white" else "black"
                        startGameWithBackend(bottomColor)
                    }
                    
                    withContext(Dispatchers.Main) {
                        showToast("Board detected! Playing as ${if (cachedOrientation == true) "white" else "black"}")
                    }
                }
                state
            }
            
            if (currentBoardState != null) {
                // Create UCI snapshot (no bitmap stored)
                val allPieces = currentBoardState.white + currentBoardState.black
                val currentUci = UciSnapshot(
                    allPieces = allPieces,
                    whitePieces = currentBoardState.white,
                    blackPieces = currentBoardState.black,
                    captureNumber = frameNumber,
                    timestamp = System.currentTimeMillis()
                )
                
                Log.d(TAG, "📊 Frame #$frameNumber UCI: ${currentUci.totalPieceCount()} pieces (W:${currentUci.whitePieces.size}, B:${currentUci.blackPieces.size})")
                
                // Validate against previous UCI (noise filtering) - ONLY if game has started
                if (lastValidUci != null && gameStarted) {
                    val pieceDifference = kotlin.math.abs(currentUci.totalPieceCount() - lastValidUci!!.totalPieceCount())
                    
                    if (pieceDifference > MAX_PIECE_DIFFERENCE) {
                        Log.w(TAG, "🚫 NOISE DETECTED! Piece difference: $pieceDifference (max: $MAX_PIECE_DIFFERENCE)")
                        Log.w(TAG, "   Previous: ${lastValidUci!!.totalPieceCount()} pieces, Current: ${currentUci.totalPieceCount()} pieces")
                        Log.w(TAG, "   Skipping this UCI, keeping previous valid state")
                        // Don't increment consecutiveErrors - this is just noise, not a detection failure
                        return  // Skip this noisy UCI
                    }
                    
                    Log.d(TAG, "✅ Valid UCI change: $pieceDifference pieces difference (within limit)")
                } else if (lastValidUci == null) {
                    Log.d(TAG, "ℹ️ First UCI capture - no noise filtering")
                } else {
                    Log.d(TAG, "ℹ️ Game not started yet - noise filtering disabled")
                }
                
                // UCI is valid, store it and detect moves
                consecutiveErrors = 0
                
                if (previousValidUci != null && gameStarted && !waitingForBackendMove) {
                    detectMoveFromUciChange(previousValidUci!!, currentUci)
                }
                
                // Shift: current becomes previous for next comparison
                previousValidUci = lastValidUci
                lastValidUci = currentUci
                
                Log.d(TAG, "💾 Stored UCI snapshot #$frameNumber: ${currentUci.totalPieceCount()} pieces")
                
            } else {
                consecutiveErrors++
                Log.w(TAG, "⚠️ No board detected in frame #$frameNumber (Error count: $consecutiveErrors)")
                
                // Only clear cache if we have too many consecutive errors AND board was previously cached
                if (consecutiveErrors >= maxConsecutiveErrors && cachedBoardCorners != null) {
                    Log.w(TAG, "⚠️ Too many errors, but keeping game state. Will retry detection on next frame.")
                    // Don't call clearCacheAndRetry() - just reset error counter
                    consecutiveErrors = 0
                }
            }
        } catch (e: Exception) {
            consecutiveErrors++
            Log.e(TAG, "❌ Error processing frame #$frameNumber", e)
            
            // Only clear cache if we have too many consecutive errors AND board was previously cached
            if (consecutiveErrors >= maxConsecutiveErrors && cachedBoardCorners != null) {
                Log.w(TAG, "⚠️ Too many errors, but keeping game state. Will retry detection on next frame.")
                consecutiveErrors = 0
            }
        }
    }

    /**
     * Detect move by comparing two UCI snapshots
     */
    private suspend fun detectMoveFromUciChange(oldUci: UciSnapshot, newUci: UciSnapshot) {
        val enemyColor = if (appColor == "white") "black" else "white"
        
        val enemyOldPositions = if (enemyColor == "white") oldUci.whitePieces else oldUci.blackPieces
        val enemyNewPositions = if (enemyColor == "white") newUci.whitePieces else newUci.blackPieces
        
        val disappeared = enemyOldPositions - enemyNewPositions
        val appeared = enemyNewPositions - enemyOldPositions
        
        if (disappeared.size == 1 && appeared.size == 1) {
            val from = disappeared.first()
            val to = appeared.first()
            val move = "$from$to"
            
            Log.d(TAG, "🎯 Enemy ($enemyColor) moved: $move")
            Log.d(TAG, "   UCI comparison: Frame #${oldUci.captureNumber} → #${newUci.captureNumber}")
            
            handler.post {
                showToast("Enemy: $move")
            }
            
            sendMoveToBackend(move)
        } else if (disappeared.isNotEmpty() || appeared.isNotEmpty()) {
            Log.d(TAG, "🤔 Complex position change: ${disappeared.size} disappeared, ${appeared.size} appeared")
            Log.d(TAG, "   This might be a capture or castling - needs refinement")
        }
    }

    private fun startGameWithBackend(bottomColor: String) {
        processingScope.launch {
            try {
                appColor = bottomColor
                Log.d(TAG, "🎮 App is playing as: $bottomColor (bottom of screen)")
                
                val result = backendClient.startGame(bottomColor)
                
                result.onSuccess { response ->
                    gameStarted = true
                    
                    if (appColor == "white") {
                        if (response.isNotEmpty() && response != "Invalid" && response != "Game Over") {
                            Log.d(TAG, "✅ App is white - backend gave first move: $response")
                            executeBackendMove(response)
                            isAppTurn = false
                        } else {
                            Log.e(TAG, "❌ App is white but backend didn't provide first move")
                            isAppTurn = false
                        }
                    } else {
                        isAppTurn = false
                        Log.d(TAG, "✅ App is black - waiting to detect enemy's (white) first move")
                        withContext(Dispatchers.Main) {
                            showToast("Waiting for enemy's first move...")
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "❌ Failed to start game", e)
                    withContext(Dispatchers.Main) {
                        showToast("Failed to start game: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting game", e)
            }
        }
    }

    private fun sendMoveToBackend(move: String) {
        processingScope.launch {
            try {
                waitingForBackendMove = true
                
                val result = backendClient.sendMove(move)
                
                result.onSuccess { response ->
                    when (response) {
                        "Invalid" -> {
                            Log.e(TAG, "❌ Backend response: $response")
                            withContext(Dispatchers.Main) {
                                showToast("Backend rejected move: Invalid")
                            }
                        }
                        "Game Over" -> {
                            Log.d(TAG, "🏁 Backend response: $response")
                            withContext(Dispatchers.Main) {
                                showToast("Game Over!")
                            }
                            gameStarted = false
                        }
                        else -> {
                            Log.d(TAG, "✅ Backend responded: $response")
                            executeBackendMove(response)
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "❌ Failed to send move", e)
                    withContext(Dispatchers.Main) {
                        showToast("Backend error: ${e.message}")
                    }
                }
                
                waitingForBackendMove = false
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending move to backend", e)
                waitingForBackendMove = false
            }
        }
    }

    private suspend fun executeBackendMove(move: String) {
        withContext(Dispatchers.Main) {
            try {
                val executor = AutoTapExecutor.getInstance()
                
                if (executor == null) {
                    showToast("⚠️ Enable Accessibility Service to auto-execute moves")
                    Log.w(TAG, "Accessibility service not enabled")
                    return@withContext
                }
                
                if (cachedUciCoordinates == null) {
                    Log.e(TAG, "❌ UCI coordinates not cached, cannot execute move")
                    return@withContext
                }
                
                val success = executor.executeMove(move, cachedUciCoordinates!!)
                
                if (!success) {
                    showToast("❌ Failed to execute: $move")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error executing move", e)
                showToast("Error executing move: ${e.message}")
            }
        }
    }

    private fun clearCacheAndRetry() {
        Log.w(TAG, "🔄 Critical error - clearing ONLY board detection cache (keeping game state)")
        
        // Only clear board detection cache, NOT game state
        cachedBoardCorners = null
        // Keep cachedOrientation and cachedUciCoordinates for game continuity
        
        // Keep UCI snapshots for move detection
        // lastValidUci and previousValidUci are preserved
        
        consecutiveErrors = 0
        
        handler.post {
            showToast("Board detection reset - game continues")
        }
        
        Log.d(TAG, "ℹ️ Game state preserved: gameStarted=$gameStarted, appColor=$appColor")
        Log.d(TAG, "ℹ️ UCI state preserved: lastValidUci has ${lastValidUci?.totalPieceCount() ?: 0} pieces")
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
        
        Log.d(TAG, "🛑 Service destroying, cleaning up...")
        
        processingJob?.cancel()
        processingScope.cancel()
        
        // Clean up UCI snapshots (minimal memory)
        lastValidUci = null
        previousValidUci = null
        cachedBoardCorners = null
        cachedOrientation = null
        cachedUciCoordinates = null
        
        Log.d(TAG, "🗑️ Cleared all UCI snapshots and cache")
        
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            Log.d(TAG, "✅ Service destroyed and resources released")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
