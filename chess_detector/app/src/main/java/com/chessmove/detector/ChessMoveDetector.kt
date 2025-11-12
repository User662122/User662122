package com.chessmove.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.OpenCVLoader
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
const val PIECE_THRESHOLD = 15.0
const val SHRINK_FACTOR = 0.962

data class BoardState(
    val white: Set<String>,
    val black: Set<String>,
    val ambiguous: Set<String> = emptySet(),
    val annotatedBoard: Bitmap? = null,
    val boardCorners: Array<Point>? = null,
    val whiteOnBottom: Boolean? = null,
    val uciToScreenCoordinates: Map<String, android.graphics.Point>? = null
)

// Square data for batch processing
data class SquareData(
    val row: Int,
    val col: Int,
    val bitmap: Bitmap
)

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
    
    // Fallback to largest contour
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

/**
 * Shrink polygon towards center
 */
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

/**
 * Create warped top-down view of chessboard
 */
private fun createWarpedBoard(img: Mat, innerPts: MatOfPoint2f): Mat {
    val pts = innerPts.toArray()
    
    // Order points: top-left, top-right, bottom-right, bottom-left
    val sumArr = pts.map { it.x + it.y }
    val diffArr = pts.map { it.y - it.x }
    
    val ordered = arrayOf(
        pts[sumArr.indexOf(sumArr.minOrNull()!!)],      // top-left
        pts[diffArr.indexOf(diffArr.minOrNull()!!)],    // top-right
        pts[sumArr.indexOf(sumArr.maxOrNull()!!)],      // bottom-right
        pts[diffArr.indexOf(diffArr.maxOrNull()!!)]     // bottom-left
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
 * Detect pieces using edge detection (OpenCV part)
 */
private fun detectPieceSquares(boardWarped: Mat): List<Pair<Int, Int>> {
    val grayBoard = Mat()
    Imgproc.cvtColor(boardWarped, grayBoard, Imgproc.COLOR_BGR2GRAY)
    
    val edges = Mat()
    Imgproc.Canny(grayBoard, edges, 50.0, 150.0)
    
    val kernel = Mat.ones(3, 3, CvType.CV_8U)
    Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 1)
    
    val pieceSquares = mutableListOf<Pair<Int, Int>>()
    
    for (row in 0 until 8) {
        for (col in 0 until 8) {
            val y1 = row * CELL_SIZE
            val y2 = (row + 1) * CELL_SIZE
            val x1 = col * CELL_SIZE
            val x2 = (col + 1) * CELL_SIZE
            
            val square = edges.submat(y1, y2, x1, x2)
            val totalPixels = square.total().toDouble()
            val nonZeroPixels = Core.countNonZero(square).toDouble()
            val percentage = (nonZeroPixels / totalPixels) * 100
            
            if (percentage > PIECE_THRESHOLD) {
                pieceSquares.add(Pair(row, col))
            }
            
            square.release()
        }
    }
    
    grayBoard.release()
    edges.release()
    kernel.release()
    
    Log.d("ChessDetector", "✅ OpenCV detected ${pieceSquares.size} potential pieces")
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
 * Classify piece colors using TFLite model in batches
 */
private fun classifyPieceColors(
    squareDataList: List<SquareData>,
    classifier: PieceColorClassifier
): Map<Pair<Int, Int>, String> {
    val startTime = System.currentTimeMillis()
    
    // Extract bitmaps for batch processing
    val bitmaps = squareDataList.map { it.bitmap }
    
    // Batch inference with TFLite
    val colors = classifier.classifyBatch(bitmaps)
    
    val elapsedTime = System.currentTimeMillis() - startTime
    Log.d("ChessDetector", "🤖 TFLite classified ${colors.size} pieces in ${elapsedTime}ms (${elapsedTime.toFloat() / colors.size}ms/piece)")
    
    // Map results back to (row, col) positions
    val pieceTypes = mutableMapOf<Pair<Int, Int>, String>()
    for ((index, squareData) in squareDataList.withIndex()) {
        pieceTypes[Pair(squareData.row, squareData.col)] = colors[index]
        
        // Clean up bitmap
        squareData.bitmap.recycle()
    }
    
    return pieceTypes
}

/**
 * Detect board orientation by counting light/dark pieces in top vs bottom half
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
    
    Log.d("ChessDetector", "Orientation detection:")
    Log.d("ChessDetector", "  Top 4 rows: $lightInTop white, $darkInTop black")
    Log.d("ChessDetector", "  Bottom 4 rows: $lightInBottom white, $darkInBottom black")
    
    val whiteOnBottom = lightInBottom > lightInTop
    
    Log.d("ChessDetector", "  Decision: ${if (whiteOnBottom) "White on bottom" else "Black on bottom"}")
    
    return whiteOnBottom
}

/**
 * Apply UCI mapping to piece classification results
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
 * Generate hardcoded UCI coordinates for auto-tap
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

// ===============================
// MAIN ENTRY POINTS
// ===============================

/**
 * Get board state from bitmap (first detection) - HYBRID APPROACH
 */
fun getBoardStateFromBitmap(bitmap: Bitmap, boardName: String, context: Context): BoardState? {
    Log.d("ChessDetector", "🔬 Processing board (HYBRID: OpenCV + TFLite)...")
    
    val img = Mat()
    Utils.bitmapToMat(bitmap, img)
    Imgproc.cvtColor(img, img, Imgproc.COLOR_RGBA2BGR)
    
    if (img.empty()) {
        Log.e("ChessDetector", "Could not load image")
        img.release()
        return null
    }
    
    // Resize image
    val resized = Mat()
    val aspectRatio = img.width().toDouble() / img.height()
    val newWidth = 900
    val newHeight = (newWidth / aspectRatio).toInt()
    Imgproc.resize(img, resized, Size(newWidth.toDouble(), newHeight.toDouble()))
    img.release()
    
    Log.d("ChessDetector", "Resized to: ${newWidth} x ${newHeight}")
    
    // Detect chessboard
    val innerPts = detectChessboard(resized)
    if (innerPts == null) {
        Log.e("ChessDetector", "No board found")
        resized.release()
        return null
    }
    
    val pts = innerPts.toArray()
    Log.d("ChessDetector", "Board corners detected: ${pts.size} points")
    
    // Create warped board
    val boardWarped = createWarpedBoard(resized, innerPts)
    resized.release()
    
    // STEP 1: OpenCV detects which squares have pieces
    val pieceSquares = detectPieceSquares(boardWarped)
    Log.d("ChessDetector", "📊 OpenCV found ${pieceSquares.size} potential pieces")
    
    // STEP 2: Extract square bitmaps for TFLite
    val squareDataList = extractSquareBitmaps(boardWarped, pieceSquares)
    
    // STEP 3: TFLite classifies colors in batches
    val classifier = PieceColorClassifier(context)
    val pieceTypes = classifyPieceColors(squareDataList, classifier)
    classifier.close()
    
    // Detect board orientation
    val whiteOnBottom = detectBoardOrientation(pieceSquares, pieceTypes)
    
    Log.d("ChessDetector", "Board orientation: whiteOnBottom=$whiteOnBottom")
    
    // Apply UCI mapping
    val uciResults = applyUciMapping(pieceTypes, whiteOnBottom)
    
    // Separate by color
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
    
    Log.d("ChessDetector", "✅ Detected: ${lightPieces.size} white, ${darkPieces.size} black, ${ambiguousPieces.size} ambiguous pieces")
    Log.d("ChessDetector", "White pieces: ${lightPieces.sorted().joinToString(", ")}")
    Log.d("ChessDetector", "Black pieces: ${darkPieces.sorted().joinToString(", ")}")
    
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
    
    // Generate UCI coordinates
    val uciCoordinates = getHardcodedUciCoordinates(whiteOnBottom)
    
    return BoardState(
        white = lightPieces,
        black = darkPieces,
        ambiguous = ambiguousPieces,
        annotatedBoard = annotatedBitmap,
        boardCorners = pts,
        whiteOnBottom = whiteOnBottom,
        uciToScreenCoordinates = uciCoordinates
    )
}

/**
 * Get board state with cached corners (optimized for continuous capture) - HYBRID
 */
fun getBoardStateFromBitmapWithCachedCorners(
    bitmap: Bitmap,
    cachedCorners: Array<Point>,
    boardName: String,
    cachedWhiteOnBottom: Boolean,
    context: Context
): BoardState? {
    Log.d("ChessDetector", "🚀 Processing with cached corners (HYBRID)...")
    
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
    
    // Create warped board using cached corners
    val innerPts = MatOfPoint2f(*cachedCorners)
    val boardWarped = createWarpedBoard(resized, innerPts)
    resized.release()
    innerPts.release()
    
    // STEP 1: OpenCV detects pieces
    val pieceSquares = detectPieceSquares(boardWarped)
    
    // STEP 2: Extract square bitmaps
    val squareDataList = extractSquareBitmaps(boardWarped, pieceSquares)
    
    // STEP 3: TFLite classifies colors
    val classifier = PieceColorClassifier(context)
    val pieceTypes = classifyPieceColors(squareDataList, classifier)
    classifier.close()
    
    // Apply UCI mapping
    val uciResults = applyUciMapping(pieceTypes, cachedWhiteOnBottom)
    
    // Separate by color
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
    
    Log.d("ChessDetector", "✅ Detected (cached): ${lightPieces.size} white, ${darkPieces.size} black pieces")
    
    boardWarped.release()
    
    return BoardState(
        white = lightPieces,
        black = darkPieces,
        ambiguous = ambiguousPieces,
        annotatedBoard = null,
        boardCorners = null,
        whiteOnBottom = cachedWhiteOnBottom,
        uciToScreenCoordinates = null
    )
}