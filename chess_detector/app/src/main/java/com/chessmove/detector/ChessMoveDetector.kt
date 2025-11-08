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

// SIGNIFICANTLY MORE RELAXED - Detect pieces much easier
const val WHITE_DETECTION_SENSITIVITY = 115  // Increased from 100 (more relaxed)
const val BLACK_DETECTION_SENSITIVITY = 35   // Decreased from 45 (more relaxed)
const val EMPTY_DETECTION_SENSITIVITY = 65   // Increased from 55 (more relaxed)

data class BoardState(
    val white: Set<String>,
    val black: Set<String>,
    val annotatedBoard: Bitmap? = null,
    val boardCorners: Array<Point>? = null,
    val whiteOnBottom: Boolean? = null,
    val uciToScreenCoordinates: Map<String, android.graphics.Point>? = null
)

private fun normalizeSensitivity(sensitivity: Int): Double {
    return if (sensitivity <= 100) {
        sensitivity.toDouble()
    } else {
        100.0 + (sensitivity - 100) * 0.5
    }
}

private fun shrinkPolygon(pts: MatOfPoint2f, shrinkFactor: Double = 0.95): MatOfPoint2f {
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

// ✅ HARDCODED UCI COORDINATES for both orientations
private fun getHardcodedUciCoordinates(whiteOnBottom: Boolean): Map<String, android.graphics.Point> {
    val coordinatesMap = mutableMapOf<String, android.graphics.Point>()
    
    if (whiteOnBottom) {
        // White on bottom (coordinates as provided)
        val files = "abcdefgh"
        val xCoords = listOf(54, 141, 229, 316, 404, 491, 579, 666)
        
        // Rank 8 (top)
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}8"] = android.graphics.Point(xCoords[i], 547)
        }
        
        // Rank 7
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}7"] = android.graphics.Point(xCoords[i], 634)
        }
        
        // Rank 6
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}6"] = android.graphics.Point(xCoords[i], 721)
        }
        
        // Rank 5
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}5"] = android.graphics.Point(xCoords[i], 809)
        }
        
        // Rank 4
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}4"] = android.graphics.Point(xCoords[i], 896)
        }
        
        // Rank 3
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}3"] = android.graphics.Point(xCoords[i], 983)
        }
        
        // Rank 2
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}2"] = android.graphics.Point(xCoords[i], 1070)
        }
        
        // Rank 1 (bottom)
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}1"] = android.graphics.Point(xCoords[i], 1158)
        }
    } else {
        // Black on bottom (board flipped 180°)
        val files = "hgfedcba"  // Reversed files
        val xCoords = listOf(54, 141, 229, 316, 404, 491, 579, 666)
        
        // Rank 1 (top when black on bottom)
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}1"] = android.graphics.Point(xCoords[i], 547)
        }
        
        // Rank 2
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}2"] = android.graphics.Point(xCoords[i], 634)
        }
        
        // Rank 3
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}3"] = android.graphics.Point(xCoords[i], 721)
        }
        
        // Rank 4
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}4"] = android.graphics.Point(xCoords[i], 809)
        }
        
        // Rank 5
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}5"] = android.graphics.Point(xCoords[i], 896)
        }
        
        // Rank 6
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}6"] = android.graphics.Point(xCoords[i], 983)
        }
        
        // Rank 7
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}7"] = android.graphics.Point(xCoords[i], 1070)
        }
        
        // Rank 8 (bottom when black on bottom)
        files.forEachIndexed { i, file ->
            coordinatesMap["${file}8"] = android.graphics.Point(xCoords[i], 1158)
        }
    }
    
    Log.d("ChessDetector", "📍 Generated ${coordinatesMap.size} hardcoded coordinates (whiteOnBottom=$whiteOnBottom)")
    
    // Log sample coordinates for verification
    coordinatesMap.entries.take(5).forEach { (uci, point) ->
        Log.d("ChessDetector", "  $uci -> (${point.x}, ${point.y})")
    }
    
    return coordinatesMap
}

