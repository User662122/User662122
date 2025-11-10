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
 * Detect pieces using image processing pipeline
 */
private fun detectPieces(boardWarped: Mat): Pair<List<Pair<Int, Int>>, Mat> {
    // Preprocessing
    val inverted = Mat()
    Core.bitwise_not(boardWarped, inverted)
    
    val grayInverted = Mat()
    Imgproc.cvtColor(inverted, grayInverted, Imgproc.COLOR_BGR2GRAY)
    
    val edgesInverted = Mat()
    Imgproc.Canny(grayInverted, edgesInverted, 50.0, 150.0)
    
    val kernel = Mat.ones(3, 3, CvType.CV_8U)
    val processedImage = Mat()
    Imgproc.dilate(edgesInverted, processedImage, kernel, Point(-1.0, -1.0), 1)
    
    // Piece detection
    val pieceLocations = mutableListOf<Pair<Int, Int>>()
    
    for (row in 0 until 8) {
        for (col in 0 until 8) {
            val y1 = row * CELL_SIZE
            val y2 = (row + 1) * CELL_SIZE
            val x1 = col * CELL_SIZE
            val x2 = (col + 1) * CELL_SIZE
            
            val square = processedImage.submat(y1, y2, x1, x2)
            val totalPixels = square.total().toDouble()
            val nonZeroPixels = Core.countNonZero(square).toDouble()
            val percentage = (nonZeroPixels / totalPixels) * 100
            
            if (percentage > PIECE_THRESHOLD) {
                pieceLocations.add(Pair(row, col))
            }
            
            square.release()
        }
    }
    
    inverted.release()
    grayInverted.release()
    edgesInverted.release()
    kernel.release()
    
    return Pair(pieceLocations, processedImage)
}

/**
 * Classify pieces as light or dark using sampling method
 */
