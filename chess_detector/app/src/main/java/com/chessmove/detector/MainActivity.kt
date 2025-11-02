package com.chessmove.detector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
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
    private var bitmap1: Bitmap? = null
    private var bitmap2: Bitmap? = null
    private var selectingImageNumber = 1
    
    companion object {
        private const val TAG = "ChessMoveDetector"
    }
    
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            loadImageFromUri(it, selectingImageNumber)
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
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
        
        binding.selectImage1Button.setOnClickListener {
            selectingImageNumber = 1
            checkPermissionAndPickImage()
        }
        
        binding.selectImage2Button.setOnClickListener {
            selectingImageNumber = 2
            checkPermissionAndPickImage()
        }
        
        binding.detectButton.setOnClickListener {
            detectMove()
        }
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
    
    private fun loadImageFromUri(uri: Uri, imageNumber: Int) {
        try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            
            if (imageNumber == 1) {
                bitmap1 = bitmap
                binding.imageView1.setImageBitmap(bitmap)
                binding.placeholder1.visibility = View.GONE
            } else {
                bitmap2 = bitmap
                binding.imageView2.setImageBitmap(bitmap)
                binding.placeholder2.visibility = View.GONE
            }
            
            Toast.makeText(this, "Image $imageNumber loaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image", e)
            Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun detectMove() {
        val img1 = bitmap1
        val img2 = bitmap2
        
        if (img1 == null || img2 == null) {
            Toast.makeText(this, getString(R.string.no_image_selected), Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.detectButton.isEnabled = false
        binding.resultText.text = getString(R.string.processing)
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state1 = getBoardStateFromBitmap(img1, "First Board")
                val state2 = getBoardStateFromBitmap(img2, "Second Board")
                
                val result = if (state1 != null && state2 != null) {
                    val moves = detectUciMoves(state1, state2)
                    
                    buildString {
                        appendLine("♟️ CHESS MOVE DETECTOR ♟️")
                        appendLine()
                        appendLine("Sensitivity: White=$WHITE_DETECTION_SENSITIVITY, Black=$BLACK_DETECTION_SENSITIVITY, Empty=$EMPTY_DETECTION_SENSITIVITY")
                        appendLine()
                        appendLine("--- First Board ---")
                        appendLine("✅ ${state1.white.size} white, ${state1.black.size} black pieces")
                        appendLine("White pieces: ${state1.white.sorted()}")
                        appendLine("Black pieces: ${state1.black.sorted()}")
                        appendLine()
                        appendLine("--- Second Board ---")
                        appendLine("✅ ${state2.white.size} white, ${state2.black.size} black pieces")
                        appendLine("White pieces: ${state2.white.sorted()}")
                        appendLine("Black pieces: ${state2.black.sorted()}")
                        appendLine()
                        appendLine("--- MOVE DETECTION RESULTS ---")
                        if (moves.isNotEmpty()) {
                            appendLine("🎯 DETECTED MOVES:")
                            moves.forEach { move ->
                                appendLine("   $move")
                            }
                        } else {
                            appendLine("❌ No clear moves detected")
                            val white1 = state1.white
                            val black1 = state1.black
                            val white2 = state2.white
                            val black2 = state2.black
                            val whiteMoved = white1 - white2
                            val blackMoved = black1 - black2
                            val whiteAppeared = white2 - white1
                            val blackAppeared = black2 - black1
                            appendLine("   White changes: ${whiteMoved.sorted()} -> ${whiteAppeared.sorted()}")
                            appendLine("   Black changes: ${blackMoved.sorted()} -> ${blackAppeared.sorted()}")
                        }
                    }
                } else {
                    "❌ Failed to process one or both boards.\n" +
                    "Please ensure the images contain clear chessboards."
                }
                
                withContext(Dispatchers.Main) {
                    binding.resultText.text = result
                    binding.detectButton.isEnabled = true
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error detecting move", e)
                withContext(Dispatchers.Main) {
                    binding.resultText.text = "Error: ${e.message}"
                    binding.detectButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Detection failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
