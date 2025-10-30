package com.chesschat.app

import android.util.Log

/**
 * Touch Simulator for automated move execution
 * Uses ChessAccessibilityService to dispatch gestures
 * 
 * ENHANCED with comprehensive UCI grid mapping (a1-h8)
 */
class TouchSimulator {
    
    companion object {
        private const val TAG = "TouchSimulator"
        
        /**
         * Complete UCI Grid Mapping (64 squares)
         * 
         * IMPORTANT: Files (a-h) ALWAYS go from left to right
         *           Only ranks flip based on color selection
         * 
         * WHITE ORIENTATION (white pieces at bottom, isFlipped=false):
         * Top:    a8 b8 c8 d8 e8 f8 g8 h8  (black pieces)
         *         a7 b7 c7 d7 e7 f7 g7 h7  (black pawns)
         *         a6 b6 c6 d6 e6 f6 g6 h6
         *         a5 b5 c5 d5 e5 f5 g5 h5
         *         a4 b4 c4 d4 e4 f4 g4 h4
         *         a3 b3 c3 d3 e3 f3 g3 h3
         *         a2 b2 c2 d2 e2 f2 g2 h2  (white pawns)
         * Bottom: a1 b1 c1 d1 e1 f1 g1 h1  (white pieces)
         * 
         * BLACK ORIENTATION (black pieces at bottom, isFlipped=true):
         * Bottom: a1 b1 c1 d1 e1 f1 g1 h1  (white pieces at bottom from black's view)
         *         a2 b2 c2 d2 e2 f2 g2 h2  (white pawns)
         *         a3 b3 c3 d3 e3 f3 g3 h3
         *         a4 b4 c4 d4 e4 f4 g4 h4
         *         a5 b5 c5 d5 e5 f5 g5 h5
         *         a6 b6 c6 d6 e6 f6 g6 h6
         *         a7 b7 c7 d7 e7 f7 g7 h7  (black pawns)
         * Top:    a8 b8 c8 d8 e8 f8 g8 h8  (black pieces at top from black's view)
         */
        
        // All 64 UCI squares in standard order
        private val ALL_UCI_SQUARES = listOf(
            // Rank 8 (top in white orientation)
            "a8", "b8", "c8", "d8", "e8", "f8", "g8", "h8",
            // Rank 7
            "a7", "b7", "c7", "d7", "e7", "f7", "g7", "h7",
            // Rank 6
            "a6", "b6", "c6", "d6", "e6", "f6", "g6", "h6",
            // Rank 5
            "a5", "b5", "c5", "d5", "e5", "f5", "g5", "h5",
            // Rank 4
            "a4", "b4", "c4", "d4", "e4", "f4", "g4", "h4",
            // Rank 3
            "a3", "b3", "c3", "d3", "e3", "f3", "g3", "h3",
            // Rank 2
            "a2", "b2", "c2", "d2", "e2", "f2", "g2", "h2",
            // Rank 1 (bottom in white orientation)
            "a1", "b1", "c1", "d1", "e1", "f1", "g1", "h1"
        )
    }
    
