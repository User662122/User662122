package com.chessmove.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite classifier for chess piece colors
 * Model input: 150x150 RGB image (SINGLE image, not batched)
 * Model output: Single float [0-1], >0.5 = white, <0.5 = black
 */
class PieceColorClassifier(context: Context) {
    
    companion object {
        const val TAG = "PieceColorClassifier"
        const val MODEL_PATH = "white_black_classifier.tflite"
        const val INPUT_SIZE = 150
        private const val PIXEL_SIZE = 3 // RGB
        private const val IMAGE_MEAN = 0f
        private const val IMAGE_STD = 255f
    }
    
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // ✅ FIXED: Buffer for SINGLE image only (not batch)
    private val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * 4)
        .apply { order(ByteOrder.nativeOrder()) }
    
    // ✅ FIXED: Output for SINGLE prediction
    private val outputBuffer = Array(1) { FloatArray(1) }
    
    init {
        try {
            val model = loadModelFile(context)
            
            // Try GPU acceleration first
            val compatList = CompatibilityList()
            val options = Interpreter.Options().apply {
                if (compatList.isDelegateSupportedOnThisDevice) {
                    val delegateOptions = compatList.bestOptionsForThisDevice
                    gpuDelegate = GpuDelegate(delegateOptions)
                    addDelegate(gpuDelegate)
                    Log.d(TAG, "✅ GPU delegate enabled")
                } else {
                    setNumThreads(4)
                    Log.d(TAG, "⚠️ GPU not supported, using CPU with 4 threads")
                }
            }
            
            interpreter = Interpreter(model, options)
            Log.d(TAG, "✅ TFLite model loaded: $MODEL_PATH")
            Log.d(TAG, "   Input shape: ${interpreter?.getInputTensor(0)?.shape()?.contentToString()}")
            Log.d(TAG, "   Output shape: ${interpreter?.getOutputTensor(0)?.shape()?.contentToString()}")
            Log.d(TAG, "   Expected input bytes: ${INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * 4}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load TFLite model", e)
        }
    }
    
    private fun loadModelFile(context: Context): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(MODEL_PATH)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    /**
     * ✅ FIXED: Process squares one at a time to avoid buffer issues
     * This is more reliable and the performance difference is negligible
     * for 32-64 squares per frame (30-60ms total vs 20-40ms with batching)
     */
    fun classifyBatch(squares: List<Bitmap>): List<String> {
        if (interpreter == null) {
            Log.e(TAG, "❌ Interpreter not initialized")
            return List(squares.size) { "ambiguous" }
        }
        
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<String>()
        
        // Process each square individually
        for (bitmap in squares) {
            val color = classifySingle(bitmap)
            results.add(color)
        }
        
        val elapsedTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "🤖 TFLite classified ${results.size} pieces in ${elapsedTime}ms " +
                "(${if (results.isNotEmpty()) elapsedTime.toFloat() / results.size else 0f}ms/piece)")
        
        return results
    }
    
    /**
     * Classify a single square image
     */
    fun classifySingle(squareBitmap: Bitmap): String {
        if (interpreter == null) {
            return "ambiguous"
        }
        
        try {
            // Convert bitmap to buffer
            inputBuffer.rewind()
            convertBitmapToByteBuffer(squareBitmap)
            
            // Run inference
            inputBuffer.rewind()
            interpreter?.run(inputBuffer, outputBuffer)
            
            // Extract result
            val prediction = outputBuffer[0][0]
            return if (prediction > 0.5f) "white" else "black"
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error classifying square", e)
            return "ambiguous"
        }
    }
    
    /**
     * Convert bitmap to normalized float buffer
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaledBitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val value = intValues[pixel++]
                
                // RGB normalization: [0-255] -> [0-1]
                inputBuffer.putFloat(((value shr 16 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                inputBuffer.putFloat(((value shr 8 and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
                inputBuffer.putFloat(((value and 0xFF) - IMAGE_MEAN) / IMAGE_STD)
            }
        }
        
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        Log.d(TAG, "🗑️ Classifier resources released")
    }
}