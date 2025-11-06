package com.chessmove.detector

import android.graphics.Bitmap
import android.os.Environment
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

// --- SENSITIVITY CONTROLS ---
const val WHITE_DETECTION_SENSITIVITY = 95
const val BLACK_DETECTION_SENSITIVITY = 50  
const val EMPTY_DETECTION_SENSITIVITY = 50

data class BoardState(
    val white: Set<String>,
    val black: Set<String>,
    val annotatedBoard: Bitmap?
)

// --- Shrink the polygon slightly inward ---
private fun shrinkPolygon(pts: MatOfPoint, shrinkFactor: Double = 0.95): MatOfPoint {
    val points = pts.toList()
    val center = Point(
        points.map { it.x }.average(),
        points.map { it.y }.average()
    )
    
    val shrunkPoints = points.map { p ->
        val direction = Point(p.x - center.x, p.y - center.y)
        Point(
            center.x + direction.x * shrinkFactor,
            center.y + direction.y * shrinkFactor
        )
    }
    
    val result = MatOfPoint()
    result.fromList(shrunkPoints)
    return result
}

// --- Detect largest square-like contour (outer board) ---
private fun detectLargestSquareLike(img: Mat): Pair<Mat, MatOfPoint?> {
    val orig = img.clone()
    val gray = Mat()
    Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
    Imgproc.bilateralFilter(gray, gray, 11, 17.0, 17.0)
    
    val edges = Mat()
    Imgproc.Canny(gray, edges, 40.0, 150.0)
    
    val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
    Imgproc.erode(edges, edges, kernel, Point(-1.0, -1.0), 1)
    
    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
    
    val imgArea = img.rows() * img.cols()
    var best: MatOfPoint? = null
    var bestArea = 0.0
    
    for (c in contours) {
        val peri = Imgproc.arcLength(c, true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, 0.02 * peri, true)
        
        val area = Imgproc.contourArea(c)
        if (approx.rows() == 4 && area > 0.2 * imgArea) {
            val rect = Imgproc.boundingRect(approx)
            val ratio = rect.width.toDouble() / rect.height
            if (ratio in 0.7..1.3 && area > bestArea) {
                best = MatOfPoint(*approx.toArray())
                bestArea = area
            }
        }
    }
    
    // If no good contour found, use largest contour
    if (best == null && contours.isNotEmpty()) {
        val c = contours.maxByOrNull { Imgproc.contourArea(it) }!!
        val rect = Imgproc.boundingRect(c)
        best = MatOfPoint(
            Point(rect.x.toDouble(), rect.y.toDouble()),
            Point((rect.x + rect.width).toDouble(), rect.y.toDouble()),
            Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
            Point(rect.x.toDouble(), (rect.y + rect.height).toDouble())
        )
    }
    
    var innerPts: MatOfPoint? = null
    if (best != null) {
        innerPts = shrinkPolygon(best, 0.962)
        
        // Draw outer contour (green)
        Imgproc.polylines(orig, listOf(best), true, Scalar(0.0, 255.0, 0.0), 3)
        // Draw inner contour (red)
        Imgproc.polylines(orig, listOf(innerPts), true, Scalar(0.0, 0.0, 255.0), 8)
        
        // Draw corner points
        for (point in innerPts.toArray()) {
            Imgproc.circle(orig, point, 8, Scalar(0.0, 0.0, 255.0), -1)
        }
    }
    
    return Pair(orig, innerPts)
}

private fun normalizeSensitivity(sensitivity: Int): Double {
    return if (sensitivity <= 100) {
        sensitivity.toDouble()
    } else {
        100 + (sensitivity - 100) * 0.5
    }
}

