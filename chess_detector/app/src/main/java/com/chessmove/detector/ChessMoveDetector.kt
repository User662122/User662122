package com.chessmove.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

// ===============================
// CONSTANTS AND CONFIGURATION
// ===============================
const val BOARD_SIZE = 800
const val CELL_SIZE = BOARD_SIZE / 8

// ✅ IMPROVED: Match Python code exactly
const val PIECE_THRESHOLD = 15.0  // Fixed 15% threshold
const val SHRINK_FACTOR = 0.962

data class BoardState(
    val white: Set<String>,
    val black: Set<String>,
    val ambiguous: Set<String> = emptySet(),
    val annotatedBoard: Bitmap? = null,
    val boardCorners: Array<Point>? = null,
    val whiteOnBottom: Boolean? = null,
    val uciToScreenCoordinates: Map<String, android.graphics.Point>? = null,
    val detectedSquares: Set<Pair<Int, Int>>? = null
)

data class SquareData(
    val row: Int,
    val col: Int,
    val bitmap: Bitmap
)

private data class UciCache(
    val detectedSquares: Set<Pair<Int, Int>>,
    val pieceColors: Map<Pair<Int, Int>, String>,
    val timestamp: Long
)

private var lastUciCache: UciCache? = null

// ===============================
// CORE FUNCTIONS
// ===============================

/**
 * Detect chessboard and return inner points
 */
private fun detectChessboard(img: Mat): MatOfPoint2f? {
    val gray = Mat()
    Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
    
    val blurred = Mat()
    Imgproc.bilateralFilter(gray, blurred, 11, 17.0, 17.0)
    
    val edges = Mat()
    Imgproc.Canny(blurred, edges, 40.0, 150.0)
    
    val kernel = Mat()
    Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
    Imgproc.erode(edges, edges, kernel, Point(-1.0, -1.0), 1)
    
    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
    
    val imgArea = img.rows() * img.cols().toDouble()
    var bestContour: MatOfPoint2f? = null
    
    for (contour in contours) {
        val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)
        val area = Imgproc.contourArea(contour)
        
        if (approx.toArray().size == 4 && area > 0.2 * imgArea) {
            val rect = Imgproc.boundingRect(contour)
            val ratio = rect.width.toDouble() / rect.height.toDouble()
            
            if (ratio > 0.7 && ratio < 1.3) {
                bestContour = approx
                break
            }
        }
    }
    
    if (bestContour == null && contours.isNotEmpty()) {
        val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
        if (largest != null) {
            val rect = Imgproc.boundingRect(largest)
            bestContour = MatOfPoint2f(
                Point(rect.x.toDouble(), rect.y.toDouble()),
                Point((rect.x + rect.width).toDouble(), rect.y.toDouble()),
                Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
                Point(rect.x.toDouble(), (rect.y + rect.height).toDouble())
            )
        }
    }
    
    gray.release()
    blurred.release()
    edges.release()
    hierarchy.release()
    
    return if (bestContour != null) {
        shrinkPolygon(bestContour, SHRINK_FACTOR)
    } else null
}

private fun shrinkPolygon(pts: MatOfPoint2f, shrinkFactor: Double): MatOfPoint2f {
    val points = pts.toArray()
    val center = Point(
        points.map { it.x }.average(),
        points.map { it.y }.average()
    )
    
    val shrunk = points.map { p ->
        val direction = Point(p.x - center.x, p.y - center.y)
        Point(center.x + direction.x * shrinkFactor, center.y + direction.y * shrinkFactor)
    }.toTypedArray()
    
    return MatOfPoint2f(*shrunk)
}

private fun createWarpedBoard(img: Mat, innerPts: MatOfPoint2f): Mat {
    val pts = innerPts.toArray()
    
    val sumArr = pts.map { it.x + it.y }
    val diffArr = pts.map { it.y - it.x }
    
    val ordered = arrayOf(
        pts[sumArr.indexOf(sumArr.minOrNull()!!)],
        pts[diffArr.indexOf(diffArr.minOrNull()!!)],
        pts[sumArr.indexOf(sumArr.maxOrNull()!!)],
        pts[diffArr.indexOf(diffArr.maxOrNull()!!)]
    )
    
    val srcMat = MatOfPoint2f(*ordered)
    val dstMat = MatOfPoint2f(
        Point(0.0, 0.0),
        Point((BOARD_SIZE - 1).toDouble(), 0.0),
        Point((BOARD_SIZE - 1).toDouble(), (BOARD_SIZE - 1).toDouble()),
        Point(0.0, (BOARD_SIZE - 1).toDouble())
    )
    
    val M = Imgproc.getPerspectiveTransform(srcMat, dstMat)
    val boardWarped = Mat()
    Imgproc.warpPerspective(img, boardWarped, M, Size(BOARD_SIZE.toDouble(), BOARD_SIZE.toDouble()))
    
    srcMat.release()
    dstMat.release()
    M.release()
    
    return boardWarped
}

