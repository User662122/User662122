package com.chessmove.detector

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min

var WHITE_DETECTION_SENSITIVITY = 120
var BLACK_DETECTION_SENSITIVITY = 20
var EMPTY_DETECTION_SENSITIVITY = 50

data class BoardState(
    val white: Set<String>,
    val black: Set<String>
)

fun shrinkPolygon(pts: MatOfPoint, shrinkFactor: Double = 0.95): MatOfPoint {
    val points = pts.toArray()
    val centerX = points.map { it.x }.average()
    val centerY = points.map { it.y }.average()
    val center = Point(centerX, centerY)
    
    val shrunk = points.map { p ->
        val directionX = p.x - center.x
        val directionY = p.y - center.y
        Point(
            center.x + directionX * shrinkFactor,
            center.y + directionY * shrinkFactor
        )
    }
    
    return MatOfPoint(*shrunk.toTypedArray())
}

fun detectLargestSquareLike(img: Mat): Pair<Mat, MatOfPoint?> {
    val orig = img.clone()
    val gray = Mat()
    Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
    
    val filtered = Mat()
    Imgproc.bilateralFilter(gray, filtered, 11, 17.0, 17.0)
    
    val edges = Mat()
    Imgproc.Canny(filtered, edges, 40.0, 150.0)
    Imgproc.dilate(edges, edges, Mat(), Point(-1.0, -1.0), 2)
    Imgproc.erode(edges, edges, Mat(), Point(-1.0, -1.0), 1)
    
    val contours = mutableListOf<MatOfPoint>()
    val hierarchy = Mat()
    Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
    
    val h = img.rows()
    val w = img.cols()
    val imgArea = w * h
    
    var best: MatOfPoint? = null
    var bestArea = 0.0
    
    for (c in contours) {
        val peri = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, 0.02 * peri, true)
        val area = Imgproc.contourArea(c)
        
        if (approx.toArray().size == 4 && area > 0.2 * imgArea) {
            val rect = Imgproc.boundingRect(MatOfPoint(*approx.toArray()))
            val ratio = rect.width.toDouble() / rect.height.toDouble()
            if (ratio in 0.7..1.3 && area > bestArea) {
                best = MatOfPoint(*approx.toArray())
                bestArea = area
            }
        }
    }
    
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
    
    val imgShow = orig.clone()
    var innerPts: MatOfPoint? = null
    
    if (best != null) {
        innerPts = shrinkPolygon(best, 0.962)
        Imgproc.polylines(imgShow, listOf(best), true, Scalar(0.0, 255.0, 0.0), 3)
        Imgproc.polylines(imgShow, listOf(innerPts), true, Scalar(0.0, 0.0, 255.0), 8)
        for (pt in innerPts.toArray()) {
            Imgproc.circle(imgShow, pt, 8, Scalar(0.0, 0.0, 255.0), -1)
        }
    }
    
    return Pair(imgShow, innerPts)
}

fun normalizeSensitivity(sensitivity: Int): Double {
    return if (sensitivity <= 100) {
        sensitivity.toDouble()
    } else {
        100.0 + (sensitivity - 100) * 0.5
    }
}

