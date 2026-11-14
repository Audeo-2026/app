package com.audeo.app.ui.audeo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audeo.app.R
import com.audeo.app.audeo.MapPoint
import com.audeo.app.ui.theme.Audeo
import com.audeo.app.ui.theme.AudeoColors
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * 앱 영역을 채우는 지도. 실제 도로(OpenStreetMap)를 그린 낮/밤 이미지 위에
 * 남은 경로, 지나온 길, 차량, 픽업 핀, 상대방 위치, 칩 두 개(픽업 지점·남은 시간)를 겹쳐 그립니다.
 */
@Composable
fun RouteMap(
    route: List<MapPoint>,
    progress: Float,
    modifier: Modifier = Modifier,
    idle: Boolean = false,
    pickupLabel: String? = null,
    etaLabel: String? = null,
    passenger: Pair<MapPoint, String>? = null,
) {
    val colors = Audeo.colors
    val measurer = rememberTextMeasurer()
    val arrow = remember { Path().apply { PathParser().parsePathString("M12 3l7 18-7-4-7 4z").toPath(this) } }
    val image = if (colors.isNight) R.drawable.map_t2_night else R.drawable.map_t2_day
    Box(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = if (idle) "지도: 현재 차량 위치" else "지도: 차량에서 ${pickupLabel ?: "픽업 지점"}까지 경로" },
    ) {
        Image(painterResource(image), contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            drawRoute(route, progress, idle, pickupLabel, etaLabel, passenger, colors, measurer, arrow)
        }
    }
}

private fun DrawScope.drawRoute(
    route: List<MapPoint>,
    progress: Float,
    idle: Boolean,
    pickupLabel: String?,
    etaLabel: String?,
    passenger: Pair<MapPoint, String>?,
    c: AudeoColors,
    measurer: TextMeasurer,
    arrow: Path,
) {
    if (route.size < 2) return
    val pts = route.map { Offset(it.x * size.width, it.y * size.height) }
    val segs = pts.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
    val total = segs.sum()
    val target = if (idle) 0f else progress.coerceIn(0f, 1f) * total

    var acc = 0f
    var k = 1
    var car = pts.last()
    val done = mutableListOf(pts.first())
    while (k < pts.size) {
        val len = segs[k - 1]
        if (acc + len >= target) {
            val t = if (len == 0f) 0f else (target - acc) / len
            car = pts[k - 1] + (pts[k] - pts[k - 1]) * t
            break
        }
        acc += len
        done += pts[k]
        k++
    }
    done += car
    val rest = if (k < pts.size) listOf(car) + pts.subList(k, pts.size) else listOf(car)
    val segIdx = k.coerceIn(1, pts.lastIndex)
    val dir = pts[segIdx] - pts[segIdx - 1]
    val angle = Math.toDegrees(atan2(dir.y, dir.x).toDouble()).toFloat() + 90f
    val end = pts.last()

    if (!idle) {
        drawPath(polyline(done), c.inkFaint, style = Stroke(8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 18.dp.toPx()))))
        drawPath(polyline(rest), c.ground, style = Stroke(28.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(polyline(rest), c.route, style = Stroke(14.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    passenger?.let { (p, label) ->
        val o = Offset(p.x * size.width, p.y * size.height)
        drawCircle(c.surface, 18.dp.toPx(), o)
        drawCircle(c.ink, 18.dp.toPx(), o, style = Stroke(5.dp.toPx()))
        chip(measurer, label, Offset(o.x + 32.dp.toPx(), o.y - 28.dp.toPx()), c, numeric = false)
    }

    if (!idle) {
        drawCircle(c.surface, 29.dp.toPx(), end)
        drawCircle(c.ink, 26.dp.toPx(), end)
        drawCircle(c.surface, 9.dp.toPx(), end)
        pickupLabel?.let {
            val w = chipWidth(measurer, it, numeric = false)
            chip(measurer, it, Offset(end.x - w / 2, end.y - 104.dp.toPx()), c, numeric = false)
        }
    }

    drawCircle(c.surface, 41.dp.toPx(), car)
    drawCircle(c.brand, 38.dp.toPx(), car)
    val arrowScale = 1.5f * density
    withTransform({
        translate(car.x, car.y)
        rotate(angle, pivot = Offset.Zero)
        scale(arrowScale, arrowScale, pivot = Offset.Zero)
        translate(-12f, -12f)
    }) {
        drawPath(arrow, c.onBrand)
    }
    etaLabel?.let { chip(measurer, it, Offset(car.x + 56.dp.toPx(), car.y - 30.dp.toPx()), c, numeric = true) }
}

private fun polyline(points: List<Offset>): Path = Path().apply {
    points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
}

private fun chipStyle(numeric: Boolean, color: Color) =
    if (numeric) {
        TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Medium, color = color, fontFeatureSettings = "tnum")
    } else {
        TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = color)
    }

private fun DrawScope.chipWidth(measurer: TextMeasurer, text: String, numeric: Boolean): Float =
    measurer.measure(text, chipStyle(numeric, Color.Black)).size.width + 40.dp.toPx()

/** 지도 칩: 다이얼로그와 같은 surface + dialog-line 2px 테두리. */
private fun DrawScope.chip(measurer: TextMeasurer, text: String, topLeft: Offset, c: AudeoColors, numeric: Boolean) {
    val layout = measurer.measure(text, chipStyle(numeric, if (numeric) c.brandInk else c.ink))
    val h = (if (numeric) 60.dp else 56.dp).toPx()
    val w = layout.size.width + 40.dp.toPx()
    val r = CornerRadius(8.dp.toPx())
    drawRoundRect(c.surface, topLeft, Size(w, h), r)
    drawRoundRect(c.dialogLine, topLeft, Size(w, h), r, style = Stroke(2.dp.toPx()))
    drawText(layout, topLeft = Offset(topLeft.x + 20.dp.toPx(), topLeft.y + (h - layout.size.height) / 2))
}
