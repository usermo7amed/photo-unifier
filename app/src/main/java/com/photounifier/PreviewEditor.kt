package com.photounifier

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
internal fun PreviewPanel(
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
internal fun BeforeAfterPreview(
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
internal fun CropCornerEditor(
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

