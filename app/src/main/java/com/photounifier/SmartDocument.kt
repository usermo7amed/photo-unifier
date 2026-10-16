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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative quadrilateral detection for paper/posters.
 *
 * Returns a perspective-corrected bitmap only when a large, convex four-corner
 * contour is found with enough area and frame margin. Otherwise returns null,
 * allowing the caller to preserve the original framing.
 */
object SmartDocument {
    private const val MAX_DETECTION_SIDE = 1000
    private const val MIN_AREA_RATIO = 0.22
    private const val MAX_AREA_RATIO = 0.96

    fun rectifyOrNull(source: Bitmap): Bitmap? {
        if (source.width < 240 || source.height < 240) return null

        val scale = min(1.0, MAX_DETECTION_SIDE.toDouble() / max(source.width, source.height))
        val detection = Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * scale).toInt()),
            max(1, (source.height * scale).toInt()),
            true
        )
        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            Utils.bitmapToMat(detection, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 55.0, 165.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            try {
                Imgproc.dilate(edges, edges, kernel)
            } finally {
                kernel.release()
            }
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            val frameArea = detection.width.toDouble() * detection.height
            var best: List<Point>? = null
            var bestScore = 0.0
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                val ratio = area / frameArea
                if (ratio !in MIN_AREA_RATIO..MAX_AREA_RATIO || ratio <= bestScore) continue

                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
                    val perimeter = Imgproc.arcLength(curve, true)
                    Imgproc.approxPolyDP(curve, approx, perimeter * 0.02, true)
                    val points = approx.toArray()
                    if (points.size != 4) continue
                    val polygon = MatOfPoint(*points)
                    try {
                        if (!Imgproc.isContourConvex(polygon)) continue
                    } finally {
                        polygon.release()
                    }
                    if (points.any {
                            it.x < detection.width * 0.012 ||
                            it.y < detection.height * 0.012 ||
                            it.x > detection.width * 0.988 ||
                            it.y > detection.height * 0.988
                        }) continue

                    val ordered = orderCorners(points)
                    val width = max(distance(ordered[0], ordered[1]), distance(ordered[2], ordered[3]))
                    val height = max(distance(ordered[0], ordered[3]), distance(ordered[1], ordered[2]))
                    if (width < 100 || height < 100) continue
                    val aspect = max(width / height, height / width)
                    if (aspect > 3.2) continue

                    // Prefer larger candidates, but avoid a nearly full-frame border.
                    val score = ratio * (1.0 - max(0.0, ratio - 0.88) * 2.0)
                    if (score > bestScore) {
                        bestScore = score
                        best = ordered
                    }
                } finally {
                    curve.release()
                    approx.release()
                }
            }

            val corners = best ?: return null
            val sx = source.width.toDouble() / detection.width
            val sy = source.height.toDouble() / detection.height
            val srcPoints = MatOfPoint2f(*corners.map { Point(it.x * sx, it.y * sy) }.toTypedArray())
            val width = max(distance(corners[0], corners[1]), distance(corners[2], corners[3])) * sx
            val height = max(distance(corners[0], corners[3]), distance(corners[1], corners[2])) * sy
            val outWidth = width.toInt().coerceIn(1, source.width * 2)
            val outHeight = height.toInt().coerceIn(1, source.height * 2)
            if (outWidth < 100 || outHeight < 100) {
                srcPoints.release()
                return null
            }

            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(outWidth - 1.0, 0.0),
                Point(outWidth - 1.0, outHeight - 1.0),
                Point(0.0, outHeight - 1.0)
            )
            val sourceMat = Mat()
            val transform = Mat()
            val warped = Mat()
            try {
                Utils.bitmapToMat(source, sourceMat)
                Imgproc.getPerspectiveTransform(srcPoints, dstPoints).copyTo(transform)
                Imgproc.warpPerspective(
                    sourceMat, warped, transform, Size(outWidth.toDouble(), outHeight.toDouble()),
                    Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE
                )
                val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, result)
                return result
            } finally {
                srcPoints.release()
                dstPoints.release()
                sourceMat.release()
                transform.release()
                warped.release()
            }
        } finally {
            detection.recycle()
            rgba.release()
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun orderCorners(points: Array<Point>): List<Point> {
        val tl = points.minBy { it.x + it.y }
        val br = points.maxBy { it.x + it.y }
        val tr = points.minBy { it.y - it.x }
        val bl = points.maxBy { it.y - it.x }
        return listOf(tl, tr, br, bl)
    }

    private fun distance(a: Point, b: Point): Double = hypot(a.x - b.x, a.y - b.y)
}
