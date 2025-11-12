package com.chessmove.detector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite classifier for chess piece colors
 * Model input: 150x150 RGB image in JPEG format (quality 100)
 * Model output: Single float [0-1], >0.5 = white, <0.5 = black
 */
class PieceColorClassifier(context: Context) {
    
    companion object {
        const val TAG = "PieceColorClassifier"
        const val MODEL_PATH = "white_black_classifier.tflite"
        const val INPUT_SIZE = 150
        const val JPEG_QUALITY = 100  // ✅ Match training data quality
        private const val PIXEL_SIZE = 3 // RGB
        private const val IMAGE_MEAN = 0f
        private const val IMAGE_STD = 255f
    }
    
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // Buffer for SINGLE image (150x150x3 floats)
    private val inputBuffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE * 4)
        .apply { order(ByteOrder.nativeOrder()) }
    
    // Output for SINGLE prediction
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
            Log.d(TAG, "   Input format: JPEG quality $JPEG_QUALITY, ${INPUT_SIZE}x$INPUT_SIZE")
            
            // Verify buffer size
            val inputTensor = interpreter?.getInputTensor(0)
            val expectedBytes = inputTensor?.numBytes() ?: 0
            val actualBytes = inputBuffer.capacity()
            Log.d(TAG, "   Buffer: expected=$expectedBytes, actual=$actualBytes")
            
            if (expectedBytes != actualBytes) {
                Log.e(TAG, "❌ BUFFER SIZE MISMATCH!")
            }
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
     * Process squares one at a time with proper JPEG conversion
     */
    fun classifyBatch(squares: List<Bitmap>): List<String> {
        if (interpreter == null) {
            Log.e(TAG, "❌ Interpreter not initialized")
            return List(squares.size) { "ambiguous" }
        }
        
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<String>()
        
        // Process each square individually with JPEG conversion
        for (bitmap in squares) {
            val color = classifySingle(bitmap)
            results.add(color)
        }
        
        val elapsedTime = System.currentTimeMillis() - startTime
        Log.d(TAG, "🤖 TFLite classified ${results.size} pieces in ${elapsedTime}ms " +
                "(${if (results.isNotEmpty()) "%.1f".format(elapsedTime.toFloat() / results.size) else "0"}ms/piece)")
        
        return results
    }
    
    /**
     * Classify a single square image with proper JPEG preprocessing
     */
    fun classifySingle(squareBitmap: Bitmap): String {
        if (interpreter == null) {
            return "ambiguous"
        }
        
        try {
            // ✅ STEP 1: Convert to JPEG format (matches training data)
            val jpegBitmap = convertToJpegFormat(squareBitmap)
            
            // ✅ STEP 2: Resize to exact model input size (150x150)
            val resizedBitmap = resizeToModelInput(jpegBitmap)
            
            // Clean up intermediate bitmap
            if (jpegBitmap != squareBitmap) {
                jpegBitmap.recycle()
            }
            
            // ✅ STEP 3: Convert to model input buffer
            inputBuffer.rewind()
            convertBitmapToByteBuffer(resizedBitmap)
            
            // Clean up resized bitmap
            if (resizedBitmap != squareBitmap) {
                resizedBitmap.recycle()
            }
            
            // ✅ STEP 4: Run inference
            inputBuffer.rewind()
            interpreter?.run(inputBuffer, outputBuffer)
            
            // ✅ STEP 5: Extract result
            val prediction = outputBuffer[0][0]
            return if (prediction > 0.5f) "white" else "black"
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error classifying square", e)
            return "ambiguous"
        }
    }
    
    /**
     * ✅ NEW: Convert bitmap to JPEG format and back (simulates training data format)
     * This ensures the model receives images in the same format it was trained on
     */
    private fun convertToJpegFormat(bitmap: Bitmap): Bitmap {
        val outputStream = ByteArrayOutputStream()
        
        // Compress to JPEG at quality 100 (same as training)
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        val jpegBytes = outputStream.toByteArray()
        outputStream.close()
        
        // Decode back to Bitmap (now in JPEG format with compression artifacts)
        val jpegBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        
        return jpegBitmap
    }
    
    /**
     * ✅ NEW: Resize bitmap to exact model input dimensions
     * Uses high-quality filtering to maintain aspect ratio and quality
     */
    private fun resizeToModelInput(bitmap: Bitmap): Bitmap {
        // If already correct size, return as-is
        if (bitmap.width == INPUT_SIZE && bitmap.height == INPUT_SIZE) {
            return bitmap
        }
        
        // Resize to 150x150 with high-quality filtering
        return Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
    }
    
    /**
     * Convert bitmap to normalized float buffer
     * Input: 150x150 RGB bitmap
     * Output: Normalized float values [0-1]
     */
    private fun convertBitmapToByteBuffer(bitmap: Bitmap) {
        // Bitmap should already be 150x150 at this point
        if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
            Log.w(TAG, "⚠️ Bitmap size mismatch: ${bitmap.width}x${bitmap.height} (expected ${INPUT_SIZE}x$INPUT_SIZE)")
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