package com.photounifier

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun PreviewComparisonPanel(
    source: Bitmap?,
    result: Bitmap?,
    modifier: Modifier = Modifier
) {
    var reveal by remember { mutableFloatStateOf(0.5f) }
    var zoom by remember { mutableFloatStateOf(1f) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("قبل", style = MaterialTheme.typography.labelLarge)
            Text("بعد", style = MaterialTheme.typography.labelLarge)
        }
        Box(modifier = Modifier.fillMaxWidth().height(300.dp).clip(MaterialTheme.shapes.medium)) {
            if (source != null && result != null && !source.isRecycled && !result.isRecycled) {
                val before = remember(source) { source.asImageBitmap() }
                val after = remember(result) { result.asImageBitmap() }
                Canvas(Modifier.fillMaxWidth().height(300.dp)) {
                    val fit = min(size.width / before.width, size.height / before.height)
                    val imageWidth = before.width * fit * zoom
                    val imageHeight = before.height * fit * zoom
                    val left = (size.width - imageWidth) / 2f
                    val top = (size.height - imageHeight) / 2f
                    val destination = IntOffset(left.roundToInt(), top.roundToInt())
                    val destinationSize = IntSize(imageWidth.roundToInt().coerceAtLeast(1), imageHeight.roundToInt().coerceAtLeast(1))
                    drawImage(before, dstOffset = destination, dstSize = destinationSize)
                    val dividerX = size.width * reveal
                    clipRect(left = 0f, top = 0f, right = dividerX, bottom = size.height) {
                        drawImage(after, dstOffset = destination, dstSize = destinationSize)
                    }
                    drawLine(
                        color = androidx.compose.ui.graphics.Color.White,
                        start = Offset(dividerX, 0f),
                        end = Offset(dividerX, size.height),
                        strokeWidth = 3.dp.toPx()
                    )
                    drawCircle(
                        color = androidx.compose.ui.graphics.Color.White,
                        radius = 12.dp.toPx(),
                        center = Offset(dividerX, size.height / 2f)
                    )
                    drawCircle(
                        color = MaterialTheme.colorScheme.primary,
                        radius = 8.dp.toPx(),
                        center = Offset(dividerX, size.height / 2f)
                    )
                }
            } else {
                Text(
                    if (source == null) "اختار صورة علشان تبدأ المعاينة" else "النتيجة المباشرة هتظهر هنا",
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text("اسحب شريط المقارنة لمراجعة الفرق", style = MaterialTheme.typography.bodySmall)
        Slider(value = reveal, onValueChange = { reveal = it }, valueRange = 0.05f..0.95f)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("تكبير التفاصيل", style = MaterialTheme.typography.labelLarge)
            Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..3f)
    }
}