/**
 * ✅ IMPROVED: Detect pieces using EXACT Python approach
 * 1. Invert image (bitwise_not)
 * 2. Convert to grayscale
 * 3. Apply adaptive threshold (optional, for debugging)
 * 4. Apply Canny edge detection
 * 5. Dilate edges (3x3 kernel, 1 iteration)
 * 6. Calculate non-zero pixels per square with 15% threshold
 */
private fun detectPieceSquares(boardWarped: Mat): List<Pair<Int, Int>> {
    Log.d("ChessDetector", "🔍 Starting IMPROVED piece detection (Python method)")
    
    // STEP 1: Invert the image (bitwise NOT)
    val inverted = Mat()
    Core.bitwise_not(boardWarped, inverted)
    Log.d("ChessDetector", "   ✅ Step 1: Inverted image")
    
    // STEP 2: Convert to grayscale
    val grayInverted = Mat()
    Imgproc.cvtColor(inverted, grayInverted, Imgproc.COLOR_BGR2GRAY)
    Log.d("ChessDetector", "   ✅ Step 2: Converted to grayscale")
    
    // STEP 3: Apply adaptive threshold (optional for visualization)
    val adaptiveInverted = Mat()
    Imgproc.adaptiveThreshold(
        grayInverted,
        adaptiveInverted,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY,
        11,
        2.0
    )
    Log.d("ChessDetector", "   ✅ Step 3: Applied adaptive threshold")
    
    // STEP 4: Apply Canny edge detection
    val edgesInverted = Mat()
    Imgproc.Canny(grayInverted, edgesInverted, 50.0, 150.0)
    Log.d("ChessDetector", "   ✅ Step 4: Applied Canny edge detection")
    
    // STEP 5: Dilate edges (3x3 kernel, 1 iteration)
    val kernel = Mat.ones(3, 3, CvType.CV_8U)
    val dilatedInverted = Mat()
    Imgproc.dilate(edgesInverted, dilatedInverted, kernel, Point(-1.0, -1.0), 1)
    Log.d("ChessDetector", "   ✅ Step 5: Dilated edges (3x3, 1 iteration)")
    
    // STEP 6: Analyze each square with 15% threshold
    val pieceSquares = mutableListOf<Pair<Int, Int>>()
    
    Log.d("ChessDetector", "   Board size: ${boardWarped.cols()}x${boardWarped.rows()}")
    Log.d("ChessDetector", "   Each square: ${CELL_SIZE}x${CELL_SIZE}")
    Log.d("ChessDetector", "   Using FIXED ${PIECE_THRESHOLD}% threshold")
    
    for (row in 0 until 8) {
        for (col in 0 until 8) {
            val y1 = row * CELL_SIZE
            val y2 = (row + 1) * CELL_SIZE
            val x1 = col * CELL_SIZE
            val x2 = (col + 1) * CELL_SIZE
            
            // Extract square from dilated edge image
            val square = dilatedInverted.submat(y1, y2, x1, x2)
            
            // Calculate non-zero pixels percentage
            val totalPixels = square.total().toDouble()
            val nonZeroPixels = Core.countNonZero(square).toDouble()
            val percentage = (nonZeroPixels / totalPixels) * 100.0
            
            // Apply 15% threshold
            if (percentage > PIECE_THRESHOLD) {
                pieceSquares.add(Pair(row, col))
                Log.d("ChessDetector", "      PIECE at ($row, $col): ${"%.1f".format(percentage)}%")
            } else {
                Log.d("ChessDetector", "      EMPTY at ($row, $col): ${"%.1f".format(percentage)}%")
            }
            
            square.release()
        }
    }
    
    // Cleanup
    inverted.release()
    grayInverted.release()
    adaptiveInverted.release()
    edgesInverted.release()
    kernel.release()
    dilatedInverted.release()
    
    Log.d("ChessDetector", "✅ IMPROVED detection complete: ${pieceSquares.size} pieces found")
    Log.d("ChessDetector", "   Expected: 32 pieces (in standard position)")
    
    if (pieceSquares.size > 34) {
        Log.w("ChessDetector", "   ⚠️ WARNING: Too many pieces detected! Likely noise.")
    } else if (pieceSquares.size < 30) {
        Log.w("ChessDetector", "   ⚠️ WARNING: Too few pieces detected! Check lighting/threshold.")
    }
    
    return pieceSquares
}

