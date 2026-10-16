package com.photounifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.livedata.observeAsState
import com.photounifier.ui.PhotoUnifierTheme
import org.opencv.android.OpenCVLoader
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: PhotoViewModel by viewModels()
    private var openCvReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openCvReady = OpenCVLoader.initLocal()
        setContent {
            PhotoUnifierTheme {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalLayoutDirection provides LayoutDirection.Rtl
                ) {
                    val state by viewModel.state.observeAsState(PhotoUiState())
                    val picker = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetMultipleContents()
                    ) { uris -> if (uris.isNotEmpty()) viewModel.selectImages(uris) }
                    PhotoUnifierScreen(
                        state = state,
                        openCvReady = openCvReady,
                        onPickImages = { picker.launch("image/*") },
                        onProcess = { viewModel.process(openCvReady) },
                        onBrightnessChange = viewModel::setBrightness,
                        onContrastChange = viewModel::setContrast
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoUnifierScreen(
    state: PhotoUiState,
    openCvReady: Boolean,
    onPickImages: () -> Unit,
    onProcess: () -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onContrastChange: (Int) -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text("مُوحِّد الصور", fontWeight = FontWeight.Bold)
                    Text(
                        "تحسين ذكي وتحكم يدوي",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "خلّي صورك أوضح ومتوازنة",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        "حلّل الصور وحسّن الإضاءة والتباين، وراجع النتيجة قبل الحفظ.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onPickImages, enabled = !state.busy) {
                            Text("اختيار الصور")
                        }
                        OutlinedButton(
                            onClick = onProcess,
                            enabled = !state.busy && openCvReady && state.selected.isNotEmpty()
                        ) {
                            Text(if (state.busy) "جاري التحسين..." else "بدء التحسين")
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeading("معاينة الصورة", "قارن الأصل بالنتيجة المحسّنة")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PreviewPanel("الأصلية", state.sourcePreview, Modifier.weight(1f))
                        PreviewPanel(
                            "بعد التحسين",
                            state.resultPreview ?: state.enhancedPreview,
                            Modifier.weight(1f)
                        )
                    }
                    Text(
                        if (state.selected.isEmpty()) "اختار صورة أو أكتر علشان تبدأ."
                        else "تم اختيار ${state.selected.size} صورة",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeading("التحكم اليدوي", "اضبط السطوع والتباين حسب الصورة")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("السطوع", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        ValuePill(if (state.brightness > 0) "+${state.brightness}" else "${state.brightness}")
                    }
                    Slider(
                        value = state.brightness.toFloat(),
                        onValueChange = { onBrightnessChange(it.roundToInt()) },
                        valueRange = -50f..50f
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("التباين", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        ValuePill("${state.contrastPercent}%")
                    }
                    Slider(
                        value = state.contrastPercent.toFloat(),
                        onValueChange = { onContrastChange(it.roundToInt()) },
                        valueRange = 50f..200f
                    )
                    Text(
                        if (state.enhancedPreview == null)
                            "اضبط القيم قبل بدء المعالجة؛ هتتطبق على الملفات المحفوظة."
                        else
                            "التغييرات بعد انتهاء المعالجة بتحدّث المعاينة؛ ابدأ المعالجة من جديد لحفظ نسخة بالقيم الجديدة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeading("حالة المعالجة", "متابعة مباشرة للصور")
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    if (state.busy) {
                        LinearProgressIndicator(
                            progress = state.progress.toFloat() / state.progressMax.coerceAtLeast(1),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${state.progress} من ${state.progressMax}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!openCvReady) {
                        Text(
                            "مكتبة OpenCV مش جاهزة؛ المعالجة متوقفة لحد ما تتوفر.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "الصور الأصلية مش بتتغير. النسخ المحسّنة بتتحفظ في Pictures/PhotoUnifier.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PreviewPanel(
    title: String,
    bitmap: android.graphics.Bitmap?,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier.fillMaxWidth().height(190.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null && !bitmap.isRecycled) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("＋", fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("المعاينة هتظهر هنا", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun ValuePill(value: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            value,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
        )
    }
}
