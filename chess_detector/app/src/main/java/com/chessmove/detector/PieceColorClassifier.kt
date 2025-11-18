package com.chessmove.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite classifier with 8 FIXED CPU instances for parallel processing
 * Model input: 96x96 RGB image
 * Model output: 3 classes [Black, White, Empty] with softmax probabilities
 */
class PieceColorClassifier(context: Context) {
    
    companion object {
        const val TAG = "PieceColorClassifier"
        const val MODEL_PATH = "white_black_empty_classifier_mobilenetv2_96x96.tflite"
        const val INPUT_SIZE = 96
        private const val PIXEL_SIZE = 3 // RGB
        private const val IMAGE_MEAN = 0f
        private const val IMAGE_STD = 255f
        private const val NUM_INSTANCES = 8  // Fixed 8 instances for 8 cores
        private const val NUM_CLASSES = 3    // Black, White, Empty
        
        // Class indices (should match your training dataset class order)
        // Typically: alphabetical order -> [Black=0, Empty=1, White=2]
private const val CLASS_BLACK = 0
private const val CLASS_WHITE = 1  
private const val CLASS_EMPTY = 2
        
        // Confidence threshold for empty detection
        private const val EMPTY_THRESHOLD = 0.5f
    }
    
    private val interpreters = mutableListOf<Interpreter>()
    
    init {
        try {
            val model = loadModelFile(context)
            
            // ✅ Create 8 fixed CPU instances (one per core)
            for (i in 0 until NUM_INSTANCES) {
                val options = Interpreter.Options().apply {
                    setNumThreads(1)  // Each instance uses 1 thread
                }
                
                interpreters.add(Interpreter(model, options))
            }
            
            Log.d(TAG, "✅ Created $NUM_INSTANCES CPU TFLite instances (8 cores)")
            Log.d(TAG, "   Each instance: 1 thread")
            Log.d(TAG, "   Input: ${INPUT_SIZE}x$INPUT_SIZE RGB")
            Log.d(TAG, "   Output: 3 classes [Black, Empty, White]")
            
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
     * Process squares in PARALLEL using 8 CPU instances
     * Returns list of classifications: "white", "black", or "empty"
     */
    fun classifyBatch(squares: List<Bitmap>): List<String> = runBlocking {
        if (interpreters.isEmpty()) {
            Log.e(TAG, "❌ No interpreters initialized")
            return@runBlocking List(squares.size) { "empty" }
        }
        
        val startTime = System.currentTimeMillis()
        
        // ✅ Process in parallel using all 8 CPU instances
        val results = squares.mapIndexed { index, bitmap ->
            async(Dispatchers.Default) {
                // Round-robin assignment to 8 instances
                val instanceIndex = index % NUM_INSTANCES
                classifySingleWithInstance(bitmap, instanceIndex)
            }
        }.awaitAll()
        
        val elapsedTime = System.currentTimeMillis() - startTime
        val whiteCount = results.count { it == "white" }
        val blackCount = results.count { it == "black" }
        val emptyCount = results.count { it == "empty" }
        
        Log.d(TAG, "🤖 TFLite (8 CPU parallel): ${results.size} squares in ${elapsedTime}ms " +
                "(${if (results.isNotEmpty()) "%.1f".format(elapsedTime.toFloat() / results.size) else "0"}ms/square)")
        Log.d(TAG, "   Results: W=$whiteCount, B=$blackCount, Empty=$emptyCount")
        
        return@runBlocking results
    }
    
    /**
     * Classify using specific interpreter instance
     * Returns: "white", "black", or "empty"
     */
    private fun classifySingleWithInstance(squareBitmap: Bitmap, instanceIndex: Int): String {
        if (instanceIndex >= interpreters.size) {
            return "empty"
        }
        
        try {
            val interpreter = interpreters[instanceIndex]
            
            // ✅ Resize to exact model input size (96x96)
            val resizedBitmap = resizeToModelInput(squareBitmap)
            
            // ✅ Create buffer for this instance
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * 4)
                .apply { order(ByteOrder.nativeOrder()) }
            
            convertBitmapToByteBuffer(resizedBitmap, inputBuffer)
            
            if (resizedBitmap != squareBitmap) {
                resizedBitmap.recycle()
            }
            
            // ✅ Run inference on this instance
            // Output shape: [1, 3] -> [Black, Empty, White] probabilities
            val outputBuffer = Array(1) { FloatArray(NUM_CLASSES) }
            inputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            
            val probabilities = outputBuffer[0]
            
            // Find the class with highest probability
            val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: CLASS_EMPTY
            val maxProb = probabilities[maxIndex]
            
            // Map index to class name
            return when (maxIndex) {
                CLASS_BLACK -> {
                    // Extra check: if empty has high probability, classify as empty
                    if (probabilities[CLASS_EMPTY] > EMPTY_THRESHOLD) "empty" else "black"
                }
                CLASS_WHITE -> {
                    // Extra check: if empty has high probability, classify as empty
                    if (probabilities[CLASS_EMPTY] > EMPTY_THRESHOLD) "empty" else "white"
                }
                CLASS_EMPTY -> "empty"
                else -> "empty"
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error classifying with instance $instanceIndex", e)
            return "empty"
        }
    }
    
    /**
     * Resize bitmap to exact model input dimensions (96x96)
     */
    private fun resizeToModelInput(bitmap: Bitmap): Bitmap {
        if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
    }
    
    /**
     * Convert bitmap to normalized float buffer
     * Normalization: [0-255] -> [0-1]
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap, inputBuffer: ByteBuffer) {
        if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
            Log.w(TAG, "⚠️ Bitmap size mismatch: ${bitmap.width}x${bitmap.height}")
        }
        
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        
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
    }
    
    /**
     * Clean up all resources
     */
    fun close() {
        interpreters.forEach { it.close() }
        interpreters.clear()
        
        Log.d(TAG, "🗑️ All $NUM_INSTANCES CPU classifier instances released")
    }
}