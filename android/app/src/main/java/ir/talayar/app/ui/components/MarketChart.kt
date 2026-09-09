package ir.talayar.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import ir.talayar.app.core.Formatters
import ir.talayar.app.domain.model.HistoryPoint
import ir.talayar.app.ui.theme.Dimens
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Interactive, smooth price chart — pure Compose Canvas (no chart library).
 *
 *  - Time flows left → right (standard financial convention) even in RTL layout.
 *  - Touch/drag shows a crosshair with the selected price & time.
 *  - Auto colors by trend direction (positive/negative).
 */
@Composable
fun MarketChart(
    points: List<HistoryPoint>,
    modifier: Modifier = Modifier,
    useDateLabels: Boolean = false,
) {
    val marketColors = ir.talayar.app.ui.theme.TalayarTheme.market
    val trendUp = points.size >= 2 && points.last().p >= points.first().p
    val lineColor = if (trendUp) marketColors.positive else marketColors.negative
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    if (points.size < 2) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(Dimens.ChartHeight)
                .semantics { contentDescription = "نمودار قیمت در دسترس نیست" },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "داده کافی برای نمایش نمودار ثبت نشده است.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Dimens.SpaceL),
            )
            Text(
                text = "تاریخچه قیمت به‌مرور و با هر بروزرسانی کامل‌تر می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = Dimens.SpaceL),
            )
        }
        return
    }

    val selected = selectedIndex?.let { points.getOrNull(it) } ?: points.last()

    Column(modifier = modifier.fillMaxWidth()) {
        // header: selected (or last) value + time — the "tooltip"
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.SpaceL, vertical = Dimens.SpaceS),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = Formatters.price(selected.p),
                    style = MaterialTheme.typography.titleLarge,
                    color = lineColor,
                )
                Text(
                    text = if (useDateLabels) {
                        "${Formatters.date(selected.t)} — ${Formatters.shortTime(selected.t)}"
                    } else {
                        Formatters.time(selected.t)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${Formatters.price(points.first().p)} ← ${Formatters.price(points.last().p)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.ChartHeight)
                .semantics { contentDescription = "نمودار قیمت" },
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }

            fun indexFor(x: Float): Int =
                (((x / widthPx) * (points.size - 1)).roundToInt()).coerceIn(0, points.size - 1)

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(points) {
                        detectTapGestures { change -> selectedIndex = indexFor(change.x) }
                    }
                    .pointerInput(points) {
                        detectDragGestures { change, _ -> selectedIndex = indexFor(change.position.x) }
                    },
            ) {
                val w = size.width
                val h = size.height
                val padY = h * 0.12f
                val padX = 8f

                val minP = points.minOf { it.p }
                val maxP = points.maxOf { it.p }
                val range = (maxP - minP).coerceAtLeast(abs(maxP) * 1e-6)
                val yMax = padY
                val yMin = h - padY

                fun px(i: Int): Float =
                    if (points.size == 1) w / 2f else padX + i.toFloat() / (points.size - 1) * (w - 2 * padX)

                fun py(p: Double): Float = (yMin - ((p - minP) / range).toFloat() * (yMin - yMax))

                // grid
                val gridColor = Color.Gray.copy(alpha = 0.18f)
                for (g in 1..3) {
                    val y = yMax + (yMin - yMax) * g / 4f
                    drawLine(gridColor, Offset(padX, y), Offset(w - padX, y), strokeWidth = 1.2f)
                }

                // smooth path (quadratic through midpoints)
                val path = Path()
                path.moveTo(px(0), py(points[0].p))
                for (i in 1 until points.size) {
                    val prev = Offset(px(i - 1), py(points[i - 1].p))
                    val curr = Offset(px(i), py(points[i].p))
                    val midX = (prev.x + curr.x) / 2f
                    path.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
                }

                // area fill
                val area = Path().apply {
                    addPath(path)
                    lineTo(px(points.size - 1), yMin)
                    lineTo(px(0), yMin)
                    close()
                }
                drawPath(
                    area,
                    Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0.02f)),
                        startY = yMax,
                        endY = yMin,
                    ),
                )

                drawPath(
                    path,
                    color = lineColor,
                    style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                // last point marker
                drawCircle(lineColor, radius = 10f, center = Offset(px(points.size - 1), py(points.last().p)))
                drawCircle(Color.White, radius = 4f, center = Offset(px(points.size - 1), py(points.last().p)))

                // crosshair
                selectedIndex?.let { idx ->
                    val cx = px(idx)
                    val cy = py(points[idx].p)
                    drawLine(
                        lineColor.copy(alpha = 0.6f),
                        Offset(cx, yMax),
                        Offset(cx, yMin),
                        strokeWidth = 1.6f,
                    )
                    drawCircle(lineColor, radius = 9f, center = Offset(cx, cy))
                    drawCircle(Color.White, radius = 4f, center = Offset(cx, cy))
                }
            }
        }
    }
}
