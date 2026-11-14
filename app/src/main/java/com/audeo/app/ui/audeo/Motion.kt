package com.audeo.app.ui.audeo

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Audeo 움직임. Tailwind UI 모달 전환과 같은 값을 씁니다.
 * - 나타날 때: ease-out 300ms, 투명도 0→1, 살짝 커지며 아래에서 올라옴
 * - 사라질 때: ease-in 200ms, 반대로
 * 운전 중 화면이라 튀는 움직임(바운스·회전)은 쓰지 않고, 짧고 차분하게 둡니다.
 */
object Motion {
    /** Tailwind `ease-out` = cubic-bezier(0, 0, 0.2, 1) */
    val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** Tailwind `ease-in` = cubic-bezier(0.4, 0, 1, 1) */
    val EaseIn = CubicBezierEasing(0.4f, 0f, 1f, 1f)

    /** Tailwind `ease-in-out` = cubic-bezier(0.4, 0, 0.2, 1) */
    val EaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    const val ENTER_MS = 300
    const val EXIT_MS = 200

    /** 화면 아래 비서 시트: 아래에서 살짝 올라오며 나타나고, 내려가며 사라집니다. */
    @Composable
    fun sheetEnter(): EnterTransition {
        val rise = with(LocalDensity.current) { 32.dp.roundToPx() }
        return fadeIn(tween(ENTER_MS, easing = EaseOut)) +
            scaleIn(tween(ENTER_MS, easing = EaseOut), initialScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 1f)) +
            slideInVertically(tween(ENTER_MS, easing = EaseOut)) { rise }
    }

    fun sheetExit(): ExitTransition =
        fadeOut(tween(EXIT_MS, easing = EaseIn)) +
            scaleOut(tween(EXIT_MS, easing = EaseIn), targetScale = 0.97f, transformOrigin = TransformOrigin(0.5f, 1f))

    /** 위에서 내려오는 띠(통화 바, 결과 배너). */
    fun barEnter(): EnterTransition =
        fadeIn(tween(ENTER_MS, easing = EaseOut)) +
            slideInVertically(tween(ENTER_MS, easing = EaseOut)) { -it / 2 } +
            expandVertically(tween(ENTER_MS, easing = EaseOut), expandFrom = Alignment.Top)

    fun barExit(): ExitTransition =
        fadeOut(tween(EXIT_MS, easing = EaseIn)) +
            slideOutVertically(tween(EXIT_MS, easing = EaseIn)) { -it / 2 } +
            shrinkVertically(tween(EXIT_MS, easing = EaseIn), shrinkTowards = Alignment.Top)
}

/** Tailwind `active:scale-95`처럼 누르는 동안 살짝 작아집니다. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.96f): Modifier {
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = tween(if (isPressed) 100 else 150, easing = Motion.EaseOut),
        label = "press",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
