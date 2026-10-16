package com.photounifier

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.opencv.android.OpenCVLoader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var selected: List<Uri> = emptyList()
    private var busy = false
    private var openCvReady = false
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var pickButton: Button
    private lateinit var startButton: Button
    private lateinit var countText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var sourcePreview: ImageView
    private lateinit var resultPreview: ImageView
    private lateinit var brightnessSeek: SeekBar
    private lateinit var contrastSeek: SeekBar
    private lateinit var brightnessValue: TextView
    private lateinit var contrastValue: TextView
    private var manualBrightness = 0
    private var manualContrast = 100
    @Volatile private var previewEnhanced: Bitmap? = null

    private val picker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                selected = uris
                progressBar.progress = 0
                statusText.setText(R.string.status_ready)
                refresh()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pickButton = findViewById(R.id.pickButton)
        startButton = findViewById(R.id.startButton)
        countText = findViewById(R.id.countText)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        sourcePreview = findViewById(R.id.sourcePreview)
        resultPreview = findViewById(R.id.resultPreview)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        contrastSeek = findViewById(R.id.contrastSeek)
        brightnessValue = findViewById(R.id.brightnessValue)
        contrastValue = findViewById(R.id.contrastValue)
        brightnessSeek.max = 100
        brightnessSeek.progress = 50
        contrastSeek.max = 150
        contrastSeek.progress = 50
        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                manualBrightness = progress - 50
                brightnessValue.text = getString(R.string.manual_brightness, manualBrightness)
                refreshManualPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        contrastSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                manualContrast = progress + 50
                contrastValue.text = getString(R.string.manual_contrast, manualContrast)
                refreshManualPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        brightnessValue.text = getString(R.string.manual_brightness, manualBrightness)
        contrastValue.text = getString(R.string.manual_contrast, manualContrast)
        openCvReady = OpenCVLoader.initLocal()
        if (!openCvReady) {
            Toast.makeText(this, R.string.opencv_failed, Toast.LENGTH_LONG).show()
        }

        pickButton.setOnClickListener { picker.launch("image/*") }
        startButton.setOnClickListener { startProcessing() }
        refresh()
    }

    override fun onDestroy() {
        worker.shutdown()
        previewEnhanced?.let { if (!it.isRecycled) it.recycle() }
        previewEnhanced = null
        super.onDestroy()
    }

    private fun refresh() {
        countText.text = if (selected.isEmpty()) {
            getString(R.string.count_none)
        } else {
            getString(R.string.count_fmt, selected.size)
        }
        pickButton.isEnabled = !busy
        startButton.isEnabled = !busy && openCvReady && selected.isNotEmpty()
    }

    private fun applyManualAdjustments(source: Bitmap, brightness: Int, contrast: Float): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val offset = brightness.toFloat()
        val scale = contrast.coerceIn(0.5f, 2.0f)
        val matrix = ColorMatrix(floatArrayOf(
            scale, 0f, 0f, 0f, offset,
            0f, scale, 0f, 0f, offset,
            0f, 0f, scale, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = ColorMatrixColorFilter(matrix)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    private fun refreshManualPreview() {
        val base = previewEnhanced ?: return
        if (base.isRecycled) return
        val adjusted = try {
            applyManualAdjustments(base, manualBrightness, manualContrast / 100f)
        } catch (_: Exception) {
            return
        }
        resultPreview.setImageBitmap(adjusted)
    }

    private fun startProcessing() {
        val items = selected
        val brightnessAtStart = manualBrightness
        val contrastAtStart = manualContrast
        if (items.isEmpty() || busy) return

        busy = true
        progressBar.max = items.size
        progressBar.progress = 0
        refresh()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        worker.execute {
            var successCount = 0
            var failureCount = 0
            var lastError: String? = null
            val classifier = try {
                SceneClassifier()
            } catch (_: Exception) {
                null
            }

            try {
                items.forEachIndexed { index, uri ->
                    runOnUiThread {
                        statusText.text = getString(
                            R.string.status_analyzing_fmt,
                            index + 1,
                            items.size
                        )
                    }

                    var source: Bitmap? = null
                    var result: Bitmap? = null
                    try {
                        val loaded = Processor.load(this, uri)
                        source = loaded
                        runOnUiThread { sourcePreview.setImageBitmap(loaded.copy(Bitmap.Config.ARGB_8888, false)) }
                        val classification = classifier?.classify(loaded)
                            ?: SceneClassification(ImageKind.GENERAL, 0f, emptyList())

                        runOnUiThread {
                            statusText.text = getString(
                                R.string.status_processing_kind_fmt,
                                index + 1,
                                items.size,
                                getString(classification.kind.labelRes)
                            )
                        }

                        val enhanced = Processor.enhance(loaded, classification.kind)
                        val previewCopy = enhanced.copy(Bitmap.Config.ARGB_8888, false)
                        runOnUiThread {
                            val oldPreview = previewEnhanced
                            previewEnhanced = previewCopy
                            oldPreview?.let { if (!it.isRecycled) it.recycle() }
                            refreshManualPreview()
                        }
                        result = applyManualAdjustments(enhanced, brightnessAtStart, contrastAtStart / 100f)
                        enhanced.recycle()
                        runOnUiThread { resultPreview.setImageBitmap(result?.copy(Bitmap.Config.ARGB_8888, false)) }
                        Processor.save(
                            this,
                            result!!,
                            "PU_%s_%03d.jpg".format(Locale.US, stamp, index + 1)
                        )
                        successCount++
                    } catch (error: Exception) {
                        failureCount++
                        lastError = error.message ?: error.javaClass.simpleName
                    } finally {
                        source?.let { if (!it.isRecycled) it.recycle() }
                        result?.let { if (!it.isRecycled) it.recycle() }
                    }
                    runOnUiThread { progressBar.progress = index + 1 }
                }
            } finally {
                classifier?.close()
            }

            runOnUiThread {
                busy = false
                var message = getString(R.string.status_done_fmt, successCount, failureCount)
                lastError?.let { message += "\n" + getString(R.string.status_error_fmt, it) }
                statusText.text = message
                refresh()
            }
        }
    }
}