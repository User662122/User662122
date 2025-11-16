package com.chessmove.detector

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class BackendClient(private val context: Context) {
    
    companion object {
        const val TAG = "BackendClient"
        private const val PREFS_NAME = "ChessBackendPrefs"
        private const val KEY_BACKEND_URL = "backend_url"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    fun saveBackendUrl(url: String) {
        val cleanUrl = url.trim().removeSuffix("/")
        prefs.edit().putString(KEY_BACKEND_URL, cleanUrl).apply()
        Log.d(TAG, "✅ Saved backend URL: $cleanUrl")
    }
    
    fun getBackendUrl(): String? {
        return prefs.getString(KEY_BACKEND_URL, null)
    }
    
    fun hasBackendUrl(): Boolean {
        return !getBackendUrl().isNullOrEmpty()
    }
    
    /**
     * Start game with backend
     * @param color: "white" or "black" - the color the app will play as
     * @return Response: empty string if black (waiting for opponent), or first move if white
     */
    suspend fun startGame(color: String): Result<String> = withContext(Dispatchers.IO) {
        val url = getBackendUrl()
        if (url.isNullOrEmpty()) {
            return@withContext Result.failure(Exception("Backend URL not set"))
        }
        
        try {
            Log.d(TAG, "🎮 Starting game with color: $color")
            
            val requestBody = color.lowercase()
                .toRequestBody("text/plain".toMediaType())
            
            val request = Request.Builder()
                .url("$url/start")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val result = response.body?.string() ?: ""
                Log.d(TAG, "✅ Start game response: $result")
                Result.success(result)
            } else {
                Log.e(TAG, "❌ Start game failed: ${response.code}")
                Result.failure(Exception("Backend returned ${response.code}"))
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ Network error starting game", e)
            Result.failure(e)
        }
    }
    
    /**
     * Send board position to backend
     * Backend will detect opponent's move and respond with its own move
     * @param whitePieces: Set of white piece positions (e.g., ["a2", "b2", "c2"])
     * @param blackPieces: Set of black piece positions (e.g., ["a7", "b7", "c7"])
     * @return Response: 
     *   - Empty string: No move detected (board unchanged)
     *   - "Invalid": Invalid position
     *   - "Game Over": Game ended
     *   - UCI move string: Backend's next move (e.g., "e2e4")
     */
    suspend fun sendBoardPosition(whitePieces: Set<String>, blackPieces: Set<String>): Result<String> = withContext(Dispatchers.IO) {
        val url = getBackendUrl()
        if (url.isNullOrEmpty()) {
            return@withContext Result.failure(Exception("Backend URL not set"))
        }
        
        try {
            // Format: "White at : a2, b2, c2\nBlack at : a7, b7, c7"
            val whiteList = whitePieces.sorted().joinToString(", ")
            val blackList = blackPieces.sorted().joinToString(", ")
            val boardPosition = "White at : $whiteList\nBlack at : $blackList"
            
            Log.d(TAG, "📤 Sending board position:")
            Log.d(TAG, "   White: $whiteList")
            Log.d(TAG, "   Black: $blackList")
            
            val requestBody = boardPosition
                .toRequestBody("text/plain".toMediaType())
            
            val request = Request.Builder()
                .url("$url/move")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val result = response.body?.string() ?: ""
                Log.d(TAG, "✅ Backend response: $result")
                Result.success(result)
            } else {
                Log.e(TAG, "❌ Send board position failed: ${response.code}")
                Result.failure(Exception("Backend returned ${response.code}"))
            }
        } catch (e: IOException) {
            Log.e(TAG, "❌ Network error sending board position", e)
            Result.failure(e)
        }
    }
}