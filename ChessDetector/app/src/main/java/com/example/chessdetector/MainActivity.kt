package com.example.chessdetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var selectImage1Button: Button
    private lateinit var selectImage2Button: Button
    private lateinit var detectMovesButton: Button
    private lateinit var resultTextView: TextView
    
    private var image1Path: String? = null
    private var image2Path: String? = null
    
    private val PERMISSION_REQUEST_CODE = 100

    private val pickImage1Launcher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                image1Path = copyUriToTempFile(it, "image1.jpg")
                if (image1Path != null) {
                    selectImage1Button.text = "Image 1: Selected ✓"
                    selectImage1Button.setBackgroundColor(getColor(android.R.color.holo_green_light))
                    checkIfBothImagesSelected()
                    Log.d("MainActivity", "Image 1 saved to: $image1Path")
                } else {
                    Toast.makeText(this, "Failed to load Image 1", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading image 1", e)
                Toast.makeText(this, "Error loading Image 1: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickImage2Launcher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                image2Path = copyUriToTempFile(it, "image2.jpg")
                if (image2Path != null) {
                    selectImage2Button.text = "Image 2: Selected ✓"
                    selectImage2Button.setBackgroundColor(getColor(android.R.color.holo_green_light))
                    checkIfBothImagesSelected()
                    Log.d("MainActivity", "Image 2 saved to: $image2Path")
                } else {
                    Toast.makeText(this, "Failed to load Image 2", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading image 2", e)
                Toast.makeText(this, "Error loading Image 2: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show()
            Log.e("MainActivity", "OpenCV initialization failed!")
            return
        }
        Log.d("MainActivity", "OpenCV initialized successfully")

        initializeViews()
        checkPermissions()
        setupClickListeners()
    }

    private fun initializeViews() {
        selectImage1Button = findViewById(R.id.selectImage1Button)
        selectImage2Button = findViewById(R.id.selectImage2Button)
        detectMovesButton = findViewById(R.id.detectMovesButton)
        resultTextView = findViewById(R.id.resultTextView)
        
        detectMovesButton.isEnabled = false
    }

    private fun setupClickListeners() {
        selectImage1Button.setOnClickListener {
            pickImage1Launcher.launch("image/*")
        }

        selectImage2Button.setOnClickListener {
            pickImage2Launcher.launch("image/*")
        }

        detectMovesButton.setOnClickListener {
            detectChessMoves()
        }
    }

    private fun checkIfBothImagesSelected() {
        detectMovesButton.isEnabled = image1Path != null && image2Path != null
    }

    private fun copyUriToTempFile(uri: Uri, filename: String): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val tempFile = File(cacheDir, filename)
            
            FileOutputStream(tempFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            inputStream.close()
            
            val path = tempFile.absolutePath
            Log.d("MainActivity", "Image copied to: $path, size: ${tempFile.length()} bytes")
            path
        } catch (e: Exception) {
            Log.e("MainActivity", "Error copying URI to file", e)
            null
        }
    }

    private fun detectChessMoves() {
        val path1 = image1Path
        val path2 = image2Path

        if (path1 == null || path2 == null) {
            Toast.makeText(this, "Please select both images", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("MainActivity", "Starting chess move detection")
        Log.d("MainActivity", "Image 1: $path1")
        Log.d("MainActivity", "Image 2: $path2")

        resultTextView.text = "Processing images...\nThis may take a few seconds."
        detectMovesButton.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val moves = ChessMoveDetector.compareBoardsAndDetectMoves(path1, path2)
                
                withContext(Dispatchers.Main) {
                    if (moves.isNotEmpty()) {
                        val formattedMoves = moves.joinToString("\n") { move ->
                            when {
                                move.startsWith("White moved") -> {
                                    val moveNotation = move.substringAfter("White moved ")
                                    "White moved: $moveNotation"
                                }
                                move.startsWith("Black moved") -> {
                                    val moveNotation = move.substringAfter("Black moved ")
                                    "Black moved: $moveNotation"
                                }
                                move.startsWith("White captured") -> {
                                    val moveNotation = move.substringAfter("White captured ")
                                    "White captured: $moveNotation"
                                }
                                move.startsWith("Black captured") -> {
                                    val moveNotation = move.substringAfter("Black captured ")
                                    "Black captured: $moveNotation"
                                }
                                else -> move
                            }
                        }
                        resultTextView.text = "✓ Detected Moves:\n\n$formattedMoves"
                        Toast.makeText(this@MainActivity, "Detection successful!", Toast.LENGTH_SHORT).show()
                    } else {
                        resultTextView.text = "No clear moves detected.\n\nPossible reasons:\n" +
                                "• Board not clearly visible\n" +
                                "• Multiple pieces moved\n" +
                                "• Pieces not detected correctly\n" +
                                "• Images too similar or different\n\n" +
                                "Try using clear, well-lit photos of the chessboard."
                        Toast.makeText(this@MainActivity, "No moves detected", Toast.LENGTH_SHORT).show()
                    }
                    detectMovesButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Detection error", e)
                withContext(Dispatchers.Main) {
                    resultTextView.text = "Error during detection:\n${e.message}\n\n" +
                            "Please check:\n" +
                            "• Images are valid chess board photos\n" +
                            "• Images loaded correctly\n" +
                            "• Sufficient storage space"
                    detectMovesButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Detection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }

        val permissionsNeeded = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(
                    this,
                    "Storage permissions are required to select images",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
