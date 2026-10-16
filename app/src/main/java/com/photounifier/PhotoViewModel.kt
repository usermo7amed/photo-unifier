package com.photounifier

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

data class PhotoUiState(
    val selected: List<Uri> = emptyList(),
    val busy: Boolean = false,
    val progress: Int = 0,
    val progressMax: Int = 1,
    val status: String = "جاهز لاختيار الصور",
    val sourcePreview: Bitmap? = null,
    val enhancedPreview: Bitmap? = null,
    val resultPreview: Bitmap? = null,
    val brightness: Int = 0,
    val contrastPercent: Int = 100
)

/** Owns screen state and coordinates background work; the Activity only renders state. */
class PhotoViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PhotoRepository()
    private val executor = Executors.newSingleThreadExecutor()
    private val previewExecutor = Executors.newSingleThreadExecutor()
    private val previewGeneration = AtomicInteger(0)
    private val mutableState = MutableLiveData(PhotoUiState())
    val state: LiveData<PhotoUiState> = mutableState
    @Volatile private var currentState = PhotoUiState()

    @Synchronized
    private fun update(transform: (PhotoUiState) -> PhotoUiState) {
        currentState = transform(currentState)
        mutableState.postValue(currentState)
    }

    fun selectImages(uris: List<Uri>) = update {
        it.copy(
            selected = uris.toList(),
            progress = 0,
            status = if (uris.isEmpty()) "لم يتم اختيار صور" else "تم اختيار ${uris.size} صورة",
            sourcePreview = null,
            enhancedPreview = null,
            resultPreview = null
        )
    }

    fun setBrightness(value: Int) {
        update { it.copy(brightness = value.coerceIn(-50, 50)) }
        refreshPreview()
    }

    fun setContrast(value: Int) {
        update { it.copy(contrastPercent = value.coerceIn(50, 200)) }
        refreshPreview()
    }

    private fun refreshPreview() {
        val snapshot = currentState
        val base = snapshot.enhancedPreview ?: return
        val generation = previewGeneration.incrementAndGet()
        previewExecutor.execute {
            if (base.isRecycled) return@execute
            val preview = try {
                BitmapAdjustments.apply(base, snapshot.brightness, snapshot.contrastPercent / 100f)
            } catch (_: Exception) { return@execute }
            if (generation != previewGeneration.get()) {
                preview.recycle()
                return@execute
            }
            update { old ->
                if (old.enhancedPreview !== base || generation != previewGeneration.get()) {
                    preview.recycle()
                    old
                } else old.copy(resultPreview = preview)
            }
        }
    }

    fun process(openCvReady: Boolean) {
        val snapshot = currentState
        if (snapshot.busy || snapshot.selected.isEmpty()) return
        if (!openCvReady) {
            update { it.copy(status = "مكتبة معالجة الصور غير جاهزة") }
            return
        }
        val uris = snapshot.selected
        val brightness = snapshot.brightness
        val contrast = snapshot.contrastPercent
        update { it.copy(busy = true, progress = 0, progressMax = uris.size, status = "بدء معالجة الصور…") }

        executor.execute {
            repository.process(
                getApplication<Application>().applicationContext, uris, brightness, contrast,
                object : PhotoRepository.Observer {
                    override fun onSource(index: Int, total: Int, bitmap: Bitmap) {
                        update { it.copy(status = "تحليل الصورة $index من $total", sourcePreview = bitmap) }
                    }
                    override fun onClassification(index: Int, total: Int, kind: ImageKind) {
                        update { it.copy(status = "معالجة الصورة $index من $total — ${kind.title}") }
                    }
                    override fun onEnhanced(bitmap: Bitmap) {
                        update { it.copy(enhancedPreview = bitmap) }
                        refreshPreview()
                    }
                    override fun onSaved(index: Int, bitmap: Bitmap) {
                        update { it.copy(progress = index, resultPreview = bitmap) }
                    }
                    override fun onFinished(success: Int, failed: Int, error: String?) {
                        val detail = error?.let { "\nآخر خطأ: $it" }.orEmpty()
                        update {
                            it.copy(
                                busy = false,
                                progress = it.progressMax,
                                status = "اكتملت المعالجة: نجاح $success، فشل $failed$detail"
                            )
                        }
                    }
                }
            )
        }
    }

    override fun onCleared() {
        executor.shutdownNow()
        previewExecutor.shutdownNow()
        currentState.sourcePreview?.let { if (!it.isRecycled) it.recycle() }
        currentState.enhancedPreview?.let { if (!it.isRecycled) it.recycle() }
        currentState.resultPreview?.let { if (!it.isRecycled) it.recycle() }
        super.onCleared()
    }
}
