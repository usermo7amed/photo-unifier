package com.photounifier

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** OpenCV image I/O and mode-specific enhancement pipelines. */
object Processor {
    private const val MAX_SIDE = 4096
    private const val NEUTRAL_CHROMA = 14.0
    private const val MIN_NEUTRAL_FRACTION = 0.05
    private const val WB_MIN = 0.85
    private const val WB_MAX = 1.2

    private data class ToneConfig(
        val target: Double,
        val alpha: Double,
        val gainMin: Double,
        val gainMax: Double,
        val claheClip: Double,
        val saturation: Double,
        val sharpening: Double,
        val whiteBalance: Boolean
    )

    private val interiorConfig = ToneConfig(0.50, 0.50, 0.62, 2.25, 2.0, 1.12, 0.24, true)
    private val exteriorConfig = ToneConfig(0.46, 0.52, 0.55, 1.95, 1.8, 1.08, 0.22, false)
    private val generalConfig = ToneConfig(0.50, 0.45, 0.65, 2.20, 2.0, 1.08, 0.25, true)
    private val posterConfig = ToneConfig(0.50, 0.30, 0.78, 1.60, 1.45, 1.02, 0.16, false)

    /** Keeps native OpenCV allocations alive until the pipeline finishes, then releases them. */
    private class Pool : AutoCloseable {
        private val items = ArrayList<Mat>()
        fun mat(): Mat = Mat().also { items.add(it) }
        fun <T : Mat> own(value: T): T = value.also { items.add(it) }
        fun split(src: Mat): List<Mat> {
            val output = ArrayList<Mat>()
            Core.split(src, output)
            items.addAll(output)
            return output
        }
        override fun close() {
            items.forEach { it.release() }
            items.clear()
        }
    }

    // ------------------------------------------------------------------ I/O

