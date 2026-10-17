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

            Card(
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

            Card(
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StepHeading(4, "حسّن الصور واحفظها", "المعالجة بتحفظ نسخ جديدة، والصور الأصلية بتفضل زي ما هي.")
                    Button(
                        onClick = onProcess,
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
                            if (state.resultPreview == null && state.enhancedPreview == null)
                                "حرّك شريط المقارنة لمراجعة الأصل. النتيجة تظهر هنا مع تغييرات السطوع والتباين، ويمكنك التكبير والسحب لفحص التفاصيل."
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

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StepHeading(5, "حالة المعالجة", "تابع التقدم والنتيجة النهائية.")
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
                    Text(
                        "مكان حفظ النسخ الجديدة: Pictures/PhotoUnifier",
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
private fun PreviewPanel(
    title: String,
    bitmap: android.graphics.Bitmap?,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Box(
            modifier = Modifier.fillMaxWidth().height(280.dp)
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
private fun BeforeAfterPreview(
    source: android.graphics.Bitmap?,
    enhanced: android.graphics.Bitmap?,
    modifier: Modifier = Modifier
) {
    var reveal by remember(source, enhanced) { mutableStateOf(0.5f) }
    var zoom by remember(source, enhanced) { mutableStateOf(1f) }
    var pan by remember(source, enhanced) { mutableStateOf(Offset.Zero) }
    val safeSource = source?.takeUnless { it.isRecycled }
    val safeEnhanced = enhanced?.takeUnless { it.isRecycled }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("قبل", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text("بعد", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(300.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF16191D))
                .pointerInput(safeSource, safeEnhanced) {
                    detectTransformGestures { _, panChange, zoomChange, _ ->
                        zoom = (zoom * zoomChange).coerceIn(1f, 5f)
                        pan = if (zoom <= 1f) Offset.Zero else pan + panChange
                    }
                }
        ) {
            if (safeSource != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val after = safeEnhanced ?: safeSource
                    val fit = minOf(
                        size.width / safeSource.width.toFloat(),
                        size.height / safeSource.height.toFloat()
                    )
                    fun drawFitted(bitmap: android.graphics.Bitmap) {
                        val drawWidth = (bitmap.width * fit * zoom).roundToInt().coerceAtLeast(1)
                        val drawHeight = (bitmap.height * fit * zoom).roundToInt().coerceAtLeast(1)
                        val left = ((size.width - drawWidth) / 2f + pan.x).roundToInt()
                        val top = ((size.height - drawHeight) / 2f + pan.y).roundToInt()
                        drawImage(
                            image = bitmap.asImageBitmap(),
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bitmap.width, bitmap.height),
                            dstOffset = IntOffset(left, top),
                            dstSize = IntSize(drawWidth, drawHeight),
                            filterQuality = FilterQuality.High
                        )
                    }
                    drawFitted(after)
                    clipRect(left = 0f, top = 0f, right = size.width * reveal, bottom = size.height) {
                        drawFitted(safeSource)
                    }
                    val dividerX = size.width * reveal
                    drawLine(Color.White, Offset(dividerX, 0f), Offset(dividerX, size.height), strokeWidth = 4f)
                    drawCircle(Color.White, radius = 16f, center = Offset(dividerX, size.height / 2f))
                    drawCircle(Color(0xFF1565C0), radius = 11f, center = Offset(dividerX, size.height / 2f))
                }
                if (safeEnhanced == null) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("بانتظار النتيجة", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                Text("اختار صورة لعرض المقارنة", modifier = Modifier.align(Alignment.Center),
                    color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Slider(
            value = reveal,
            onValueChange = { reveal = it },
            valueRange = 0.02f..0.98f,
            enabled = safeSource != null && safeEnhanced != null
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = { zoom = (zoom / 1.25f).coerceAtLeast(1f); if (zoom == 1f) pan = Offset.Zero },
                enabled = zoom > 1f,
                modifier = Modifier.weight(1f)
            ) { Text("−") }
            Text("${(zoom * 100).roundToInt()}%", modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = { zoom = (zoom * 1.25f).coerceAtMost(5f) },
                enabled = safeSource != null,
                modifier = Modifier.weight(1f)
            ) { Text("+") }
            OutlinedButton(
                onClick = { zoom = 1f; pan = Offset.Zero; reveal = 0.5f },
                modifier = Modifier.weight(1.4f)
            ) { Text("إعادة") }
        }
    }
}

@Composable
private fun CropCornerEditor(
    bitmap: android.graphics.Bitmap?,
    corners: List<CropPoint>,
    onMoveCorner: (Int, Float, Float) -> Unit
) {
    if (bitmap == null || bitmap.isRecycled || corners.size != 4) return
    val latestCorners by rememberUpdatedState(corners)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(320.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF16191D))
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "الصورة مع نقاط تحديد الزوايا",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        Canvas(modifier = Modifier.fillMaxSize().pointerInput(bitmap) {
            var activeCorner = -1
            var dragPosition = Offset.Zero
            detectDragGestures(
                onDragStart = { position ->
                    val scale = minOf(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
                    val left = (size.width - bitmap.width * scale) / 2f
                    val top = (size.height - bitmap.height * scale) / 2f
                    val screenPoints = latestCorners.map { Offset(left + it.x * scale, top + it.y * scale) }
                    activeCorner = screenPoints.indices.minByOrNull { (screenPoints[it] - position).getDistance() } ?: -1
                    if (activeCorner >= 0 && (screenPoints[activeCorner] - position).getDistance() > 58f) activeCorner = -1
                    dragPosition = position
                },
                onDrag = { change, amount ->
                    if (activeCorner >= 0) {
                        change.consume()
                        dragPosition += amount
                        val scale = minOf(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
                        val left = (size.width - bitmap.width * scale) / 2f
                        val top = (size.height - bitmap.height * scale) / 2f
                        val x = ((dragPosition.x - left) / scale).coerceIn(0f, (bitmap.width - 1).toFloat())
                        val y = ((dragPosition.y - top) / scale).coerceIn(0f, (bitmap.height - 1).toFloat())
                        onMoveCorner(activeCorner, x, y)
                    }
                },
                onDragEnd = { activeCorner = -1 },
                onDragCancel = { activeCorner = -1 }
            )
        }) {
            val scale = minOf(size.width / bitmap.width.toFloat(), size.height / bitmap.height.toFloat())
            val left = (size.width - bitmap.width * scale) / 2f
            val top = (size.height - bitmap.height * scale) / 2f
            val screenPoints = corners.map { Offset(left + it.x * scale, top + it.y * scale) }
            for (i in 0..3) drawLine(Color.Cyan, screenPoints[i], screenPoints[(i + 1) % 4], strokeWidth = 4f)
            screenPoints.forEach { point ->
                drawCircle(Color.White, radius = 17f, center = point)
                drawCircle(Color(0xFF00A8C6), radius = 12f, center = point)
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