fun detectPiecesOnBoard(grayBoard: Mat, files: String, ranks: String, cellSize: Int): Pair<List<String>, List<String>> {
    val whiteSquares = mutableListOf<String>()
    val blackSquares = mutableListOf<String>()
    
    val whiteNorm = normalizeSensitivity(WHITE_DETECTION_SENSITIVITY)
    val blackNorm = normalizeSensitivity(BLACK_DETECTION_SENSITIVITY)
    val emptyNorm = normalizeSensitivity(EMPTY_DETECTION_SENSITIVITY)
    
    val POS_BRIGHT_DIFF = max(1.0, 25.0 - (whiteNorm * 0.2))
    val MIN_WHITE_STD = max(1.0, 30.0 - (whiteNorm * 0.25))
    val WHITE_EDGE_BOOST = whiteNorm * 0.3
    val WHITE_BRIGHTNESS_THRESH = max(100.0, 200.0 - (whiteNorm * 0.5))
    val NEG_BRIGHT_DIFF = min(-1.0, -10.0 - (blackNorm * 0.2))
    val BLACK_STD_THRESH = max(1.0, 5.0 + (blackNorm * 0.1))
    val BLACK_EDGE_BOOST = blackNorm * 0.3
    val EDGE_COUNT_THRESH = max(5.0, 80.0 - (emptyNorm * 0.6))
    val STD_BG_THRESH = max(1.0, 5.0 + (emptyNorm * 0.15))
    val MAX_EMPTY_STD = max(10.0, 15.0 + (emptyNorm * 0.25))
    val EMPTY_EDGE_THRESH = max(1.0, 20.0 - (emptyNorm * 0.15))
    
    for (r in 0 until 8) {
        for (c in 0 until 8) {
            val x1 = c * cellSize
            val y1 = r * cellSize
            val x2 = x1 + cellSize
            val y2 = y1 + cellSize
            
            val square = grayBoard.submat(y1, y2, x1, x2)
            if (square.rows() == 0 || square.cols() == 0) {
                continue
            }
            
            val m = max(1, (cellSize * 0.25).toInt())
            val inner = square.submat(m, cellSize - m, m, cellSize - m)
            if (inner.empty()) {
                continue
            }
            
            val innerMean = Core.mean(inner).`val`[0]
            val meanMat = MatOfDouble()
            val stdMat = MatOfDouble()
            Core.meanStdDev(inner, meanMat, stdMat)
            val innerStd = stdMat.toArray()[0]
            
            val p = max(2, (cellSize * 0.15).toInt())
            val patchesCoords = listOf(
                Rect(0, 0, p, p),
                Rect(0, cellSize - p, p, p),
                Rect(cellSize - p, 0, p, p),
                Rect(cellSize - p, cellSize - p, p, p)
            )
            
            val patchMeans = mutableListOf<Double>()
            val patchStds = mutableListOf<Double>()
            
            for (rect in patchesCoords) {
                try {
                    val patch = square.submat(rect)
                    if (!patch.empty()) {
                        patchMeans.add(Core.mean(patch).`val`[0])
                        val pMean = MatOfDouble()
                        val pStd = MatOfDouble()
                        Core.meanStdDev(patch, pMean, pStd)
                        patchStds.add(pStd.toArray()[0])
                    }
                } catch (e: Exception) {
                }
            }
            
            val localBg = if (patchMeans.isNotEmpty()) patchMeans.sorted()[patchMeans.size / 2] else innerMean
            val localBgStd = if (patchStds.isNotEmpty()) patchStds.sorted()[patchStds.size / 2] else innerStd
            
            val innerBlur = Mat()
            Imgproc.GaussianBlur(inner, innerBlur, Size(3.0, 3.0), 0.0)
            val edges = Mat()
            Imgproc.Canny(innerBlur, edges, 50.0, 150.0)
            val edgeCount = Core.countNonZero(edges)
            
            val absBrightness = innerMean
            val isVeryBright = absBrightness > WHITE_BRIGHTNESS_THRESH
            
            val diff = innerMean - localBg
            val label = "${files[c]}${ranks[r]}"
            var pieceDetected = false
            var colorIsWhite = false
            
            val curPosThresh: Double
            val curNegThresh: Double
            val curEdgeThresh: Double
            
            if (localBgStd > STD_BG_THRESH) {
                curPosThresh = POS_BRIGHT_DIFF + 8
                curNegThresh = NEG_BRIGHT_DIFF - 8
                curEdgeThresh = EDGE_COUNT_THRESH + 25 + WHITE_EDGE_BOOST + BLACK_EDGE_BOOST
            } else {
                curPosThresh = POS_BRIGHT_DIFF
                curNegThresh = NEG_BRIGHT_DIFF
                curEdgeThresh = EDGE_COUNT_THRESH + WHITE_EDGE_BOOST + BLACK_EDGE_BOOST
            }
            
            val brightPixels = Core.countNonZero(inner.apply { 
                Core.compare(this, Scalar(200.0), this, Core.CMP_GT)
            })
            val brightRatio = if (inner.total() > 0) brightPixels.toDouble() / inner.total() else 0.0
            val isSmallBrightSpot = (brightRatio < 0.05 && edgeCount < 15 && 
                                    innerStd < 25 && absBrightness > 180)
            
            if (edgeCount >= curEdgeThresh && !isSmallBrightSpot) {
                pieceDetected = true
                colorIsWhite = diff > 0 || isVeryBright
            } else {
                if ((diff >= curPosThresh || isVeryBright) && !isSmallBrightSpot) {
                    if (innerStd in MIN_WHITE_STD..MAX_EMPTY_STD) {
                        pieceDetected = true
                        colorIsWhite = true
                    }
                } else if (diff <= curNegThresh) {
                    if (innerStd >= BLACK_STD_THRESH) {
                        pieceDetected = true
                        colorIsWhite = false
                    }
                }
            }
            
            if (pieceDetected && innerStd < 10 && edgeCount < EMPTY_EDGE_THRESH) {
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
        }
    }
    
    return Pair(whiteSquares, blackSquares)
}

fun getBoardStateFromBitmap(bitmap: Bitmap, boardName: String): BoardState? {
    val img = Mat()
    Utils.bitmapToMat(bitmap, img)
    
    if (img.empty()) {
        return null
    }
    
    Imgproc.cvtColor(img, img, Imgproc.COLOR_RGBA2BGR)
    
    val resized = Mat()
    val scale = 900.0 / img.cols()
    Imgproc.resize(img, resized, Size(), scale, scale, Imgproc.INTER_AREA)
    
    val (detectedImg, innerPts) = detectLargestSquareLike(resized)
    
    if (innerPts == null) {
        return null
    }
    
    val pts = innerPts.toArray()
    val sums = pts.map { it.x + it.y }
    val diffs = pts.map { it.y - it.x }
    
    val ordered = arrayOf(
        pts[sums.indexOf(sums.minOrNull()!!)],
        pts[diffs.indexOf(diffs.minOrNull()!!)],
        pts[sums.indexOf(sums.maxOrNull()!!)],
        pts[diffs.indexOf(diffs.maxOrNull()!!)]
    )
    
    val side = 800.0
    val dst = arrayOf(
        Point(0.0, 0.0),
        Point(side - 1, 0.0),
        Point(side - 1, side - 1),
        Point(0.0, side - 1)
    )
    
    val srcMat = MatOfPoint2f(*ordered)
    val dstMat = MatOfPoint2f(*dst)
    val M = Imgproc.getPerspectiveTransform(srcMat, dstMat)
    val boardWarped = Mat()
    Imgproc.warpPerspective(resized, boardWarped, M, Size(side, side))
    
    val cellSize = side.toInt() / 8
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
    
    val grayBoard = Mat()
    Imgproc.cvtColor(boardWarped, grayBoard, Imgproc.COLOR_BGR2GRAY)
    val (whiteSquares, blackSquares) = detectPiecesOnBoard(grayBoard, files, ranks, cellSize)
    
    return BoardState(whiteSquares.toSet(), blackSquares.toSet())
}

fun detectUciMoves(state1: BoardState, state2: BoardState): List<String> {
    val white1 = state1.white
    val black1 = state1.black
    val white2 = state2.white
    val black2 = state2.black
    
    val whiteMoved = white1 - white2
    val blackMoved = black1 - black2
    val whiteAppeared = white2 - white1
    val blackAppeared = black2 - black1
    
    val moves = mutableListOf<String>()
    
    if (whiteMoved.size == 1 && whiteAppeared.size == 1) {
        val source = whiteMoved.first()
        val destination = whiteAppeared.first()
        moves.add("White moved $source$destination")
    }
    
    if (blackMoved.size == 1 && blackAppeared.size == 1) {
        val source = blackMoved.first()
        val destination = blackAppeared.first()
        moves.add("Black moved $source$destination")
    }
    
    if (whiteMoved.size == 1 && blackMoved.size == 1 && whiteAppeared.isEmpty()) {
        val whiteSource = whiteMoved.first()
        val blackCaptured = blackMoved.first()
        moves.add("White captured $whiteSource$blackCaptured")
    }
    
    if (blackMoved.size == 1 && whiteMoved.size == 1 && blackAppeared.isEmpty()) {
        val blackSource = blackMoved.first()
        val whiteCaptured = whiteMoved.first()
        moves.add("Black captured $blackSource$whiteCaptured")
    }
    
    return moves
}