private fun classifyPieceColors(
    boardWarped: Mat,
    piecesFound: List<Pair<Int, Int>>
): Pair<Map<Pair<Int, Int>, String>, Mat> {
    val grayBoard = Mat()
    Imgproc.cvtColor(boardWarped, grayBoard, Imgproc.COLOR_BGR2GRAY)
    
    // Clean the image
    val binary = Mat()
    Imgproc.threshold(grayBoard, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
    
    val inv = Mat()
    Core.bitwise_not(binary, inv)
    
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
    val closed = Mat()
    Imgproc.morphologyEx(inv, closed, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)
    
    val opened = Mat()
    Imgproc.morphologyEx(closed, opened, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), 2)
    
    val cleanedImage = Mat()
    Core.bitwise_not(opened, cleanedImage)
    
    // Remove small blobs
    val contours = ArrayList<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(cleanedImage, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
    
    for (contour in contours) {
        val area = Imgproc.contourArea(contour)
        if (area < 300) {
            Imgproc.drawContours(cleanedImage, listOf(contour), -1, Scalar(0.0), -1)
        }
    }
    
    // Classify pieces
    val pieceTypes = mutableMapOf<Pair<Int, Int>, String>()
    val height = cleanedImage.rows()
    val width = cleanedImage.cols()
    
    for ((row, col) in piecesFound) {
        val y1 = row * CELL_SIZE
        val x1 = col * CELL_SIZE
        
        // Sample from piece center
        val centerY = y1 + CELL_SIZE / 2 + 13  // y_offset
        val centerX = x1 + CELL_SIZE / 2 - 4   // x_offset
        
        val yStart = max(centerY - 2, 0)
        val yEnd = min(centerY + 2, height)
        val xStart = max(centerX - 7, 0)
        val xEnd = min(centerX + 7, width)
        
        val region = cleanedImage.submat(yStart, yEnd, xStart, xEnd)
        
        // Classify based on pixel ratio
        var lightPixels = 0
        var darkPixels = 0
        
        for (r in 0 until region.rows()) {
            for (c in 0 until region.cols()) {
                val pixel = region.get(r, c)[0]
                if (pixel > 127) {
                    lightPixels++
                } else {
                    darkPixels++
                }
            }
        }
        
        val ratio = lightPixels.toDouble() / (darkPixels.toDouble() + 1e-5)
        
        val pieceType = when {
            ratio > 1.2 -> "light"
            ratio < 0.8 -> "dark"
            else -> "ambiguous"
        }
        
        pieceTypes[Pair(row, col)] = pieceType
        region.release()
    }
    
    grayBoard.release()
    binary.release()
    inv.release()
    kernel.release()
    closed.release()
    opened.release()
    hierarchy.release()
    
    return Pair(pieceTypes, cleanedImage)
}

/**
 * Detect board orientation by counting light/dark pieces in top vs bottom half
 */
private fun detectBoardOrientation(
    piecesFound: List<Pair<Int, Int>>,
    pieceTypes: Map<Pair<Int, Int>, String>
): Boolean {
    // Count light and dark pieces in top 4 rows (rows 0-3)
    var lightInTop = 0
    var darkInTop = 0
    
    // Count light and dark pieces in bottom 4 rows (rows 4-7)
    var lightInBottom = 0
    var darkInBottom = 0
    
    for ((row, col) in piecesFound) {
        val pieceType = pieceTypes[Pair(row, col)] ?: continue
        
        if (pieceType == "ambiguous") continue // Skip ambiguous pieces
        
        if (row < 4) {
            // Top 4 rows
            if (pieceType == "light") lightInTop++
            else if (pieceType == "dark") darkInTop++
        } else {
            // Bottom 4 rows
            if (pieceType == "light") lightInBottom++
            else if (pieceType == "dark") darkInBottom++
        }
    }
    
    Log.d("ChessDetector", "Orientation detection:")
    Log.d("ChessDetector", "  Top 4 rows: $lightInTop light, $darkInTop dark")
    Log.d("ChessDetector", "  Bottom 4 rows: $lightInBottom light, $darkInBottom dark")
    
    // If more light pieces in bottom, then white is on bottom
    // If more light pieces in top, then white is on top (black on bottom)
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
 * Get board state from bitmap (first detection)
 */
fun getBoardStateFromBitmap(bitmap: Bitmap, boardName: String): BoardState? {
    Log.d("ChessDetector", "Processing board (NEW DETECTION LOGIC)...")
    
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
    
    // Detect pieces
    val (piecesFound, processedImg) = detectPieces(boardWarped)
    Log.d("ChessDetector", "Found ${piecesFound.size} potential pieces")
    
    // Classify piece colors
    val (pieceTypes, cleanedImg) = classifyPieceColors(boardWarped, piecesFound)
    
    // Detect board orientation by counting pieces in top 4 rows vs bottom 4 rows
    val whiteOnBottom = detectBoardOrientation(piecesFound, pieceTypes)
    
    Log.d("ChessDetector", "Board orientation: whiteOnBottom=$whiteOnBottom")
    
    // Apply UCI mapping
    val uciResults = applyUciMapping(pieceTypes, whiteOnBottom)
    
    // Separate by color
    val lightPieces = mutableSetOf<String>()
    val darkPieces = mutableSetOf<String>()
    val ambiguousPieces = mutableSetOf<String>()
    
    for ((uciSquare, pieceType) in uciResults) {
        when (pieceType) {
            "light" -> lightPieces.add(uciSquare)
            "dark" -> darkPieces.add(uciSquare)
            "ambiguous" -> ambiguousPieces.add(uciSquare)
        }
    }
    
    Log.d("ChessDetector", "✅ Detected: ${lightPieces.size} light, ${darkPieces.size} dark, ${ambiguousPieces.size} ambiguous pieces")
    Log.d("ChessDetector", "Light pieces: ${lightPieces.sorted().joinToString(", ")}")
    Log.d("ChessDetector", "Dark pieces: ${darkPieces.sorted().joinToString(", ")}")
    
    // Create annotated image
    val annotated = boardWarped.clone()
    for ((row, col) in piecesFound) {
        val x1 = col * CELL_SIZE
        val y1 = row * CELL_SIZE
        val x2 = x1 + CELL_SIZE
        val y2 = y1 + CELL_SIZE
        
        val color = when (pieceTypes[Pair(row, col)]) {
            "light" -> Scalar(255.0, 255.0, 255.0)
            "dark" -> Scalar(0.0, 0.0, 0.0)
            else -> Scalar(128.0, 128.0, 128.0)
        }
        
        Imgproc.rectangle(annotated, Point(x1.toDouble(), y1.toDouble()), 
            Point(x2.toDouble(), y2.toDouble()), color, 4)
    }
    
    val annotatedBitmap = Bitmap.createBitmap(annotated.cols(), annotated.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(annotated, annotatedBitmap)
    
    annotated.release()
    boardWarped.release()
    processedImg.release()
    cleanedImg.release()
    
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
 * Get board state with cached corners (optimized for continuous capture)
 */
fun getBoardStateFromBitmapWithCachedCorners(
    bitmap: Bitmap,
    cachedCorners: Array<Point>,
    boardName: String,
    cachedWhiteOnBottom: Boolean
): BoardState? {
    Log.d("ChessDetector", "Processing board with cached corners (NEW DETECTION LOGIC)...")
    
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
    
    // Detect pieces
    val (piecesFound, processedImg) = detectPieces(boardWarped)
    
    // Classify piece colors
    val (pieceTypes, cleanedImg) = classifyPieceColors(boardWarped, piecesFound)
    
    // Apply UCI mapping
    val uciResults = applyUciMapping(pieceTypes, cachedWhiteOnBottom)
    
    // Separate by color
    val lightPieces = mutableSetOf<String>()
    val darkPieces = mutableSetOf<String>()
    val ambiguousPieces = mutableSetOf<String>()
    
    for ((uciSquare, pieceType) in uciResults) {
        when (pieceType) {
            "light" -> lightPieces.add(uciSquare)
            "dark" -> darkPieces.add(uciSquare)
            "ambiguous" -> ambiguousPieces.add(uciSquare)
        }
    }
    
    Log.d("ChessDetector", "✅ Detected (cached): ${lightPieces.size} light, ${darkPieces.size} dark pieces")
    
    boardWarped.release()
    processedImg.release()
    cleanedImg.release()
    
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