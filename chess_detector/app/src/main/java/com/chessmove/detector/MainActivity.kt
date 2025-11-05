package com.chessmove.detector

import android.Manifest
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
    private var inputBitmap: Bitmap? = null
    
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
        
        binding.selectImageButton.setOnClickListener {
            checkPermissionAndPickImage()
        }
        
        binding.detectButton.setOnClickListener {
            detectPieces()
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
            
            Toast.makeText(this, "Image loaded successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading image", e)
            Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()
        }
    }
    
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
                val boardState = getBoardStateFromBitmap(img, "Chess Board")
                
                if (boardState != null) {
                    val resultText = buildString {
                        appendLine("♟️ CHESS PIECE DETECTOR ♟️")
                        appendLine()
                        appendLine("Sensitivity Settings:")
                        appendLine("  White=$WHITE_DETECTION_SENSITIVITY")
                        appendLine("  Black=$BLACK_DETECTION_SENSITIVITY")
                        appendLine("  Empty=$EMPTY_DETECTION_SENSITIVITY")
                        appendLine()
                        appendLine("--- DETECTED PIECES ---")
                        appendLine()
                        appendLine("✅ White Pieces: ${boardState.white.size}")
                        appendLine("Positions: ${boardState.white.sorted()}")
                        appendLine()
                        appendLine("✅ Black Pieces: ${boardState.black.size}")
                        appendLine("Positions: ${boardState.black.sorted()}")
                        appendLine()
                        appendLine("Total Pieces: ${boardState.white.size + boardState.black.size}")
                        appendLine()
                        appendLine("📷 Annotated image displayed below")
                        appendLine("   White squares = White pieces")
                        appendLine("   Black squares = Black pieces")
                    }
                    
                    withContext(Dispatchers.Main) {
                        binding.resultText.text = resultText
                        
                        // Display annotated image
                        boardState.annotatedBoard?.let { annotatedBitmap ->
                            binding.resultImageView.setImageBitmap(annotatedBitmap)
                            binding.placeholderResult.visibility = View.GONE
                        }
                        
                        binding.detectButton.isEnabled = true
                        Toast.makeText(this@MainActivity, "Detection complete!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        binding.resultText.text = "❌ Failed to process board.\nPlease ensure the image contains a clear chessboard."
                        binding.detectButton.isEnabled = true
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
}
