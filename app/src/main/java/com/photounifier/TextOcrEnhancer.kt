package com.photounifier

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.CLAHE

/**
 * Detects printed Latin-script text on-device and improves document readability.
 *
 * This is deliberately conservative: photos without substantial text are returned
 * unchanged. Call from a UI/ViewModel layer and update the UI from [callback].
 */
class TextOcrEnhancer : AutoCloseable {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class Result(
        val recognizedText: String,
        val looksLikeDocument: Boolean,
        val enhancedBitmap: Bitmap
    )

    fun analyzeAndEnhance(
        bitmap: Bitmap,
        callback: (kotlin.Result<Result>) -> Unit
    ) {
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                val textBlocks = visionText.textBlocks.size
                val looksLikeDocument = text.length >= 24 || textBlocks >= 3

                val output = try {
                    if (looksLikeDocument && OpenCVLoader.initLocal()) {
                        enhanceDocument(bitmap)
                    } else {
                        bitmap
                    }
                } catch (_: Exception) {
                    // OCR should still be useful even if optional image enhancement fails.
                    bitmap
                }

                callback(
                    kotlin.Result.success(
                        Result(
                            recognizedText = text,
                            looksLikeDocument = looksLikeDocument,
                            enhancedBitmap = output
                        )
                    )
                )
            }
            .addOnFailureListener { error ->
                callback(kotlin.Result.failure(error))
            }
    }

    private fun enhanceDocument(bitmap: Bitmap): Bitmap {
        val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw IllegalArgumentException("Could not copy bitmap for OCR enhancement")

        val source = Mat()
        val gray = Mat()
        val equalized = Mat()
        val binary = Mat()
        val output = Bitmap.createBitmap(
            safeBitmap.width,
            safeBitmap.height,
            Bitmap.Config.ARGB_8888
        )

        try {
            Utils.bitmapToMat(safeBitmap, source)
            Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)

            val clahe: CLAHE = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            try {
                clahe.apply(gray, equalized)
            } finally {
                clahe.collectGarbage()
            }

            // Adaptive threshold helps with uneven paper lighting and mild shadows.
            // It is only used after OCR finds enough text to suggest a document.
            val blockSize = minOf(31, minOf(equalized.rows(), equalized.cols()) / 2 * 2 - 1)
            if (blockSize >= 3) {
                Imgproc.adaptiveThreshold(
                    equalized,
                    binary,
                    255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY,
                    blockSize,
                    11.0
                )
                Imgproc.cvtColor(binary, source, Imgproc.COLOR_GRAY2RGBA)
            } else {
                Imgproc.cvtColor(equalized, source, Imgproc.COLOR_GRAY2RGBA)
            }

            Utils.matToBitmap(source, output)
            return output
        } finally {
            source.release()
            gray.release()
            equalized.release()
            binary.release()
            safeBitmap.recycle()
        }
    }

    override fun close() {
        recognizer.close()
    }
}
