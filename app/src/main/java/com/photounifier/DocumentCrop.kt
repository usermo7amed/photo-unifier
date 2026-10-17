package com.photounifier

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Coordinates are in bitmap pixels, ordered top-left, top-right, bottom-right, bottom-left. */
data class CropPoint(val x: Float, val y: Float)

object DocumentCrop {
    /**
     * Detects a document using several complementary edge/threshold passes.
     * A single Canny pass often misses pale paper, shadows, or broken edges.
     * Perspective correction still uses four outer corners; internal contour
     * points are evidence for finding those corners, not additional warp anchors.
     */
    fun detect(bitmap: Bitmap): List<CropPoint>? {
        if (bitmap.width < 32 || bitmap.height < 32) return null
        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        val work = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            Utils.bitmapToMat(bitmap, rgba)
            if (rgba.channels() == 4) Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            else Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            val imageArea = bitmap.width.toDouble() * bitmap.height
            var best: Array<Point>? = null
            var bestScore = 0.0

            // Try both continuous edges and adaptive thresholding. This helps
            // when a page edge is weak or partially hidden by a cast shadow.
            val passes = 3
            for (pass in 0 until passes) {
                work.release()
                when (pass) {
                    0 -> {
                        Imgproc.Canny(blurred, work, 25.0, 85.0)
                        Imgproc.morphologyEx(work, work, Imgproc.MORPH_CLOSE, kernel)
                    }
                    1 -> {
                        Imgproc.Canny(blurred, work, 45.0, 135.0)
                        Imgproc.dilate(work, work, kernel)
                        Imgproc.morphologyEx(work, work, Imgproc.MORPH_CLOSE, kernel)
                    }
                    else -> {
                        Imgproc.adaptiveThreshold(
                            blurred, work, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                            Imgproc.THRESH_BINARY, 31, 9.0
                        )
                        Imgproc.morphologyEx(work, work, Imgproc.MORPH_CLOSE, kernel)
                    }
                }
                contours.clear()
                Imgproc.findContours(work, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                for (contour in contours.sortedByDescending { abs(Imgproc.contourArea(it)) }.take(100)) {
                    val area = abs(Imgproc.contourArea(contour))
                    val fraction = area / imageArea
                    if (fraction !in 0.045..0.985) continue
                    val curve = MatOfPoint2f(*contour.toArray())
                    try {
                        val perimeter = Imgproc.arcLength(curve, true)
                        for (factor in doubleArrayOf(0.008, 0.012, 0.018, 0.025, 0.035, 0.045)) {
                            val approx = MatOfPoint2f()
                            try {
                                Imgproc.approxPolyDP(curve, approx, perimeter * factor, true)
                                val points = approx.toArray()
                                if (points.size != 4 || !isConvex(points)) continue
                                val ordered = order(points)
                                val candidateFraction = abs(Imgproc.contourArea(approx)) / imageArea
                                if (candidateFraction !in 0.045..0.985) continue
                                val cosine = cornerCosine(ordered)
                                // Prefer large, nearly rectangular convex candidates,
                                // but don't reject strong perspective solely for non-right angles.
                                val centerX = ordered.map { it.x }.average()
                                val centerY = ordered.map { it.y }.average()
                                val centerPenalty = sqrt(
                                    ((centerX - bitmap.width / 2.0) / bitmap.width).let { it * it } +
                                    ((centerY - bitmap.height / 2.0) / bitmap.height).let { it * it }
                                ).coerceAtMost(0.7)
                                val score = candidateFraction * (1.0 - cosine * 0.55) * (1.0 - centerPenalty * 0.12)
                                if (score > bestScore) {
                                    best = ordered
                                    bestScore = score
                                }
                            } finally { approx.release() }
                        }
                    } finally { curve.release() }
                }
                contours.forEach { it.release() }
                contours.clear()
            }
            return best?.takeIf { bestScore > 0.035 }?.map {
                CropPoint(it.x.toFloat().coerceIn(0f, (bitmap.width - 1).toFloat()),
                    it.y.toFloat().coerceIn(0f, (bitmap.height - 1).toFloat()))
            }
        } finally {
            contours.forEach { it.release() }
            rgba.release(); gray.release(); blurred.release(); hierarchy.release()
            kernel.release(); work.release()
        }
    }

