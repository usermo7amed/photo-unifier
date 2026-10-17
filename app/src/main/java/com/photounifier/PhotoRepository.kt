package com.photounifier

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Owns image processing and persistence; it has no Activity or View references. */
class PhotoRepository {
    interface Observer {
        fun onSource(index: Int, total: Int, bitmap: Bitmap)
        fun onClassification(index: Int, total: Int, kind: ImageKind)
        fun onEnhanced(bitmap: Bitmap)
        fun onSaved(index: Int, bitmap: Bitmap)
        fun onFinished(success: Int, failed: Int, error: String?)
    }

    fun process(context: Context, uris: List<Uri>, brightness: Int, contrastPercent: Int, observer: Observer, firstSourceOverride: Bitmap? = null) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var success = 0
        var failed = 0
        var lastError: String? = null
        val classifier = try { SceneClassifier() } catch (_: Exception) { null }
        try {
            uris.forEachIndexed { index, uri ->
                var source: Bitmap? = null
                var enhanced: Bitmap? = null
                var output: Bitmap? = null
                try {
                    val loadedSource = if (index == 0 && firstSourceOverride != null) {
                        firstSourceOverride.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        Processor.load(context, uri)
                    }
                    source = loadedSource
                    observer.onSource(index + 1, uris.size, loadedSource.copy(Bitmap.Config.ARGB_8888, false))
                    val classification = classifier?.classify(loadedSource)
                        ?: SceneClassification(ImageKind.GENERAL, 0f, emptyList())
                    observer.onClassification(index + 1, uris.size, classification.kind)

                    enhanced = Processor.enhance(loadedSource, classification.kind)

                    // OCR is an additional pass only for classifier-confirmed documents.
                    // A sign or text-rich photograph classified as a photo never enters
                    // this path, and OCR enhancement preserves document colour channels.
                    if (OcrRoutingPolicy.shouldRunDocumentOcr(classification.kind)) {
                        try {
                            val ocrResult = TextOcrEnhancer().use { it.analyzeAndEnhanceBlocking(enhanced!!) }
                            if (ocrResult.looksLikeDocument && ocrResult.enhancedBitmap !== enhanced) {
                                enhanced?.let { if (!it.isRecycled) it.recycle() }
                                enhanced = ocrResult.enhancedBitmap
                            }
                        } catch (_: Exception) {
                            // OCR is optional: keep the regular document enhancement on any OCR failure.
                        }
                    }

                    // Both the visible preview and saved file derive from this same final bitmap.
                    observer.onEnhanced(enhanced.copy(Bitmap.Config.ARGB_8888, false))
                    output = BitmapAdjustments.apply(enhanced, brightness, contrastPercent / 100f)
                    Processor.save(context, output, "PU_%s_%03d.jpg".format(Locale.US, stamp, index + 1))
                    observer.onSaved(index + 1, output.copy(Bitmap.Config.ARGB_8888, false))
                    success++
                } catch (error: Exception) {
                    failed++
                    lastError = error.message ?: error.javaClass.simpleName
                } finally {
                    source?.let { if (!it.isRecycled) it.recycle() }
                    enhanced?.let { if (!it.isRecycled) it.recycle() }
                    output?.let { if (!it.isRecycled) it.recycle() }
                }
            }
        } finally {
            classifier?.close()
            observer.onFinished(success, failed, lastError)
        }
    }
}
