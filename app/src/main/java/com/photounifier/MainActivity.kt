package com.photounifier

import android.net.Uri
import android.os.Bundle
import android.widget.Button
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

        openCvReady = OpenCVLoader.initLocal()
        if (!openCvReady) {
            Toast.makeText(this, R.string.opencv_failed, Toast.LENGTH_LONG).show()
        }

        pickButton.setOnClickListener { picker.launch("image/*") }
        startButton.setOnClickListener { startProcessing() }
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun refresh() {
        countText.text =
            if (selected.isEmpty()) getString(R.string.count_none)
            else getString(R.string.count_fmt, selected.size)
        pickButton.isEnabled = !busy
        startButton.isEnabled = !busy && openCvReady && selected.isNotEmpty()
    }

    private fun startProcessing() {
        val items = selected
        if (items.isEmpty() || busy) return

        busy = true
        progressBar.max = items.size
        progressBar.progress = 0
        refresh()

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        worker.execute {
            var ok = 0
            var failed = 0
            var lastError: String? = null

            items.forEachIndexed { index, uri ->
                runOnUiThread {
                    statusText.text =
                        getString(R.string.status_progress_fmt, index + 1, items.size)
                }
                try {
                    val source = Processor.load(this, uri)
                    val result = Processor.enhance(source)
                    source.recycle()
                    Processor.save(this, result, "PU_%s_%03d.jpg".format(stamp, index + 1))
                    result.recycle()
                    ok++
                } catch (t: Throwable) {
                    failed++
                    lastError = t.message ?: t.javaClass.simpleName
                }
                runOnUiThread { progressBar.progress = index + 1 }
            }

            runOnUiThread {
                busy = false
                var message = getString(R.string.status_done_fmt, ok, failed)
                lastError?.let { message += "\n" + getString(R.string.status_error_fmt, it) }
                statusText.text = message
                refresh()
            }
        }
    }
}
