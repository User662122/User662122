package com.chessmove.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

// ===============================
// CONSTANTS AND CONFIGURATION
// ===============================
const val BOARD_SIZE = 800
const val CELL_SIZE = BOARD_SIZE / 8
const val PIECE_THRESHOLD = 15.0  // ✅ Matches Python exactly
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
    
    val kernel = Mat.ones(3, 3, CvType.CV_8U)  // ✅ Proper kernel
    Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
    Imgproc.erode(edges, edges, kernel, Point(-1.0, -1.0), 1)
    kernel.release()
    
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
 * ✅ FIXED: Detect pieces using EXACT Python logic
 * Now matches Python 100%: invert -> grayscale -> Canny -> dilate
 */
private fun detectPieceSquares(boardWarped: Mat): List<Pair<Int, Int>> {
    // ✅ Step 1: Invert (matches Python: cv2.bitwise_not)
    val inverted = Mat()
    Core.bitwise_not(boardWarped, inverted)
    
    // ✅ Step 2: Convert to grayscale (matches Python: cv2.cvtColor)
    val grayInverted = Mat()
    Imgproc.cvtColor(inverted, grayInverted, Imgproc.COLOR_BGR2GRAY)
    inverted.release()
    
    // ✅ Step 3: Canny edges (matches Python: cv2.Canny(gray_inverted, 50, 150))
    val edgesInverted = Mat()
    Imgproc.Canny(grayInverted, edgesInverted, 50.0, 150.0)
    grayInverted.release()
    
    // ✅ Step 4: Create proper 3x3 kernel of ones (CRITICAL FIX!)
    val kernel = Mat.ones(3, 3, CvType.CV_8U)
    
    // ✅ Step 5: Dilate with iterations=1 (matches Python: cv2.dilate(..., iterations=1))
    val dilatedInverted = Mat()
    Imgproc.dilate(edgesInverted, dilatedInverted, kernel, Point(-1.0, -1.0), 1)
    edgesInverted.release()
    kernel.release()
    
    // ✅ Step 6: Check each square with 15% threshold
    val pieceSquares = mutableListOf<Pair<Int, Int>>()
    
    for (row in 0 until 8) {
        for (col in 0 until 8) {
            val y1 = row * CELL_SIZE
            val y2 = (row + 1) * CELL_SIZE
            val x1 = col * CELL_SIZE
            val x2 = (col + 1) * CELL_SIZE
            
            val square = dilatedInverted.submat(y1, y2, x1, x2)
            val totalPixels = square.total().toDouble()
            val nonZeroPixels = Core.countNonZero(square).toDouble()
            val percentage = (nonZeroPixels / totalPixels) * 100.0
            
            // ✅ EXACT Python logic: if percentage > 15.0
            if (percentage > PIECE_THRESHOLD) {
                pieceSquares.add(Pair(row, col))
            }
            
            square.release()
        }
    }
    
    dilatedInverted.release()
    
    Log.d("ChessDetector", "✅ Detected ${pieceSquares.size} pieces (15% threshold)")
    
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
 * Classify piece colors with TFLite (NO CACHING)
 */
private fun classifyPieceColors(
    squareDataList: List<SquareData>,
    classifier: PieceColorClassifier
): Map<Pair<Int, Int>, String> {
    
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
 * Apply UCI mapping with validation
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
        
        // ✅ Validate row and col are within board bounds
        if (row !in 0..7 || col !in 0..7) {
            Log.w("ChessDetector", "⚠️ Invalid position: row=$row, col=$col")
            continue
        }
        
        val fileChar = files[col]
        val rankChar = ranks[row]
        val uciSquare = "$fileChar$rankChar"
        
        // ✅ Double-check UCI is valid
        if (isValidUciSquare(uciSquare)) {
            uciMapping[uciSquare] = pieceType
        } else {
            Log.w("ChessDetector", "⚠️ Generated invalid UCI: $uciSquare")
        }
    }
    
    return uciMapping
}

/**
 * Generate hardcoded UCI coordinates for screen tapping
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
 * ✅ ONLY USED FOR FIRST DETECTION (orientation + corners + UCI coordinates)
 */
fun getBoardStateFromBitmap(bitmap: Bitmap, boardName: String, context: Context): BoardState? {
    Log.d("ChessDetector", "🔬 First detection (corner detection + orientation)...")
    
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
    
    val pieceSquares = detectPieceSquares(boardWarped)
    val squareDataList = extractSquareBitmaps(boardWarped, pieceSquares)
    val classifier = PieceColorClassifier(context)
    val pieceTypes = classifyPieceColors(squareDataList, classifier)
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
    
    // ✅ Filter valid UCI squares
    val validWhite = filterValidUciSquares(lightPieces)
    val validBlack = filterValidUciSquares(darkPieces)
    val validAmbiguous = filterValidUciSquares(ambiguousPieces)
    
    Log.d("ChessDetector", "✅ First detection: ${validWhite.size}W, ${validBlack.size}B")
    
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
        white = validWhite,
        black = validBlack,
        ambiguous = validAmbiguous,
        annotatedBoard = annotatedBitmap,
        boardCorners = innerPts.toArray(),
        whiteOnBottom = whiteOnBottom,
        uciToScreenCoordinates = uciCoordinates
    )
}

