package com.chessmove.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite classifier with 8 FIXED CPU instances for parallel processing
 * Model input: 96x96 RGB image
 * Model output: 3 classes [Black, White, empty] with softmax probabilities
 * ✅ FIXED: Matches Python implementation exactly
 */
class PieceColorClassifier(context: Context) {
    
    companion object {
        const val TAG = "PieceColorClassifier"
        const val MODEL_PATH = "white_black_empty_classifier_mobilenetv2_96x96.tflite"
        const val INPUT_SIZE = 96
        private const val PIXEL_SIZE = 3 // RGB
        private const val NUM_INSTANCES = 8  // Fixed 8 instances for 8 cores
        private const val NUM_CLASSES = 3    // Black, White, Empty
        
        // ✅ FIXED: Class indices must match Python training order (alphabetical)
        // Python: CLASS_NAMES = ["Black", "White", "empty"]
        private const val CLASS_BLACK = 0    // Index 0
        private const val CLASS_WHITE = 1    // Index 1
        private const val CLASS_EMPTY = 2    // Index 2
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
            Log.d(TAG, "   Input: ${INPUT_SIZE}x$INPUT_SIZE RGB")
            Log.d(TAG, "   Output: 3 classes [Black=0, White=1, Empty=2]")
            
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
     * ✅ FIXED: Classify using specific interpreter instance
     * Matches Python logic exactly - no threshold checks, just argmax
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
            // Output shape: [1, 3] -> [Black, White, Empty] probabilities
            val outputBuffer = Array(1) { FloatArray(NUM_CLASSES) }
            inputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            
            val probabilities = outputBuffer[0]
            
            // ✅ FIXED: Just use argmax like Python - no extra threshold checks
            // Python: class_id = np.argmax(output)
            val maxIndex = probabilities.indices.maxByOrNull { probabilities[it] } ?: CLASS_EMPTY
            
            // ✅ FIXED: Map index to class name (matches Python CLASS_NAMES order)
            return when (maxIndex) {
                CLASS_BLACK -> "black"  // Index 0
                CLASS_WHITE -> "white"  // Index 1
                CLASS_EMPTY -> "empty"  // Index 2
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
     * ✅ FIXED: Convert bitmap to normalized float buffer
     * Matches Python: img_norm = img_rgb.astype(np.float32)/255.0
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
                
                // ✅ FIXED: RGB normalization [0-255] -> [0-1] (divide by 255)
                // Matches Python: img_rgb.astype(np.float32)/255.0
                inputBuffer.putFloat(((value shr 16 and 0xFF).toFloat()) / 255f)  // R
                inputBuffer.putFloat(((value shr 8 and 0xFF).toFloat()) / 255f)   // G
                inputBuffer.putFloat(((value and 0xFF).toFloat()) / 255f)          // B
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
