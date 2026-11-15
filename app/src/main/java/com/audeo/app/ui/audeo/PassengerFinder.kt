package com.audeo.app.ui.audeo

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audeo.app.audeo.AudeoUiState
import com.audeo.app.audeo.CallStatus
import com.audeo.app.map.PlaceFinder
import com.audeo.app.ui.theme.Audeo
import com.audeo.app.ui.theme.AudeoType
import com.audeo.app.ui.theme.Space
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/** 이 거리 안으로 들어오면 '탑승자 찾기'를 띄워요(나갈 때는 50m 더 멀어져야 내려서 경계에서 깜빡이지 않아요). */
private const val NEAR_M = 300.0

/** 이보다 오래된 위치는 쓰지 않아요(통화가 끝나고 시간이 지나면 위치가 멈춰 있어요). */
private const val FRESH_MS = 3 * 60_000L

/** 지금 보여 줄지. 진행 중인 픽업(또는 통화)에 탑승자 위치가 있고 차와 가까울 때만. */
fun showPassengerFinder(s: AudeoUiState, wasShown: Boolean): Boolean {
    val pax = s.passenger ?: return false
    val car = s.car ?: return false
    if (s.journey == null && s.call?.status != CallStatus.ACTIVE) return false
    if (System.currentTimeMillis() - pax.atMillis > FRESH_MS) return false
    val d = PlaceFinder.distanceM(car, pax.point)
    return d <= if (wasShown) NEAR_M + 50 else NEAR_M
}

/**
 * 차 화면의 '탑승자 찾기'(iPhone '나의 찾기'처럼). 탑승자 휴대폰이 통화 중 보낸 정밀 위치로,
 * 차를 가운데 두고 탑승자가 어느 쪽 몇 m에 있는지 보여 줘요. 차 방향을 모르면 북쪽이 위입니다.
 * 탑승자 휴대폰에서는 반대로 차 쪽을 가리키는 나침반이 떠요.
 */
@Composable
fun PassengerFinder(s: AudeoUiState, modifier: Modifier = Modifier) {
    val c = Audeo.colors
    val pax = s.passenger ?: return
    val car = s.car ?: return
    val name = s.journey?.passengerCallName ?: s.call?.peerCallName ?: "탑승자"
    val d = PlaceFinder.distanceM(car, pax.point)
    val b = PlaceFinder.bearing(car, pax.point)
    val close = d <= maxOf(15.0, minOf(pax.accuracy ?: 0.0, 40.0))
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pax.atMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val ago = ((now - pax.atMillis) / 1000).coerceAtLeast(0)
    val t = rememberInfiniteTransition(label = "paxPulse")
    val pulses = List(2) { i -> t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart, StartOffset(i * 800)), label = "p$i") }
    Column(
        modifier.floatingSurface(background = if (close) c.brand else c.surface).padding(horizontal = Space.s4, vertical = Space.s3),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        val ink = if (close) c.onBrand else c.ink
        val muted = if (close) c.onBrand.copy(alpha = 0.8f) else c.inkMuted
        Text(if (close) "${name}이 바로 옆에 있어요" else "${name}이 가까워요", style = AudeoType.label, color = if (close) c.onBrand else c.brandInk, modifier = Modifier.align(Alignment.Start))
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(260.dp)) {
                val r = size.minDimension / 2
                val center = Offset(size.width / 2, size.height / 2)
                // 거리 고리: 50m · 150m · 300m (로그 눈금이라 가까운 쪽이 넓어요)
                listOf(50.0, 150.0, 300.0).forEach { m -> drawCircle(muted.copy(alpha = 0.35f), radius = r * scale(m), center = center, style = Stroke(2.dp.toPx())) }
                drawLine(muted.copy(alpha = 0.5f), Offset(center.x, center.y - r + 6.dp.toPx()), Offset(center.x, center.y - r + 22.dp.toPx()), strokeWidth = 3.dp.toPx())
                // 차(가운데)
                drawCircle(if (close) c.onBrand else c.brand, radius = 12.dp.toPx(), center = center)
                drawCircle(if (close) c.brand else c.surface, radius = 5.dp.toPx(), center = center)
                // 탑승자 점과 퍼지는 고리
                val rr = r * scale(d)
                val a = Math.toRadians(b)
                val p = Offset(center.x + (rr * sin(a)).toFloat(), center.y - (rr * cos(a)).toFloat())
                pulses.forEach { v -> drawCircle(c.caution.copy(alpha = (1f - v.value) * 0.6f), radius = 14.dp.toPx() + v.value * 30.dp.toPx(), center = p) }
                drawCircle(c.caution, radius = 13.dp.toPx(), center = p)
                drawCircle(c.surface, radius = 13.dp.toPx(), center = p, style = Stroke(4.dp.toPx()))
            }
            Text("N", style = AudeoType.label.copy(fontWeight = FontWeight.Bold), color = muted, modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp))
        }
        Text(if (close) "바로 근처" else distance(d), style = AudeoType.figure, color = ink)
        Text(if (close) "주위를 둘러보세요" else "${COMPASS[((b + 22.5) / 45).toInt() % 8]} 방향", style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = ink)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            val bits = listOfNotNull(
                if (ago < 5) "방금 받은 위치" else "${ago}초 전 위치",
                pax.accuracy?.takeIf { it > 20 }?.let { "오차 ${it.roundToInt()}m" },
                "지도 위쪽이 북쪽",
            )
            Text(bits.joinToString(" · "), style = AudeoType.bodySm, color = muted)
        }
    }
}

/** 0~300m를 고리 반지름 0.18~0.95로. 가까울수록 차이가 잘 보이게 로그 눈금을 써요. */
private fun scale(m: Double): Float {
    val v = ln(1 + m.coerceIn(0.0, 400.0) / 10) / ln(1 + 300.0 / 10)
    return (0.18 + 0.77 * v).toFloat().coerceAtMost(0.95f)
}

private fun distance(m: Double) = if (m < 1000) "${maxOf(5, (m / 5).roundToInt() * 5)}m" else "%.1fkm".format(m / 1000)

private val COMPASS = listOf("북쪽", "북동쪽", "동쪽", "남동쪽", "남쪽", "남서쪽", "서쪽", "북서쪽")