private fun detectPiecesOnBoard(
    grayBoard: Mat, 
    files: String, 
    ranks: String, 
    cellSize: Int
): Pair<List<String>, List<String>> {
    val whiteSquares = mutableListOf<String>()
    val blackSquares = mutableListOf<String>()
    
    val whiteNorm = normalizeSensitivity(WHITE_DETECTION_SENSITIVITY)
    val blackNorm = normalizeSensitivity(BLACK_DETECTION_SENSITIVITY)
    val emptyNorm = normalizeSensitivity(EMPTY_DETECTION_SENSITIVITY)
    
    // Parameter calculations
    val posBrightDiff = max(1.0, 25.0 - (whiteNorm * 0.2))
    val minWhiteStd = max(1.0, 30.0 - (whiteNorm * 0.25))
    val whiteEdgeBoost = whiteNorm * 0.3
    val whiteBrightnessThresh = max(100.0, 200.0 - (whiteNorm * 0.5))
    val negBrightDiff = min(-1.0, -10.0 - (blackNorm * 0.2))
    val blackStdThresh = max(1.0, 5.0 + (blackNorm * 0.1))
    val blackEdgeBoost = blackNorm * 0.3
    val edgeCountThresh = max(5.0, 80.0 - (emptyNorm * 0.6))
    val stdBgThresh = max(1.0, 5.0 + (emptyNorm * 0.15))
    val maxEmptyStd = max(10.0, 15.0 + (emptyNorm * 0.25))
    val emptyEdgeThresh = max(1.0, 20.0 - (emptyNorm * 0.15))
    
    for (r in 0 until 8) {
        for (c in 0 until 8) {
            val x1 = c * cellSize
            val y1 = r * cellSize
            val x2 = x1 + cellSize
            val y2 = y1 + cellSize
            
            if (y2 > grayBoard.rows() || x2 > grayBoard.cols()) continue
            
            val square = grayBoard.submat(y1, y2, x1, x2)
            if (square.empty()) continue
            
            val m = max(1, (cellSize * 0.25).toInt())
            val inner = if (square.rows() > 2*m && square.cols() > 2*m) {
                square.submat(m, square.rows() - m, m, square.cols() - m)
            } else {
                continue
            }
            
            if (inner.empty()) continue
            
            val innerMean = Core.mean(inner).`val`[0]
            val innerStd = getStdDev(inner)
            
            val p = max(2, (cellSize * 0.15).toInt())
            val patches = listOf(
                square.submat(0, p, 0, p),
                square.submat(square.rows() - p, square.rows(), 0, p),
                square.submat(0, p, square.cols() - p, square.cols()),
                square.submat(square.rows() - p, square.rows(), square.cols() - p, square.cols())
            )
            
            val patchMeans = patches.filter { !it.empty() }.map { Core.mean(it).`val`[0] }
            val patchStds = patches.filter { !it.empty() }.map { getStdDev(it) }
            
            val localBg = if (patchMeans.isNotEmpty()) median(patchMeans) else innerMean
            val localBgStd = if (patchStds.isNotEmpty()) median(patchStds) else innerStd
            
            val innerBlur = Mat()
            Imgproc.GaussianBlur(inner, innerBlur, Size(3.0, 3.0), 0.0)
            
            val edges = Mat()
            Imgproc.Canny(innerBlur, edges, 50.0, 150.0)
            val edgeCount = Core.countNonZero(edges).toDouble()
            
            val absBrightness = innerMean
            val isVeryBright = absBrightness > whiteBrightnessThresh
            
            val diff = innerMean - localBg
            val label = "${files[c]}${ranks[r]}"
            
            var pieceDetected = false
            var colorIsWhite = false
            
            val curPosThresh: Double
            val curNegThresh: Double
            val curEdgeThresh: Double
            
            if (localBgStd > stdBgThresh) {
                curPosThresh = posBrightDiff + 8
                curNegThresh = negBrightDiff - 8
                curEdgeThresh = edgeCountThresh + 25 + whiteEdgeBoost + blackEdgeBoost
            } else {
                curPosThresh = posBrightDiff
                curNegThresh = negBrightDiff
                curEdgeThresh = edgeCountThresh + whiteEdgeBoost + blackEdgeBoost
            }
            
            // Bright pixels calculation
            val brightMask = Mat()
            Core.compare(inner, Scalar(200.0), brightMask, Core.CMP_GT)
            val brightPixels = Core.countNonZero(brightMask).toDouble()
            val brightRatio = brightPixels / inner.total()
            val isSmallBrightSpot = (brightRatio < 0.05 && edgeCount < 15 && 
                                   innerStd < 25 && absBrightness > 180)
            
            // Detection logic
            if (edgeCount >= curEdgeThresh && !isSmallBrightSpot) {
                pieceDetected = true
                colorIsWhite = diff > 0 || isVeryBright
            } else {
                if ((diff >= curPosThresh || isVeryBright) && !isSmallBrightSpot) {
                    if (innerStd in minWhiteStd..maxEmptyStd) {
                        pieceDetected = true
                        colorIsWhite = true
                    }
                } else if (diff <= curNegThresh) {
                    if (innerStd >= blackStdThresh) {
                        pieceDetected = true
                        colorIsWhite = false
                    }
                }
            }
            
            if (pieceDetected && innerStd < 10 && edgeCount < emptyEdgeThresh) {
                pieceDetected = false
            }
            
            if (pieceDetected && colorIsWhite && brightRatio < 0.03 && innerStd < 15) {
                pieceDetected = false
            }
            
            if (pieceDetected) {
                if (colorIsWhite) {
                    whiteSquares.add(label)
                } else {
                    blackSquares.add(label)
                }
            }
            
            // Clean up Mats
            square.release()
            inner.release()
            innerBlur.release()
            edges.release()
            brightMask.release()
            patches.forEach { it.release() }
        }
    }
    
    return Pair(whiteSquares, blackSquares)
}

// Helper function to calculate standard deviation
private fun getStdDev(mat: Mat): Double {
    val mean = Scalar(0.0)
    val stddev = Scalar(0.0)
    Core.meanStdDev(mat, mean, stddev)
    return stddev.`val`[0]
}

