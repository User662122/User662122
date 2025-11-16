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
    
    // ✅ NEW: Send full board position instead of moves
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

// ==========================================
// ScreenCaptureService.kt - KEY CHANGES
// ==========================================

// In processFrameAndExtractUci(), replace the detectMoveFromUciChange call with:

// ✅ NEW: Send board position to backend if game is active and not waiting
if (gameStarted && !waitingForBackendMove) {
    // Check if board state changed
    if (lastValidUci == null || currentUci.allPieces != lastValidUci!!.allPieces) {
        sendBoardPositionToBackend(currentUci)
    }
}

// ✅ NEW: Add this function to ScreenCaptureService
private suspend fun sendBoardPositionToBackend(currentUci: UciSnapshot) {
    try {
        waitingForBackendMove = true
        
        val result = backendClient.sendBoardPosition(
            currentUci.whitePieces,
            currentUci.blackPieces
        )
        
        result.onSuccess { response ->
            when {
                response.isEmpty() -> {
                    // No move detected by backend (board state unchanged)
                    Log.d(TAG, "ℹ️ Backend: No move detected")
                }
                response == "Invalid" -> {
                    withContext(Dispatchers.Main) {
                        showToast("Backend: Invalid position")
                    }
                }
                response == "Game Over" -> {
                    withContext(Dispatchers.Main) {
                        showToast("Game Over!")
                    }
                    gameStarted = false
                }
                else -> {
                    // Backend detected a move and responded with its move
                    Log.d(TAG, "🎯 Backend move: $response")
                    executeBackendMove(response)
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "❌ Error sending board position", e)
        }
        
        waitingForBackendMove = false
    } catch (e: Exception) {
        Log.e(TAG, "❌ Error in sendBoardPositionToBackend", e)
        waitingForBackendMove = false
    }
}

// ✅ UPDATED: Simplified startGameWithBackend
private fun startGameWithBackend(bottomColor: String) {
    processingScope.launch {
        try {
            appColor = bottomColor
            Log.d(TAG, "🎮 App is playing as: $bottomColor")
            
            val result = backendClient.startGame(bottomColor)
            
            result.onSuccess { response ->
                gameStarted = true
                
                if (appColor == "white") {
                    if (response.isNotEmpty() && response != "Invalid" && response != "Game Over") {
                        Log.d(TAG, "✅ App is white - backend gave first move: $response")
                        executeBackendMove(response)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showToast("Waiting for opponent's first move...")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting game", e)
        }
    }
}