private fun detectLargestSquareLike(img: Mat): MatOfPoint2f? {
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
    var bestArea = 0.0
    
    for (contour in contours) {
        val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)
        val area = Imgproc.contourArea(contour)
        
        if (approx.toArray().size == 4 && area > 0.2 * imgArea) {
            val rect = Imgproc.boundingRect(contour)
            val ratio = rect.width.toDouble() / rect.height.toDouble()
            
            if (ratio > 0.7 && ratio < 1.3 && area > bestArea) {
                bestContour = approx
                bestArea = area
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
        shrinkPolygon(bestContour, 0.962)
    } else null
}

private fun orderPoints(pts: Array<Point>): Array<Point> {
    val sumArr = pts.map { it.x + it.y }
    val diffArr = pts.map { it.y - it.x }
    
    return arrayOf(
        pts[sumArr.indexOf(sumArr.minOrNull()!!)],
        pts[diffArr.indexOf(diffArr.minOrNull()!!)],
        pts[sumArr.indexOf(sumArr.maxOrNull()!!)],
        pts[diffArr.indexOf(diffArr.maxOrNull()!!)]
    )
}

private fun detectPiecesOnBoard(
    grayBoard: Mat,
    boardWarped: Mat,
    files: String,
    ranks: String,
    cellSize: Int
): BoardState {
    val whiteSquares = mutableListOf<String>()
    val blackSquares = mutableListOf<String>()
    val annotated = boardWarped.clone()
    
    val whiteNorm = normalizeSensitivity(WHITE_DETECTION_SENSITIVITY)
    val blackNorm = normalizeSensitivity(BLACK_DETECTION_SENSITIVITY)
    val emptyNorm = normalizeSensitivity(EMPTY_DETECTION_SENSITIVITY)
    
    // MUCH MORE RELAXED THRESHOLDS
    val posBrightDiff = max(0.5, 20 - (whiteNorm * 0.25))
    val minWhiteStd = max(0.5, 25 - (whiteNorm * 0.3))
    val whiteEdgeBoost = whiteNorm * 0.4
    val whiteBrightnessThresh = max(90.0, 190 - (whiteNorm * 0.6))
    val negBrightDiff = min(-0.5, -8 - (blackNorm * 0.25))
    val blackStdThresh = max(0.5, 4 + (blackNorm * 0.08))
    val blackEdgeBoost = blackNorm * 0.4
    val edgeCountThresh = max(3.0, 70 - (emptyNorm * 0.7))
    val stdBgThresh = max(0.5, 4 + (emptyNorm * 0.12))
    val maxEmptyStd = max(12.0, 18 + (emptyNorm * 0.3))
    val emptyEdgeThresh = max(0.5, 18 - (emptyNorm * 0.18))
    
    for (r in 0 until 8) {
        for (c in 0 until 8) {
            val x1 = c * cellSize
            val y1 = r * cellSize
            val x2 = x1 + cellSize
            val y2 = y1 + cellSize
            
            val square = grayBoard.submat(y1, y2, x1, x2)
            if (square.rows() == 0 || square.cols() == 0) continue
            
            val m = max(1, (cellSize * 0.25).toInt())
            val inner = square.submat(m, cellSize - m, m, cellSize - m)
            if (inner.total() == 0L) continue
            
            val innerMean = Core.mean(inner).`val`[0]
            val innerMeanScalar = MatOfDouble(innerMean)
            val innerStdDev = MatOfDouble()
            Core.meanStdDev(inner, innerMeanScalar, innerStdDev)
            val innerStd = innerStdDev.get(0, 0)[0]
            
            val p = max(2, (cellSize * 0.15).toInt())
            val patchCoords = listOf(
                intArrayOf(0, 0, p, p),
                intArrayOf(0, cellSize - p, p, cellSize),
                intArrayOf(cellSize - p, 0, cellSize, p),
                intArrayOf(cellSize - p, cellSize - p, cellSize, cellSize)
            )
            
            val patchMeans = mutableListOf<Double>()
            val patchStds = mutableListOf<Double>()
            
            for ((ya, xa, yb, xb) in patchCoords) {
                val patch = square.submat(ya, yb, xa, xb)
                if (patch.total() > 0) {
                    patchMeans.add(Core.mean(patch).`val`[0])
                    val pMean = MatOfDouble()
                    val pStd = MatOfDouble()
                    Core.meanStdDev(patch, pMean, pStd)
                    patchStds.add(pStd.get(0, 0)[0])
                    pMean.release()
                    pStd.release()
                }
                patch.release()
            }
            
            val localBg = if (patchMeans.isNotEmpty()) patchMeans.sorted()[patchMeans.size / 2] else innerMean
            val localBgStd = if (patchStds.isNotEmpty()) patchStds.sorted()[patchStds.size / 2] else innerStd
            
            val innerBlur = Mat()
            Imgproc.GaussianBlur(inner, innerBlur, Size(3.0, 3.0), 0.0)
            val edges = Mat()
            Imgproc.Canny(innerBlur, edges, 40.0, 140.0)
            val edgeCount = Core.countNonZero(edges)
            
            val absBrightness = innerMean
            val isVeryBright = absBrightness > whiteBrightnessThresh
            val diff = innerMean - localBg
            val label = "${files[c]}${ranks[r]}"
            
            var curPosThresh = posBrightDiff - 5
            var curNegThresh = negBrightDiff + 5
            var curEdgeThresh = edgeCountThresh + whiteEdgeBoost + blackEdgeBoost - 8
            
            if (localBgStd > stdBgThresh) {
                curPosThresh += 3
                curNegThresh -= 3
                curEdgeThresh += 15 + whiteEdgeBoost + blackEdgeBoost
            }
            
            val brightMask = Mat()
            Core.compare(inner, Scalar(190.0), brightMask, Core.CMP_GT)
            val brightPixels = Core.countNonZero(brightMask)
            val brightRatio = brightPixels.toDouble() / inner.total()
            val isSmallBrightSpot = brightRatio < 0.06 && edgeCount < 18 && innerStd < 28 && absBrightness > 170
            
            var pieceDetected = false
            var colorIsWhite = false
            
            if (edgeCount >= curEdgeThresh && !isSmallBrightSpot) {
                pieceDetected = true
                colorIsWhite = diff > 0 || isVeryBright
            }
            
            if (!pieceDetected) {
                if ((diff >= curPosThresh || isVeryBright || absBrightness > 160) && !isSmallBrightSpot) {
                    if (innerStd >= minWhiteStd || absBrightness > 170) {
                        pieceDetected = true
                        colorIsWhite = true
                    }
                } else if (diff <= curNegThresh || absBrightness < 120) {
                    if (innerStd >= blackStdThresh || absBrightness < 110) {
                        pieceDetected = true
                        colorIsWhite = false
                    }
                }
            }
            
            if (!pieceDetected && edgeCount >= (curEdgeThresh * 0.7)) {
                if (innerStd > 3) {
                    pieceDetected = true
                    colorIsWhite = absBrightness > 130
                }
            }
            
            if (pieceDetected && innerStd < 6 && edgeCount < (emptyEdgeThresh * 0.8)) {
                pieceDetected = false
            }
            
            if (pieceDetected && colorIsWhite && brightRatio < 0.015 && innerStd < 10) {
                pieceDetected = false
            }
            
            if (!pieceDetected && absBrightness < 100 && innerStd > 8) {
                pieceDetected = true
                colorIsWhite = false
            }
            
            if (pieceDetected) {
                if (colorIsWhite) {
                    whiteSquares.add(label)
                    Imgproc.rectangle(annotated, Point(x1.toDouble(), y1.toDouble()), 
                        Point(x2.toDouble(), y2.toDouble()), Scalar(255.0, 255.0, 255.0), 4)
                } else {
                    blackSquares.add(label)
                    Imgproc.rectangle(annotated, Point(x1.toDouble(), y1.toDouble()), 
                        Point(x2.toDouble(), y2.toDouble()), Scalar(0.0, 0.0, 0.0), 4)
                }
            }
            
            innerBlur.release()
            edges.release()
            brightMask.release()
            innerMeanScalar.release()
            innerStdDev.release()
            inner.release()
            square.release()
        }
    }
    
    val bitmap = Bitmap.createBitmap(annotated.cols(), annotated.rows(), Bitmap.Config.ARGB_8888)
    Utils.matToBitmap(annotated, bitmap)
    annotated.release()
    
    return BoardState(whiteSquares.toSet(), blackSquares.toSet(), bitmap, null)
}

fun getBoardStateFromBitmapWithCachedCorners(
    bitmap: Bitmap, 
    cachedCorners: Array<Point>,
    boardName: String,
    cachedWhiteOnBottom: Boolean
): BoardState? {
    Log.d("ChessDetector", "Processing board with cached corners AND orientation...")
    
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
    
    val side = 800
    val srcMat = MatOfPoint2f(*cachedCorners)
    val dstMat = MatOfPoint2f(
        Point(0.0, 0.0),
        Point((side - 1).toDouble(), 0.0),
        Point((side - 1).toDouble(), (side - 1).toDouble()),
        Point(0.0, (side - 1).toDouble())
    )
    
    val M = Imgproc.getPerspectiveTransform(srcMat, dstMat)
    val boardWarped = Mat()
    Imgproc.warpPerspective(resized, boardWarped, M, Size(side.toDouble(), side.toDouble()))
    resized.release()
    srcMat.release()
    dstMat.release()
    M.release()
    
    val cellSize = side / 8
    val whiteOnBottom = cachedWhiteOnBottom
    
    val files = if (whiteOnBottom) "abcdefgh" else "hgfedcba"
    val ranks = if (whiteOnBottom) "87654321" else "12345678"
    
    val grayBoard = Mat()
    Imgproc.cvtColor(boardWarped, grayBoard, Imgproc.COLOR_BGR2GRAY)
    
    val boardState = detectPiecesOnBoard(grayBoard, boardWarped, files, ranks, cellSize)
    
    grayBoard.release()
    boardWarped.release()
    
    Log.d("ChessDetector", "✅ Detected (fully cached): ${boardState.white.size} white, ${boardState.black.size} black pieces")
    
    return boardState
}

fun getBoardStateFromBitmap(bitmap: Bitmap, boardName: String): BoardState? {
    Log.d("ChessDetector", "Processing board (first detection)...")
    
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
    
    Log.d("ChessDetector", "Resized to: ${newWidth} x ${newHeight}")
    
    val innerPts = detectLargestSquareLike(resized)
    if (innerPts == null) {
        Log.e("ChessDetector", "No board found")
        resized.release()
        return null
    }
    
    val pts = innerPts.toArray()
    val ordered = orderPoints(pts)
    
    Log.d("ChessDetector", "Board corners on resized image:")
    ordered.forEachIndexed { index, point ->
        Log.d("ChessDetector", "  Corner $index: (${point.x.toInt()}, ${point.y.toInt()})")
    }
    
    val side = 800
    val srcMat = MatOfPoint2f(*ordered)
    val dstMat = MatOfPoint2f(
        Point(0.0, 0.0),
        Point((side - 1).toDouble(), 0.0),
        Point((side - 1).toDouble(), (side - 1).toDouble()),
        Point(0.0, (side - 1).toDouble())
    )
    
    val M = Imgproc.getPerspectiveTransform(srcMat, dstMat)
    val boardWarped = Mat()
    Imgproc.warpPerspective(resized, boardWarped, M, Size(side.toDouble(), side.toDouble()))
    resized.release()
    srcMat.release()
    dstMat.release()
    M.release()
    
    val cellSize = side / 8
    val topLeftSquare = boardWarped.submat(0, cellSize, 0, cellSize)
    val bottomRightSquare = boardWarped.submat(7 * cellSize, 8 * cellSize, 7 * cellSize, 8 * cellSize)
    
    val topLeftGray = Mat()
    val bottomRightGray = Mat()
    Imgproc.cvtColor(topLeftSquare, topLeftGray, Imgproc.COLOR_BGR2GRAY)
    Imgproc.cvtColor(bottomRightSquare, bottomRightGray, Imgproc.COLOR_BGR2GRAY)
    
    val meanTop = Core.mean(topLeftGray).`val`[0]
    val meanBottom = Core.mean(bottomRightGray).`val`[0]
    val whiteOnBottom = meanBottom > meanTop
    
    topLeftSquare.release()
    bottomRightSquare.release()
    topLeftGray.release()
    bottomRightGray.release()
    
    val files = if (whiteOnBottom) "abcdefgh" else "hgfedcba"
    val ranks = if (whiteOnBottom) "87654321" else "12345678"
    
    val grayBoard = Mat()
    Imgproc.cvtColor(boardWarped, grayBoard, Imgproc.COLOR_BGR2GRAY)
    
    val boardState = detectPiecesOnBoard(grayBoard, boardWarped, files, ranks, cellSize)
    
    grayBoard.release()
    boardWarped.release()
    
    Log.d("ChessDetector", "✅ Detected: ${boardState.white.size} white, ${boardState.black.size} black pieces")
    Log.d("ChessDetector", "White pieces at: ${boardState.white.sorted().joinToString(", ")}")
    Log.d("ChessDetector", "Black pieces at: ${boardState.black.sorted().joinToString(", ")}")
    
   // âœ… Use hardcoded UCI coordinates based on orientation
    Log.d("ChessDetector", "ðŸŽ¯ Using HARDCODED screen coordinates (whiteOnBottom=$whiteOnBottom)")
    val uciCoordinates = getHardcodedUciCoordinates(whiteOnBottom)
    Log.d("ChessDetector", "âœ… Loaded ${uciCoordinates.size} hardcoded coordinates")
    
    return BoardState(boardState.white, boardState.black, boardState.annotatedBoard, ordered, whiteOnBottom, uciCoordinates)
}