// Helper function to calculate median
private fun median(list: List<Double>): Double {
    val sorted = list.sorted()
    return if (sorted.size % 2 == 0) {
        (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    } else {
        sorted[sorted.size / 2]
    }
}

// Main function to get board state from bitmap
fun getBoardStateFromBitmap(bitmap: Bitmap, boardName: String): BoardState? {
    try {
        // Convert Bitmap to Mat
        val img = Mat()
        Utils.bitmapToMat(bitmap, img)
        
        // Resize image
        val resized = Mat()
        val scale = 900.0 / img.cols()
        Imgproc.resize(img, resized, Size(900.0, img.rows() * scale))
        
        // Detect board
        val (detectedImg, innerPts) = detectLargestSquareLike(resized)
        
        if (innerPts == null) {
            return null
        }
        
        // Warp perspective
        val pts = innerPts.toArray()
        val s = pts.map { it.x + it.y }
        val diff = pts.map { it.x - it.y }
        
        val ordered = listOf(
            pts[s.indexOf(s.minOrNull()!!)],
            pts[diff.indexOf(diff.minOrNull()!!)],
            pts[s.indexOf(s.maxOrNull()!!)],
            pts[diff.indexOf(diff.maxOrNull()!!)]
        )
        
        val side = 800.0
        val srcPoints = MatOfPoint2f(*ordered.toTypedArray())
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(side - 1, 0.0),
            Point(side - 1, side - 1),
            Point(0.0, side - 1)
        )
        
        val M = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val boardWarped = Mat()
        Imgproc.warpPerspective(resized, boardWarped, M, Size(side, side))
        
        // Detect orientation
        val cellSize = (side / 8).toInt()
        val topLeftSquare = boardWarped.submat(0, cellSize, 0, cellSize)
        val bottomRightSquare = boardWarped.submat(7 * cellSize, 8 * cellSize, 7 * cellSize, 8 * cellSize)
        
        val topLeftGray = Mat()
        val bottomRightGray = Mat()
        Imgproc.cvtColor(topLeftSquare, topLeftGray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(bottomRightSquare, bottomRightGray, Imgproc.COLOR_BGR2GRAY)
        
        val meanTop = Core.mean(topLeftGray).`val`[0]
        val meanBottom = Core.mean(bottomRightGray).`val`[0]
        val whiteOnBottom = meanBottom > meanTop
        
        val files = if (whiteOnBottom) "abcdefgh" else "hgfedcba"
        val ranks = if (whiteOnBottom) "87654321" else "12345678"
        
        // Detect pieces
        val grayBoard = Mat()
        Imgproc.cvtColor(boardWarped, grayBoard, Imgproc.COLOR_BGR2GRAY)
        val (whiteSquares, blackSquares) = detectPiecesOnBoard(grayBoard, files, ranks, cellSize)
        
        // Create annotated image with bounding boxes
        val annotated = boardWarped.clone()
        
        // Draw white pieces with white squares
        for (square in whiteSquares) {
            val fileChar = square[0]
            val rankChar = square[1]
            val fileIndex = files.indexOf(fileChar)
            val rankIndex = ranks.indexOf(rankChar)
            
            if (fileIndex != -1 && rankIndex != -1) {
                val x1 = fileIndex * cellSize
                val y1 = rankIndex * cellSize
                val x2 = x1 + cellSize
                val y2 = y1 + cellSize
                
                Imgproc.rectangle(
                    annotated, 
                    Point(x1.toDouble(), y1.toDouble()), 
                    Point(x2.toDouble(), y2.toDouble()), 
                    Scalar(255.0, 255.0, 255.0), 
                    4
                )
            }
        }
        
        // Draw black pieces with black squares  
        for (square in blackSquares) {
            val fileChar = square[0]
            val rankChar = square[1]
            val fileIndex = files.indexOf(fileChar)
            val rankIndex = ranks.indexOf(rankChar)
            
            if (fileIndex != -1 && rankIndex != -1) {
                val x1 = fileIndex * cellSize
                val y1 = rankIndex * cellSize
                val x2 = x1 + cellSize
                val y2 = y1 + cellSize
                
                Imgproc.rectangle(
                    annotated, 
                    Point(x1.toDouble(), y1.toDouble()), 
                    Point(x2.toDouble(), y2.toDouble()), 
                    Scalar(0.0, 0.0, 0.0), 
                    4
                )
            }
        }
        
        // Convert annotated Mat back to Bitmap
        val annotatedBitmap = Bitmap.createBitmap(annotated.cols(), annotated.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(annotated, annotatedBitmap)
        
        // Clean up Mats
        img.release()
        resized.release()
        detectedImg.release()
        srcPoints.release()
        dstPoints.release()
        M.release()
        boardWarped.release()
        topLeftSquare.release()
        bottomRightSquare.release()
        topLeftGray.release()
        bottomRightGray.release()
        grayBoard.release()
        annotated.release()
        
        return BoardState(
            white = whiteSquares.toSet(),
            black = blackSquares.toSet(),
            annotatedBoard = annotatedBitmap
        )
        
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}