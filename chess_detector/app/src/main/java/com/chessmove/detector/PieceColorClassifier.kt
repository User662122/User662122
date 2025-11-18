package com.chessmove.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite classifier with 8 FIXED CPU instances for parallel processing
 * Model input: 150x150 RGB image in JPEG format (quality 100)
 * Model output: Single float [0-1], >0.5 = white, <0.5 = black
 */
class PieceColorClassifier(context: Context) {
    
    companion object {
        const val TAG = "PieceColorClassifier"
        const val MODEL_PATH = "white_black_classifier.tflite"
        const val INPUT_SIZE = 150
        const val JPEG_QUALITY = 100
        private const val PIXEL_SIZE = 3 // RGB
        private const val IMAGE_MEAN = 0f
        private const val IMAGE_STD = 255f
        private const val NUM_INSTANCES = 8  // Fixed 8 instances for 8 cores
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
            Log.d(TAG, "   Total parallel threads: $NUM_INSTANCES")
            Log.d(TAG, "   Input format: JPEG quality $JPEG_QUALITY, ${INPUT_SIZE}x$INPUT_SIZE")
            
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
     */
    fun classifyBatch(squares: List<Bitmap>): List<String> = runBlocking {
        if (interpreters.isEmpty()) {
            Log.e(TAG, "❌ No interpreters initialized")
            return@runBlocking List(squares.size) { "ambiguous" }
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
        Log.d(TAG, "🤖 TFLite (8 CPU parallel): ${results.size} pieces in ${elapsedTime}ms " +
                "(${if (results.isNotEmpty()) "%.1f".format(elapsedTime.toFloat() / results.size) else "0"}ms/piece)")
        
        return@runBlocking results
    }
    
    /**
     * Classify using specific interpreter instance
     */
    private fun classifySingleWithInstance(squareBitmap: Bitmap, instanceIndex: Int): String {
        if (instanceIndex >= interpreters.size) {
            return "ambiguous"
        }
        
        try {
            val interpreter = interpreters[instanceIndex]
            
            // ✅ Convert to JPEG format
            val jpegBitmap = convertToJpegFormat(squareBitmap)
            
            // ✅ Resize to exact model input size (150x150)
            val resizedBitmap = resizeToModelInput(jpegBitmap)
            
            if (jpegBitmap != squareBitmap) {
                jpegBitmap.recycle()
            }
            
            // ✅ Create buffer for this instance
            val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * 4)
                .apply { order(ByteOrder.nativeOrder()) }
            
            convertBitmapToByteBuffer(resizedBitmap, inputBuffer)
            
            if (resizedBitmap != squareBitmap) {
                resizedBitmap.recycle()
            }
            
            // ✅ Run inference on this instance
            val outputBuffer = Array(1) { FloatArray(1) }
            inputBuffer.rewind()
            interpreter.run(inputBuffer, outputBuffer)
            
            val prediction = outputBuffer[0][0]
            return if (prediction > 0.5f) "white" else "black"
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error classifying with instance $instanceIndex", e)
            return "ambiguous"
        }
    }
    
    /**
     * Convert bitmap to JPEG format (simulates training data format)
     */
    private fun convertToJpegFormat(bitmap: Bitmap): Bitmap {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val jpegBytes = outputStream.toByteArray()
        outputStream.close()
        
        val jpegBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        return jpegBitmap
    }
    
    /**
     * Resize bitmap to exact model input dimensions
     */
    private fun resizeToModelInput(bitmap: Bitmap): Bitmap {
        if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
    }
    
    /**
     * Convert bitmap to normalized float buffer
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