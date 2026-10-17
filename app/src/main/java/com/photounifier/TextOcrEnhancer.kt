package com.photounifier

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * OCR-assisted readability enhancement for images already classified as documents.
 * It intentionally does not threshold pixels to black/white: the Lab chroma channels
 * are preserved so colored stamps, highlighting and ink remain colored.
 */
class TextOcrEnhancer : AutoCloseable {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class Result(
        val recognizedText: String,
        val looksLikeDocument: Boolean,
        val enhancedBitmap: Bitmap
    )

    /** Call on a worker thread; OCR runs on-device and this method waits for its result. */
    fun analyzeAndEnhanceBlocking(bitmap: Bitmap): Result {
        val visionText = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
        val text = visionText.text.trim()
        val looksLikeDocument = text.length >= MIN_TEXT_LENGTH || visionText.textBlocks.size >= MIN_TEXT_BLOCKS
        val output = if (looksLikeDocument && OpenCVLoader.initLocal()) {
            try {
                enhanceDocumentColorPreserving(bitmap)
            } catch (_: Exception) {
                bitmap
            }
        } else {
            bitmap
        }
        return Result(text, looksLikeDocument, output)
    }

    private fun enhanceDocumentColorPreserving(bitmap: Bitmap): Bitmap {
        val sourceBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalArgumentException("Could not copy bitmap for OCR enhancement")
        val rgba = Mat()
        val rgb = Mat()
        val lab = Mat()
        val enhancedLuminance = Mat()
        val outputRgb = Mat()
        val outputRgba = Mat()
        val channels = ArrayList<Mat>()
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        try {
            Utils.bitmapToMat(sourceBitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            Core.split(lab, channels)

            val luminance = channels[0]
            val clahe = Imgproc.createCLAHE(1.35, Size(8.0, 8.0))
            try {
                clahe.apply(luminance, enhancedLuminance)
            } finally {
                clahe.collectGarbage()
            }

            // Replace only L in Lab; retain a/b to preserve the original image colours.
            channels[0] = enhancedLuminance
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, outputRgb, Imgproc.COLOR_Lab2RGB)
            Imgproc.cvtColor(outputRgb, outputRgba, Imgproc.COLOR_RGB2RGBA)
            Utils.matToBitmap(outputRgba, output)
            return output
        } finally {
            rgba.release()
            rgb.release()
            lab.release()
            enhancedLuminance.release()
            outputRgb.release()
            outputRgba.release()
            channels.forEach { it.release() }
            sourceBitmap.recycle()
        }
    }

    override fun close() = recognizer.close()

    companion object {
        private const val MIN_TEXT_LENGTH = 24
        private const val MIN_TEXT_BLOCKS = 3
    }
}

/** OCR is only allowed to alter images the scene classifier identified as documents. */
internal object OcrRoutingPolicy {
    fun shouldRunDocumentOcr(kind: ImageKind): Boolean = kind == ImageKind.DOCUMENT
}
