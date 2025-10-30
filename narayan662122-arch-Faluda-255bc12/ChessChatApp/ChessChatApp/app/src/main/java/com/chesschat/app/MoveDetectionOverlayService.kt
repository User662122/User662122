package com.chesschat.app

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.opencv.core.Mat
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Improved Move Detection Overlay Service with COMPREHENSIVE LIVE LOGGING
 * All operations are logged to both LogCat and on-screen live log
 */
class MoveDetectionOverlayService : Service() {

    companion object {
        private const val TAG = "MoveDetection"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "chess_move_detection"
    }

    enum class ResizeMode {
    NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT
    }


    // Game State
    private var gameStarted = false
    private var playerColor: String? = null
    
    // UI Components
    private var windowManager: WindowManager? = null
    private var compactOverlay: View? = null
    private var expandedOverlay: View? = null
    private var isExpanded = false
    
    // Status Views
    private var statusTextView: TextView? = null
    private var liveLogTextView: TextView? = null
    private val logBuffer = StringBuilder()
    
    // Screen Capture
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    // Memory Management
    private val bitmapPool = BitmapPool(maxPoolSize = 3)
    private var previousBitmap: Bitmap? = null
    
    // OpenCV-based board detection with frame differencing
    private val boardDetector = ChessBoardDetector()
    private var previousMat: Mat? = null
    
    // Detection State
    private val handler = Handler(Looper.getMainLooper())
    private var isDetecting = false
    private var isAutoPlayEnabled = false
    
    // Board Configuration - will be auto-detected
    private var boardX = 50
    private var boardY = 300
    private var boardSize = 800
    private var isFlipped = false
    private var boardAutoDetected = false  // Track if board has been automatically detected
    
    // Manual Board Setup
    private var manualSetupOverlay: View? = null
    private var manualBoardX = 50
    private var manualBoardY = 300
    private var manualBoardSize = 600
    
    // App Profile
    private var currentProfile: ChessAppProfile = ChessAppProfiles.profiles.last()
    private var detectionInterval = 100L  // Optimized detection interval (100ms for balance of speed and accuracy)
    
    // Network (increased timeouts for Stockfish computation time)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()
    
    private var ngrokUrl: String = ""
    
    // Touch Simulation
    private val touchSimulator = TouchSimulator()
    