    fun load(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IllegalStateException("ملف الصورة غير صالح أو غير مدعوم")
        }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IllegalStateException("تعذر قراءة الصورة")

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        return applyOrientation(bitmap, orientation)
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun save(context: Context, bitmap: Bitmap, displayName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoUnifier")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("تعذر إنشاء ملف الصورة")
        val written = resolver.openOutputStream(uri)?.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)
        } ?: false
        if (!written) {
            resolver.delete(uri, null, null)
            throw IllegalStateException("تعذر حفظ الصورة")
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    // ----------------------------------------------------------- entry point

    /** Backwards-compatible entry point for callers that do not run classification. */
    fun enhance(src: Bitmap): Bitmap = enhance(src, ImageKind.GENERAL)

    fun enhance(src: Bitmap, kind: ImageKind): Bitmap = Pool().use { pool ->
        val rgba = pool.mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = pool.mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

        val processed = when (kind) {
            ImageKind.DOCUMENT -> enhanceDocument(pool, rectifyPageIfConfident(pool, rgb))
            ImageKind.POSTER -> enhancePoster(pool, rectifyPageIfConfident(pool, rgb))
            ImageKind.INTERIOR -> enhancePhoto(pool, rgb, interiorConfig)
            ImageKind.EXTERIOR -> enhancePhoto(pool, rgb, exteriorConfig)
            ImageKind.GENERAL -> enhancePhoto(pool, rgb, generalConfig)
        }
        toBitmap(pool, processed)
    }

    // ------------------------------------------------------------ documents

    /**
     * Detects a page using both edge and adaptive-threshold candidates.
     *
     * A single Canny pass is fragile with shadows, low-contrast paper and textured
     * backgrounds. Multiple contour approximations make detection less dependent
     * on one threshold while the area/convexity/right-angle checks reject noise.
     */
    private fun rectifyPageIfConfident(pool: Pool, rgb: Mat): Mat {
        val width = rgb.cols()
        val height = rgb.rows()
        val totalArea = width.toDouble() * height.toDouble()
        if (width < 160 || height < 160) return rgb

        val gray = pool.mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val blurred = pool.mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edgeMask = pool.mat()
        Imgproc.Canny(blurred, edgeMask, 35.0, 110.0)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val closedEdges = pool.mat()
        Imgproc.morphologyEx(edgeMask, closedEdges, Imgproc.MORPH_CLOSE, kernel)
        kernel.release()

        val adaptiveMask = pool.mat()
        Imgproc.adaptiveThreshold(
            blurred, adaptiveMask, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV, 31, 10.0
        )

        var bestCorners: Array<Point>? = null
        var bestScore = 0.0

        fun inspectMask(mask: Mat) {
            val hierarchy = pool.mat()
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(
                mask, contours, hierarchy,
                Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
            )
            try {
                for (contour in contours.sortedByDescending { Imgproc.contourArea(it) }.take(60)) {
                    val contourArea = abs(Imgproc.contourArea(contour))
                    val areaFraction = contourArea / totalArea
                    // Include large pages that nearly fill the frame, but reject the frame itself.
                    if (areaFraction < 0.10 || areaFraction > 0.992) continue

                    val curve = MatOfPoint2f(*contour.toArray())
                    try {
                        val perimeter = Imgproc.arcLength(curve, true)
                        if (perimeter <= 0.0) continue

                        for (epsilonFactor in doubleArrayOf(0.012, 0.018, 0.025, 0.035)) {
                            val approximation = MatOfPoint2f()
                            try {
                                Imgproc.approxPolyDP(curve, approximation, perimeter * epsilonFactor, true)
                                val points = approximation.toArray()
                                if (points.size != 4) continue

                                val polygon = MatOfPoint(*points)
                                val valid = try {
                                    Imgproc.isContourConvex(polygon) && maxCornerCosine(points) < 0.68
                                } finally {
                                    polygon.release()
                                }
                                if (!valid) continue

                                val polygonArea = abs(Imgproc.contourArea(approximation))
                                val fraction = polygonArea / totalArea
                                if (fraction < 0.10 || fraction > 0.992) continue

                                val corners = orderCorners(points)
                                val top = distance(corners[0], corners[1])
                                val bottom = distance(corners[3], corners[2])
                                val left = distance(corners[0], corners[3])
                                val right = distance(corners[1], corners[2])
                                if (minOf(top, bottom, left, right) < minOf(width, height) * 0.08) continue

                                val anglePenalty = maxCornerCosine(points)
                                val borderPenalty = if (corners.all {
                                        it.x < width * 0.015 || it.x > width * 0.985 ||
                                            it.y < height * 0.015 || it.y > height * 0.985
                                    }) 0.65 else 1.0
                                val score = fraction * (1.0 - anglePenalty) * borderPenalty
                                if (score > bestScore) {
                                    bestScore = score
                                    bestCorners = corners
                                }
                            } finally {
                                approximation.release()
                            }
                        }
                    } finally {
                        curve.release()
                    }
                }
            } finally {
                contours.forEach { it.release() }
                hierarchy.release()
            }
        }

        inspectMask(closedEdges)
        inspectMask(adaptiveMask)

        val corners = bestCorners ?: return rgb
        // Avoid cropping to a weak or implausibly small detection.
        if (bestScore < 0.075) return rgb

        val outputWidth = max(
            distance(corners[0], corners[1]),
            distance(corners[3], corners[2])
        ).roundToInt().coerceIn(2, MAX_SIDE)
        val outputHeight = max(
            distance(corners[0], corners[3]),
            distance(corners[1], corners[2])
        ).roundToInt().coerceIn(2, MAX_SIDE)
        if (outputWidth < 80 || outputHeight < 80) return rgb

        val sourcePoints = pool.own(MatOfPoint2f(*corners))
        val targetPoints = pool.own(
            MatOfPoint2f(
                Point(0.0, 0.0),
                Point((outputWidth - 1).toDouble(), 0.0),
                Point((outputWidth - 1).toDouble(), (outputHeight - 1).toDouble()),
                Point(0.0, (outputHeight - 1).toDouble())
            )
        )
        val transform = pool.own(
            Imgproc.getPerspectiveTransform(sourcePoints, targetPoints, Core.DECOMP_LU)
        )
        val warped = pool.mat()
        Imgproc.warpPerspective(
            rgb, warped, transform,
            Size(outputWidth.toDouble(), outputHeight.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE,
            Scalar(255.0, 255.0, 255.0)
        )
        return warped
    }

    private fun enhanceDocument(pool: Pool, source: Mat): Mat {
        val lab = pool.mat()
        Imgproc.cvtColor(source, lab, Imgproc.COLOR_RGB2Lab)
        val channels = pool.split(lab)

        // Correct illumination on the luminance channel only; keep Lab a/b, hence ink
        // and coloured stamps retain their hue instead of becoming grayscale.
        removePaperShadows(pool, channels[0])
        applyClahe(pool, channels[0], 1.45)
        Core.merge(channels, lab)
        val rgb = pool.mat()
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
        return sharpen(pool, rgb, 0.12)
    }

    /** Large-scale illumination division designed for slowly varying paper shadows. */
    private fun removePaperShadows(pool: Pool, luminance: Mat) {
        val width = luminance.cols()
        val height = luminance.rows()
        val input = pool.mat()
        luminance.convertTo(input, CvType.CV_32F, 1.0 / 255.0)

        val small = pool.mat()
        Imgproc.resize(
            input,
            small,
            Size(max(width / 8, 1).toDouble(), max(height / 8, 1).toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_AREA
        )
        val sigma = max(max(small.cols(), small.rows()) / 7.0, 1.0)
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), sigma)
        val illumination = pool.mat()
        Imgproc.resize(
            small,
            illumination,
            Size(width.toDouble(), height.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_LINEAR
        )
        Core.add(illumination, Scalar(1e-3), illumination)

        val target = pool.mat()
        target.create(height, width, CvType.CV_32F)
        target.setTo(Scalar(0.92))
        val gain = pool.mat()
        Core.divide(target, illumination, gain)
        Core.max(gain, Scalar(0.70), gain)
        Core.min(gain, Scalar(1.90), gain)

        Core.multiply(input, gain, input)
        Core.max(input, Scalar(0.0), input)
        Core.min(input, Scalar(1.0), input)
        input.convertTo(luminance, CvType.CV_8U, 255.0)
    }

    // --------------------------------------------------------------- posters

    private fun enhancePoster(pool: Pool, source: Mat): Mat {
        val lab = pool.mat()
        Imgproc.cvtColor(source, lab, Imgproc.COLOR_RGB2Lab)
        val channels = pool.split(lab)
        localToneMap(pool, channels[0], posterConfig)
        applyClahe(pool, channels[0], posterConfig.claheClip)
        Core.merge(channels, lab)
        val rgb = pool.mat()
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
        // No gray-world balancing and almost no saturation change: poster colours are intentional.
        boostSaturation(pool, rgb, posterConfig.saturation)
        return sharpen(pool, rgb, posterConfig.sharpening)
    }

    // ----------------------------------------------------- interior / exterior

    private fun enhancePhoto(pool: Pool, rgb: Mat, config: ToneConfig): Mat {
        if (config.whiteBalance) grayWorld(pool, rgb)
        val lab = pool.mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = pool.split(lab)
        localToneMap(pool, channels[0], config)
        applyClahe(pool, channels[0], config.claheClip)
        Core.merge(channels, lab)
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)
        boostSaturation(pool, rgb, config.saturation)
        return sharpen(pool, rgb, config.sharpening)
    }

    /** Gray World estimates white balance only from near-neutral midtone pixels. */
    private fun grayWorld(pool: Pool, rgb: Mat) {
        val lab = pool.mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val channels = pool.split(lab)
        val a = pool.mat()
        val b = pool.mat()
        channels[1].convertTo(a, CvType.CV_32F, 1.0, -128.0)
        channels[2].convertTo(b, CvType.CV_32F, 1.0, -128.0)
        val chroma = pool.mat()
        Core.magnitude(a, b, chroma)
        val neutralFloat = pool.mat()
        Imgproc.threshold(chroma, neutralFloat, NEUTRAL_CHROMA, 255.0, Imgproc.THRESH_BINARY_INV)
        val neutral = pool.mat()
        neutralFloat.convertTo(neutral, CvType.CV_8U)
        val midtone = pool.mat()
        Core.inRange(channels[0], Scalar(41.0), Scalar(234.0), midtone)
        val mask = pool.mat()
        Core.bitwise_and(neutral, midtone, mask)
        val fraction = Core.countNonZero(mask).toDouble() / mask.total().toDouble()
        if (fraction < MIN_NEUTRAL_FRACTION) return

        val means = Core.mean(rgb, mask).`val`
        val gray = (means[0] + means[1] + means[2]) / 3.0
        val rgbChannels = pool.split(rgb)
        for (index in 0..2) {
            val gain = (gray / max(means[index], 1e-3)).coerceIn(WB_MIN, WB_MAX)
            rgbChannels[index].convertTo(rgbChannels[index], -1, gain, 0.0)
        }
        Core.merge(rgbChannels, rgb)
    }

    private fun localToneMap(pool: Pool, luminance: Mat, config: ToneConfig) {
        val width = luminance.cols()
        val height = luminance.rows()
        val normalized = pool.mat()
        luminance.convertTo(normalized, CvType.CV_32F, 1.0 / 255.0)
        val small = pool.mat()
        Imgproc.resize(
            normalized,
            small,
            Size(max(width / 8, 1).toDouble(), max(height / 8, 1).toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_AREA
        )
        val sigma = max(small.cols(), small.rows()) / 20.0
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), sigma)
        val illumination = pool.mat()
        Imgproc.resize(
            small,
            illumination,
            Size(width.toDouble(), height.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_LINEAR
        )
        Core.add(illumination, Scalar(1e-3), illumination)
        val gain = pool.mat()
        Core.divide(config.target, illumination, gain)
        Core.pow(gain, config.alpha, gain)
        Core.max(gain, Scalar(config.gainMin), gain)
        Core.min(gain, Scalar(config.gainMax), gain)
        Core.multiply(normalized, gain, normalized)
        Core.max(normalized, Scalar(0.0), normalized)
        Core.min(normalized, Scalar(1.0), normalized)
        normalized.convertTo(luminance, CvType.CV_8U, 255.0, 0.5)
    }

    private fun applyClahe(pool: Pool, luminance: Mat, clipLimit: Double) {
        val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
        try {
            clahe.apply(luminance, luminance)
        } finally {
            clahe.collectGarbage()
        }
    }

    private fun boostSaturation(pool: Pool, rgb: Mat, factor: Double) {
        if (abs(factor - 1.0) < 1e-6) return
        val hsv = pool.mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = pool.split(hsv)
        channels[1].convertTo(channels[1], -1, factor, 0.0)
        Core.merge(channels, hsv)
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
    }

    private fun sharpen(pool: Pool, rgb: Mat, strength: Double): Mat {
        if (strength <= 0.0) return rgb
        val blurred = pool.mat()
        Imgproc.GaussianBlur(rgb, blurred, Size(0.0, 0.0), 1.6)
        val result = pool.mat()
        Core.addWeighted(rgb, 1.0 + strength, blurred, -strength, 0.0, result)
        return result
    }

    private fun toBitmap(pool: Pool, rgb: Mat): Bitmap {
        val rgba = pool.mat()
        Imgproc.cvtColor(rgb, rgba, Imgproc.COLOR_RGB2RGBA)
        val output = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, output)
        return output
    }

    // ------------------------------------------------------------- geometry

    private fun orderCorners(points: Array<Point>): Array<Point> {
        val centerX = points.map { it.x }.average()
        val centerY = points.map { it.y }.average()
        val aroundCenter = points.sortedBy { atan2(it.y - centerY, it.x - centerX) }
        val topLeft = aroundCenter.indices.minByOrNull { index ->
            aroundCenter[index].x + aroundCenter[index].y
        } ?: 0
        return Array(4) { index -> aroundCenter[(topLeft + index) % 4] }
    }

    private fun maxCornerCosine(points: Array<Point>): Double {
        if (points.size != 4) return 1.0
        var maximum = 0.0
        for (index in points.indices) {
            val previous = points[(index + 3) % 4]
            val current = points[index]
            val next = points[(index + 1) % 4]
            val ax = previous.x - current.x
            val ay = previous.y - current.y
            val bx = next.x - current.x
            val by = next.y - current.y
            val denominator = sqrt((ax * ax + ay * ay) * (bx * bx + by * by))
            if (denominator <= 1e-6) return 1.0
            maximum = max(maximum, abs((ax * bx + ay * by) / denominator))
        }
        return maximum
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}