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
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * Stage 1 pipeline. Mirrors tools/prototype/enhance.py step by step, so values
 * tuned there can be copied here as they are.
 *
 *  1. White balance from near-neutral pixels only (a blue sky must not be "corrected").
 *  2. Local tone mapping on the L channel: pull highlights down, lift shadows.
 *  3. CLAHE on L for local contrast.
 *  4. Mild saturation boost (tone mapping washes colour out).
 *  5. Light unsharp mask.
 */
object Processor {

    // ---- tunable parameters (same names and values as the Python prototype) ----
    private const val MAX_SIDE = 4096
    private const val TARGET = 0.5
    private const val ALPHA = 0.45
    private const val GAIN_MIN = 0.65
    private const val GAIN_MAX = 2.2
    private const val NEUTRAL_CHROMA = 14.0
    private const val MIN_NEUTRAL_FRACTION = 0.05
    private const val WB_MIN = 0.85
    private const val WB_MAX = 1.2
    private const val CLAHE_CLIP = 2.0
    private const val SAT = 1.35
    private const val SHARP = 0.35

    /** Owns every Mat created during one image, so native memory is always released. */
    private class Pool : AutoCloseable {
        private val items = ArrayList<Mat>()
        fun mat(): Mat = Mat().also { items.add(it) }
        fun split(src: Mat): List<Mat> {
            val out = ArrayList<Mat>()
            Core.split(src, out)
            items.addAll(out)
            return out
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

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("تعذر قراءة الصورة")

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        return applyOrientation(bmp, orientation)
    }

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f); m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f); m.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
    }

    fun save(context: Context, bmp: Bitmap, displayName: String): Uri {
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
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
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

    // ------------------------------------------------------------- pipeline

    fun enhance(src: Bitmap): Bitmap = Pool().use { p ->
        val rgba = p.mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = p.mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)

        grayWorld(p, rgb)

        val lab = p.mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = p.split(lab)
        localToneMap(p, ch[0])
        Imgproc.createCLAHE(CLAHE_CLIP, Size(8.0, 8.0)).apply(ch[0], ch[0])
        Core.merge(ch, lab)
        Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB)

        boostSaturation(p, rgb)

        val blur = p.mat()
        Imgproc.GaussianBlur(rgb, blur, Size(0.0, 0.0), 2.0)
        val sharp = p.mat()
        Core.addWeighted(rgb, 1.0 + SHARP, blur, -SHARP, 0.0, sharp)

        val outRgba = p.mat()
        Imgproc.cvtColor(sharp, outRgba, Imgproc.COLOR_RGB2RGBA)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(outRgba, out)
        out
    }

    /** Gray World over near-neutral, mid-brightness pixels only. Edits [rgb] in place. */
    private fun grayWorld(p: Pool, rgb: Mat) {
        val lab = p.mat()
        Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
        val ch = p.split(lab)

        val a = p.mat()
        val b = p.mat()
        ch[1].convertTo(a, CvType.CV_32F, 1.0, -128.0)
        ch[2].convertTo(b, CvType.CV_32F, 1.0, -128.0)
        val chroma = p.mat()
        Core.magnitude(a, b, chroma)

        val neutral32 = p.mat()
        Imgproc.threshold(chroma, neutral32, NEUTRAL_CHROMA, 255.0, Imgproc.THRESH_BINARY_INV)
        val neutral = p.mat()
        neutral32.convertTo(neutral, CvType.CV_8U)

        val midTone = p.mat()
        Core.inRange(ch[0], Scalar(41.0), Scalar(234.0), midTone)
        val mask = p.mat()
        Core.bitwise_and(neutral, midTone, mask)

        val fraction = Core.countNonZero(mask).toDouble() / mask.total().toDouble()
        if (fraction < MIN_NEUTRAL_FRACTION) return

        val means = Core.mean(rgb, mask).`val`
        val gray = (means[0] + means[1] + means[2]) / 3.0
        val chans = p.split(rgb)
        for (i in 0..2) {
            val gain = (gray / max(means[i], 1e-3)).coerceIn(WB_MIN, WB_MAX)
            chans[i].convertTo(chans[i], -1, gain, 0.0)
        }
        Core.merge(chans, rgb)
    }

    /** In-place on an 8-bit L channel. */
    private fun localToneMap(p: Pool, l: Mat) {
        val w = l.cols()
        val h = l.rows()

        val lf = p.mat()
        l.convertTo(lf, CvType.CV_32F, 1.0 / 255.0)

        // Illumination estimate on a 1/8 size copy: fast, and smooth enough.
        val small = p.mat()
        Imgproc.resize(
            lf, small,
            Size(max(w / 8, 1).toDouble(), max(h / 8, 1).toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA
        )
        val sigma = max(small.cols(), small.rows()) / 20.0
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), sigma)
        val illum = p.mat()
        Imgproc.resize(small, illum, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        // gain = (TARGET / illumination) ^ ALPHA, limited to [GAIN_MIN, GAIN_MAX]
        Core.add(illum, Scalar(1e-3), illum)
        val gain = p.mat()
        Core.divide(TARGET, illum, gain)
        Core.pow(gain, ALPHA, gain)
        Core.max(gain, Scalar(GAIN_MIN), gain)
        Core.min(gain, Scalar(GAIN_MAX), gain)

        Core.multiply(lf, gain, lf)
        Core.max(lf, Scalar(0.0), lf)
        Core.min(lf, Scalar(1.0), lf)
        lf.convertTo(l, CvType.CV_8U, 255.0, 0.5)
    }

    private fun boostSaturation(p: Pool, rgb: Mat) {
        val hsv = p.mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val ch = p.split(hsv)
        ch[1].convertTo(ch[1], -1, SAT, 0.0)
        Core.merge(ch, hsv)
        Imgproc.cvtColor(hsv, rgb, Imgproc.COLOR_HSV2RGB)
    }
}
