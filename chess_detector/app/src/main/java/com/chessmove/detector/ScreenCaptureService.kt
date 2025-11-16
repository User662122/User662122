package com.chessmove.detector

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class UciSnapshot(
    val allPieces: Set<String>,
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
    
    private var lastValidUci: UciSnapshot? = null
    private var previousValidUci: UciSnapshot? = null
    
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
    
    private val MAX_PIECE_DIFFERENCE = 3
    
    private lateinit var backendClient: BackendClient
    private var gameStarted = false
    private var appColor: String? = null
    private var waitingForBackendMove = false
    
    private var debugImageCounter = 0
    private var enableDebugImages = true

    companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "ScreenCaptureChannel"
        const val TAG = "ScreenCaptureService"
        const val MAX_CAPTURES = 1000
        const val CAPTURE_INTERVAL = 1000L
        const val PROCESSING_TIMEOUT = 5000L
        const val JPEG_QUALITY = 100
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
        
        clearOldDebugImages()
        
        Log.d(TAG, "✅ Service created with debug image saving: $enableDebugImages")
        Log.d(TAG, "   Screen: ${screenWidth}x${screenHeight}, Density: $screenDensity")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 onStartCommand called")
        
        if (intent?.action == "TOGGLE_DEBUG") {
            enableDebugImages = !enableDebugImages
            showToast("Debug images: ${if (enableDebugImages) "ON" else "OFF"}")
            Log.d(TAG, "🔧 Debug images toggled: $enableDebugImages")
            return START_STICKY
        }
        
        if (intent?.action == "STOP_CAPTURE") {
            Log.d(TAG, "🛑 Stop capture requested")
            stopSelf()
            return START_NOT_STICKY
        }
        
        if (intent != null) {
            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")

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
    
    private fun clearOldDebugImages() {
        try {
            val movesDir = java.io.File("/storage/emulated/0/Moves")
            if (movesDir.exists() && movesDir.isDirectory) {
                val files = movesDir.listFiles { file -> 
                    file.name.startsWith("move") && file.name.endsWith(".jpeg")
                }
                files?.forEach { it.delete() }
                Log.d(TAG, "🗑️ Cleared ${files?.size ?: 0} old debug images")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing old debug images", e)
        }
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
        
        val debugIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = "TOGGLE_DEBUG"
        }
        val debugPendingIntent = PendingIntent.getService(
            this,
            1,
            debugIntent,
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
            val debugStatus = if (enableDebugImages) " | 📸" else ""
            "$text (#$count-$cacheStatus$gameStatus$uciInfo$debugStatus)"
        } else text
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("♟️ Chess Detector")
            .setContentText(displayText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_camera, 
                if (enableDebugImages) "Debug:ON" else "Debug:OFF", 
                debugPendingIntent
            )
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
        Log.d(TAG, "🔄 Direct processing mode with JPEG conversion")
    }

    private fun startContinuousCapture() {
        isCapturing = true
        captureCount = 0
        debugImageCounter = 0
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
                val executor = AutoTapExecutor.getInstance()
                if (executor?.shouldPauseCapture() == true) {
                    Log.d(TAG, "⏸️ Capture paused due to recent touch")
                    updateNotification("Paused (touch detected)...", captureCount, lastValidUci?.totalPieceCount() ?: 0)
                    delay(CAPTURE_INTERVAL)
                    continue
                }
                
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
                Log.d(TAG, "$statusMessage JPEG capture #$captureCount")
                
                updateNotification("Monitoring...", captureCount, lastValidUci?.totalPieceCount() ?: 0)
                
                captureAndProcessWithJpegConversion()
                
                delay(CAPTURE_INTERVAL)
            }
            
            if (captureCount >= MAX_CAPTURES) {
                showToast("Stopped after $MAX_CAPTURES captures")
            }
            
            stopSelf()
        }
    }

    private fun captureAndProcessWithJpegConversion() {
        var rawBitmap: Bitmap? = null
        var jpegBitmap: Bitmap? = null
        
        try {
            isProcessing.set(true)
            
            Thread.sleep(200)
            
            val image = imageReader?.acquireLatestImage()
            
            if (image != null) {
                rawBitmap = imageToBitmap(image)
                image.close()
                
                Log.d(TAG, "📥 Frame #$captureCount captured (raw RGBA)")
                
                jpegBitmap = convertToJpegFormat(rawBitmap)
                rawBitmap.recycle()
                rawBitmap = null
                
                Log.d(TAG, "🎨 Frame #$captureCount converted to JPEG format")
                
                val bitmapToProcess = jpegBitmap
                jpegBitmap = null
                
                runBlocking {
                    processFrameAndExtractUci(bitmapToProcess, captureCount)
                }
                
                bitmapToProcess.recycle()
                Log.d(TAG, "🗑️ Frame #$captureCount cleanup complete")
                
            } else {
                Log.w(TAG, "⚠️ No image available on capture #$captureCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error capturing/processing frame #$captureCount", e)
            consecutiveErrors++
            
            rawBitmap?.recycle()
            jpegBitmap?.recycle()
        } finally {
            isProcessing.set(false)
        }
    }

    private fun convertToJpegFormat(rawBitmap: Bitmap): Bitmap {
        val startTime = System.currentTimeMillis()
        
        val outputStream = ByteArrayOutputStream()
        rawBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val jpegBytes = outputStream.toByteArray()
        outputStream.close()
        
        val compressionTime = System.currentTimeMillis() - startTime
        val originalSize = rawBitmap.byteCount / 1024
        val compressedSize = jpegBytes.size / 1024
        val compressionRatio = originalSize.toFloat() / compressedSize.toFloat()
        
        Log.d(TAG, "   JPEG compression: ${originalSize}KB → ${compressedSize}KB (${compressionRatio}x, ${compressionTime}ms)")
        
        val jpegBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        Log.d(TAG, "   JPEG decoded: ${jpegBitmap.width}x${jpegBitmap.height}")
        
        return jpegBitmap
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

    private suspend fun processFrameAndExtractUci(bitmap: Bitmap, frameNumber: Int) {
        try {
            val currentBoardState = if (cachedBoardCorners != null && cachedOrientation != null) {
                getBoardStateFromBitmapWithCachedCorners(
                    bitmap, 
                    cachedBoardCorners!!, 
                    "Frame #$frameNumber",
                    cachedOrientation!!,
                    this@ScreenCaptureService,
                    saveDebugImage = enableDebugImages,
                    debugImageCounter = debugImageCounter
                )
            } else {
                val state = getBoardStateFromBitmap(bitmap, "Frame #$frameNumber", this@ScreenCaptureService)
                
                if (state?.boardCorners != null && state.whiteOnBottom != null) {
                    cachedBoardCorners = state.boardCorners
                    cachedOrientation = state.whiteOnBottom
                    cachedUciCoordinates = state.uciToScreenCoordinates
                    consecutiveErrors = 0
                    
                    Log.d(TAG, "✅ Cached board corners, orientation AND ${cachedUciCoordinates?.size} UCI coordinates")
                    
                    // ✅ Start game when board is first detected
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
                val allPieces = currentBoardState.white + currentBoardState.black
                val currentUci = UciSnapshot(
                    allPieces = allPieces,
                    whitePieces = currentBoardState.white,
                    blackPieces = currentBoardState.black,
                    captureNumber = frameNumber,
                    timestamp = System.currentTimeMillis()
                )
                
                Log.d(TAG, "📊 Frame #$frameNumber UCI: ${currentUci.totalPieceCount()} pieces (W:${currentUci.whitePieces.size}, B:${currentUci.blackPieces.size})")
                
                // ✅ Apply noise filtering when game is active
                if (lastValidUci != null && gameStarted) {
                    val pieceDifference = kotlin.math.abs(currentUci.totalPieceCount() - lastValidUci!!.totalPieceCount())
                    
                    if (pieceDifference > MAX_PIECE_DIFFERENCE) {
                        Log.w(TAG, "🚫 NOISE DETECTED! Piece difference: $pieceDifference (max: $MAX_PIECE_DIFFERENCE)")
                        return
                    }
                    
                    Log.d(TAG, "✅ Valid UCI change: $pieceDifference pieces difference")
                }
                
                consecutiveErrors = 0
                
                // ✅ Send board position to backend if game is active and board changed
                if (gameStarted && !waitingForBackendMove) {
                    if (lastValidUci == null || currentUci.allPieces != lastValidUci!!.allPieces) {
                        Log.d(TAG, "🔄 Board state changed, sending to backend...")
                        sendBoardPositionToBackend(currentUci)
                    }
                }
                
                previousValidUci = lastValidUci
                lastValidUci = currentUci
                
                if (enableDebugImages) {
                    debugImageCounter++
                    Log.d(TAG, "📸 Debug image #$debugImageCounter saved")
                }
                
                Log.d(TAG, "💾 Stored UCI snapshot #$frameNumber: ${currentUci.totalPieceCount()} pieces")
                
            } else {
                consecutiveErrors++
                Log.w(TAG, "⚠️ No board detected in frame #$frameNumber (Error count: $consecutiveErrors)")
                
                if (consecutiveErrors >= maxConsecutiveErrors && cachedBoardCorners != null) {
                    Log.w(TAG, "⚠️ Too many errors, but keeping game state")
                    consecutiveErrors = 0
                }
            }
        } catch (e: Exception) {
            consecutiveErrors++
            Log.e(TAG, "❌ Error processing frame #$frameNumber", e)
            
            if (consecutiveErrors >= maxConsecutiveErrors && cachedBoardCorners != null) {
                Log.w(TAG, "⚠️ Too many errors, but keeping game state")
                consecutiveErrors = 0
            }
        }
    }

    // ✅ Send board position to backend (backend detects moves and responds)
    private suspend fun sendBoardPositionToBackend(currentUci: UciSnapshot) {
        try {
            waitingForBackendMove = true
            
            val result = backendClient.sendBoardPosition(
                currentUci.whitePieces,
                currentUci.blackPieces
            )
            
            result.onSuccess { response ->
                when {
                    response.isEmpty() -> {
                        // No move detected by backend (board state unchanged)
                        Log.d(TAG, "ℹ️ Backend: No move detected")
                    }
                    response == "Invalid" -> {
                        withContext(Dispatchers.Main) {
                            showToast("Backend: Invalid position")
                        }
                    }
                    response == "Game Over" -> {
                        withContext(Dispatchers.Main) {
                            showToast("Game Over!")
                        }
                        gameStarted = false
                    }
                    else -> {
                        // Backend detected a move and responded with its move
                        Log.d(TAG, "🎯 Backend move: $response")
                        executeBackendMove(response)
                    }
                }
            }.onFailure { e ->
                Log.e(TAG, "❌ Error sending board position", e)
            }
            
            waitingForBackendMove = false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in sendBoardPositionToBackend", e)
            waitingForBackendMove = false
        }
    }

    // ✅ Start game with backend and handle first move
    private fun startGameWithBackend(bottomColor: String) {
        processingScope.launch {
            try {
                appColor = bottomColor
                Log.d(TAG, "🎮 App is playing as: $bottomColor")
                
                val result = backendClient.startGame(bottomColor)
                
                result.onSuccess { response ->
                    gameStarted = true
                    
                    // ✅ If app plays white, backend gives first move
                    if (appColor == "white") {
                        if (response.isNotEmpty() && response != "Invalid" && response != "Game Over") {
                            Log.d(TAG, "✅ App is white - backend gave first move: $response")
                            executeBackendMove(response)
                        }
                    } else {
                        // ✅ If app plays black, wait for opponent's first move
                        withContext(Dispatchers.Main) {
                            showToast("Waiting for opponent's first move...")
                        }
                        Log.d(TAG, "⏳ App is black - waiting for opponent to move first")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting game", e)
            }
        }
    }

    private suspend fun executeBackendMove(move: String) {
        withContext(Dispatchers.Main) {
            try {
                val executor = AutoTapExecutor.getInstance()
                
                if (executor == null) {
                    showToast("⚠️ Enable Accessibility Service")
                    return@withContext
                }
                
                if (cachedUciCoordinates == null) {
                    Log.e(TAG, "❌ UCI coordinates not cached")
                    return@withContext
                }
                
                Log.d(TAG, "🤖 Executing backend's move: $move")
                val success = executor.executeMove(move, cachedUciCoordinates!!)
                
                if (success) {
                    showToast("Played: $move")
                } else {
                    showToast("Failed to execute: $move")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error executing move", e)
            }
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
        
        Log.d(TAG, "🛑 Service destroying...")
        Log.d(TAG, "📸 Total debug images saved: $debugImageCounter")
        
        processingJob?.cancel()
        processingScope.cancel()
        
        lastValidUci = null
        previousValidUci = null
        cachedBoardCorners = null
        cachedOrientation = null
        cachedUciCoordinates = null
        
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            Log.d(TAG, "✅ Service destroyed")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error during cleanup", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
