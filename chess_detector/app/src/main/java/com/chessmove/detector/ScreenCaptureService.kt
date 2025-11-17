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
    
    // ✅ NEW: Chess board crop coordinates
    private val BOARD_CROP_X = 44
    private val BOARD_CROP_Y = 459
    private val BOARD_CROP_WIDTH = 675 - 44  // 698 pixels
    private val BOARD_CROP_HEIGHT = 1090 - 459 // 701 pixels
    
    private var lastValidUci: UciSnapshot? = null
    private var previousValidUci: UciSnapshot? = null
    
    // ✅ REMOVED: No more board corner detection needed
    // private var cachedBoardCorners: Array<Point>? = null
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
    private var isAppTurn = false
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
        
        Log.d(TAG, "✅ Service created with PARTIAL capture mode")
        Log.d(TAG, "   Full Screen: ${screenWidth}x${screenHeight}")
        Log.d(TAG, "   Board Crop: ${BOARD_CROP_WIDTH}x${BOARD_CROP_HEIGHT} at ($BOARD_CROP_X,$BOARD_CROP_Y)")
        Log.d(TAG, "   Memory savings: ~${((1 - (BOARD_CROP_WIDTH * BOARD_CROP_HEIGHT).toFloat() / (screenWidth * screenHeight))) * 100}%")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "📱 onStartCommand called")
        
        if (intent?.action == "TOGGLE_DEBUG") {
            enableDebugImages = !enableDebugImages
            showToast("Debug images: ${if (enableDebugImages) "ON" else "OFF"}")
            Log.d(TAG, "🔧 Debug images toggled: $enableDebugImages")
            return START_STICKY
        }
        
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
                        Log.d(TAG, "🎬 Starting continuous PARTIAL capture!")
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
            description = "Captures chess board region for detection"
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
            val gameStatus = if (gameStarted) " | ${appColor?.take(1)?.uppercase()}" else ""
            val uciInfo = if (lastValidUci != null) {
                " | W:${lastValidUci!!.whitePieces.size} B:${lastValidUci!!.blackPieces.size}"
            } else ""
            val debugStatus = if (enableDebugImages) " | 📸" else ""
            "$text (#$count-Crop$gameStatus$uciInfo$debugStatus)"
        } else text
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("♟️ Chess Detector [PARTIAL]")
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
        Log.d(TAG, "🔧 Setting up media projection for PARTIAL capture...")
        
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        if (mediaProjection == null) {
            Log.e(TAG, "❌ MediaProjection is null!")
            throw Exception("MediaProjection is null")
        }
        Log.d(TAG, "✅ MediaProjection created")

        // ✅ IMPORTANT: Still capture full screen, but we'll crop later
        // This is because VirtualDisplay doesn't support offset capture
        imageReader = ImageReader.newInstance(
            screenWidth,
            screenHeight,
            PixelFormat.RGBA_8888,
            2
        )
        Log.d(TAG, "✅ ImageReader created: ${screenWidth}x${screenHeight} (will crop to board)")

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
        Log.d(TAG, "🔄 Direct processing mode with PARTIAL crop + JPEG")
    }

    private fun startContinuousCapture() {
        isCapturing = true
        captureCount = 0
        debugImageCounter = 0
        lastValidUci = null
        previousValidUci = null
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
                
                Log.d(TAG, "✂️ PARTIAL capture #$captureCount (${BOARD_CROP_WIDTH}x${BOARD_CROP_HEIGHT})")
                
                updateNotification("Monitoring...", captureCount, lastValidUci?.totalPieceCount() ?: 0)
                
                captureAndProcessPartialWithJpeg()
                
                delay(CAPTURE_INTERVAL)
            }
            
            if (captureCount >= MAX_CAPTURES) {
                showToast("Stopped after $MAX_CAPTURES captures")
            }
            
            stopSelf()
        }
    }

    /**
     * ✅ NEW: Capture full screen, crop to board region, then convert to JPEG
     */
    private fun captureAndProcessPartialWithJpeg() {
        var fullBitmap: Bitmap? = null
        var croppedBitmap: Bitmap? = null
        var jpegBitmap: Bitmap? = null
        
        try {
            isProcessing.set(true)
            
            Thread.sleep(200)
            
            val image = imageReader?.acquireLatestImage()
            
            if (image != null) {
                // Step 1: Get full screen bitmap
                fullBitmap = imageToBitmap(image)
                image.close()
                
                val fullSize = fullBitmap.byteCount / 1024
                Log.d(TAG, "📥 Full screen captured: ${fullBitmap.width}x${fullBitmap.height} (${fullSize}KB)")
                
                // Step 2: Crop to chess board region
                croppedBitmap = Bitmap.createBitmap(
                    fullBitmap,
                    BOARD_CROP_X,
                    BOARD_CROP_Y,
                    BOARD_CROP_WIDTH,
                    BOARD_CROP_HEIGHT
                )
                fullBitmap.recycle()
                fullBitmap = null
                
                val croppedSize = croppedBitmap.byteCount / 1024
                val savings = ((1 - croppedSize.toFloat() / fullSize) * 100).toInt()
                Log.d(TAG, "✂️ Cropped to board: ${croppedBitmap.width}x${croppedBitmap.height} (${croppedSize}KB, ${savings}% saved)")
                
                // Step 3: Convert to JPEG format
                jpegBitmap = convertToJpegFormat(croppedBitmap)
                croppedBitmap.recycle()
                croppedBitmap = null
                
                Log.d(TAG, "🎨 Converted to JPEG format")
                
                // Step 4: Process the board
                val bitmapToProcess = jpegBitmap
                jpegBitmap = null
                
                runBlocking {
                    processPartialFrameAndExtractUci(bitmapToProcess, captureCount)
                }
                
                bitmapToProcess.recycle()
                Log.d(TAG, "🗑️ Frame #$captureCount cleanup complete")
                
            } else {
                Log.w(TAG, "⚠️ No image available on capture #$captureCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error capturing/processing frame #$captureCount", e)
            consecutiveErrors++
            
            fullBitmap?.recycle()
            croppedBitmap?.recycle()
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
        
        Log.d(TAG, "   JPEG: ${originalSize}KB → ${compressedSize}KB (${compressionRatio}x, ${compressionTime}ms)")
        
        val jpegBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
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

    /**
     * ✅ NEW: Process pre-cropped board image (no board detection needed!)
     */
    private suspend fun processPartialFrameAndExtractUci(boardBitmap: Bitmap, frameNumber: Int) {
        try {
            // ✅ SIMPLIFIED: Board is already cropped, just detect pieces
            // No corner detection needed since we captured exact board region
            
            // First time: detect orientation and setup coordinates
            if (cachedOrientation == null) {
                Log.d(TAG, "🔍 First capture - detecting orientation...")
                val initialState = getBoardStateFromBitmap(
                    boardBitmap, 
                    "Frame #$frameNumber", 
                    this@ScreenCaptureService
                )
                
                if (initialState?.whiteOnBottom != null) {
                    cachedOrientation = initialState.whiteOnBottom
                    cachedUciCoordinates = initialState.uciToScreenCoordinates
                    
                    Log.d(TAG, "✅ Orientation: ${if (cachedOrientation == true) "White" else "Black"} on bottom")
                    Log.d(TAG, "✅ UCI coordinates cached: ${cachedUciCoordinates?.size} squares")
                    
                    if (!gameStarted && backendClient.hasBackendUrl()) {
                        val bottomColor = if (cachedOrientation == true) "white" else "black"
                        startGameWithBackend(bottomColor)
                    }
                    
                    withContext(Dispatchers.Main) {
                        showToast("Board detected! Playing as ${if (cachedOrientation == true) "white" else "black"}")
                    }
                }
                
                // Process first UCI
                if (initialState != null) {
                    val allPieces = initialState.white + initialState.black
                    lastValidUci = UciSnapshot(
                        allPieces = allPieces,
                        whitePieces = initialState.white,
                        blackPieces = initialState.black,
                        captureNumber = frameNumber,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    if (enableDebugImages) {
                        debugImageCounter++
                    }
                }
                
                return
            }
            
            // ✅ Subsequent captures: Use fast detection without corner detection
            val currentBoardState = getBoardStateFromBitmapDirectly(
                boardBitmap,
                "Frame #$frameNumber",
                cachedOrientation!!,
                this@ScreenCaptureService,
                saveDebugImage = enableDebugImages,
                debugImageCounter = debugImageCounter
            )
            
            if (currentBoardState != null) {
                val allPieces = currentBoardState.white + currentBoardState.black
                val currentUci = UciSnapshot(
                    allPieces = allPieces,
                    whitePieces = currentBoardState.white,
                    blackPieces = currentBoardState.black,
                    captureNumber = frameNumber,
                    timestamp = System.currentTimeMillis()
                )
                
                Log.d(TAG, "📊 Frame #$frameNumber: ${currentUci.totalPieceCount()} pieces (W:${currentUci.whitePieces.size}, B:${currentUci.blackPieces.size})")
                
                // Noise filtering
                if (lastValidUci != null && gameStarted) {
                    val pieceDifference = kotlin.math.abs(currentUci.totalPieceCount() - lastValidUci!!.totalPieceCount())
                    
                    if (pieceDifference > MAX_PIECE_DIFFERENCE) {
                        Log.w(TAG, "🚫 NOISE! Difference: $pieceDifference")
                        return
                    }
                }
                
                consecutiveErrors = 0
                
                // Detect moves
                if (previousValidUci != null && gameStarted && !waitingForBackendMove) {
                    detectMoveFromUciChange(previousValidUci!!, currentUci)
                }
                
                previousValidUci = lastValidUci
                lastValidUci = currentUci
                
                if (enableDebugImages) {
                    debugImageCounter++
                }
                
            } else {
                consecutiveErrors++
                Log.w(TAG, "⚠️ Detection failed (Error: $consecutiveErrors)")
            }
            
        } catch (e: Exception) {
            consecutiveErrors++
            Log.e(TAG, "❌ Error processing frame #$frameNumber", e)
        }
    }

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
            
            handler.post {
                showToast("Enemy: $move")
            }
            
            sendMoveToBackend(move)
        }
    }

    private fun startGameWithBackend(bottomColor: String) {
        processingScope.launch {
            try {
                appColor = bottomColor
                Log.d(TAG, "🎮 App is playing as: $bottomColor")
                
                val result = backendClient.startGame(bottomColor)
                
                result.onSuccess { response ->
                    gameStarted = true
                    
                    if (appColor == "white") {
                        if (response.isNotEmpty() && response != "Invalid" && response != "Game Over") {
                            Log.d(TAG, "✅ First move: $response")
                            executeBackendMove(response)
                            isAppTurn = false
                        }
                    } else {
                        isAppTurn = false
                        withContext(Dispatchers.Main) {
                            showToast("Waiting for enemy's first move...")
                        }
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
                            withContext(Dispatchers.Main) {
                                showToast("Backend rejected move")
                            }
                        }
                        "Game Over" -> {
                            withContext(Dispatchers.Main) {
                                showToast("Game Over!")
                            }
                            gameStarted = false
                        }
                        else -> {
                            executeBackendMove(response)
                        }
                    }
                }
                
                waitingForBackendMove = false
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error sending move", e)
                waitingForBackendMove = false
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
                
                executor.executeMove(move, cachedUciCoordinates!!)
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
        Log.d(TAG, "📸 Total captures: $debugImageCounter")
        
        processingJob?.cancel()
        processingScope.cancel()
        
        lastValidUci = null
        previousValidUci = null
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