/**
 * Extract square images as bitmaps for TFLite classification
 */
private fun extractSquareBitmaps(boardWarped: Mat, pieceSquares: List<Pair<Int, Int>>): List<SquareData> {
    val squareDataList = mutableListOf<SquareData>()
    
    for ((row, col) in pieceSquares) {
        val y1 = row * CELL_SIZE
        val y2 = (row + 1) * CELL_SIZE
        val x1 = col * CELL_SIZE
        val x2 = (col + 1) * CELL_SIZE
        
        val squareMat = boardWarped.submat(y1, y2, x1, x2)
        val squareBitmap = Bitmap.createBitmap(squareMat.cols(), squareMat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(squareMat, squareBitmap)
        squareMat.release()
        
        squareDataList.add(SquareData(row, col, squareBitmap))
    }
    
    return squareDataList
}

/**
 * Check if UCI positions match cached positions
 */
private fun shouldSkipColorClassification(
    currentSquares: Set<Pair<Int, Int>>,
    cache: UciCache?
): Boolean {
    if (cache == null) {
        Log.d("ChessDetector", "🆕 No cache - first detection")
        return false
    }
    
    val isSame = currentSquares == cache.detectedSquares
    
    if (isSame) {
        val age = System.currentTimeMillis() - cache.timestamp
        Log.d("ChessDetector", "♻️ UCI CACHE HIT! Skipping TFLite (cache age: ${age}ms)")
    } else {
        Log.d("ChessDetector", "🔄 UCI changed: ${cache.detectedSquares.size} → ${currentSquares.size} pieces")
    }
    
    return isSame
}

/**
 * Classify piece colors with caching
 */
private fun classifyPieceColorsWithCache(
    squareDataList: List<SquareData>,
    classifier: PieceColorClassifier,
    currentSquares: Set<Pair<Int, Int>>
): Map<Pair<Int, Int>, String> {
    
    if (shouldSkipColorClassification(currentSquares, lastUciCache)) {
        squareDataList.forEach { it.bitmap.recycle() }
        return lastUciCache!!.pieceColors
    }
    
    val startTime = System.currentTimeMillis()
    val bitmaps = squareDataList.map { it.bitmap }
    val colors = classifier.classifyBatch(bitmaps)
    val elapsedTime = System.currentTimeMillis() - startTime
    
    Log.d("ChessDetector", "🤖 TFLite: ${colors.size} pieces in ${elapsedTime}ms")
    
    val pieceTypes = mutableMapOf<Pair<Int, Int>, String>()
    for ((index, squareData) in squareDataList.withIndex()) {
        pieceTypes[Pair(squareData.row, squareData.col)] = colors[index]
        squareData.bitmap.recycle()
    }
    
    lastUciCache = UciCache(
        detectedSquares = currentSquares,
        pieceColors = pieceTypes,
        timestamp = System.currentTimeMillis()
    )
    
    return pieceTypes
}

/**
 * Detect board orientation
 */
private fun detectBoardOrientation(
    piecesFound: List<Pair<Int, Int>>,
    pieceTypes: Map<Pair<Int, Int>, String>
): Boolean {
    var lightInTop = 0
    var darkInTop = 0
    var lightInBottom = 0
    var darkInBottom = 0
    
    for ((row, col) in piecesFound) {
        val pieceType = pieceTypes[Pair(row, col)] ?: continue
        
        if (pieceType == "ambiguous") continue
        
        if (row < 4) {
            if (pieceType == "white") lightInTop++
            else if (pieceType == "black") darkInTop++
        } else {
            if (pieceType == "white") lightInBottom++
            else if (pieceType == "black") darkInBottom++
        }
    }
    
    val whiteOnBottom = lightInBottom > lightInTop
    Log.d("ChessDetector", "Orientation: ${if (whiteOnBottom) "White" else "Black"} on bottom")
    
    return whiteOnBottom
}

/**
 * Apply UCI mapping
 */
private fun applyUciMapping(
    pieceTypes: Map<Pair<Int, Int>, String>,
    whiteOnBottom: Boolean
): Map<String, String> {
    val files = if (whiteOnBottom) "abcdefgh" else "hgfedcba"
    val ranks = if (whiteOnBottom) "87654321" else "12345678"
    
    val uciMapping = mutableMapOf<String, String>()
    
    for ((location, pieceType) in pieceTypes) {
        val (row, col) = location
        val fileChar = files[col]
        val rankChar = ranks[row]
        val uciSquare = "$fileChar$rankChar"
        uciMapping[uciSquare] = pieceType
    }
    
    return uciMapping
}

/**
 * Generate hardcoded UCI coordinates
 */
private fun getHardcodedUciCoordinates(whiteOnBottom: Boolean): Map<String, android.graphics.Point> {
    val coordinatesMap = mutableMapOf<String, android.graphics.Point>()
    
    if (whiteOnBottom) {
        val files = "abcdefgh"
        val xCoords = listOf(54, 141, 229, 316, 404, 491, 579, 666)
        val yCoords = listOf(547, 634, 721, 809, 896, 983, 1070, 1158)
        
        for (rankIdx in 0 until 8) {
            val rank = 8 - rankIdx
            for (fileIdx in 0 until 8) {
                val file = files[fileIdx]
                coordinatesMap["$file$rank"] = android.graphics.Point(xCoords[fileIdx], yCoords[rankIdx])
            }
        }
    } else {
        val files = "hgfedcba"
        val xCoords = listOf(54, 141, 229, 316, 404, 491, 579, 666)
        val yCoords = listOf(547, 634, 721, 809, 896, 983, 1070, 1158)
        
        for (rankIdx in 0 until 8) {
            val rank = rankIdx + 1
            for (fileIdx in 0 until 8) {
                val file = files[fileIdx]
                coordinatesMap["$file$rank"] = android.graphics.Point(xCoords[fileIdx], yCoords[rankIdx])
            }
        }
    }
    
    return coordinatesMap
}

fun clearUciCache() {
    lastUciCache = null
    Log.d("ChessDetector", "🗑️ UCI cache cleared")
}

// ===============================
// MAIN ENTRY POINTS
// ===============================

/**
 * Get board state from bitmap (first detection)
 */
fun getBoardStateFromBitmap(bitmap: Bitmap, boardName: String, context: Context): BoardState? {
    Log.d("ChessDetector", "🔬 Processing board (IMPROVED Python method)...")
    
    val img = Mat()
    Utils.bitmapToMat(bitmap, img)
    Imgproc.cvtColor(img, img, Imgproc.COLOR_RGBA2BGR)
    
    if (img.empty()) {
        Log.e("ChessDetector", "Could not load image")
        img.release()
        return null
    }
    
    val resized = Mat()
    val aspectRatio = img.width().toDouble() / img.height()
    val newWidth = 900
    val newHeight = (newWidth / aspectRatio).toInt()
    Imgproc.resize(img, resized, Size(newWidth.toDouble(), newHeight.toDouble()))
    img.release()
    
    val innerPts = detectChessboard(resized)
    if (innerPts == null) {
        Log.e("ChessDetector", "No board found")
        resized.release()
        return null
    }
    
    val boardWarped = createWarpedBoard(resized, innerPts)
    resized.release()
    
    // ✅ IMPROVED: Use new Python-based detection
    val pieceSquares = detectPieceSquares(boardWarped)
    val currentSquares = pieceSquares.toSet()
    
    val squareDataList = extractSquareBitmaps(boardWarped, pieceSquares)
    val classifier = PieceColorClassifier(context)
    val pieceTypes = classifyPieceColorsWithCache(squareDataList, classifier, currentSquares)
    classifier.close()
    
    val whiteOnBottom = detectBoardOrientation(pieceSquares, pieceTypes)
    val uciResults = applyUciMapping(pieceTypes, whiteOnBottom)
    
    val lightPieces = mutableSetOf<String>()
    val darkPieces = mutableSetOf<String>()
    val ambiguousPieces = mutableSetOf<String>()
    
    for ((uciSquare, pieceType) in uciResults) {
        when (pieceType) {
            "white" -> lightPieces.add(uciSquare)
            "black" -> darkPieces.add(uciSquare)
            "ambiguous" -> ambiguousPieces.add(uciSquare)
        }
    }
    
    Log.d("ChessDetector", "✅ Final: ${lightPieces.size} white, ${darkPieces.size} black")
    
    // Create annotated image
    val annotated = boardWarped.clone()
    for ((row, col) in pieceSquares) {
        val x1 = col * CELL_SIZE
        val y1 = row * CELL_SIZE
        val x2 = x1 + CELL_SIZE
        val y2 = y1 + CELL_SIZE
        
        val color = when (pieceTypes[Pair(row, col)]) {
            "white" -> Scalar(255.0, 255.0, 255.0)
            "black" -> Scalar(0.0, 0.0, 0.0)
            else -> Scalar(128.0, 128.0, 128.0)
        }
        
        Imgproc.rectangle(annotated, Point(x1.toDouble(), y1.toDouble()), 
            Point(x2.toDouble(), y2.toDouble()), color, 4)
    }
    
    val annotatedBitmap = Bitmap.createBitmap(annotated.cols(), annotated.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(annotated, annotatedBitmap)
    
    annotated.release()
    boardWarped.release()
    
    val uciCoordinates = getHardcodedUciCoordinates(whiteOnBottom)
    
    return BoardState(
        white = lightPieces,
        black = darkPieces,
        ambiguous = ambiguousPieces,
        annotatedBoard = annotatedBitmap,
        boardCorners = innerPts.toArray(),
        whiteOnBottom = whiteOnBottom,
        uciToScreenCoordinates = uciCoordinates,
        detectedSquares = currentSquares
    )
}

/**
 * Get board state with cached corners (with optional debug image saving)
 */
fun getBoardStateFromBitmapWithCachedCorners(
    bitmap: Bitmap,
    cachedCorners: Array<Point>,
    boardName: String,
    cachedWhiteOnBottom: Boolean,
    context: Context,
    saveDebugImage: Boolean = false,
    debugImageCounter: Int = 0
): BoardState? {
    Log.d("ChessDetector", "🚀 Processing with cached corners...")
    
    val img = Mat()
    Utils.bitmapToMat(bitmap, img)
    Imgproc.cvtColor(img, img, Imgproc.COLOR_RGBA2BGR)
    
    if (img.empty()) {
        img.release()
        return null
    }
    
    val resized = Mat()
    val aspectRatio = img.width().toDouble() / img.height()
    val newWidth = 900
    val newHeight = (newWidth / aspectRatio).toInt()
    Imgproc.resize(img, resized, Size(newWidth.toDouble(), newHeight.toDouble()))
    img.release()
    
    val innerPts = MatOfPoint2f(*cachedCorners)
    val boardWarped = createWarpedBoard(resized, innerPts)
    resized.release()
    innerPts.release()
    
    // ✅ IMPROVED: Use new detection method
    val pieceSquares = detectPieceSquares(boardWarped)
    val currentSquares = pieceSquares.toSet()
    
    val squareDataList = extractSquareBitmaps(boardWarped, pieceSquares)
    val classifier = PieceColorClassifier(context)
    val pieceTypes = classifyPieceColorsWithCache(squareDataList, classifier, currentSquares)
    classifier.close()
    
    val uciResults = applyUciMapping(pieceTypes, cachedWhiteOnBottom)
    
    val lightPieces = mutableSetOf<String>()
    val darkPieces = mutableSetOf<String>()
    val ambiguousPieces = mutableSetOf<String>()
    
    for ((uciSquare, pieceType) in uciResults) {
        when (pieceType) {
            "white" -> lightPieces.add(uciSquare)
            "black" -> darkPieces.add(uciSquare)
            "ambiguous" -> ambiguousPieces.add(uciSquare)
        }
    }
    
    // ✅ NEW: Create and save debug annotated image if requested
    if (saveDebugImage) {
        saveAnnotatedDebugImage(
            boardWarped,
            pieceSquares,
            pieceTypes,
            context,
            debugImageCounter,
            lightPieces.size,
            darkPieces.size
        )
    }
    
    boardWarped.release()
    
    return BoardState(
        white = lightPieces,
        black = darkPieces,
        ambiguous = ambiguousPieces,
        annotatedBoard = null,
        boardCorners = null,
        whiteOnBottom = cachedWhiteOnBottom,
        uciToScreenCoordinates = null,
        detectedSquares = currentSquares
    )
}

/**
 * ✅ NEW: Save annotated debug image to gallery
 * Draws white/black/gray rectangles on detected pieces
 * Saves to /storage/emulated/0/Moves/move{N}.jpeg
 */
private fun saveAnnotatedDebugImage(
    boardWarped: Mat,
    pieceSquares: List<Pair<Int, Int>>,
    pieceTypes: Map<Pair<Int, Int>, String>,
    context: Context,
    moveNumber: Int,
    whiteCount: Int,
    blackCount: Int
) {
    try {
        // Create annotated image
        val annotated = boardWarped.clone()
        
        // Draw rectangles for each detected piece
        for ((row, col) in pieceSquares) {
            val x1 = col * CELL_SIZE
            val y1 = row * CELL_SIZE
            val x2 = x1 + CELL_SIZE
            val y2 = y1 + CELL_SIZE
            
            // Color based on piece type
            val color = when (pieceTypes[Pair(row, col)]) {
                "white" -> Scalar(255.0, 255.0, 255.0)  // White
                "black" -> Scalar(0.0, 0.0, 0.0)        // Black
                else -> Scalar(128.0, 128.0, 128.0)     // Gray for ambiguous
            }
            
            // Draw thick rectangle
            Imgproc.rectangle(
                annotated,
                Point(x1.toDouble(), y1.toDouble()),
                Point(x2.toDouble(), y2.toDouble()),
                color,
                6  // Thicker for better visibility
            )
            
            // Add text label showing piece type
            val label = when (pieceTypes[Pair(row, col)]) {
                "white" -> "W"
                "black" -> "B"
                else -> "?"
            }
            
            Imgproc.putText(
                annotated,
                label,
                Point(x1.toDouble() + 10, y1.toDouble() + 30),
                Imgproc.FONT_HERSHEY_SIMPLEX,
                0.8,
                color,
                3
            )
        }
        
        // Add summary text at the top
        val summaryText = "W:$whiteCount B:$blackCount Total:${pieceSquares.size}"
        Imgproc.putText(
            annotated,
            summaryText,
            Point(10.0, 40.0),
            Imgproc.FONT_HERSHEY_SIMPLEX,
            1.2,
            Scalar(255.0, 0.0, 0.0),  // Red text
            3
        )
        
        // Convert Mat to Bitmap
        val annotatedBitmap = Bitmap.createBitmap(
            annotated.cols(),
            annotated.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(annotated, annotatedBitmap)
        annotated.release()
        
        // Save to gallery
        val saved = saveImageToGallery(context, annotatedBitmap, moveNumber)
        annotatedBitmap.recycle()
        
        if (saved) {
            Log.d("ChessDetector", "📸 Debug image saved: move$moveNumber.jpeg")
            Log.d("ChessDetector", "   Location: /storage/emulated/0/Moves/move$moveNumber.jpeg")
            Log.d("ChessDetector", "   Pieces: $whiteCount white, $blackCount black")
        } else {
            Log.e("ChessDetector", "❌ Failed to save debug image")
        }
        
    } catch (e: Exception) {
        Log.e("ChessDetector", "❌ Error creating debug image", e)
    }
}

/**
 * ✅ FIXED: Save bitmap to Pictures/ChessMoves/ using MediaStore (Android 10+)
 */
private fun saveImageToGallery(context: Context, bitmap: Bitmap, moveNumber: Int): Boolean {
    return try {
        val fileName = "move$moveNumber.jpeg"
        
        // Use MediaStore for Android 10+ compatibility
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ChessMoves")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            
            val resolver = context.contentResolver
            val imageUri = resolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            
            if (imageUri != null) {
                resolver.openOutputStream(imageUri)?.use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                }
                
                // Mark as not pending (makes it visible)
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
                
                Log.d("ChessDetector", "✅ Saved to Pictures/ChessMoves/$fileName")
                Log.d("ChessDetector", "   URI: $imageUri")
                true
            } else {
                Log.e("ChessDetector", "❌ Failed to create MediaStore entry")
                false
            }
        } else {
            // Fallback for Android 9 and below
            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val movesDir = java.io.File(picturesDir, "ChessMoves")
            
            if (!movesDir.exists()) {
                val created = movesDir.mkdirs()
                Log.d("ChessDetector", "📁 Created directory: $created")
            }
            
            val file = java.io.File(movesDir, fileName)
            val outputStream = java.io.FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.flush()
            outputStream.close()
            
            // Notify media scanner
            val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = android.net.Uri.fromFile(file)
            context.sendBroadcast(intent)
            
            Log.d("ChessDetector", "✅ Saved: ${file.absolutePath}")
            true
        }
    } catch (e: Exception) {
        Log.e("ChessDetector", "❌ Error saving image: ${e.message}", e)
        e.printStackTrace()
        false
    }
}