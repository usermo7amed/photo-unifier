package com.photounifier

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.rememberUpdatedState
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
                        onContrastChange = viewModel::setContrast,
                        onOpenCrop = { viewModel.openCropEditor(openCvReady) },
                        onMoveCropCorner = viewModel::moveCropCorner,
                        onUndoCrop = viewModel::undoCrop,
                        onResetCrop = viewModel::resetCrop,
                        onCancelCrop = viewModel::cancelCrop,
                        onApplyCrop = viewModel::applyCrop
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
    onContrastChange: (Int) -> Unit,
    onOpenCrop: () -> Unit,
    onMoveCropCorner: (Int, Float, Float) -> Unit,
    onUndoCrop: () -> Unit,
    onResetCrop: () -> Unit,
    onCancelCrop: () -> Unit,
    onApplyCrop: () -> Unit
) {
    var currentStep by remember { mutableStateOf(0) }
    val stepTitles = listOf("اختيار الصور", "قص الصورة", "الإضاءة", "المعاينة والحفظ", "النتيجة")

    // Move forward only when the previous action has produced its state change.
    LaunchedEffect(state.selected.size) {
        if (state.selected.isNotEmpty() && currentStep == 0) currentStep = 1
        if (state.selected.isEmpty() && currentStep > 0 && !state.busy) currentStep = 0
    }
    LaunchedEffect(state.cropApplied) {
        if (state.cropApplied && currentStep == 1) currentStep = 2
    }
    LaunchedEffect(state.busy, state.status, state.resultPreview, state.enhancedPreview) {
        if (state.busy) {
            currentStep = 4
        } else if (currentStep == 4 && (state.resultPreview != null || state.enhancedPreview != null)) {
            currentStep = 4
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text("مُوحِّد الصور", fontWeight = FontWeight.Bold)
                    Text(
                        "اختار، راجع، حسّن — من غير ما تغيّر الأصل",
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("الخطوة " + (currentStep + 1) + " من " + stepTitles.size + ": " + stepTitles[currentStep],
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        stepTitles.indices.forEach { index ->
                            Surface(
                                modifier = Modifier.weight(1f).height(5.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = if (index <= currentStep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ) {}
                        }
                    }
                }
            }
            if (currentStep == 0) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepHeading(1, "اختار الصور", "اختار صورة واحدة أو مجموعة صور من جهازك.")
                    Button(
                        onClick = onPickImages,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.selected.isEmpty()) "اختيار الصور" else "تغيير الصور المختارة")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (state.selected.isEmpty()) "لسه مفيش صور مختارة"
                            else "تم اختيار ${state.selected.size} صورة",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (state.cropApplied) ValuePill("القص مطبّق على أول صورة")
                    }
                }
            }

            if (currentStep == 1) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepHeading(2, "راجع حدود الصورة", "لو الصورة مستند أو ورقة، ظبّط الزوايا قبل التحسين.")
                    OutlinedButton(
                        onClick = onOpenCrop,
                        enabled = !state.busy && state.cropSource != null && openCvReady,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.cropApplied) "مراجعة القص مرة تانية" else "اكتشاف الورقة وضبط الزوايا")
                    }

                    if (state.cropOpen) {
                        Text(
                            "اسحب كل نقطة لحد ركن الورقة. القص اليدوي بيتطبق على أول صورة مختارة بس.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CropCornerEditor(state.cropSource, state.cropCorners, onMoveCropCorner)
                        PreviewPanel("معاينة القص وتصحيح المنظور", state.cropPreview, Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onUndoCrop,
                                enabled = state.cropUndoStack.isNotEmpty(),
                                modifier = Modifier.weight(1f)
                            ) { Text("تراجع") }
                            OutlinedButton(
                                onClick = onResetCrop,
                                modifier = Modifier.weight(1f)
                            ) { Text("إعادة ضبط") }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = onCancelCrop, modifier = Modifier.weight(1f)) {
                                Text("إلغاء")
                            }
                            Button(
                                onClick = onApplyCrop,
                                enabled = state.cropPreview != null,
                                modifier = Modifier.weight(1f)
                            ) { Text("تطبيق القص") }
                        }
                    } else {
                        Text(
                            when {
                                state.selected.isEmpty() -> "اختار صورة الأول علشان تقدر تراجع حدودها."
                                state.cropSource == null -> "جاري تجهيز معاينة أول صورة..."
                                !openCvReady -> "ميزة القص محتاجة مكتبة معالجة الصور، وهي مش جاهزة حاليًا."
                                state.cropApplied -> "تم تطبيق القص على أول صورة. تقدر تكمّل للتحسين."
                                else -> "الخطوة دي اختيارية؛ لو الصورة مش مستند، تقدر تتخطاها."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (currentStep == 2) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepHeading(3, "اضبط الإضاءة والتباين", "سيتم استخدام القيم دي أثناء معالجة الصور المختارة.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("السطوع", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        ValuePill(if (state.brightness > 0) "+${state.brightness}" else "${state.brightness}")
                    }
                    Slider(
                        value = state.brightness.toFloat(),
                        onValueChange = { onBrightnessChange(it.roundToInt()) },
                        valueRange = -50f..50f,
                        enabled = !state.busy
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("التباين", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        ValuePill("${state.contrastPercent}%")
                    }
                    Slider(
                        value = state.contrastPercent.toFloat(),
                        onValueChange = { onContrastChange(it.roundToInt()) },
                        valueRange = 50f..200f,
                        enabled = !state.busy
                    )
                    OutlinedButton(
                        onClick = {
                            onBrightnessChange(0)
                            onContrastChange(100)
                        },
                        enabled = !state.busy && (state.brightness != 0 || state.contrastPercent != 100),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("إرجاع الضبط للوضع الطبيعي")
                    }
                    Text(
                        "السطوع والتباين تعديلان عامّان؛ المعالجة الذكية كمان بتختار طريقة مناسبة لنوع الصورة.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (currentStep == 3) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepHeading(4, "حسّن الصور واحفظها", "المعالجة بتحفظ نسخ جديدة، والصور الأصلية بتفضل زي ما هي.")
                    Button(
                        onClick = { onProcess() },
                        enabled = !state.busy && openCvReady && state.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.busy) "جاري التحسين..." else "بدء التحسين والحفظ")
                    }

                    if (state.selected.isNotEmpty()) {
                        BeforeAfterPreview(
                            source = state.sourcePreview,
                            enhanced = state.resultPreview ?: state.enhancedPreview,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            if (state.cropApplied)
                                "المعاينة تبدأ من الصورة بعد القص، ثم تعرض تأثير السطوع والتباين. اسحب الفاصل وافحص التفاصيل بالتكبير قبل الحفظ."
                            else if (state.resultPreview == null && state.enhancedPreview == null)
                                "حرّك شريط المقارنة لمراجعة الأصل. النتيجة تتحدث مع تغييرات السطوع والتباين، ويمكنك التكبير والسحب لفحص التفاصيل."
                            else "اسحب شريط المقارنة يمينًا ويسارًا. استخدم إصبعيك للتكبير والتحريك أو أزرار التكبير أسفل الصورة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "بعد اختيار الصور، هتظهر معاينة المصدر والنتيجة هنا.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!openCvReady) {
                        Text(
                            "مكتبة OpenCV مش جاهزة؛ مش هتقدر تبدأ المعالجة حاليًا.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (currentStep == 4) Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepHeading(5, "حالة المعالجة", "تابع التقدم والنتيجة النهائية.")
                    Text(state.status, style = MaterialTheme.typography.bodyMedium)
                    if (state.progress > 0 || state.errors.isNotEmpty()) {
                        Text("تم حفظ ${state.savedCount} من ${state.progressMax} صورة",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.errors.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("صور لم تكتمل معالجتها", fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                state.errors.forEach { item ->
                                    Text("• $item", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
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
                    Text(
                        "مكان حفظ النسخ الجديدة: Pictures/PhotoUnifier. الأصل لا يتم تعديله أو حذفه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = { currentStep = (currentStep - 1).coerceAtLeast(0) },
                    enabled = currentStep > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("السابق") }
                Button(
                    onClick = { currentStep = (currentStep + 1).coerceAtMost(stepTitles.lastIndex) },
                    enabled = currentStep < stepTitles.lastIndex && (currentStep != 0 || state.selected.isNotEmpty()),
                    modifier = Modifier.weight(1f)
                ) { Text(if (currentStep == 3) "عرض حالة المعالجة" else "التالي") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StepHeading(number: Int, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(32.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$number",
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
