package com.audeo.app.ui.audeo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audeo.app.R
import com.audeo.app.audeo.VoiceMode
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Audeo의 AI 비서 캐릭터 '오디'. 그림은 Gemini(나노바나나 2)로 만든 표정 네 장이고,
 * 상태에 따라 표정을 바꾸고 둥실 떠 있는 움직임·그림자·안테나 물결은 코드로 더합니다.
 *
 * - 말할 때(SPEAKING): 입 벌린 표정과 기본 표정을 말소리처럼 불규칙하게 오가요.
 * - 들을 때(LISTENING): 귀 기울이는 표정, 안테나에서 동그란 물결이 퍼져요.
 * - 생각할 때(THINKING): 눈웃음 표정으로 살짝 더 천천히 흔들려요.
 * - 쉴 때(IDLE): 기본 표정으로 떠 있고 가끔 눈웃음으로 깜빡여요.
 */
@Composable
fun Odi(mode: VoiceMode, size: Dp = 96.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "odi")
    // 둥실 떠 있는 움직임(생각할 땐 조금 느리게)
    // 움직임 값은 그릴 때(draw·graphicsLayer)만 읽습니다. 화면 구성 중에 읽으면 매 프레임 다시 구성돼요.
    val bob = t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (mode == VoiceMode.THINKING) 3600 else 2800, easing = LinearEasing)), label = "bob")
    fun wave() = sin(bob.value * 2 * PI).toFloat()
    // 가끔 눈 깜빡임(눈웃음 표정을 잠깐 보여 줍니다)
    val blink = t.animateFloat(
        0f, 0f,
        infiniteRepeatable(
            keyframes {
                durationMillis = 4200
                0f at 0
                0f at 3900
                1f at 3910
                1f at 4080
                0f at 4090
            },
        ),
        label = "blink",
    )
    // 안테나 물결
    val pulse = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "pulse")

    // 말할 때 입: 열림·닫힘 길이를 매번 조금씩 달리해 말소리처럼 보이게 합니다(그릴 때만 읽음).
    val mouthOpen = remember { mutableStateOf(false) }
    LaunchedEffect(mode) {
        mouthOpen.value = false
        if (mode != VoiceMode.SPEAKING) return@LaunchedEffect
        while (true) {
            mouthOpen.value = !mouthOpen.value
            delay(if (mouthOpen.value) Random.nextLong(110, 220) else Random.nextLong(70, 160))
        }
    }

    val idle = painterResource(R.drawable.odi_idle)
    val talk = painterResource(R.drawable.odi_talk)
    val listen = painterResource(R.drawable.odi_listen)
    val think = painterResource(R.drawable.odi_think)
    val desc = when (mode) {
        VoiceMode.SPEAKING -> "오디가 말하는 중"
        VoiceMode.LISTENING -> "오디가 듣는 중"
        VoiceMode.THINKING -> "오디가 생각하는 중"
        VoiceMode.IDLE -> "오디"
    }
    // 표정 고르기·흔들림은 모두 그릴 때 정합니다. 화면 구성을 다시 하지 않으니 접근성 트리도 조용해요
    // (매 프레임 다시 구성하면 TalkBack이 계속 다시 읽고 uiautomator도 화면을 읽지 못해요).
    Box(modifier.size(size).semantics { contentDescription = desc }) {
        Canvas(Modifier.fillMaxSize()) {
            val wave = wave()
            val w = this.size.width
            val h = this.size.height
            // 발밑 그림자: 오디가 올라가면 작고 옅어집니다.
            val sw = w * (0.30f - wave * 0.02f)
            drawOval(Color.Black.copy(alpha = 0.16f - wave * 0.03f), Offset(w * FEET_X - sw / 2, h * 0.945f), Size(sw, h * 0.05f))
            val frame = when (mode) {
                VoiceMode.SPEAKING -> if (mouthOpen.value) talk else idle
                VoiceMode.LISTENING -> listen
                VoiceMode.THINKING -> think
                VoiceMode.IDLE -> if (blink.value > 0.5f) think else idle
            }
            val side = h * IMAGE_H
            val left = (w - side) / 2
            // 내려올 때 아주 살짝 눌리는 느낌(발 쪽을 기준으로)
            withTransform({
                translate(left, -(wave + 1f) * 0.018f * side)
                scale(1f - wave * 0.008f, 1f + wave * 0.012f, pivot = Offset(side * FEET_X, side))
            }) {
                with(frame) { draw(Size(side, side)) }
            }
        }
        if (mode == VoiceMode.LISTENING) {
            Canvas(Modifier.fillMaxSize()) {
                val wave = wave()
                val s = this.size.height * IMAGE_H
                val c = Offset(this.size.width / 2 + (BALL_X - 0.5f) * s, BALL_Y * s - (wave + 1f) * 0.018f * s)
                for (k in 0..1) {
                    val p = (pulse.value + k * 0.5f) % 1f
                    drawCircle(
                        Glow.copy(alpha = (1f - p) * 0.75f),
                        radius = (0.04f + p * 0.11f) * s,
                        center = c,
                        style = Stroke(0.012f * s),
                    )
                }
            }
        }
    }
}

/** 그림 안 위치(그림 한 변 = 1): 그림 높이 비율, 안테나 불빛 중심, 두 발 가운데. */
private const val IMAGE_H = 0.96f
private const val BALL_X = 0.562f
private const val BALL_Y = 0.07f
private const val FEET_X = 0.536f
private val Glow = Color(0xFF7FE3FF)
