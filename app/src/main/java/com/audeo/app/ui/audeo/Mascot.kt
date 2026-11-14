package com.audeo.app.ui.audeo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audeo.app.audeo.VoiceMode
import kotlin.math.PI
import kotlin.math.sin

/**
 * Audeo의 AI 비서 캐릭터 '오디'. 그림 파일 없이 코드로 그려서 크기와 상태에 따라 자연스럽게 움직입니다.
 *
 * 생김새: 브랜드 파랑의 둥근 몸, 흰 얼굴 화면, 양옆 헤드폰 귀, 머리 위 작은 안테나.
 * - 말할 때(SPEAKING): 입이 말소리처럼 열렸다 닫히고 안테나 불빛이 깜빡여요.
 * - 들을 때(LISTENING): 안테나에서 동그란 물결이 퍼지고 입은 작게 오므려요.
 * - 생각할 때(THINKING): 눈이 웃는 반달이 되고 안테나가 천천히 빛나요.
 * - 쉴 때(IDLE): 가끔 눈을 깜빡이며 살짝 떠 있어요.
 */
@Composable
fun Odi(mode: VoiceMode, size: Dp = 96.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "odi")
    // 둥실 떠 있는 움직임(2.8초 한 번)
    val bob by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "bob")
    // 눈 깜빡임: 3.6초마다 짧게
    val blink by t.animateFloat(
        1f, 1f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 3600
                1f at 0
                1f at 3300
                0.08f at 3400
                1f at 3520
            },
        ),
        label = "blink",
    )
    // 말하는 입: 서로 다른 박자 둘을 섞어 말소리처럼 불규칙하게
    val talkA by t.animateFloat(0f, 1f, infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Reverse), label = "talkA")
    val talkB by t.animateFloat(0f, 1f, infiniteRepeatable(tween(410, easing = LinearEasing), RepeatMode.Reverse), label = "talkB")
    // 안테나 물결·빛
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")

    val desc = when (mode) {
        VoiceMode.SPEAKING -> "오디가 말하는 중"
        VoiceMode.LISTENING -> "오디가 듣는 중"
        VoiceMode.THINKING -> "오디가 생각하는 중"
        VoiceMode.IDLE -> "오디"
    }
    Canvas(modifier.size(size).semantics { contentDescription = desc }) {
        val u = this.size.minDimension / 100f
        val float = sin(bob * 2 * PI).toFloat() * 2.2f * u
        drawShadow(u, float)
        drawOdi(u, float, mode, blink, talkA * 0.6f + talkB * 0.4f, pulse)
    }
}

private val Body1 = Color(0xFF5B7BFF)
private val Body2 = Color(0xFF2347F5)
private val Ear = Color(0xFF1A33C9)
private val Face = Color(0xFFFFFFFF)
private val EyeInk = Color(0xFF0E1013)
private val Cheek = Color(0xFFFF8FA3)
private val Glow = Color(0xFF7FE3FF)

private fun DrawScope.drawShadow(u: Float, float: Float) {
    val w = 54 * u - float * 0.8f
    drawOval(Color.Black.copy(alpha = 0.10f), Offset(50 * u - w / 2, 92 * u), Size(w, 6 * u))
}