    // Detection Runnable
    private val detectionRunnable = object : Runnable {
        override fun run() {
            if (isDetecting) {
                captureScreen()
                handler.postDelayed(this, detectionInterval)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        addLog("onCreate", "Service created")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Load saved settings
        loadSettings()
        
        // Detect current chess app and apply profile
        detectCurrentApp()
        
        // Create compact overlay first
        createCompactOverlay()
        addLog("onCreate", "Overlay created successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        addLog("onStartCommand", "Service starting with intent")
        intent?.let {
            val resultCode = it.getIntExtra("resultCode", 0)
            val data = it.getParcelableExtra<Intent>("data")
            val x = it.getIntExtra("boardX", 50)
            val y = it.getIntExtra("boardY", 300)
            val size = it.getIntExtra("boardSize", 800)
            
            boardX = x
            boardY = y
            boardSize = size
            
            addLog("onStartCommand", "Board config: X=$x, Y=$y, Size=$size")
            saveSettings()
            
            if (resultCode != 0 && data != null) {
                addLog("onStartCommand", "Starting screen capture immediately (0ms)")
                // Start immediately - no delay for maximum responsiveness
                startScreenCapture(resultCode, data)
            }
        }
        return START_STICKY
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("ChessChatPrefs", Context.MODE_PRIVATE)
        ngrokUrl = prefs.getString("base_url", "") ?: ""
        boardX = prefs.getInt("board_x", 50)
        boardY = prefs.getInt("board_y", 300)
        boardSize = prefs.getInt("board_size", 800)
        isFlipped = prefs.getBoolean("board_flipped", false)
        boardAutoDetected = prefs.getBoolean("board_auto_detected", false)
        addLog("loadSettings", "Server: $ngrokUrl")
        addLog("loadSettings", "Board: X=$boardX Y=$boardY Size=$boardSize Flipped=$isFlipped ManualSetup=$boardAutoDetected")
    }

    private fun saveSettings() {
        val prefs = getSharedPreferences("ChessChatPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("base_url", ngrokUrl)
            .putInt("board_x", boardX)
            .putInt("board_y", boardY)
            .putInt("board_size", boardSize)
            .putBoolean("board_flipped", isFlipped)
            .putBoolean("board_auto_detected", boardAutoDetected)
            .apply()
        addLog("saveSettings", "Settings saved (API: $ngrokUrl, ManualSetup=$boardAutoDetected)")
    }

    private fun detectCurrentApp() {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.getRunningTasks(1)
            if (tasks.isNotEmpty()) {
                val topActivity = tasks[0].topActivity
                val packageName = topActivity?.packageName ?: ""
                currentProfile = ChessAppProfiles.getProfile(packageName)
                detectionInterval = currentProfile.frameRateLimit
                addLog("detectCurrentApp", "App: ${currentProfile.appName}")
                addLog("detectCurrentApp", "Interval: ${detectionInterval}ms")
            }
        } catch (e: Exception) {
            addLog("detectCurrentApp", "ERROR: ${e.message}")
        }
    }

    private fun minimizeOverlay() {
        compactOverlay?.let { windowManager?.removeView(it) }
        compactOverlay = null
        isExpanded = false
        createMinimizedButton()
        addLog("minimizeOverlay", "Overlay minimized to icon")
    }
    
    private fun createMinimizedButton() {
        val layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                dp(80),
                dp(80),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                dp(80),
                dp(80),
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        }

        layoutParams.gravity = Gravity.TOP or Gravity.END
        layoutParams.x = dp(20)
        layoutParams.y = dp(100)

        val container = FrameLayout(this).apply {
            setBackgroundResource(android.R.drawable.btn_default)
            background.alpha = 200
            elevation = dp(10).toFloat()
        }

        val iconTextView = TextView(this).apply {
            text = "🤖"
            textSize = 32f
            gravity = Gravity.CENTER
            setOnClickListener {
                container.let { windowManager?.removeView(it) }
                createCompactOverlay()
            }
        }

        container.addView(iconTextView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        makeDraggable(container, layoutParams)
        windowManager?.addView(container, layoutParams)
    }
    
    /**
     * AUTOMATIC WORKFLOW: Uses manual board setup and color
     * 1. Uses YOUR manual setup (board position AND color)
     * 2. Shows visual feedback (red border)
     * 3. Sends game start to API
     * 4. Sends YOUR selected color to API
     * 5. Starts automatic move detection using OpenCV
     */
    private fun startAutomaticWorkflow() {
        addLog("startAutomaticWorkflow", "=== STARTING AUTOMATIC WORKFLOW ===")
        
        if (ngrokUrl.isEmpty()) {
            updateStatus("⚠️ No server URL")
            addLog("startAutomaticWorkflow", "FAILED - Server URL not configured!")
            Toast.makeText(this, "Configure server URL in main app first", Toast.LENGTH_LONG).show()
            return
        }
        
        if (imageReader == null) {
            updateStatus("⚠️ No screen capture")
            addLog("startAutomaticWorkflow", "FAILED - Screen capture not initialized!")
            Toast.makeText(this, "Please restart detection from main app", Toast.LENGTH_LONG).show()
            return
        }
        
        // Reload settings to ensure we have the latest manual setup values
        loadSettings()
        addLog("startAutomaticWorkflow", "Checking board setup status:")
        addLog("startAutomaticWorkflow", "  boardAutoDetected = $boardAutoDetected")
        addLog("startAutomaticWorkflow", "  boardX = $boardX")
        addLog("startAutomaticWorkflow", "  boardY = $boardY")
        addLog("startAutomaticWorkflow", "  boardSize = $boardSize")
        
        // Require manual setup first
        if (!boardAutoDetected || boardX <= 0 || boardY <= 0 || boardSize <= 0) {
            updateStatus("⚠️ Setup required")
            addLog("startAutomaticWorkflow", "FAILED - Manual setup not done!")
            addLog("startAutomaticWorkflow", "  Reason: boardAutoDetected=$boardAutoDetected, X=$boardX, Y=$boardY, Size=$boardSize")
            addLog("startAutomaticWorkflow", "Please click 'Manual Setup' button first")
            Toast.makeText(this, "Please do Manual Setup first:\n1. Click 'Manual Setup...'\n2. Drag corners to match board\n3. Click DONE\n4. Select your color", Toast.LENGTH_LONG).show()
            return
        }
        
        // Enable auto-play automatically for automatic workflow
        isAutoPlayEnabled = true
        addLog("startAutomaticWorkflow", "✓ Auto-play ENABLED automatically")
        
        addLog("startAutomaticWorkflow", "✓ Using MANUAL SETUP configuration")
        addLog("startAutomaticWorkflow", "  Position: X=$boardX, Y=$boardY, Size=$boardSize")
        
        val manualColor = if (isFlipped) "black" else "white"
        playerColor = manualColor
        
        // Send the SAME color as selected by user during board setup
        addLog("startAutomaticWorkflow", "  Your selected color: $manualColor (${if (isFlipped) "Black" else "White"} at bottom)")
        addLog("startAutomaticWorkflow", "  Sending color to engine: $manualColor")
        
        handler.post {
            // Show visual red border feedback
            showBoardDetectionBorder()
            
            // Start game via API (after 1 second)
            handler.postDelayed({
                addLog("startAutomaticWorkflow", "Step 1: Starting game via API...")
                sendStartCommandAutomatic { success ->
                    if (success) {
                        // Send the SAME color to API as user selected
                        handler.postDelayed({
                            addLog("startAutomaticWorkflow", "Step 2: Setting color '$manualColor' on server...")
                            sendColorCommandAutomatic(manualColor) { colorSuccess ->
                                if (colorSuccess) {
                                    // Start move detection
                                    handler.postDelayed({
                                        addLog("startAutomaticWorkflow", "Step 3: Starting automatic move detection with OpenCV...")
                                        updateStatus("✅ Auto-playing as $manualColor")
                                        startDetection()
                                        addLog("startAutomaticWorkflow", "=== AUTOMATIC WORKFLOW COMPLETE ===")
                                    }, 500)
                                } else {
                                    updateStatus("⚠️ Color setup failed")
                                }
                            }
                        }, 500)
                    } else {
                        updateStatus("⚠️ Game start failed")
                    }
                }
            }, 1000)
        }
    }
    
    /**
     * Show red border around detected board area for 3 seconds
     */
    private fun showBoardDetectionBorder() {
        addLog("showBoardDetectionBorder", "Showing red border at X=$boardX, Y=$boardY, Size=$boardSize")
        
        val borderView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        
        val layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                boardSize,
                boardSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                boardSize,
                boardSize,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        }
        
        layoutParams.gravity = Gravity.TOP or Gravity.START
        layoutParams.x = boardX
        layoutParams.y = boardY
        
        // Create custom drawable for red border
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 15f
            isAntiAlias = true
        }
        
        borderView.background = object : android.graphics.drawable.Drawable() {
            override fun draw(canvas: Canvas) {
                val rect = RectF(7.5f, 7.5f, bounds.width() - 7.5f, bounds.height() - 7.5f)
                canvas.drawRect(rect, paint)
            }
            
            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }
            
            override fun setColorFilter(colorFilter: ColorFilter?) {
                paint.colorFilter = colorFilter
            }
            
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        
        try {
            windowManager?.addView(borderView, layoutParams)
            addLog("showBoardDetectionBorder", "✓ Red border displayed")
            
            // Remove border after 3 seconds
            handler.postDelayed({
                try {
                    windowManager?.removeView(borderView)
                    addLog("showBoardDetectionBorder", "Border removed after 3 seconds")
                } catch (e: Exception) {
                    addLog("showBoardDetectionBorder", "Error removing border: ${e.message}")
                }
            }, 3000)
        } catch (e: Exception) {
            addLog("showBoardDetectionBorder", "Error showing border: ${e.message}")
        }
    }
    
    /**
     * Send start command automatically (with callback)
     */
    private fun sendStartCommandAutomatic(callback: (Boolean) -> Unit) {
        addLog("sendStartCommandAutomatic", "Sending game start request...")
        
        Thread {
            try {
                val request = Request.Builder()
                    .url("$ngrokUrl/start")
                    .post("".toRequestBody(null))
                    .build()
                
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        addLog("sendStartCommandAutomatic", "FAILED - ${e.message}")
                        handler.post { callback(false) }
                    }
                    
                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: ""
                        addLog("sendStartCommandAutomatic", "Response ${response.code}: $responseBody")
                        
                        if (response.isSuccessful) {
                            gameStarted = true
                            addLog("sendStartCommandAutomatic", "✓ Game started successfully")
                            handler.post { callback(true) }
                        } else {
                            addLog("sendStartCommandAutomatic", "FAILED - Server error ${response.code}")
                            handler.post { callback(false) }
                        }
                    }
                })
            } catch (e: Exception) {
                addLog("sendStartCommandAutomatic", "EXCEPTION - ${e.message}")
                handler.post { callback(false) }
            }
        }.start()
    }
    
    /**
     * Send color command automatically (with callback)
     */
    private fun sendColorCommandAutomatic(color: String, callback: (Boolean) -> Unit) {
        addLog("sendColorCommandAutomatic", "Sending color: $color")
        
        Thread {
            try {
                val requestBody = color.toRequestBody(null)
                val request = Request.Builder()
                    .url("$ngrokUrl/move")
                    .post(requestBody)
                    .build()
                
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        addLog("sendColorCommandAutomatic", "FAILED - ${e.message}")
                        handler.post { callback(false) }
                    }
                    
                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: ""
                        addLog("sendColorCommandAutomatic", "Response ${response.code}: $responseBody")
                        
                        if (response.isSuccessful) {
                            addLog("sendColorCommandAutomatic", "✓ Color set to $color")
                            
                            // Extract any move from the response (for both white and black)
                            val movePattern = "[a-h][1-8][a-h][1-8][qrbn]?".toRegex()
                            val engineMove = movePattern.find(responseBody)?.value
                            
                            addLog("sendColorCommandAutomatic", "Extracted move from response: ${engineMove ?: "NONE"}")
                            addLog("sendColorCommandAutomatic", "Auto-play enabled: $isAutoPlayEnabled")
                            
                            // If engine made first move, execute it automatically
                            // - When user chose "white": we send "white" → user plays white → user moves first → no move to execute
                            // - When user chose "black": we send "black" → user plays black → engine moves first (e.g., "e2e4") → execute it
                            if (engineMove != null && isAutoPlayEnabled && responseBody.isNotEmpty()) {
                                addLog("sendColorCommandAutomatic", "✓ Engine made first move: $engineMove - EXECUTING NOW")
                                handler.post {
                                    executeMoveAutomatically(engineMove)
                                }
                            } else {
                                if (engineMove == null) {
                                    addLog("sendColorCommandAutomatic", "No move to execute (engine didn't move first)")
                                } else if (!isAutoPlayEnabled) {
                                    addLog("sendColorCommandAutomatic", "Move found but auto-play is DISABLED")
                                }
                            }
                            
                            handler.post { callback(true) }
                        } else {
                            addLog("sendColorCommandAutomatic", "FAILED - Server error ${response.code}")
                            handler.post { callback(false) }
                        }
                    }
                })
            } catch (e: Exception) {
                addLog("sendColorCommandAutomatic", "EXCEPTION - ${e.message}")
                handler.post { callback(false) }
            }
        }.start()
    }
    
    private fun sendStartCommand() {
        addLog("sendStartCommand", "CALLED - Initiating game start")
        
        if (ngrokUrl.isEmpty()) {
            updateStatus("⚠️ No server URL")
            addLog("sendStartCommand", "FAILED - Server URL not configured!")
            Toast.makeText(this, "Configure server URL in main app first", Toast.LENGTH_LONG).show()
            return
        }

        addLog("sendStartCommand", "URL: $ngrokUrl/start")
        addLog("sendStartCommand", "Sending POST request...")
        updateStatus("🚀 Starting game...")

        Thread {
            try {
                val request = Request.Builder()
                    .url("$ngrokUrl/start")
                    .post("".toRequestBody(null))
                    .build()

                addLog("HTTP", "POST $ngrokUrl/start")
                
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        addLog("sendStartCommand", "FAILED - ${e.message}")
                        handler.post {
                            updateStatus("⚠️ Connection failed")
                            Toast.makeText(this@MoveDetectionOverlayService, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: "No response"
                        addLog("HTTP", "Response ${response.code}: $responseBody")
                        
                        if (response.isSuccessful) {
                            addLog("sendStartCommand", "SUCCESS - Game started")
                            handler.post {
                                gameStarted = true
                                updateStatus("✅ Game started!")
                                Toast.makeText(this@MoveDetectionOverlayService, "Game started!", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            addLog("sendStartCommand", "FAILED - Server error ${response.code}")
                            handler.post {
                                updateStatus("⚠️ Server error")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                addLog("sendStartCommand", "EXCEPTION - ${e.message}")
                handler.post {
                    updateStatus("⚠️ Error")
                }
            }
        }.start()
    }
    
    private fun sendColorCommand(color: String) {
        addLog("sendColorCommand", "CALLED - Setting color to $color")
        
        if (ngrokUrl.isEmpty()) {
            updateStatus("⚠️ No server URL")
            addLog("sendColorCommand", "FAILED - No server URL")
            Toast.makeText(this, "Configure server URL first", Toast.LENGTH_SHORT).show()
            return
        }

        if (!gameStarted) {
            updateStatus("⚠️ Start game first")
            addLog("sendColorCommand", "FAILED - Game not started")
            Toast.makeText(this, "Press Start button first", Toast.LENGTH_SHORT).show()
            return
        }

        addLog("sendColorCommand", "URL: $ngrokUrl/move")
        addLog("sendColorCommand", "Data: $color")
        updateStatus("🎨 Setting color: $color")

        Thread {
            try {
                val requestBody = color.toRequestBody(null)
                val request = Request.Builder()
                    .url("$ngrokUrl/move")
                    .post(requestBody)
                    .build()

                addLog("HTTP", "POST $ngrokUrl/move body=$color")
                
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        addLog("sendColorCommand", "FAILED - ${e.message}")
                        handler.post {
                            updateStatus("⚠️ Connection failed")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: "No response"
                        addLog("HTTP", "Response ${response.code}: $responseBody")
                        
                        if (response.isSuccessful) {
                            addLog("sendColorCommand", "SUCCESS - Playing as $color")
                            addLog("sendColorCommand", "Engine response: $responseBody")
                            handler.post {
                                playerColor = color
                                updateStatus("✅ Playing as $color")
                                
                                // If engine made first move, execute it automatically
                                // - When user chose "white": engine is WHITE and makes first move (e.g., "Engine is white. First move: e2e4")
                                // - When user chose "black": engine is BLACK and makes first move (e.g., "e2e4")
                                if (isAutoPlayEnabled && responseBody.isNotEmpty()) {
                                    val movePattern = "[a-h][1-8][a-h][1-8][qrbn]?".toRegex()
                                    val engineMove = movePattern.find(responseBody)?.value
                                    if (engineMove != null) {
                                        addLog("sendColorCommand", "Engine made first move: $engineMove")
                                        executeMoveAutomatically(engineMove)
                                    }
                                }
                            }
                        } else {
                            addLog("sendColorCommand", "FAILED - Server error ${response.code}")
                            handler.post {
                                updateStatus("⚠️ Server error")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                addLog("sendColorCommand", "EXCEPTION - ${e.message}")
                handler.post {
                    updateStatus("⚠️ Error")
                }
            }
        }.start()
    }

    private fun createCompactOverlay() {
        addLog("createCompactOverlay", "Building overlay UI")
        
        val layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        } else {
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        }

        layoutParams.gravity = Gravity.TOP or Gravity.END
        layoutParams.x = dp(20)
        layoutParams.y = dp(100)

        // Inflate the overlay layout
        val overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_compact, null)
        
        // Get all button references
        val startButton = overlayView.findViewById<Button>(R.id.startButton)
        val stopButton = overlayView.findViewById<Button>(R.id.stopButton)
        val updateApiButton = overlayView.findViewById<Button>(R.id.updateApiButton)
        val manualSetupButton = overlayView.findViewById<Button>(R.id.manualSetupButton)
        val copyLogsButton = overlayView.findViewById<Button>(R.id.copyLogsButton)
        val minimizeButton = overlayView.findViewById<Button>(R.id.minimizeButton)
        val autoPlayCheckbox = overlayView.findViewById<CheckBox>(R.id.autoPlayCheckbox)
        statusTextView = overlayView.findViewById(R.id.statusTextView)
        liveLogTextView = overlayView.findViewById(R.id.liveLogTextView)
        
        // Make log scrollable
        liveLogTextView?.movementMethod = ScrollingMovementMethod()
        
        // Set up button handlers
        startButton.setOnClickListener {
            addLog("UI", "🤖 Auto Start button clicked")
            startAutomaticWorkflow()
        }
        
        stopButton.setOnClickListener {
            addLog("UI", "Stop button clicked")
            stopDetection()
        }
        
        updateApiButton.setOnClickListener {
            addLog("UI", "Update API button clicked")
            showUpdateApiDialog()
        }
        
        manualSetupButton.setOnClickListener {
            addLog("UI", "📐 Manual Setup button clicked")
            startManualBoardSetup()
        }
        
        copyLogsButton.setOnClickListener {
            addLog("UI", "📋 Copy Logs button clicked")
            copyLogsToClipboard()
        }
        
        minimizeButton.setOnClickListener {
            addLog("UI", "Minimize button clicked")
            minimizeOverlay()
        }
        
        autoPlayCheckbox.setOnCheckedChangeListener { _, isChecked ->
            isAutoPlayEnabled = isChecked
            updateStatus(if (isChecked) "🤖 Auto-Play ON" else "👀 Watching")
            addLog("UI", "Auto-play ${if (isChecked) "ENABLED" else "DISABLED"}")
        }
        
        // Make draggable
        makeDraggable(overlayView, layoutParams)

        compactOverlay = overlayView
        isExpanded = true
        windowManager?.addView(compactOverlay, layoutParams)
        
        addLog("createCompactOverlay", "Overlay displayed successfully")
    }

    private fun makeDraggable(view: View, layoutParams: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initialX + (initialTouchX - event.rawX).toInt()
                    layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(view, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        addLog("startScreenCapture", "CALLED - Initializing screen capture")
        
        val metrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        addLog("startScreenCapture", "Screen: ${width}x${height}")
        
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        // Add flags to prevent black screen detection
        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ChessAutomation",
            width,
            height,
            metrics.densityDpi,
            flags,
            imageReader?.surface,
            null,
            null
        )

        updateStatus("📸 Capture Ready")
        addLog("startScreenCapture", "SUCCESS - Virtual display created")
    }

    private fun startDetection() {
        addLog("startDetection", "CALLED - Starting move detection")
        isDetecting = true
        updateStatus("🔍 Detecting...")
        handler.post(detectionRunnable)
        addLog("startDetection", "Detection loop started (interval: ${detectionInterval}ms)")
    }

    private fun stopDetection() {
        addLog("stopDetection", "CALLED - Stopping detection")
        isDetecting = false
        handler.removeCallbacks(detectionRunnable)
        updateStatus("⚪ Stopped")
        addLog("stopDetection", "Detection stopped")
    }
    
    /**
     * Resume detection IMMEDIATELY (0ms) after executing an automated move
     * Captures fresh baseline and starts detecting for opponent's response instantly
     */
    private fun resumeDetectionImmediately() {
        addLog("resumeDetectionImmediately", "=== INSTANT DETECTION START (0ms) ===")
        
        // Clear previous frame to start fresh
        previousMat?.release()
        previousMat = null
        addLog("resumeDetectionImmediately", "Cleared previous frame")
        
        // Start detection loop immediately - no delay
        isDetecting = true
        updateStatus("🔍 Detecting...")
        addLog("resumeDetectionImmediately", "Detection active with ${detectionInterval}ms interval")
        
        // Post immediately to start detection loop
        handler.post(detectionRunnable)
        
        addLog("resumeDetectionImmediately", "Detection started instantly - waiting for opponent move")
    }

    private fun flipBoard() {
        isFlipped = !isFlipped
        saveSettings()
        val orientation = if (isFlipped) "Black bottom" else "White bottom"
        updateStatus(if (isFlipped) "⬛ Black Bottom" else "⬜ White Bottom")
        addLog("flipBoard", "Board flipped - $orientation")
    }
    
    /**
     * Show dialog to update API endpoint URL
     */
    private fun showUpdateApiDialog() {
        addLog("showUpdateApiDialog", "Opening dialog to update API endpoint")
        
        handler.post {
            // Create EditText for URL input
            val input = android.widget.EditText(this)
            input.setText(ngrokUrl)
            input.hint = "https://your-ngrok-url.ngrok.io"
            input.setPadding(dp(16), dp(16), dp(16), dp(16))
            
            // Create AlertDialog
            val dialog = AlertDialog.Builder(this)
                .setTitle("Update API Endpoint")
                .setMessage("Enter your ngrok or API server URL:")
                .setView(input)
                .setPositiveButton("Update") { _, _ ->
                    val newUrl = input.text.toString().trim()
                    if (newUrl.isNotEmpty()) {
                        ngrokUrl = newUrl
                        saveSettings()
                        addLog("showUpdateApiDialog", "API endpoint updated to: $ngrokUrl")
                        updateStatus("🔗 API Updated")
                        Toast.makeText(this, "API endpoint updated!", Toast.LENGTH_SHORT).show()
                    } else {
                        addLog("showUpdateApiDialog", "Empty URL - no changes made")
                        Toast.makeText(this, "URL cannot be empty!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel") { dialog, _ ->
                    addLog("showUpdateApiDialog", "Dialog cancelled")
                    dialog.cancel()
                }
                .create()
            
            // Set window type for overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            
            dialog.show()
            addLog("showUpdateApiDialog", "Dialog displayed")
        }
    }

/**
 * Start manual board setup with draggable/resizable square
 */
private fun startManualBoardSetup() {
    addLog("startManualBoardSetup", "=== MANUAL BOARD SETUP STARTED ===")
    
    if (imageReader == null) {
        addLog("startManualBoardSetup", "ERROR - Screen capture not initialized")
        Toast.makeText(this, "Please start from main app first", Toast.LENGTH_LONG).show()
        return
    }
    
    updateStatus("📐 Manual Setup...")
    
    // Get screen dimensions
    val metrics = DisplayMetrics()
    windowManager?.defaultDisplay?.getMetrics(metrics)
    val screenWidth = metrics.widthPixels
    val screenHeight = metrics.heightPixels
    
    // Initialize with current or default values
    manualBoardX = if (boardX > 0) boardX else 50
    manualBoardY = if (boardY > 0) boardY else 300
    manualBoardSize = if (boardSize > 0) boardSize else Math.min(screenWidth - 100, 600)
    
    addLog("startManualBoardSetup", "Initial: X=$manualBoardX, Y=$manualBoardY, Size=$manualBoardSize")
    
    // Create the manual setup overlay
    createManualSetupOverlay()
}

/**
 * Create draggable and resizable board setup overlay
 */
private fun createManualSetupOverlay() {
    val container = FrameLayout(this)
    
    // Main board area (green semi-transparent)
    val boardArea = View(this).apply {
        setBackgroundColor(Color.argb(100, 0, 255, 0))
    }
    
    // Border view with handles
    val borderView = View(this).apply {
        setBackgroundColor(Color.TRANSPARENT)
    }
    
    // Custom drawable for border with resize handles
    val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }
    
    val handlePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    borderView.background = object : android.graphics.drawable.Drawable() {
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            
            // Draw border
            canvas.drawRect(5f, 5f, w - 5f, h - 5f, paint)
            
            // Draw corner handles (20x20 pixels)
            val handleSize = 20f
            canvas.drawCircle(5f, 5f, handleSize, handlePaint) // Top-left
            canvas.drawCircle(w - 5f, 5f, handleSize, handlePaint) // Top-right
            canvas.drawCircle(5f, h - 5f, handleSize, handlePaint) // Bottom-left
            canvas.drawCircle(w - 5f, h - 5f, handleSize, handlePaint) // Bottom-right
            
            // Draw edge handles
            canvas.drawCircle(w / 2, 5f, handleSize, handlePaint) // Top
            canvas.drawCircle(w / 2, h - 5f, handleSize, handlePaint) // Bottom
            canvas.drawCircle(5f, h / 2, handleSize, handlePaint) // Left
            canvas.drawCircle(w - 5f, h / 2, handleSize, handlePaint) // Right
        }
        
        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }
        
        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }
        
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
    
    container.addView(boardArea, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    ))
    
    container.addView(borderView, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    ))
    
    // Instructions text
    val instructions = TextView(this).apply {
        text = "Drag corners/edges to resize\nTap DONE when ready"
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(200, 0, 0, 0))
        gravity = Gravity.CENTER
        setPadding(10, 10, 10, 10)
    }
    
    container.addView(instructions, FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT
    ).apply {
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        bottomMargin = 10
    })
    
    // DONE button
    val doneButton = Button(this).apply {
        text = "✓ DONE"
        textSize = 14f
        setBackgroundColor(Color.argb(220, 0, 200, 0))
        setTextColor(Color.WHITE)
        setOnClickListener {
            finishManualSetup()
        }
    }
    
    container.addView(doneButton, FrameLayout.LayoutParams(
        dp(100),
        dp(50)
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        topMargin = dp(10)
    })
    
    // CANCEL button
    val cancelButton = Button(this).apply {
        text = "✗ CANCEL"
        textSize = 14f
        setBackgroundColor(Color.argb(220, 200, 0, 0))
        setTextColor(Color.WHITE)
        setOnClickListener {
            cancelManualSetup()
        }
    }
    
    container.addView(cancelButton, FrameLayout.LayoutParams(
        dp(100),
        dp(50)
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        topMargin = dp(10)
        rightMargin = dp(10)
    })
    
    // Create layout params
    val layoutParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams(
            manualBoardSize,
            manualBoardSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    } else {
        WindowManager.LayoutParams(
            manualBoardSize,
            manualBoardSize,
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }
    
    layoutParams.gravity = Gravity.TOP or Gravity.START
    layoutParams.x = manualBoardX
    layoutParams.y = manualBoardY
    
    // Make draggable and resizable
    makeManualSetupDraggable(container, layoutParams)
    
    manualSetupOverlay = container
    windowManager?.addView(manualSetupOverlay, layoutParams)
    
    addLog("createManualSetupOverlay", "✓ Manual setup overlay displayed")
}

/**
 * Make the manual setup overlay draggable and resizable
 */
private fun makeManualSetupDraggable(view: View, layoutParams: WindowManager.LayoutParams) {
    var initialX = 0
    var initialY = 0
    var initialWidth = 0
    var initialHeight = 0
    var initialTouchX = 0f
    var initialTouchY = 0f
    var resizeMode = ResizeMode.NONE
    
    view.setOnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialWidth = layoutParams.width
                initialHeight = layoutParams.height
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                
                // Determine what to resize/move based on touch position
                val touchX = event.x
                val touchY = event.y
                val handleSize = 60f // Touch area for handles
                
                resizeMode = when {
                    // Corners
                    touchX < handleSize && touchY < handleSize -> ResizeMode.TOP_LEFT
                    touchX > initialWidth - handleSize && touchY < handleSize -> ResizeMode.TOP_RIGHT
                    touchX < handleSize && touchY > initialHeight - handleSize -> ResizeMode.BOTTOM_LEFT
                    touchX > initialWidth - handleSize && touchY > initialHeight - handleSize -> ResizeMode.BOTTOM_RIGHT
                    // Edges
                    touchY < handleSize -> ResizeMode.TOP
                    touchY > initialHeight - handleSize -> ResizeMode.BOTTOM
                    touchX < handleSize -> ResizeMode.LEFT
                    touchX > initialWidth - handleSize -> ResizeMode.RIGHT
                    // Center - move
                    else -> ResizeMode.MOVE
                }
                
                true
            }
            
            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()
                
                when (resizeMode) {
                    ResizeMode.MOVE -> {
                        layoutParams.x = initialX + deltaX
                        layoutParams.y = initialY + deltaY
                    }
                    ResizeMode.TOP_LEFT -> {
                        layoutParams.x = initialX + deltaX
                        layoutParams.y = initialY + deltaY
                        layoutParams.width = (initialWidth - deltaX).coerceAtLeast(200)
                        layoutParams.height = (initialHeight - deltaY).coerceAtLeast(200)
                    }
                    ResizeMode.TOP_RIGHT -> {
                        layoutParams.y = initialY + deltaY
                        layoutParams.width = (initialWidth + deltaX).coerceAtLeast(200)
                        layoutParams.height = (initialHeight - deltaY).coerceAtLeast(200)
                    }
                    ResizeMode.BOTTOM_LEFT -> {
                        layoutParams.x = initialX + deltaX
                        layoutParams.width = (initialWidth - deltaX).coerceAtLeast(200)
                        layoutParams.height = (initialHeight + deltaY).coerceAtLeast(200)
                    }
                    ResizeMode.BOTTOM_RIGHT -> {
                        layoutParams.width = (initialWidth + deltaX).coerceAtLeast(200)
                        layoutParams.height = (initialHeight + deltaY).coerceAtLeast(200)
                    }
                    ResizeMode.TOP -> {
                        layoutParams.y = initialY + deltaY
                        layoutParams.height = (initialHeight - deltaY).coerceAtLeast(200)
                    }
                    ResizeMode.BOTTOM -> {
                        layoutParams.height = (initialHeight + deltaY).coerceAtLeast(200)
                    }
                    ResizeMode.LEFT -> {
                        layoutParams.x = initialX + deltaX
                        layoutParams.width = (initialWidth - deltaX).coerceAtLeast(200)
                    }
                    ResizeMode.RIGHT -> {
                        layoutParams.width = (initialWidth + deltaX).coerceAtLeast(200)
                    }
                    else -> {}
                }
                
                // Keep square shape
                val size = Math.max(layoutParams.width, layoutParams.height)
                layoutParams.width = size
                layoutParams.height = size
                
                windowManager?.updateViewLayout(view, layoutParams)
                true
            }
            
            MotionEvent.ACTION_UP -> {
                // Save current position
                manualBoardX = layoutParams.x
                manualBoardY = layoutParams.y
                manualBoardSize = layoutParams.width
                addLog("makeManualSetupDraggable", "Position: X=$manualBoardX, Y=$manualBoardY, Size=$manualBoardSize")
                true
            }
            
            else -> false
        }
    }
}