    fun fullFrame(width: Int, height: Int): List<CropPoint> = listOf(
        CropPoint(0f, 0f), CropPoint((width - 1).toFloat(), 0f),
        CropPoint((width - 1).toFloat(), (height - 1).toFloat()),
        CropPoint(0f, (height - 1).toFloat())
    )

    fun isValid(points: List<CropPoint>, width: Int, height: Int): Boolean {
        if (points.size != 4 || width < 2 || height < 2) return false
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..(width - 1).toFloat() || it.y !in 0f..(height - 1).toFloat() }) return false
        val polygon = points.map { it.x.toDouble() to it.y.toDouble() }
        var twiceArea = 0.0
        for (i in 0..3) {
            val a = polygon[i]; val b = polygon[(i + 1) % 4]
            twiceArea += a.first * b.second - b.first * a.second
        }
        if (abs(twiceArea) < width.toDouble() * height * 0.015) return false
        if (segmentsIntersect(polygon[0], polygon[1], polygon[2], polygon[3]) ||
            segmentsIntersect(polygon[1], polygon[2], polygon[3], polygon[0])) return false
        // A valid perspective quadrilateral must be strictly convex. Merely
        // rejecting intersecting opposite edges still allows concave point sets,
        // which can produce folded or unstable perspective transforms.
        val crosses = points.indices.map { i ->
            val a = points[(i + 3) % 4]
            val b = points[i]
            val c = points[(i + 1) % 4]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        if (crosses.any { abs(it) <= 1f }) return false
        return crosses.all { it > 0f } || crosses.all { it < 0f }
    }

    fun warp(bitmap: Bitmap, points: List<CropPoint>): Bitmap {
        require(isValid(points, bitmap.width, bitmap.height)) { "زوايا القص غير صالحة" }
        val ordered = order(points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
        val outWidth = max(distance(ordered[0], ordered[1]), distance(ordered[3], ordered[2])).roundToInt().coerceIn(2, 4096)
        val outHeight = max(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2])).roundToInt().coerceIn(2, 4096)
        val source = MatOfPoint2f(*ordered)
        val target = MatOfPoint2f(
            Point(0.0, 0.0), Point((outWidth - 1).toDouble(), 0.0),
            Point((outWidth - 1).toDouble(), (outHeight - 1).toDouble()),
            Point(0.0, (outHeight - 1).toDouble())
        )
        val transform = Imgproc.getPerspectiveTransform(source, target, Core.DECOMP_LU)
        val rgba = Mat()
        val output = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.warpPerspective(rgba, output, transform, Size(outWidth.toDouble(), outHeight.toDouble()), Imgproc.INTER_CUBIC)
            return Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(output, it) }
        } finally {
            source.release(); target.release(); transform.release(); rgba.release(); output.release()
        }
    }

    private fun isConvex(p: Array<Point>): Boolean {
        val contour = MatOfPoint(*p)
        return try { Imgproc.isContourConvex(contour) } finally { contour.release() }
    }
    private fun order(points: Array<Point>): Array<Point> {
        val cx = points.map { it.x }.average(); val cy = points.map { it.y }.average()
        val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        val rotated = Array(4) { sorted[(start + it) % 4] }
        // Ensure TL -> TR -> BR -> BL in image coordinates.
        if (rotated[1].x < rotated[3].x) return arrayOf(rotated[0], rotated[3], rotated[2], rotated[1])
        return rotated
    }
    private fun cornerCosine(p: Array<Point>): Double {
        var maxCos = 0.0
        for (i in 0..3) {
            val a = p[(i + 3) % 4]; val b = p[i]; val c = p[(i + 1) % 4]
            val ux = a.x - b.x; val uy = a.y - b.y; val vx = c.x - b.x; val vy = c.y - b.y
            val denom = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            if (denom < 1e-6) return 1.0
            maxCos = max(maxCos, abs((ux * vx + uy * vy) / denom))
        }
        return maxCos
    }
    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
    private fun segmentsIntersect(a: Pair<Double, Double>, b: Pair<Double, Double>, c: Pair<Double, Double>, d: Pair<Double, Double>): Boolean {
        fun orient(p: Pair<Double, Double>, q: Pair<Double, Double>, r: Pair<Double, Double>) =
            (q.first - p.first) * (r.second - p.second) - (q.second - p.second) * (r.first - p.first)
        val o1 = orient(a, b, c); val o2 = orient(a, b, d); val o3 = orient(c, d, a); val o4 = orient(c, d, b)
        return (o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0)
    }
}