private fun DrawScope.drawOdi(u: Float, dy: Float, mode: VoiceMode, blink: Float, talk: Float, pulse: Float) {
    fun o(x: Float, y: Float) = Offset(x * u, y * u + dy)

    // 안테나: 줄과 빛나는 구슬, 듣는 중이면 물결
    drawLine(Body2, o(50f, 20f), o(50f, 11f), strokeWidth = 2.6f * u, cap = StrokeCap.Round)
    if (mode == VoiceMode.LISTENING) {
        for (k in 0..1) {
            val p = (pulse + k * 0.5f) % 1f
            drawCircle(Glow.copy(alpha = (1f - p) * 0.7f), radius = (5f + p * 11f) * u, center = o(50f, 9f), style = Stroke(1.6f * u))
        }
    }
    val bulbAlpha = when (mode) {
        VoiceMode.SPEAKING -> 0.55f + 0.45f * talk
        VoiceMode.THINKING -> 0.5f + 0.5f * sin(pulse * 2 * PI).toFloat().let { (it + 1f) / 2f }
        VoiceMode.LISTENING -> 1f
        VoiceMode.IDLE -> 0.85f
    }
    drawCircle(Glow.copy(alpha = 0.35f * bulbAlpha), radius = 8f * u, center = o(50f, 9f))
    drawCircle(Glow.copy(alpha = bulbAlpha), radius = 4.6f * u, center = o(50f, 9f))
    drawCircle(Color.White.copy(alpha = 0.9f), radius = 1.5f * u, center = o(48.6f, 7.8f))

    // 헤드폰 귀
    drawRoundRect(Ear, o(8f, 44f), Size(10 * u, 22 * u), CornerRadius(5 * u))
    drawRoundRect(Ear, o(82f, 44f), Size(10 * u, 22 * u), CornerRadius(5 * u))

    // 몸(위가 밝은 파랑)
    drawRoundRect(
        Brush.verticalGradient(listOf(Body1, Body2), startY = 18 * u + dy, endY = 90 * u + dy),
        o(14f, 18f),
        Size(72 * u, 72 * u),
        CornerRadius(30 * u),
    )
    // 몸 윗부분 하이라이트
    drawRoundRect(Color.White.copy(alpha = 0.18f), o(24f, 22f), Size(30 * u, 8 * u), CornerRadius(4 * u))

    // 얼굴 화면
    drawRoundRect(Face, o(23f, 34f), Size(54 * u, 40 * u), CornerRadius(18 * u))

    // 볼
    drawOval(Cheek.copy(alpha = 0.45f), o(27f, 58f), Size(9 * u, 5 * u))
    drawOval(Cheek.copy(alpha = 0.45f), o(64f, 58f), Size(9 * u, 5 * u))

    // 눈
    if (mode == VoiceMode.THINKING) {
        for (x in listOf(40f, 60f)) {
            val p = Path().apply {
                moveTo(x * u - 4.5f * u, 51 * u + dy)
                quadraticTo(x * u, 45 * u + dy, x * u + 4.5f * u, 51 * u + dy)
            }
            drawPath(p, EyeInk, style = Stroke(2.6f * u, cap = StrokeCap.Round))
        }
    } else {
        val eh = 11f * u * blink
        for (x in listOf(40f, 60f)) {
            drawRoundRect(EyeInk, Offset(x * u - 3.6f * u, 49.5f * u + dy - eh / 2), Size(7.2f * u, eh.coerceAtLeast(1.2f * u)), CornerRadius(3.6f * u))
            if (blink > 0.6f) drawCircle(Color.White, radius = 1.3f * u, center = Offset(x * u + 1.2f * u, 47.6f * u + dy))
        }
    }

    // 입
    when (mode) {
        VoiceMode.SPEAKING -> {
            val h = (2.2f + 6.5f * talk) * u
            drawRoundRect(EyeInk, Offset(50 * u - 4.6f * u, 63 * u + dy - h / 2 + 1.5f * u), Size(9.2f * u, h), CornerRadius(4.6f * u))
            drawRoundRect(Cheek, Offset(50 * u - 3f * u, 63 * u + dy + h / 2 - 1.2f * u), Size(6f * u, (h * 0.35f).coerceAtMost(2.6f * u)), CornerRadius(2f * u))
        }
        VoiceMode.LISTENING -> drawOval(EyeInk, Offset(50 * u - 2.4f * u, 61.5f * u + dy), Size(4.8f * u, 4.2f * u))
        else -> {
            val p = Path().apply {
                moveTo(44.5f * u, 61.5f * u + dy)
                quadraticTo(50 * u, 66.5f * u + dy, 55.5f * u, 61.5f * u + dy)
            }
            drawPath(p, EyeInk, style = Stroke(2.4f * u, cap = StrokeCap.Round))
        }
    }
}