/**
 * Finish manual setup and ask for color
 */
private fun finishManualSetup() {
    addLog("finishManualSetup", "User confirmed board position")
    addLog("finishManualSetup", "Final: X=$manualBoardX, Y=$manualBoardY, Size=$manualBoardSize")
    
    // Remove manual setup overlay
    manualSetupOverlay?.let {
        windowManager?.removeView(it)
    }
    manualSetupOverlay = null
    
    // Ask for color (white or black at bottom)
    handler.post {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Which color is at the bottom?")
            .setMessage("Select the piece color that is at the BOTTOM of the board:")
            .setPositiveButton("⬜ WHITE") { _, _ ->
                confirmManualSetup(isWhiteBottom = true)
            }
            .setNegativeButton("⬛ BLACK") { _, _ ->
                confirmManualSetup(isWhiteBottom = false)
            }
            .setCancelable(false)
            .create()
        
        // Set window type for overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } else {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
        }
        
        dialog.show()
    }
}

/**
 * Confirm manual setup with color selection
 */
private fun confirmManualSetup(isWhiteBottom: Boolean) {
    boardX = manualBoardX
    boardY = manualBoardY
    boardSize = manualBoardSize
    isFlipped = !isWhiteBottom
    boardAutoDetected = true // Mark as configured
    
    // Save settings TWICE to ensure persistence
    saveSettings()
    handler.postDelayed({
        saveSettings()
        addLog("confirmManualSetup", "✓ Settings saved twice for reliability")
    }, 100)
    
    val colorText = if (isWhiteBottom) "White" else "Black"
    addLog("confirmManualSetup", "✓ MANUAL SETUP COMPLETE")
    addLog("confirmManualSetup", "  Board: X=$boardX, Y=$boardY, Size=$boardSize")
    addLog("confirmManualSetup", "  Color: $colorText at bottom")
    addLog("confirmManualSetup", "  isFlipped: $isFlipped")
    addLog("confirmManualSetup", "  boardAutoDetected: $boardAutoDetected")
    
    updateStatus("✓ Manual Setup Done")
    Toast.makeText(this, "Board configured! $colorText at bottom\nSetup saved successfully!", Toast.LENGTH_LONG).show()
    
    // Show preview border for 3 seconds
    showBoardDetectionBorder()
}

