package com.example.chessdetector

import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.*

object ChessMoveDetector {

    const val WHITE_DETECTION_SENSITIVITY = 95
    const val BLACK_DETECTION_SENSITIVITY = 50
    const val EMPTY_DETECTION_SENSITIVITY = 50

    init {
        OpenCVLoader.initDebug()
    }

    private fun shrinkPolygon(pts: MatOfPoint, shrinkFactor: Double = 0.95): MatOfPoint {
        val ptsArray = pts.toArray()
        val center = Point(
            ptsArray.map { it.x }.average(),
            ptsArray.map { it.y }.average()
        )
        val shrunk = ptsArray.map {
            val direction = Point(it.x - center.x, it.y - center.y)
            Point(center.x + direction.x * shrinkFactor, center.y + direction.y * shrinkFactor)
        }
        val result = MatOfPoint()
        result.fromList(shrunk)
        return result
    }

    private fun detectLargestSquareLike(img: Mat): Pair<Mat, MatOfPoint?> {
        val orig = img.clone()
        val gray = Mat()
        Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.bilateralFilter(gray, gray, 11, 17.0, 17.0)
        
        val edges = Mat()
        Imgproc.Canny(gray, edges, 40.0, 150.0)
        
        val kernel = Mat()
        Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2)
        Imgproc.erode(edges, edges, kernel, Point(-1.0, -1.0), 1)

        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val imgArea = img.size().width * img.size().height
        var best: MatOfPoint? = null
        var bestArea = 0.0

