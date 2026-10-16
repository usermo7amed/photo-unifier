package com.photounifier

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    private val viewModel: PhotoViewModel by viewModels()
    private var openCvReady = false

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

    private val picker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) viewModel.selectImages(uris)
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
                if (fromUser) viewModel.setBrightness(progress - 50)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        contrastSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.setContrast(progress + 50)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        brightnessValue.text = getString(R.string.manual_brightness, 0)
        contrastValue.text = getString(R.string.manual_contrast, 100)
        openCvReady = OpenCVLoader.initLocal()
        if (!openCvReady) Toast.makeText(this, R.string.opencv_failed, Toast.LENGTH_LONG).show()

        pickButton.setOnClickListener { picker.launch("image/*") }
        startButton.setOnClickListener { viewModel.process(openCvReady) }
        viewModel.state.observe(this) { render(it) }
    }

    private fun render(state: PhotoUiState) {
        countText.text = if (state.selected.isEmpty()) getString(R.string.count_none)
        else getString(R.string.count_fmt, state.selected.size)
        statusText.text = state.status
        progressBar.max = state.progressMax.coerceAtLeast(1)
        progressBar.progress = state.progress
        pickButton.isEnabled = !state.busy
        startButton.isEnabled = !state.busy && openCvReady && state.selected.isNotEmpty()

        val brightness = state.brightness + 50
        if (brightnessSeek.progress != brightness) brightnessSeek.progress = brightness
        val contrast = state.contrastPercent - 50
        if (contrastSeek.progress != contrast) contrastSeek.progress = contrast
        brightnessValue.text = getString(R.string.manual_brightness, state.brightness)
        contrastValue.text = getString(R.string.manual_contrast, state.contrastPercent)
        renderBitmap(sourcePreview, state.sourcePreview)
        renderBitmap(resultPreview, state.resultPreview)
    }

    private fun renderBitmap(view: ImageView, bitmap: Bitmap?) {
        if (bitmap == null) view.setImageDrawable(null) else if (view.drawable == null || view.tag !== bitmap) {
            view.setImageBitmap(bitmap)
            view.tag = bitmap
        }
    }
}
