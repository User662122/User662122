package com.chessmove.detector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.chessmove.detector.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.InputStream

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var inputBitmap: Bitmap? = null
    private var autoStartCapture = false
    private var showUrlDialog = false
    private lateinit var backendClient: BackendClient
    
    // ✅ Cache orientation after first detection (like ScreenCaptureService)
    private var cachedOrientation: Boolean? = null
    private var firstDetectionDone = false
    
    companion object {
        private const val TAG = "ChessDetector"
    }
    
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            loadImageFromUri(it)
        }
    }
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openImagePicker()
        } else {
            Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startNotificationService()
        } else {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            Log.d(TAG, "✅ Storage permissions granted")
            Toast.makeText(this, "Storage permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            Log.w(TAG, "⚠️ Some storage permissions denied")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    showManageStoragePermissionDialog()
                }
            }
        }
    }
    
    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            if (data != null) {
                Log.d(TAG, "✅ Screen capture permission granted! Starting service...")
                
                if (!backendClient.hasBackendUrl()) {
                    Toast.makeText(this, "⚠️ Backend URL not set! Use 'Set URL' in notification", Toast.LENGTH_LONG).show()
                }
                
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("resultCode", result.resultCode)
                    putExtra("data", data)
                }
                
                ContextCompat.startForegroundService(this, serviceIntent)
                Toast.makeText(this, "Screen capture starting in 10 seconds...", Toast.LENGTH_SHORT).show()
                
                moveTaskToBack(true)
            }
        } else {
            Log.e(TAG, "❌ Screen capture permission denied")
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        backendClient = BackendClient(this)
        
        if (OpenCVLoader.initDebug()) {
            Log.i(TAG, "OpenCV loaded successfully with initDebug()")
        } else {
            Log.w(TAG, "OpenCV initDebug() failed, trying initLocal()")
            if (OpenCVLoader.initLocal()) {
                Log.i(TAG, "OpenCV loaded successfully with initLocal()")
            } else {
                Log.e(TAG, "OpenCV initialization failed!")
                Toast.makeText(this, "OpenCV initialization failed", Toast.LENGTH_LONG).show()
            }
        }
        
        binding.selectImageButton.setOnClickListener {
            checkPermissionAndPickImage()
        }
        
        binding.detectButton.setOnClickListener {
            detectPieces()
        }
        
        requestStoragePermissions()
        checkNotificationPermissionAndStart()
        
        autoStartCapture = intent.getBooleanExtra("auto_start_capture", false)
        showUrlDialog = intent.getBooleanExtra("show_url_dialog", false)
    }
    
    override fun onResume() {
        super.onResume()
        
        if (showUrlDialog) {
            showUrlDialog = false
            showSetUrlDialog()
        }
        
        if (autoStartCapture) {
            autoStartCapture = false
            Log.d(TAG, "🎬 Auto-starting screen capture from notification")
            requestScreenCapturePermission()
        }
    }
    
    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) 
                != PackageManager.PERMISSION_GRANTED) {
                requestStoragePermissionLauncher.launch(
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showManageStoragePermissionDialog()
            }
        } else {
            val permissions = mutableListOf<String>()
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            
            if (permissions.isNotEmpty()) {
                requestStoragePermissionLauncher.launch(permissions.toTypedArray())
            }
        }
    }
    
    private fun showManageStoragePermissionDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("📁 Storage Permission Needed")
                .setMessage(
                    "To save debug images, this app needs access to storage.\n\n" +
                    "Please enable 'Allow access to manage all files' in the next screen."
                )
                .setPositiveButton("Grant Permission") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }
    
    private fun showSetUrlDialog() {
        val input = EditText(this).apply {
            hint = "Enter ngrok URL (e.g., https://abc123.ngrok.io)"
            setText(backendClient.getBackendUrl() ?: "")
            setPadding(50, 40, 50, 40)
        }
        
        AlertDialog.Builder(this)
            .setTitle("🔗 Set Backend URL")
            .setMessage("Enter your ngrok public URL:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        backendClient.saveBackendUrl(url)
                        Toast.makeText(this, "✅ Backend URL saved!", Toast.LENGTH_SHORT).show()
                        Log.d(TAG, "Backend URL set: $url")
                        showAccessibilityReminder()
                    } else {
                        Toast.makeText(this, "❌ URL must start with http:// or https://", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "URL cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Test") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    testBackendConnection(url)
                } else {
                    Toast.makeText(this, "Enter URL first", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
    
    private fun testBackendConnection(url: String) {
        Toast.makeText(this, "Testing connection...", Toast.LENGTH_SHORT).show()
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val testClient = BackendClient(this@MainActivity)
                testClient.saveBackendUrl(url)
                
                val result = testClient.startGame("white")
                
                withContext(Dispatchers.Main) {
                    result.onSuccess { response ->
                        Toast.makeText(
                            this@MainActivity,
                            "✅ Connection successful! Response: $response",
                            Toast.LENGTH_LONG
                        ).show()
                    }.onFailure { e ->
                        Toast.makeText(
                            this@MainActivity,
                            "❌ Connection failed: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "❌ Test failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun showAccessibilityReminder() {
        AlertDialog.Builder(this)
            .setTitle("⚙️ Enable Accessibility Service")
            .setMessage(
                "To auto-execute moves, you need to enable the Accessibility Service:\n\n" +
                "1. Go to Settings > Accessibility\n" +
                "2. Find 'Chess Detector'\n" +
                "3. Enable it\n\n" +
                "This allows the app to tap on the chess board for you."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't open settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Later", null)
            .show()
    }
    
    private fun checkNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startNotificationService()
                }
                else -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startNotificationService()
        }
    }
    
    private fun startNotificationService() {
        ChessDetectorService.startService(this)
        Log.d(TAG, "Notification service started")
    }
    
    private fun requestScreenCapturePermission() {
        Log.d(TAG, "📱 Requesting screen capture permission...")
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
    
    override fun onDestroy() {
        super.onDestroy()
    }
    
    private fun checkPermissionAndPickImage() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> {
                openImagePicker()
            }
            else -> {
                requestPermissionLauncher.launch(permission)
            }
        }
    }
    
    private fun openImagePicker() {
        pickImageLauncher.launch("image/*")
    }
    
    private fun loadImageFromUri(uri: Uri) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            inputBitmap = bitmap
            binding.inputImageView.setImageBitmap(bitmap)
            binding.placeholderInput.visibility = View.GONE
            binding.resultImageView.setImageBitmap(null)
            binding.placeholderResult.visibility = View.VISIBLE
            binding.resultText.text = getString(R.string.result_title)
            
            // ✅ Reset orientation cache when new image loaded
            cachedOrientation = null
            firstDetectionDone = false
            
            Toast.makeText(this, "Image loaded successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image", e)
            Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * ✅ UNIFIED DETECTION - Same method as ScreenCaptureService!
     * Uses getBoardStateFromBitmapDirectly() for consistent results
     */
    private fun detectPieces() {
        val img = inputBitmap
        
        if (img == null) {
            Toast.makeText(this, getString(R.string.no_image_selected), Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.detectButton.isEnabled = false
        binding.resultText.text = getString(R.string.processing)
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ✅ FIRST DETECTION: Get orientation (like ScreenCaptureService does)
                if (!firstDetectionDone) {
                    Log.d(TAG, "🔍 First detection - getting orientation...")
                    val initialState = getBoardStateFromBitmap(img, "Chess Board", this@MainActivity)
                    
                    if (initialState != null) {
                        cachedOrientation = initialState.whiteOnBottom
                        firstDetectionDone = true
                        
                        Log.d(TAG, "✅ Orientation cached: ${if (cachedOrientation == true) "White" else "Black"} on bottom")
                        
                        // Display first detection result
                        displayResult(initialState, true)
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.resultText.text = "❌ Failed to process board.\nPlease ensure the image contains a clear chessboard."
                            binding.detectButton.isEnabled = true
                        }
                    }
                } else {
                    // ✅ SUBSEQUENT DETECTIONS: Use same method as ScreenCaptureService!
                    Log.d(TAG, "🚀 Using UNIFIED detection (same as live capture)...")
                    
                    val boardState = getBoardStateFromBitmapDirectly(
                        img,
                        "Chess Board",
                        cachedOrientation!!,
                        this@MainActivity,
                        saveDebugImage = false,
                        debugImageCounter = 0
                    )
                    
                    if (boardState != null) {
                        displayResult(boardState, false)
                    } else {
                        withContext(Dispatchers.Main) {
                            binding.resultText.text = "❌ Detection failed"
                            binding.detectButton.isEnabled = true
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error detecting pieces", e)
                withContext(Dispatchers.Main) {
                    binding.resultText.text = "Error: ${e.message}"
                    binding.detectButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Detection failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Display detection results
     */
    private suspend fun displayResult(boardState: BoardState, isFirstDetection: Boolean) {
        val resultText = buildString {
            appendLine("♟️ UNIFIED CHESS DETECTOR ♟️")
            appendLine("(${if (isFirstDetection) "FIRST" else "LIVE"} DETECTION - NEW 3-CLASS MODEL)")
            appendLine()
            if (isFirstDetection) {
                appendLine("Orientation: ${if (cachedOrientation == true) "White" else "Black"} on bottom")
                appendLine()
            }
            appendLine("Detection Settings:")
            appendLine("  Model: 3-class (Black/White/Empty)")
            appendLine("  Input Size: 96x96")
            appendLine("  Method: Extract 64 squares → TFLite classify")
            appendLine()
            appendLine("--- DETECTED PIECES ---")
            appendLine()
            appendLine("✅ White Pieces: ${boardState.white.size}")
            appendLine("Positions: ${boardState.white.sorted()}")
            appendLine()
            appendLine("✅ Black Pieces: ${boardState.black.size}")
            appendLine("Positions: ${boardState.black.sorted()}")
            appendLine()
            if (boardState.empty.isNotEmpty()) {
                appendLine("⬜ Empty Squares: ${boardState.empty.size}")
                appendLine("Positions: ${boardState.empty.sorted()}")
                appendLine()
            }
            appendLine("Total Pieces: ${boardState.white.size + boardState.black.size}")
            appendLine()
            if (isFirstDetection) {
                appendLine("📷 Annotated image displayed below")
                appendLine("   White squares = White pieces")
                appendLine("   Black squares = Black pieces")
                appendLine("   Gray squares = Empty squares")
            } else {
                appendLine("🎯 Using SAME method as live capture!")
                appendLine("   (Notification counts = App counts)")
            }
        }
        
        withContext(Dispatchers.Main) {
            binding.resultText.text = resultText
            
            // Only show annotated image on first detection
            if (isFirstDetection) {
                boardState.annotatedBoard?.let { annotatedBitmap ->
                    binding.resultImageView.setImageBitmap(annotatedBitmap)
                    binding.placeholderResult.visibility = View.GONE
                }
            }
            
            binding.detectButton.isEnabled = true
            Toast.makeText(this@MainActivity, "Detection complete!", Toast.LENGTH_SHORT).show()
        }
    }
}