        for (c in contours) {
            val peri = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*c.toArray()), approx, 0.02 * peri, true)
            
            if (approx.rows() == 4) {
                val area = Imgproc.contourArea(c)
                if (area > 0.2 * imgArea) {
                    val rect = Imgproc.boundingRect(c)
                    val ratio = rect.width.toDouble() / rect.height
                    if (ratio in 0.7..1.3 && area > bestArea) {
                        best = MatOfPoint(*approx.toArray().map { Point(it.x, it.y) }.toTypedArray())
                        bestArea = area
                    }
                }
            }
        }

        if (best == null && contours.isNotEmpty()) {
            val c = contours.maxByOrNull { Imgproc.contourArea(it) }
            val rect = Imgproc.boundingRect(c)
            val pts = arrayOf(
                Point(rect.x.toDouble(), rect.y.toDouble()),
                Point((rect.x + rect.width).toDouble(), rect.y.toDouble()),
                Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
                Point(rect.x.toDouble(), (rect.y + rect.height).toDouble())
            )
            best = MatOfPoint(*pts)
        }

        val imgShow = orig.clone()
        var innerPts: MatOfPoint? = null
        
        best?.let {
            innerPts = shrinkPolygon(it, 0.962)
            Imgproc.polylines(imgShow, listOf(it), true, Scalar(0.0, 255.0, 0.0), 3)
            Imgproc.polylines(imgShow, listOf(innerPts!!), true, Scalar(0.0, 0.0, 255.0), 8)
        }

        gray.release()
        edges.release()
        kernel.release()
        hierarchy.release()
        
        return Pair(imgShow, innerPts)
    }

    private fun normalizeSensitivity(sensitivity: Int): Double {
        return if (sensitivity <= 100) sensitivity.toDouble()
        else 100 + (sensitivity - 100) * 0.5
    }

    private fun meanStd(mat: Mat): Pair<Double, Double> {
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(mat, mean, std)
        return Pair(mean.toArray()[0], std.toArray()[0])
    }

    private fun sortCorners(pts: MatOfPoint): MatOfPoint2f {
        val points = pts.toArray()
        val sum = points.map { it.x + it.y }.toDoubleArray()
        val diff = points.map { it.x - it.y }.toDoubleArray()
        
        val topLeft = points[sum.indexOf(sum.minOrNull()!!)]
        val topRight = points[diff.indexOf(diff.minOrNull()!!)]
        val bottomRight = points[sum.indexOf(sum.maxOrNull()!!)]
        val bottomLeft = points[diff.indexOf(diff.maxOrNull()!!)]
        
        val sorted = MatOfPoint2f(topLeft, topRight, bottomRight, bottomLeft)
        return sorted
    }

    private fun detectPiecesOnBoard(
        grayBoard: Mat,
        files: String,
        ranks: String,
        cellSize: Int
    ): Pair<MutableSet<String>, MutableSet<String>> {

        val whiteSquares = mutableSetOf<String>()
        val blackSquares = mutableSetOf<String>()

        val whiteNorm = normalizeSensitivity(WHITE_DETECTION_SENSITIVITY)
        val blackNorm = normalizeSensitivity(BLACK_DETECTION_SENSITIVITY)
        val emptyNorm = normalizeSensitivity(EMPTY_DETECTION_SENSITIVITY)

        val POS_BRIGHT_DIFF = max(1.0, 25 - (whiteNorm * 0.2))
        val MIN_WHITE_STD = max(1.0, 30 - (whiteNorm * 0.25))
        val WHITE_EDGE_BOOST = whiteNorm * 0.3
        val WHITE_BRIGHTNESS_THRESH = max(100.0, 200 - (whiteNorm * 0.5))
        val NEG_BRIGHT_DIFF = min(-1.0, -10 - (blackNorm * 0.2))
        val BLACK_STD_THRESH = max(1.0, 5 + (blackNorm * 0.1))
        val BLACK_EDGE_BOOST = blackNorm * 0.3
        val EDGE_COUNT_THRESH = max(5.0, 80 - (emptyNorm * 0.6))
        val STD_BG_THRESH = max(1.0, 5 + (emptyNorm * 0.15))
        val MAX_EMPTY_STD = max(10.0, 15 + (emptyNorm * 0.25))
        val EMPTY_EDGE_THRESH = max(1.0, 20 - (emptyNorm * 0.15))

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
                if (cellSize - 2*m <= 0) continue
                
                val inner = square.submat(m, cellSize - m, m, cellSize - m)
                if (inner.empty()) continue

                val (innerMean, innerStd) = meanStd(inner)

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
                    if (rect.y + rect.height > square.rows() || rect.x + rect.width > square.cols()) continue
                    try {
                        val patch = square.submat(rect.y, rect.y + rect.height, rect.x, rect.x + rect.width)
                        val (patchMean, patchStd) = meanStd(patch)
                        patchMeans.add(patchMean)
                        patchStds.add(patchStd)
                        patch.release()
                    } catch (e: Exception) {
                        continue
                    }
                }

                val localBg = if (patchMeans.isNotEmpty()) patchMeans.sorted()[patchMeans.size / 2] else innerMean
                val localBgStd = if (patchStds.isNotEmpty()) patchStds.sorted()[patchStds.size / 2] else innerStd

                val blurred = Mat()
                Imgproc.GaussianBlur(inner, blurred, Size(3.0, 3.0), 0.0)

                val edges = Mat()
                Imgproc.Canny(blurred, edges, 50.0, 150.0)
                val edgeCount = Core.countNonZero(edges).toDouble()

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

                val brightThreshold = Mat()
                Core.compare(inner, Scalar(200.0), brightThreshold, Core.CMP_GT)
                val brightPixels = Core.countNonZero(brightThreshold).toDouble()
                val brightRatio = brightPixels / (inner.size().width * inner.size().height)
                
                val isSmallBrightSpot = (brightRatio < 0.05 && edgeCount < 15 && 
                        innerStd < 25 && absBrightness > 180)

                if (edgeCount >= curEdgeThresh && !isSmallBrightSpot) {
                    pieceDetected = true
                    colorIsWhite = (diff > 0) || isVeryBright
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
                    if (colorIsWhite) whiteSquares.add(label) else blackSquares.add(label)
                }

                inner.release()
                square.release()
                edges.release()
                blurred.release()
                brightThreshold.release()
            }
        }
        return Pair(whiteSquares, blackSquares)
    }

    fun getBoardState(imgPath: String, boardName: String): Map<String, Set<String>>? {
        Log.d("ChessDetector", "--- Processing $boardName ---")
        val img = Imgcodecs.imread(imgPath)
        if (img.empty()) {
            Log.e("ChessDetector", "❌ Could not load $imgPath")
            return null
        }

        val (detectedImg, innerPts) = detectLargestSquareLike(img)
        if (innerPts == null) {
            Log.e("ChessDetector", "❌ No board found")
            return null
        }

        val sortedCorners = sortCorners(innerPts)
        
        val side = 800
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((side - 1).toDouble(), 0.0),
            Point((side - 1).toDouble(), (side - 1).toDouble()),
            Point(0.0, (side - 1).toDouble())
        )
        
        val M = Imgproc.getPerspectiveTransform(sortedCorners, dst)
        val boardWarped = Mat()
        Imgproc.warpPerspective(img, boardWarped, M, Size(side.toDouble(), side.toDouble()))

        val cellSize = side / 8
        
        val topLeft = boardWarped.submat(0, cellSize, 0, cellSize)
        val bottomRight = boardWarped.submat(7 * cellSize, 8 * cellSize, 7 * cellSize, 8 * cellSize)
        
        val grayTopLeft = Mat()
        val grayBottomRight = Mat()
        Imgproc.cvtColor(topLeft, grayTopLeft, Imgproc.COLOR_BGR2GRAY)
        Imgproc.cvtColor(bottomRight, grayBottomRight, Imgproc.COLOR_BGR2GRAY)
        
        val meanTop = Core.mean(grayTopLeft).`val`[0]
        val meanBottom = Core.mean(grayBottomRight).`val`[0]
        val whiteOnBottom = meanBottom > meanTop

        val files = if (whiteOnBottom) "abcdefgh" else "hgfedcba"
        val ranks = if (whiteOnBottom) "87654321" else "12345678"

        val grayBoard = Mat()
        Imgproc.cvtColor(boardWarped, grayBoard, Imgproc.COLOR_BGR2GRAY)

        val (whiteSquares, blackSquares) = detectPiecesOnBoard(grayBoard, files, ranks, cellSize)
        Log.d("ChessDetector", "✅ $boardName: ${whiteSquares.size} white, ${blackSquares.size} black pieces")

        detectedImg.release()
        boardWarped.release()
        grayBoard.release()
        topLeft.release()
        bottomRight.release()
        grayTopLeft.release()
        grayBottomRight.release()
        M.release()
        sortedCorners.release()
        innerPts.release()

        return mapOf("white" to whiteSquares, "black" to blackSquares)
    }

    fun detectUciMoves(state1: Map<String, Set<String>>?, state2: Map<String, Set<String>>?): List<String> {
        if (state1 == null || state2 == null) {
            Log.e("ChessDetector", "❌ One or both board states are null")
            return emptyList()
        }

        val white1 = state1["white"] ?: emptySet()
        val black1 = state1["black"] ?: emptySet()
        val white2 = state2["white"] ?: emptySet()
        val black2 = state2["black"] ?: emptySet()

        val whiteMoved = white1 - white2
        val blackMoved = black1 - black2
        val whiteAppeared = white2 - white1
        val blackAppeared = black2 - black1

        val moves = mutableListOf<String>()

        if (whiteMoved.size == 1 && whiteAppeared.size == 1) {
            val source = whiteMoved.first()
            val destination = whiteAppeared.first()
            moves.add("White moved ${source}${destination}")
        }

        if (blackMoved.size == 1 && blackAppeared.size == 1) {
            val source = blackMoved.first()
            val destination = blackAppeared.first()
            moves.add("Black moved ${source}${destination}")
        }

        if (whiteMoved.size == 1 && blackMoved.size == 1 && whiteAppeared.isEmpty()) {
            val whiteSource = whiteMoved.first()
            val blackCaptured = blackMoved.first()
            moves.add("White captured ${whiteSource}${blackCaptured}")
        }

        if (blackMoved.size == 1 && whiteMoved.size == 1 && blackAppeared.isEmpty()) {
            val blackSource = blackMoved.first()
            val whiteCaptured = whiteMoved.first()
            moves.add("Black captured ${blackSource}${whiteCaptured}")
        }

        if (moves.isNotEmpty()) {
            Log.d("ChessDetector", "🎯 DETECTED MOVES:")
            moves.forEach { Log.d("ChessDetector", "   $it") }
        } else {
            Log.d("ChessDetector", "❌ No clear moves detected")
            Log.d("ChessDetector", "   White changes: ${whiteMoved.sorted()} -> ${whiteAppeared.sorted()}")
            Log.d("ChessDetector", "   Black changes: ${blackMoved.sorted()} -> ${blackAppeared.sorted()}")
        }

        return moves
    }

    fun compareBoardsAndDetectMoves(board1Path: String, board2Path: String): List<String> {
        Log.d("ChessDetector", "♟️ CHESS MOVE DETECTOR ♟️")
        Log.d("ChessDetector", "Sensitivity: White=$WHITE_DETECTION_SENSITIVITY, Black=$BLACK_DETECTION_SENSITIVITY, Empty=$EMPTY_DETECTION_SENSITIVITY")

        val state1 = getBoardState(board1Path, "First Board")
        val state2 = getBoardState(board2Path, "Second Board")

        return if (state1 != null && state2 != null) {
            detectUciMoves(state1, state2)
        } else {
            Log.e("ChessDetector", "❌ Failed to process one or both boards")
            emptyList()
        }
    }
}
