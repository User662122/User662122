package com.chessmove.detector

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.max
import kotlin.math.min

var WHITE_DETECTION_SENSITIVITY = 120
var BLACK_DETECTION_SENSITIVITY = 5
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

/**
 * NOTE: detectPiecesOnBoard now accepts the BGR board (boardBgr).
 * It internally converts to gray for intensity-based checks, but also
 * computes a green mask from HSV to ignore green overlays (highlights).
 */
fun detectPiecesOnBoard(boardBgr: Mat, files: String, ranks: String, cellSize: Int): Pair<List<String>, List<String>> {
    val whiteSquares = mutableListOf<String>()
    val blackSquares = mutableListOf<String>()

    // Convert to gray for intensity-based stats
    val grayBoard = Mat()
    Imgproc.cvtColor(boardBgr, grayBoard, Imgproc.COLOR_BGR2GRAY)

    // Create green mask in HSV to detect highlight/dots (like the green dot)
    val hsv = Mat()
    Imgproc.cvtColor(boardBgr, hsv, Imgproc.COLOR_BGR2HSV)
    val lowerGreen = Scalar(30.0, 80.0, 40.0)   // H around 30-90 works; tuned for highlight green
    val upperGreen = Scalar(95.0, 255.0, 255.0)
    val greenMask = Mat()
    Core.inRange(hsv, lowerGreen, upperGreen, greenMask)

    val whiteNorm = normalizeSensitivity(WHITE_DETECTION_SENSITIVITY)
    val blackNorm = normalizeSensitivity(BLACK_DETECTION_SENSITIVITY)
    val emptyNorm = normalizeSensitivity(EMPTY_DETECTION_SENSITIVITY)

    // Improved threshold calculations
    val POS_BRIGHT_DIFF = max(5.0, 20.0 - (whiteNorm * 0.15))
    val MIN_WHITE_STD = max(8.0, 25.0 - (whiteNorm * 0.2))
    val WHITE_EDGE_BOOST = whiteNorm * 0.2
    val WHITE_BRIGHTNESS_THRESH = max(120.0, 180.0 - (whiteNorm * 0.4))

    val NEG_BRIGHT_DIFF = min(-5.0, -15.0 - (blackNorm * 0.1))
    val BLACK_STD_THRESH = max(3.0, 8.0 + (blackNorm * 0.08))
    val BLACK_EDGE_BOOST = blackNorm * 0.2

    val EDGE_COUNT_THRESH = max(10.0, 60.0 - (emptyNorm * 0.5))
    val STD_BG_THRESH = max(2.0, 8.0 + (emptyNorm * 0.1))
    val MAX_EMPTY_STD = max(15.0, 25.0 + (emptyNorm * 0.2))
    val EMPTY_EDGE_THRESH = max(5.0, 25.0 - (emptyNorm * 0.2))

    // New: Calculate overall board brightness for adaptive thresholds
    val boardMean = Core.mean(grayBoard).`val`[0]
    val brightnessFactor = boardMean / 128.0 // Normalize around middle gray

    for (r in 0 until 8) {
        for (c in 0 until 8) {
            val x1 = c * cellSize
            val y1 = r * cellSize
            val x2 = x1 + cellSize
            val y2 = y1 + cellSize

            // Safety bounds
            if (x2 > grayBoard.cols() || y2 > grayBoard.rows()) continue

            val square = grayBoard.submat(y1, y2, x1, x2)
            if (square.rows() == 0 || square.cols() == 0) {
                square.release()
                continue
            }

            // Check green overlay presence (from greenMask)
            val greenPatch = greenMask.submat(y1, y2, x1, x2)
            val greenPixels = Core.countNonZero(greenPatch)
            val totalPixels = greenPatch.total()
            val greenRatio = if (totalPixels > 0) greenPixels.toDouble() / totalPixels else 0.0
            greenPatch.release()

            // If there's a visible green overlay (e.g., highlight/dot), skip detection for this square
            val GREEN_SKIP_THRESHOLD = 0.015 // ~1.5% pixels; tweakable
            if (greenRatio > GREEN_SKIP_THRESHOLD) {
                square.release()
                continue
            }

            val m = max(1, (cellSize * 0.25).toInt())
            val inner = square.submat(m, cellSize - m, m, cellSize - m)
            if (inner.empty()) {
                inner.release()
                square.release()
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
                        pMean.release()
                        pStd.release()
                    }
                    patch.release()
                } catch (e: Exception) {
                    // Continue if patch extraction fails
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
            val isVeryBright = absBrightness > WHITE_BRIGHTNESS_THRESH * brightnessFactor

            val diff = innerMean - localBg
            val label = "${files[c]}${ranks[r]}"
            var pieceDetected = false
            var colorIsWhite = false

            // Adaptive thresholds based on overall board brightness
            val adaptivePosThresh = if (localBgStd > STD_BG_THRESH) {
                POS_BRIGHT_DIFF + 5
            } else {
                POS_BRIGHT_DIFF
            }

            val adaptiveNegThresh = if (localBgStd > STD_BG_THRESH) {
                NEG_BRIGHT_DIFF - 5
            } else {
                NEG_BRIGHT_DIFF
            }

            val adaptiveEdgeThresh = if (localBgStd > STD_BG_THRESH) {
                EDGE_COUNT_THRESH + 20 + WHITE_EDGE_BOOST + BLACK_EDGE_BOOST
            } else {
                EDGE_COUNT_THRESH + WHITE_EDGE_BOOST + BLACK_EDGE_BOOST
            }

            // NEW: More sophisticated white piece detection
            val isLikelyWhitePiece = when {
                // Case 1: Very bright with good contrast
                isVeryBright && innerStd > MIN_WHITE_STD -> true
                // Case 2: Positive contrast with sufficient brightness and texture
                diff >= adaptivePosThresh && innerStd > MIN_WHITE_STD && absBrightness > 100 -> true
                // Case 3: High edge count with positive brightness characteristics
                edgeCount >= adaptiveEdgeThresh && diff > 0 && innerStd > 8 -> true
                else -> false
            }

            // NEW: More conservative black piece detection
            val isLikelyBlackPiece = when {
                // Case 1: Strong negative contrast with texture
                diff <= adaptiveNegThresh && innerStd >= BLACK_STD_THRESH -> {
                    val darkDelta = localBg - innerMean
                    darkDelta >= 15.0 && absBrightness < 150 // Ensure it's actually dark
                }
                // Case 2: High edge count with negative brightness
                edgeCount >= adaptiveEdgeThresh && diff < -10 && innerStd > 5 -> true
                else -> false
            }

            // Decision logic with priority for white detection
            when {
                isLikelyWhitePiece -> {
                    pieceDetected = true
                    colorIsWhite = true
                }
                isLikelyBlackPiece -> {
                    pieceDetected = true
                    colorIsWhite = false
                }
                // NEW: Additional check for ambiguous cases
                edgeCount >= adaptiveEdgeThresh && !isLikelyWhitePiece && !isLikelyBlackPiece -> {
                    // If we have edges but can't clearly classify, use brightness as tiebreaker
                    pieceDetected = true
                    colorIsWhite = diff > 0 || absBrightness > 120
                }
            }

            // NEW: Additional validation to prevent misclassification
            if (pieceDetected) {
                // White pieces should not be too dark
                if (colorIsWhite && absBrightness < 80) {
                    pieceDetected = false
                }
                // Black pieces should not be too bright
                else if (!colorIsWhite && absBrightness > 180) {
                    pieceDetected = false
                }
                // Both should have reasonable texture
                else if (innerStd < 5 && edgeCount < 15) {
                    pieceDetected = false
                }
            }

            // Filter out small bright spots (noise)
            val brightMask = Mat()
            Core.compare(inner, Scalar(200.0), brightMask, Core.CMP_GT)
            val brightPixels = Core.countNonZero(brightMask)
            brightMask.release()

            val brightRatio = if (inner.total() > 0) brightPixels.toDouble() / inner.total() else 0.0
            val isSmallBrightSpot = (brightRatio < 0.05 && edgeCount < 15 &&
                    innerStd < 25 && absBrightness > 180)

            if (isSmallBrightSpot) {
                pieceDetected = false
            }

            if (pieceDetected) {
                if (colorIsWhite) {
                    whiteSquares.add(label)
                } else {
                    blackSquares.add(label)
                }
            }

            // release mats
            innerBlur.release()
            edges.release()
            meanMat.release()
            stdMat.release()
            inner.release()
            square.release()
        }
    }

    // cleanup
    grayBoard.release()
    hsv.release()
    greenMask.release()

    return Pair(whiteSquares, blackSquares)
}

// The rest of your functions remain the same...
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

    // Pass the BGR warped board to detectPiecesOnBoard (so we can detect green highlights)
    val (whiteSquares, blackSquares) = detectPiecesOnBoard(boardWarped, files, ranks, cellSize)

    // release temporary mats
    topLeftSquare.release()
    bottomRightSquare.release()
    topLeftGray.release()
    bottomRightGray.release()
    resized.release()
    img.release()
    srcMat.release()
    dstMat.release()
    M.release()
    detectedImg.release()
    innerPts.release()

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