/**
 * ✅ MAIN WORKHORSE - Used for ALL subsequent detections
 */
fun getBoardStateFromBitmapDirectly(
    boardBitmap: Bitmap,
    boardName: String,
    whiteOnBottom: Boolean,
    context: Context,
    saveDebugImage: Boolean = false,
    debugImageCounter: Int = 0
): BoardState? {
    Log.d("ChessDetector", "🚀 Direct detection (pre-cropped, cached orientation)...")
    
    val img = Mat()
    Utils.bitmapToMat(boardBitmap, img)
    Imgproc.cvtColor(img, img, Imgproc.COLOR_RGBA2BGR)
    
    if (img.empty()) {
        img.release()
        return null
    }
    
    // Resize to standard 800x800
    val boardWarped = Mat()
    Imgproc.resize(img, boardWarped, Size(BOARD_SIZE.toDouble(), BOARD_SIZE.toDouble()))
    img.release()
    
    // Detect pieces
    val pieceSquares = detectPieceSquares(boardWarped)
    
    // Classify colors with TFLite
    val squareDataList = extractSquareBitmaps(boardWarped, pieceSquares)
    val classifier = PieceColorClassifier(context)
    val pieceTypes = classifyPieceColors(squareDataList, classifier)
    classifier.close()
    
    // Apply UCI mapping with cached orientation
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
    
    // ✅ Filter valid UCI squares
    val validWhite = filterValidUciSquares(lightPieces)
    val validBlack = filterValidUciSquares(darkPieces)
    val validAmbiguous = filterValidUciSquares(ambiguousPieces)
    
    Log.d("ChessDetector", "✅ Detection: ${validWhite.size}W + ${validBlack.size}B")
    
    // Save debug image if requested
    if (saveDebugImage) {
        saveAnnotatedDebugImage(
            boardWarped,
            pieceSquares,
            pieceTypes,
            context,
            debugImageCounter,
            validWhite.size,
            validBlack.size
        )
    }
    
    boardWarped.release()
    
    return BoardState(
        white = validWhite,
        black = validBlack,
        ambiguous = validAmbiguous,
        annotatedBoard = null,
        boardCorners = null,
        whiteOnBottom = whiteOnBottom,
        uciToScreenCoordinates = null
    )
}

// ===============================
// DEBUG IMAGE SAVING
// ===============================

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
            
            Imgproc.rectangle(
                annotated,
                Point(x1.toDouble(), y1.toDouble()),
                Point(x2.toDouble(), y2.toDouble()),
                color,
                6
            )
            
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
        
        val summaryText = "W:$whiteCount B:$blackCount Total:${pieceSquares.size}"
        Imgproc.putText(
            annotated,
            summaryText,
            Point(10.0, 40.0),
            Imgproc.FONT_HERSHEY_SIMPLEX,
            1.2,
            Scalar(255.0, 0.0, 0.0),
            3
        )
        
        val annotatedBitmap = Bitmap.createBitmap(
            annotated.cols(),
            annotated.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(annotated, annotatedBitmap)
        annotated.release()
        
        val saved = saveImageToGallery(context, annotatedBitmap, moveNumber)
        annotatedBitmap.recycle()
        
        if (saved) {
            Log.d("ChessDetector", "📸 Debug image saved: move$moveNumber.jpeg")
        }
        
    } catch (e: Exception) {
        Log.e("ChessDetector", "❌ Error creating debug image", e)
    }
}

private fun saveImageToGallery(context: Context, bitmap: Bitmap, moveNumber: Int): Boolean {
    return try {
        val fileName = "move$moveNumber.jpeg"
        
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
                
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(imageUri, contentValues, null, null)
                
                true
            } else {
                false
            }
        } else {
            val picturesDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_PICTURES
            )
            val movesDir = java.io.File(picturesDir, "ChessMoves")
            
            if (!movesDir.exists()) {
                movesDir.mkdirs()
            }
            
            val file = java.io.File(movesDir, fileName)
            val outputStream = java.io.FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.flush()
            outputStream.close()
            
            val intent = android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = android.net.Uri.fromFile(file)
            context.sendBroadcast(intent)
            
            true
        }
    } catch (e: Exception) {
        Log.e("ChessDetector", "❌ Error saving image: ${e.message}", e)
        false
    }
}