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

    // release temporaries
    gray.release()
    filtered.release()
    edges.release()
    hierarchy.release()

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
 * detectPiecesOnBoard:
 * - Accepts BGR warped board.
 * - Normalizes grayscale for even brightness.
 * - Builds an HSV green mask but instead of skipping small green marks,
 *   it ignores green pixels in statistics and neutralizes them before edge detection.
 * - Only skips the square if green covers a very large fraction.
 */
fun detectPiecesOnBoard(boardBgr: Mat, files: String, ranks: String, cellSize: Int): Pair<List<String>, List<String>> {
    val whiteSquares = mutableListOf<String>()
    val blackSquares = mutableListOf<String>()

    // Convert to gray for intensity-based stats
    val grayBoard = Mat()
    Imgproc.cvtColor(boardBgr, grayBoard, Imgproc.COLOR_BGR2GRAY)

    // Normalize to reduce lighting variations (helps detection)
    val grayNorm = Mat()
    Core.normalize(grayBoard, grayNorm, 0.0, 255.0, Core.NORM_MINMAX)

    // Create green mask in HSV to detect highlight/dots (like the green dot)
    val hsv = Mat()
    Imgproc.cvtColor(boardBgr, hsv, Imgproc.COLOR_BGR2HSV)
    // Tuned HSV range for typical green highlight; may tweak if UI color different
    val lowerGreen = Scalar(35.0, 60.0, 40.0)
    val upperGreen = Scalar(95.0, 255.0, 255.0)
    val greenMaskFull = Mat()
    Core.inRange(hsv, lowerGreen, upperGreen, greenMaskFull)

    val whiteNorm = normalizeSensitivity(WHITE_DETECTION_SENSITIVITY)
    val blackNorm = normalizeSensitivity(BLACK_DETECTION_SENSITIVITY)
    val emptyNorm = normalizeSensitivity(EMPTY_DETECTION_SENSITIVITY)

    // Improved threshold calculations
    val POS_BRIGHT_DIFF = max(5.0, 20.0 - (whiteNorm * 0.15))
    val MIN_WHITE_STD = max(8.0, 25.0 - (whiteNorm * 0.2))
    val WHITE_EDGE_BOOST = whiteNorm * 0.2
    val WHITE_BRIGHTNESS_THRESH = max(110.0, 180.0 - (whiteNorm * 0.4))

    val NEG_BRIGHT_DIFF = min(-5.0, -15.0 - (blackNorm * 0.1))
    val BLACK_STD_THRESH = max(3.0, 8.0 + (blackNorm * 0.08))
    val BLACK_EDGE_BOOST = blackNorm * 0.2

    val EDGE_COUNT_THRESH = max(10.0, 60.0 - (emptyNorm * 0.5))
    val STD_BG_THRESH = max(2.0, 8.0 + (emptyNorm * 0.1))
    val MAX_EMPTY_STD = max(15.0, 25.0 + (emptyNorm * 0.2))
    val EMPTY_EDGE_THRESH = max(5.0, 25.0 - (emptyNorm * 0.2))

    // New: Calculate overall board brightness for adaptive thresholds
    val boardMean = Core.mean(grayNorm).`val`[0]
    val brightnessFactor = boardMean / 128.0 // Normalize around middle gray

    for (r in 0 until 8) {
        for (c in 0 until 8) {
            val x1 = c * cellSize
            val y1 = r * cellSize
            val x2 = x1 + cellSize
            val y2 = y1 + cellSize

            // Safety bounds
            if (x2 > grayNorm.cols() || y2 > grayNorm.rows()) continue

            val square = grayNorm.submat(y1, y2, x1, x2)
            if (square.rows() == 0 || square.cols() == 0) {
                square.release()
                continue
            }

            // Check green overlay presence (from greenMaskFull)
            val greenPatch = greenMaskFull.submat(y1, y2, x1, x2)
            val greenPixels = Core.countNonZero(greenPatch)
            val totalPixels = greenPatch.total()
            val greenRatio = if (totalPixels > 0) greenPixels.toDouble() / totalPixels else 0.0

            // Heuristics:
            // - If green covers large area (e.g., entire square) we skip detection.
            // - If green is small (dot/marker), we mask out green pixels and compute stats from remaining pixels.
            val GREEN_SKIP_FULL_THRESHOLD = 0.30 // if >30% green, skip (likely overlay)
            val label = "${files[c]}${ranks[r]}"

            if (greenRatio > GREEN_SKIP_FULL_THRESHOLD) {
                // Too much green, skip this square
                greenPatch.release()
                square.release()
                continue
            }

            // create mask for non-green pixels (white = keep, 0 = ignore)
            val nonGreenMask = Mat()
            Core.bitwise_not(greenPatch, nonGreenMask) // now nonGreenMask: 255 where not green, 0 where green

            // inner margin to avoid borders
            val m = max(1, (cellSize * 0.25).toInt())
            val inner = square.submat(m, cellSize - m, m, cellSize - m)
            if (inner.empty()) {
                inner.release()
                nonGreenMask.release()
                greenPatch.release()
                square.release()
                continue
            }

            // Prepare inner mask corresponding to inner region
            val innerMask = Mat()
            try {
                innerMask.create(inner.rows(), inner.cols(), CvType.CV_8UC1)
                // extract corresponding region from nonGreenMask
                val tmp = nonGreenMask.submat(m, cellSize - m, m, cellSize - m)
                tmp.copyTo(innerMask)
                tmp.release()
            } catch (e: Exception) {
                // fallback: if mask extraction fails, treat entire inner as non-masked
                innerMask.setTo(Scalar(255.0))
            }

            // Compute mean and std dev ignoring green pixels by passing mask
            val meanMat = MatOfDouble()
            val stdMat = MatOfDouble()
            Core.meanStdDev(inner, meanMat, stdMat, innerMask)
            val innerStd = stdMat.toArray()[0]
            val innerMean = Core.mean(inner, innerMask).`val`[0]

            // fallback if mask removed too many pixels (avoid empty stats)
            if (innerMask.total() == Core.countNonZero(innerMask).toDouble() && innerMask.total() == 0.0) {
                // mask empty, fallback to unmasked
                Core.meanStdDev(inner, meanMat, stdMat)
                val fallbackStd = stdMat.toArray()[0]
                val fallbackMean = Core.mean(inner).`val`[0]
                // assign
                meanMat.put(0, 0, fallbackMean)
                stdMat.put(0, 0, fallbackStd)
            }

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
                        // compute patch mean/std ignoring green using appropriate region of mask
                        val maskPatch = greenPatch.submat(rect)
                        val nonG = Mat()
                        Core.bitwise_not(maskPatch, nonG)
                        val pMean = Core.mean(patch, nonG).`val`[0]
                        val pStdMat = MatOfDouble()
                        Core.meanStdDev(patch, MatOfDouble(), pStdMat, nonG)
                        val pStd = pStdMat.toArray()[0]
                        patchMeans.add(pMean)
                        patchStds.add(pStd)
                        pStdMat.release()
                        nonG.release()
                        maskPatch.release()
                        patch.release()
                    } else {
                        patch.release()
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }

            val localBg = if (patchMeans.isNotEmpty()) patchMeans.sorted()[patchMeans.size / 2] else innerMean
            val localBgStd = if (patchStds.isNotEmpty()) patchStds.sorted()[patchStds.size / 2] else innerStd

            // For edge detection, neutralize green pixels by setting them to localBg so they don't produce edges
            val innerBlur = Mat()
            Imgproc.GaussianBlur(inner, innerBlur, Size(3.0, 3.0), 0.0)

            // convert innerBlur to 8U if needed (should be CV_8U already)
            val innerForEdges = innerBlur.clone()
            try {
                // set green pixels (where innerMask==0) to localBg so edges ignore them
                val maskNot = Mat()
                Core.bitwise_not(innerMask, maskNot) // maskNot=255 where green pixels
                innerForEdges.setTo(Scalar(localBg), maskNot)
                maskNot.release()
            } catch (e: Exception) {
                // ignore if something fails; proceed with unmodified innerBlur
            }

            val edges = Mat()
            Imgproc.Canny(innerForEdges, edges, 40.0, 120.0)
            val edgeCount = Core.countNonZero(edges)

            val absBrightness = innerMean
            val isVeryBright = absBrightness > WHITE_BRIGHTNESS_THRESH * brightnessFactor

            val diff = innerMean - localBg
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

            // More tolerant conditions to avoid missing white pieces
            val isLikelyWhitePiece = when {
                // Very bright with texture
                isVeryBright && innerStd > MIN_WHITE_STD -> true
                // Positive contrast relative to local background
                diff >= adaptivePosThresh && innerStd > max(6.0, MIN_WHITE_STD * 0.8) && absBrightness > 95 -> true
                // Edge evidence + mild positive brightness
                edgeCount >= adaptiveEdgeThresh && diff > -2 && innerStd > 6 -> true
                else -> false
            }

            val isLikelyBlackPiece = when {
                diff <= adaptiveNegThresh && innerStd >= BLACK_STD_THRESH && (localBg - innerMean) >= 12.0 && absBrightness < 150 -> true
                edgeCount >= adaptiveEdgeThresh && diff < -8 && innerStd > 5 -> true
                else -> false
            }

            when {
                isLikelyWhitePiece -> {
                    pieceDetected = true
                    colorIsWhite = true
                }
                isLikelyBlackPiece -> {
                    pieceDetected = true
                    colorIsWhite = false
                }
                edgeCount >= adaptiveEdgeThresh && !isLikelyWhitePiece && !isLikelyBlackPiece -> {
                    pieceDetected = true
                    colorIsWhite = diff > 0 || absBrightness > 115
                }
            }

            // Additional validation
            if (pieceDetected) {
                if (colorIsWhite && absBrightness < 70) {
                    pieceDetected = false
                } else if (!colorIsWhite && absBrightness > 200) {
                    pieceDetected = false
                } else if (innerStd < 4 && edgeCount < 12) {
                    pieceDetected = false
                }
            }

            // Filter out tiny bright noise
            val brightMask = Mat()
            Core.compare(inner, Scalar(200.0), brightMask, Core.CMP_GT)
            val brightPixels = Core.countNonZero(brightMask)
            brightMask.release()
            val brightRatio = if (inner.total() > 0) brightPixels.toDouble() / inner.total() else 0.0
            val isSmallBrightSpot = (brightRatio < 0.04 && edgeCount < 15 && innerStd < 22 && absBrightness > 185)
            if (isSmallBrightSpot) pieceDetected = false

            if (pieceDetected) {
                if (colorIsWhite) whiteSquares.add(label) else blackSquares.add(label)
            }

            // release mats for this iteration
            innerBlur.release()
            innerForEdges.release()
            edges.release()
            meanMat.release()
            stdMat.release()
            innerMask.release()
            inner.release()
            nonGreenMask.release()
            greenPatch.release()
            square.release()
        }
    }

    // cleanup
    grayBoard.release()
    grayNorm.release()
    hsv.release()
    greenMaskFull.release()

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
        resized.release()
        img.release()
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
    boardWarped.release()

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