/**
 * Cancel manual setup
 */
private fun cancelManualSetup() {
    addLog("cancelManualSetup", "Manual setup cancelled by user")
    
    manualSetupOverlay?.let {
        windowManager?.removeView(it)
    }
    manualSetupOverlay = null
    
    updateStatus("✗ Setup Cancelled")
    Toast.makeText(this, "Manual setup cancelled", Toast.LENGTH_SHORT).show()
}

/**
 * Copy all logs to clipboard
 */
private fun copyLogsToClipboard() {
    try {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Chess Chat Logs", logBuffer.toString())
        clipboard.setPrimaryClip(clip)
        
        addLog("copyLogsToClipboard", "✓ Logs copied to clipboard (${logBuffer.length} chars)")
        Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        addLog("copyLogsToClipboard", "ERROR: ${e.message}")
        Toast.makeText(this, "Failed to copy logs: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

    private fun captureScreen() {
        var fullBitmap: Bitmap? = null
        try {
            addLog("captureScreen", "CALLED - Capturing screen with OpenCV frame differencing")
            
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                addLog("captureScreen", "SKIPPED - No image available from imageReader")
                return
            }

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            // Use bitmap pool for memory efficiency
            fullBitmap = bitmapPool.obtain(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // AUTOMATIC BOARD DETECTION on first capture
            if (!boardAutoDetected) {
                addLog("captureScreen", "=== AUTOMATIC BOARD DETECTION ===")
                val detectedConfig = boardDetector.detectBoardAutomatically(fullBitmap)
                
                if (detectedConfig != null) {
                    boardX = detectedConfig.x
                    boardY = detectedConfig.y
                    boardSize = detectedConfig.size
                    isFlipped = !detectedConfig.isWhiteBottom  // Flip if black is on bottom
                    boardAutoDetected = true
                    
                    saveSettings()
                    
                    addLog("captureScreen", "✓ Board auto-detected successfully!")
                    addLog("captureScreen", "  Position: X=$boardX, Y=$boardY, Size=$boardSize")
                    addLog("captureScreen", "  Orientation: ${if (detectedConfig.isWhiteBottom) "White bottom" else "Black bottom"}")
                    updateStatus("✓ Board Detected")
                } else {
                    addLog("captureScreen", "⚠ Auto-detection failed, using defaults")
                    updateStatus("⚠ Using defaults")
                }
            }

            val boardBitmap = extractBoardArea(fullBitmap)
            bitmapPool.recycle(fullBitmap)
            
            // NEW: Convert bitmap to OpenCV Mat for frame differencing
            addLog("captureScreen", "Converting to OpenCV Mat...")
            val currentMat = boardDetector.bitmapToMat(boardBitmap)
            
            // Store previousMat in local variable to avoid smart cast issues
            val prevMat = previousMat
            if (prevMat != null) {
                addLog("captureScreen", "Performing IMPROVED square-by-square detection...")
                
                // IMPROVED: Detect moves using square-by-square analysis
                val detectedMoves = boardDetector.detectMovesSquareBySquare(
                    prevMat,
                    currentMat,
                    boardBitmap.width,
                    boardBitmap.height,
                    isFlipped
                )
                
                if (detectedMoves.isNotEmpty()) {
                    addLog("captureScreen", "Detected ${detectedMoves.size} move(s): $detectedMoves")
                    
                    // Process the first detected move
                    // (In a real game, both white and black moves might be detected)
                    val move = detectedMoves[0]
                    addLog("captureScreen", "MOVE DETECTED: $move - Sending to engine")
                    onMoveDetected(move)
                } else {
                    addLog("captureScreen", "No moves detected from square analysis")
                }
            } else {
                addLog("captureScreen", "FIRST CAPTURE - Establishing baseline frame")
            }
            
            // Clean up previous Mat and store current
            previousMat?.release()
            previousMat = currentMat
            
            boardBitmap.recycle()
            addLog("captureScreen", "Frame saved for next comparison")
        } catch (e: Exception) {
            addLog("captureScreen", "ERROR - ${e.message}")
            e.printStackTrace()
            bitmapPool.recycle(fullBitmap)
        }
    }

    private fun extractBoardArea(fullBitmap: Bitmap): Bitmap {
        return try {
            val extracted = Bitmap.createBitmap(fullBitmap, boardX, boardY, boardSize, boardSize)
            extracted
        } catch (e: Exception) {
            addLog("extractBoardArea", "ERROR - ${e.message}")
            fullBitmap
        }
    }

    private fun onMoveDetected(move: String) {
        addLog("onMoveDetected", "OPPONENT MOVE DETECTED: $move")
        updateStatus("♟ Move: $move")
        
        // STOP detection immediately after detecting move
        addLog("onMoveDetected", "Stopping detection until next auto-play execution")
        stopDetection()
        
        // Send to Stockfish engine via Flask API
        if (ngrokUrl.isNotEmpty()) {
            addLog("onMoveDetected", "Sending to engine...")
            sendMoveToEngine(move)
        } else {
            addLog("onMoveDetected", "SKIPPED - No server URL")
        }
    }

    private fun sendMoveToEngine(move: String) {
        addLog("sendMoveToEngine", "CALLED - Move: $move")
        
        if (ngrokUrl.isEmpty()) {
            addLog("sendMoveToEngine", "FAILED - No ngrok URL")
            return
        }

        Thread {
            try {
                // Send as plain text body (matching Flask API)
                val requestBody = move.toRequestBody(null)
                
                val request = Request.Builder()
                    .url("$ngrokUrl/move")
                    .post(requestBody)
                    .build()

                addLog("HTTP", "POST $ngrokUrl/move body=$move")
                
                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        addLog("sendMoveToEngine", "FAILED - ${e.message}")
                        handler.post {
                            updateStatus("⚠️ Connection failed")
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string() ?: ""
                        addLog("HTTP", "Response ${response.code}: $responseBody")
                        
                        if (response.isSuccessful && responseBody.isNotEmpty()) {
                            addLog("sendMoveToEngine", "Engine response: $responseBody")
                            handler.post {
                                processEngineResponse(responseBody)
                            }
                        } else {
                            addLog("sendMoveToEngine", "FAILED - Server error ${response.code}")
                            handler.post {
                                updateStatus("⚠️ Engine error")
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                addLog("sendMoveToEngine", "EXCEPTION - ${e.message}")
            }
        }.start()
    }

    private fun processEngineResponse(response: String) {
        addLog("processEngineResponse", "CALLED - Response: $response")
        
        try {
            // Flask API returns plain text like "e2e4" or "Checkmate, I win!"
            // Extract move if present (format: a-h, 1-8, a-h, 1-8, optional promotion)
            val movePattern = "[a-h][1-8][a-h][1-8][qrbn]?".toRegex()
            val bestMove = movePattern.find(response)?.value
            
            if (bestMove != null) {
                addLog("processEngineResponse", "Best move extracted: $bestMove")
                
                if (isAutoPlayEnabled) {
                    addLog("processEngineResponse", "Auto-play enabled - executing move")
                    executeMoveAutomatically(bestMove)
                } else {
                    updateStatus("💡 Best: $bestMove")
                    addLog("processEngineResponse", "Suggestion: $bestMove (auto-play OFF)")
                }
            } else {
                addLog("processEngineResponse", "No move found in response")
                updateStatus("ℹ️ $response")
            }
        } catch (e: Exception) {
            addLog("processEngineResponse", "ERROR - ${e.message}")
        }
    }

    private fun executeMoveAutomatically(move: String) {
        addLog("executeMoveAutomatically", "╔═══════════════════════════════════════════════════╗")
        addLog("executeMoveAutomatically", "║         AUTOMATIC MOVE EXECUTION STARTED          ║")
        addLog("executeMoveAutomatically", "╠═══════════════════════════════════════════════════╣")
        addLog("executeMoveAutomatically", "Move (UCI notation): $move")
        
        if (move.length < 4) {
            addLog("executeMoveAutomatically", "FAILED - Invalid format (length=${move.length})")
            addLog("executeMoveAutomatically", "╚═══════════════════════════════════════════════════╝")
            updateStatus("⚠️ Invalid move")
            return
        }

        val fromSquare = move.substring(0, 2)
        val toSquare = move.substring(2, 4)
        val orientation = if (isFlipped) "BLACK" else "WHITE"

        addLog("executeMoveAutomatically", "─────────────────────────────────────────────────────")
        addLog("executeMoveAutomatically", "MOVE BREAKDOWN:")
        addLog("executeMoveAutomatically", "  • From Square: $fromSquare")
        addLog("executeMoveAutomatically", "  • To Square: $toSquare")
        addLog("executeMoveAutomatically", "  • Orientation: $orientation pieces on bottom")
        addLog("executeMoveAutomatically", "─────────────────────────────────────────────────────")
        addLog("executeMoveAutomatically", "BOARD CONFIGURATION:")
        addLog("executeMoveAutomatically", "  • Board X: $boardX pixels (left edge)")
        addLog("executeMoveAutomatically", "  • Board Y: $boardY pixels (top edge)")
        addLog("executeMoveAutomatically", "  • Board Size: $boardSize pixels (width × height)")
        addLog("executeMoveAutomatically", "  • Square Size: ${boardSize / 8} pixels")
        addLog("executeMoveAutomatically", "  • Flipped: $isFlipped")
        addLog("executeMoveAutomatically", "─────────────────────────────────────────────────────")

        // Calculate coordinates with comprehensive logging (getSquareCoordinates logs internally)
        addLog("executeMoveAutomatically", "CALCULATING PIXEL COORDINATES:")
        addLog("executeMoveAutomatically", "")
        addLog("executeMoveAutomatically", "FROM SQUARE ($fromSquare):")
        val fromCoords = touchSimulator.getSquareCoordinates(fromSquare, boardX, boardY, boardSize, isFlipped)
        
        addLog("executeMoveAutomatically", "")
        addLog("executeMoveAutomatically", "TO SQUARE ($toSquare):")
        val toCoords = touchSimulator.getSquareCoordinates(toSquare, boardX, boardY, boardSize, isFlipped)

        addLog("executeMoveAutomatically", "")
        addLog("executeMoveAutomatically", "─────────────────────────────────────────────────────")
        addLog("executeMoveAutomatically", "PIXEL TAP PLAN:")
        addLog("executeMoveAutomatically", "  • First Tap:  Pixel (${fromCoords.first.toInt()}, ${fromCoords.second.toInt()}) ← $fromSquare")
        addLog("executeMoveAutomatically", "  • Second Tap: Pixel (${toCoords.first.toInt()}, ${toCoords.second.toInt()}) ← $toSquare")
        addLog("executeMoveAutomatically", "  • Method: TAP-TAP (700ms press duration each)")
        addLog("executeMoveAutomatically", "  • Delay: 600ms between taps")
        addLog("executeMoveAutomatically", "─────────────────────────────────────────────────────")

        updateStatus("🤖 Playing: $move")

        // Check if accessibility service is enabled using proper Android API
        if (!isAccessibilityServiceEnabled()) {
            addLog("executeMoveAutomatically", "⚠️ FAILED - Accessibility service NOT enabled!")
            addLog("executeMoveAutomatically", "→ Go to Settings > Accessibility > Chess Automation")
            addLog("executeMoveAutomatically", "╚═══════════════════════════════════════════════════╝")
            updateStatus("⚠️ Enable accessibility")
            Toast.makeText(this, "Enable accessibility service first!", Toast.LENGTH_LONG).show()
            return
        }

        addLog("executeMoveAutomatically", "✓ Accessibility service is ENABLED")
        addLog("executeMoveAutomatically", "")
        addLog("executeMoveAutomatically", "═══════════════════════════════════════════════════")
        addLog("executeMoveAutomatically", "EXECUTING MOVE: $fromSquare → $toSquare")
        addLog("executeMoveAutomatically", "═══════════════════════════════════════════════════")
        addLog("executeMoveAutomatically", "")
        addLog("executeMoveAutomatically", "▶ STEP 1: First tap at $fromSquare")
        addLog("executeMoveAutomatically", "  Tapping pixel (${fromCoords.first.toInt()}, ${fromCoords.second.toInt()}) for 700ms...")
        
        // First tap: longer 700ms press for better detection
        val firstTapSuccess = touchSimulator.simulateTouch(
            fromCoords.first, fromCoords.second, 700
        )
        
        if (!firstTapSuccess) {
            addLog("executeMoveAutomatically", "✗ STEP 1 FAILED - First tap unsuccessful")
            addLog("executeMoveAutomatically", "  Square: $fromSquare")
            addLog("executeMoveAutomatically", "  Pixel: (${fromCoords.first.toInt()}, ${fromCoords.second.toInt()})")
            addLog("executeMoveAutomatically", "╚═══════════════════════════════════════════════════╝")
            updateStatus("⚠️ First tap failed")
            Toast.makeText(this, "First tap failed - check accessibility service", Toast.LENGTH_LONG).show()
            return
        }
        
        addLog("executeMoveAutomatically", "✓ STEP 1 SUCCESS - First tap completed")
        addLog("executeMoveAutomatically", "  Tapped: $fromSquare at pixel (${fromCoords.first.toInt()}, ${fromCoords.second.toInt()})")
        addLog("executeMoveAutomatically", "")
        addLog("executeMoveAutomatically", "⏱ Waiting 600ms for piece selection...")
        
        // LONGER delay between taps (600ms) to ensure chess app registers the selection
        // This gives the chess app UI time to show the piece is selected
        handler.postDelayed({
            addLog("executeMoveAutomatically", "")
            addLog("executeMoveAutomatically", "▶ STEP 2: Second tap at $toSquare")
            addLog("executeMoveAutomatically", "  Tapping pixel (${toCoords.first.toInt()}, ${toCoords.second.toInt()}) for 700ms...")
            
            // Second tap: longer 700ms press for better detection
            val secondTapSuccess = touchSimulator.simulateTouch(
                toCoords.first, toCoords.second, 700
            )
            
            if (secondTapSuccess) {
                addLog("executeMoveAutomatically", "✓ STEP 2 SUCCESS - Second tap completed")
                addLog("executeMoveAutomatically", "  Tapped: $toSquare at pixel (${toCoords.first.toInt()}, ${toCoords.second.toInt()})")
                addLog("executeMoveAutomatically", "")
                addLog("executeMoveAutomatically", "═══════════════════════════════════════════════════")
                addLog("executeMoveAutomatically", "✓✓✓ MOVE EXECUTION COMPLETE ✓✓✓")
                addLog("executeMoveAutomatically", "Move: $fromSquare → $toSquare ($move)")
                addLog("executeMoveAutomatically", "Pixels tapped:")
                addLog("executeMoveAutomatically", "  1st: (${fromCoords.first.toInt()}, ${fromCoords.second.toInt()})")
                addLog("executeMoveAutomatically", "  2nd: (${toCoords.first.toInt()}, ${toCoords.second.toInt()})")
                addLog("executeMoveAutomatically", "═══════════════════════════════════════════════════")
                addLog("executeMoveAutomatically", "╚═══════════════════════════════════════════════════╝")
                updateStatus("✅ Played: $move")
                
                // Wait for move animation to complete before resuming detection
                handler.postDelayed({
                    addLog("executeMoveAutomatically", "Resuming detection after move animation")
                    resumeDetectionImmediately()
                }, 400)
            } else {
                addLog("executeMoveAutomatically", "✗ STEP 2 FAILED - Second tap unsuccessful")
                addLog("executeMoveAutomatically", "  Square: $toSquare")
                addLog("executeMoveAutomatically", "  Pixel: (${toCoords.first.toInt()}, ${toCoords.second.toInt()})")
                addLog("executeMoveAutomatically", "")
                addLog("executeMoveAutomatically", "Possible causes:")
                addLog("executeMoveAutomatically", "  1. Accessibility service disconnected")
                addLog("executeMoveAutomatically", "  2. Gesture queue full")
                addLog("executeMoveAutomatically", "  3. Invalid coordinates")
                addLog("executeMoveAutomatically", "  4. Chess app not responding")
                addLog("executeMoveAutomatically", "╚═══════════════════════════════════════════════════╝")
                updateStatus("⚠️ Second tap failed")
                Toast.makeText(this, "Second tap failed - check accessibility service", Toast.LENGTH_LONG).show()
            }
        }, 600)
    }
    
    /**
     * Temporarily pause detection to avoid detecting our own automated move
     * Clears previous bitmap so detection starts fresh when resumed
     * ALWAYS resumes detection after delay (auto-starts if not running)
     */
    private fun pauseDetectionTemporarily(delayMs: Long) {
        addLog("pauseDetectionTemporarily", "=== PAUSING DETECTION ===")
        addLog("pauseDetectionTemporarily", "Duration: ${delayMs}ms")
        
        // Stop detection if it was running
        if (isDetecting) {
            isDetecting = false
            handler.removeCallbacks(detectionRunnable)
            addLog("pauseDetectionTemporarily", "Detection was active - paused")
        } else {
            addLog("pauseDetectionTemporarily", "Detection was not active - will start after pause")
        }
        
        // Clear previous frame so detection starts fresh after our automated move
        addLog("pauseDetectionTemporarily", "Clearing previous frame to reset comparison")
        previousMat?.release()
        previousMat = null
        addLog("pauseDetectionTemporarily", "Waiting for opponent to make their move...")
        
        // ALWAYS resume detection after delay (even if it wasn't running before)
        handler.postDelayed({
            addLog("pauseDetectionTemporarily", "=== RESUMING DETECTION ===")
            addLog("pauseDetectionTemporarily", "Next capture will establish baseline")
            addLog("pauseDetectionTemporarily", "Following capture will detect opponent move")
            isDetecting = true
            updateStatus("🔍 Detecting...")
            handler.post(detectionRunnable)
        }, delayMs)
    }

    /**
     * Properly check if accessibility service is enabled using Android API
     * This is more reliable than checking the instance variable
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        // Use ComponentName to get the properly formatted service name
        val componentName = ComponentName(this, ChessAccessibilityService::class.java)
        val service = componentName.flattenToString()
        
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val isEnabled = enabledServices?.contains(service) == true
        
        addLog("isAccessibilityServiceEnabled", "Checking service: $service")
        addLog("isAccessibilityServiceEnabled", "Enabled services: $enabledServices")
        addLog("isAccessibilityServiceEnabled", "Result: $isEnabled")
        
        return isEnabled
    }

    private fun updateStatus(message: String) {
        handler.post {
            statusTextView?.text = message
        }
    }
    
    private fun addLog(function: String, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val logLine = "[$timestamp] $function: $message\n"
        
        // Log to LogCat
        Log.d(TAG, "$function: $message")
        
        // Add to buffer
        logBuffer.append(logLine)
        
        // Keep only last 100 lines
        val lines = logBuffer.lines()
        if (lines.size > 100) {
            logBuffer.clear()
            logBuffer.append(lines.takeLast(100).joinToString("\n"))
        }
        
        // Update UI
        handler.post {
            liveLogTextView?.text = logBuffer.toString()
            // Auto-scroll to bottom
            liveLogTextView?.let { textView ->
                val scrollAmount = textView.layout?.getLineTop(textView.lineCount) ?: 0
                if (scrollAmount > textView.height) {
                    textView.scrollTo(0, scrollAmount - textView.height)
                }
            }
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Chess Automation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Chess move detection and automation service"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Chess Automation Active")
            .setContentText("Tap the robot icon to control")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        addLog("onDestroy", "Service shutting down")
        stopDetection()
        compactOverlay?.let { windowManager?.removeView(it) }
        expandedOverlay?.let { windowManager?.removeView(it) }
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        bitmapPool.clear()
        bitmapPool.recycle(previousBitmap)
        previousMat?.release()
        previousMat = null
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        addLog("onDestroy", "Cleanup complete")
    }
}
