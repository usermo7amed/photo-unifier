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
    fun detect(bitmap: Bitmap): List<CropPoint>? {
        val rgba = Mat()
        val rgb = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closed = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 35.0, 110.0)
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val imageArea = bitmap.width.toDouble() * bitmap.height
            var best: Array<Point>? = null
            var bestScore = 0.0
            for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(60)) {
                val area = abs(Imgproc.contourArea(contour))
                if (area / imageArea !in 0.08..0.995) continue
                val curve = MatOfPoint2f(*contour.toArray())
                try {
                    val perimeter = Imgproc.arcLength(curve, true)
                    for (factor in doubleArrayOf(0.012, 0.018, 0.025, 0.035)) {
                        val approx = MatOfPoint2f()
                        try {
                            Imgproc.approxPolyDP(curve, approx, perimeter * factor, true)
                            val points = approx.toArray()
                            if (points.size != 4 || !isConvex(points) || cornerCosine(points) > 0.75) continue
                            val ordered = order(points)
                            val fraction = abs(Imgproc.contourArea(approx)) / imageArea
                            val score = fraction * (1.0 - cornerCosine(ordered))
                            if (fraction in 0.08..0.995 && score > bestScore) {
                                best = ordered
                                bestScore = score
                            }
                        } finally { approx.release() }
                    }
                } finally { curve.release() }
            }
            return best?.takeIf { bestScore > 0.055 }?.map { CropPoint(it.x.toFloat(), it.y.toFloat()) }
        } finally {
            contours.forEach { it.release() }
            rgba.release(); rgb.release(); gray.release(); blurred.release()
            edges.release(); hierarchy.release(); kernel.release(); closed.release()
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
        return points.indices.all { i ->
            val a = points[(i + 3) % 4]; val b = points[i]; val c = points[(i + 1) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            abs(cross) > 1f
        }
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
