package com.example.chessdetector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
            image1Path = getRealPathFromURI(it)
            selectImage1Button.text = "Image 1: Selected ✓"
            selectImage1Button.setBackgroundColor(getColor(android.R.color.holo_green_light))
            checkIfBothImagesSelected()
        }
    }

    private val pickImage2Launcher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            image2Path = getRealPathFromURI(it)
            selectImage2Button.text = "Image 2: Selected ✓"
            selectImage2Button.setBackgroundColor(getColor(android.R.color.holo_green_light))
            checkIfBothImagesSelected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "OpenCV initialization failed!", Toast.LENGTH_LONG).show()
            return
        }

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

    private fun detectChessMoves() {
        val path1 = image1Path
        val path2 = image2Path

        if (path1 == null || path2 == null) {
            Toast.makeText(this, "Please select both images", Toast.LENGTH_SHORT).show()
            return
        }

        resultTextView.text = "Processing images..."
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
                        resultTextView.text = "Detected Moves:\n\n$formattedMoves"
                    } else {
                        resultTextView.text = "No clear moves detected.\nPlease ensure images show chess boards with clear piece positions."
                    }
                    detectMovesButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    resultTextView.text = "Error: ${e.message}"
                    detectMovesButton.isEnabled = true
                    Toast.makeText(this@MainActivity, "Detection failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        var result: String? = null
        val cursor = contentResolver.query(uri, null, null, null, null)
        
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                if (columnIndex != -1) {
                    result = cursor.getString(columnIndex)
                }
            }
            cursor.close()
        }
        
        if (result == null) {
            result = uri.path
        }
        
        return result
    }

    private fun checkPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )

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