    /**
     * Check if accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        return ChessAccessibilityService.isServiceEnabled()
    }
    
    /**
     * Simulate a touch at the specified coordinates
     * Requires ChessAccessibilityService to be enabled
     * IMPROVED: Longer 700ms duration for maximum reliability
     * LOGS: Detailed pixel and UCI square information
     */
    fun simulateTouch(x: Float, y: Float, durationMs: Long = 700): Boolean {
        val service = ChessAccessibilityService.getInstance()
        
        if (service == null) {
            Log.w(TAG, "Accessibility service not enabled - touch simulation unavailable")
            return false
        }
        
        return try {
            val success = service.performTouch(x, y, durationMs)
            if (success) {
                Log.d(TAG, "✓ Touch dispatched at pixel (${x.toInt()}, ${y.toInt()}) for ${durationMs}ms")
            } else {
                Log.w(TAG, "✗ Failed to dispatch touch at pixel (${x.toInt()}, ${y.toInt()})")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception simulating touch: ${e.message}")
            false
        }
    }
    
    /**
     * Simulate a drag gesture from one point to another
     * Requires ChessAccessibilityService to be enabled
     * IMPROVED: Longer duration for smoother, more reliable moves
     * LOGS: Detailed pixel coordinates for both source and destination
     */
    fun simulateDrag(fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long = 600): Boolean {
        val service = ChessAccessibilityService.getInstance()
        
        if (service == null) {
            Log.w(TAG, "Accessibility service not enabled - drag simulation unavailable")
            return false
        }
        
        return try {
            val success = service.performDrag(fromX, fromY, toX, toY, durationMs)
            if (success) {
                Log.d(TAG, "✓ Drag dispatched from pixel (${fromX.toInt()}, ${fromY.toInt()}) to pixel (${toX.toInt()}, ${toY.toInt()}) over ${durationMs}ms")
            } else {
                Log.w(TAG, "✗ Failed to dispatch drag")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Exception simulating drag: ${e.message}")
            false
        }
    }
    
    /**
     * Calculate screen coordinates for a chess square with COMPREHENSIVE LOGGING
     * 
     * This function maps UCI notation (e.g., "e2", "e4") to exact pixel coordinates
     * on the screen, taking into account board orientation (white/black on bottom)
     * 
     * ORIENTATION LOGIC:
     * - Files (a-h) ALWAYS go left to right (never flip)
     * - Ranks flip vertically based on selected color:
     *   * White selected: rank 1 at bottom, rank 8 at top
     *   * Black selected: rank 1 at bottom, rank 8 at top (same visual order)
     * 
     * @param square UCI square notation (e.g., "e2", "a1", "h8")
     * @param boardX Board area X coordinate (left edge)
     * @param boardY Board area Y coordinate (top edge)
     * @param boardSize Board area size (width = height)
     * @param isFlipped true if black pieces on bottom, false if white pieces on bottom
     * @return Pair of (x, y) pixel coordinates representing the center of the square
     */
    fun getSquareCoordinates(
        square: String,  // e.g., "e2", "e4"
        boardX: Int,
        boardY: Int,
        boardSize: Int,
        isFlipped: Boolean
    ): Pair<Float, Float> {
        val file = square[0] - 'a'  // 0-7 (a=0, b=1, ..., h=7)
        val rank = square[1] - '1'  // 0-7 (1=0, 2=1, ..., 8=7)
        
        // Apply orientation transformation
        // Files NEVER flip - always a-h from left to right
        val actualFile = file
        // Ranks flip based on color selection
        // White (isFlipped=false): rank 1 at bottom → actualRank = 7-rank (invert for screen Y)
        // Black (isFlipped=true): rank 1 at bottom → actualRank = rank (no inversion)
        val actualRank = if (isFlipped) rank else 7 - rank
        
        // FIX: Use floating-point arithmetic to prevent cumulative rounding errors
        // Integer division causes precision loss (e.g., 698/8 = 87 instead of 87.25)
        // This leads to inaccurate touch coordinates, especially on right/bottom edges
        val squareSize = boardSize.toFloat() / 8f
        val x = boardX.toFloat() + (actualFile * squareSize) + (squareSize / 2f)
        val y = boardY.toFloat() + (actualRank * squareSize) + (squareSize / 2f)
        
        // COMPREHENSIVE LOGGING
        val orientation = if (isFlipped) "BLACK" else "WHITE"
        Log.d(TAG, "═══ UCI SQUARE MAPPING (PRECISION FIX) ═══")
        Log.d(TAG, "UCI Square: $square")
        Log.d(TAG, "File: ${square[0]} (${file}) → Screen File: $actualFile (a-h always left-to-right)")
        Log.d(TAG, "Rank: ${square[1]} (${rank}) → Screen Rank: $actualRank")
        Log.d(TAG, "Board Orientation: $orientation on bottom")
        Log.d(TAG, "Board Area: X=$boardX, Y=$boardY, Size=$boardSize")
        Log.d(TAG, "Square Size: $squareSize pixels (float precision)")
        Log.d(TAG, "Grid Position: Column $actualFile, Row $actualRank (0-indexed)")
        Log.d(TAG, "Pixel Coordinates: (${x}, ${y}) [precise center of square]")
        Log.d(TAG, "═══════════════════════════════════════")
        
        return Pair(x, y)
    }
    
    /**
     * Get UCI square from pixel coordinates
     * Reverse of getSquareCoordinates - useful for debugging
     * 
     * FILES NEVER FLIP - always a-h from left to right
     * RANKS flip based on color selection
     */
    fun getSquareFromCoordinates(
        pixelX: Int,
        pixelY: Int,
        boardX: Int,
        boardY: Int,
        boardSize: Int,
        isFlipped: Boolean
    ): String? {
        // Check if coordinates are within board
        if (pixelX < boardX || pixelY < boardY ||
            pixelX >= boardX + boardSize || pixelY >= boardY + boardSize) {
            return null
        }
        
        // FIX: Use floating-point arithmetic for precise calculation
        val squareSize = boardSize.toFloat() / 8f
        val screenFile = ((pixelX - boardX).toFloat() / squareSize).toInt()
        val screenRank = ((pixelY - boardY).toFloat() / squareSize).toInt()
        
        // Reverse orientation transformation
        // Files NEVER flip
        val file = screenFile
        // Ranks flip based on color
        val rank = if (isFlipped) screenRank else 7 - screenRank
        
        // Validate range
        if (file !in 0..7 || rank !in 0..7) {
            return null
        }
        
        // Convert to Char correctly - adding Int to Char yields Int in Kotlin, must convert back
        val fileChar = ('a'.code + file).toChar()
        val rankChar = ('1'.code + rank).toChar()
        
        return "$fileChar$rankChar"
    }
    
    /**
     * Print complete UCI grid map for current board configuration
     * Useful for debugging and visualization
     */
    fun printUCIGridMap(
        boardX: Int,
        boardY: Int,
        boardSize: Int,
        isFlipped: Boolean
    ) {
        val orientation = if (isFlipped) "BLACK" else "WHITE"
        val squareSize = boardSize / 8
        
        Log.d(TAG, "╔════════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║           COMPLETE UCI GRID MAP (64 squares)              ║")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════╣")
        Log.d(TAG, "║ Orientation: $orientation pieces on bottom                    ")
        Log.d(TAG, "║ Board Area: X=$boardX, Y=$boardY, Size=$boardSize           ")
        Log.d(TAG, "║ Square Size: $squareSize pixels                              ")
        Log.d(TAG, "╠════════════════════════════════════════════════════════════╣")
        
        for (rank in 7 downTo 0) {
            val squares = mutableListOf<String>()
            for (file in 0..7) {
                // Convert to Char correctly - adding Int to Char yields Int in Kotlin
                val fileChar = ('a'.code + file).toChar()
                val rankChar = ('1'.code + rank).toChar()
                val uciSquare = "$fileChar$rankChar"
                
                val coords = getSquareCoordinates(uciSquare, boardX, boardY, boardSize, isFlipped)
                squares.add("$uciSquare:(${coords.first.toInt()},${coords.second.toInt()})")
            }
            Log.d(TAG, "║ Rank ${rank + 1}: ${squares.joinToString(" ")}")
        }
        
        Log.d(TAG, "╚════════════════════════════════════════════════════════════╝")
    }
}
