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
        fun onError(index: Int, total: Int, uri: Uri, message: String)
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

                    // Enhancement is strictly pixel-based: no OCR, text reconstruction,
                    // character replacement, or generated lettering. Document-specific
                    // contrast and illumination correction live in Processor.
                    val finalEnhanced = Processor.enhance(loadedSource, classification.kind)
                    enhanced = finalEnhanced

                    // Preview and saved file derive from the same enhanced bitmap.
                    observer.onEnhanced(finalEnhanced.copy(Bitmap.Config.ARGB_8888, false))
                    output = BitmapAdjustments.apply(finalEnhanced, brightness, contrastPercent / 100f)
                    Processor.save(context, output, "PU_%s_%03d.jpg".format(Locale.US, stamp, index + 1))
                    observer.onSaved(index + 1, output.copy(Bitmap.Config.ARGB_8888, false))
                    success++
                } catch (error: Exception) {
                    failed++
                    lastError = error.message ?: error.javaClass.simpleName
                    observer.onError(index + 1, uris.size, uri, lastError ?: "خطأ غير معروف")
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
