package com.audeo.app.ui.audeo

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.audeo.app.audeo.ActionResult
import com.audeo.app.audeo.PlanStatus
import com.audeo.app.audeo.ResultState
import com.audeo.app.audeo.SourceKind
import com.audeo.app.audeo.VoiceMode
import com.audeo.app.audeo.VoiceUi
import com.audeo.app.ui.theme.Audeo
import com.audeo.app.ui.theme.AudeoType
import com.audeo.app.ui.theme.Dimens
import com.audeo.app.ui.theme.Radius
import com.audeo.app.ui.theme.Space

/** 지도 위에 뜨는 모든 것(다이얼로그·통화 바·결과 배너)의 공통 틀: surface, dialog-line 2px, radius-lg, 그림자. */
@Composable
fun Modifier.floatingSurface(shape: Shape = RoundedCornerShape(Radius.lg), background: Color = Audeo.colors.surface): Modifier {
    val c = Audeo.colors
    return this
        .shadow(if (c.isNight) 24.dp else 16.dp, shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow)
        .border(Dimens.dialogBorder, c.dialogLine, shape)
        .clip(shape)
        .background(background)
}

enum class ButtonVariant { PRIMARY, SECONDARY, QUIET, DANGER }

enum class ButtonSize(val height: Dp, val padding: Dp, val icon: Dp) {
    LG(Dimens.controlH, Space.s5, 40.dp),
    MD(Dimens.touchMin, Space.s4, 32.dp),
}

/** 큰 터치 버튼. primary는 다이얼로그 하나에 하나, 줄은 왼쪽(운전석 쪽)부터 primary → secondary → quiet. */
@Composable
fun AudeoButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.SECONDARY,
    size: ButtonSize = ButtonSize.LG,
    icon: AudeoIcon? = null,
    enabled: Boolean = true,
    full: Boolean = false,
    description: String? = null,
) {
    val c = Audeo.colors
    val (bg, fg) = when {
        !enabled -> c.surface2 to c.inkFaint
        variant == ButtonVariant.PRIMARY -> c.brand to c.onBrand
        variant == ButtonVariant.SECONDARY -> c.surface2 to c.ink
        variant == ButtonVariant.QUIET -> Color.Transparent to c.inkMuted
        else -> c.danger to c.onDanger
    }
    val iconOnly = text.isNullOrEmpty()
    val shape = if (iconOnly && size == ButtonSize.MD) CircleShape else RoundedCornerShape(Radius.md)
    var m = modifier.height(size.height)
    m = when {
        full -> m.fillMaxWidth()
        iconOnly -> m.width(size.height)
        else -> m
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = m
            .pressScale(interaction)
            .clip(shape)
            .background(bg)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = if (iconOnly) 0.dp else size.padding),
        horizontalArrangement = Arrangement.spacedBy(Space.s2, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon.vector, contentDescription = null, tint = fg, modifier = Modifier.size(size.icon))
        if (!iconOnly) Text(text!!, style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** 값 하나의 출처. 각진 모서리, 아이콘 없이 글자만. */
@Composable
fun SourceTag(kind: SourceKind, label: String? = null) {
    val c = Audeo.colors
    val shape = RoundedCornerShape(Radius.sm)
    val (bg, fg) = when (kind) {
        SourceKind.CONVERSATION -> c.surface2 to c.ink
        SourceKind.LOOKUP -> c.brandSoft to c.brandInk
        SourceKind.ESTIMATE -> Color.Transparent to c.inkMuted
        SourceKind.CONFIRM -> c.cautionSoft to c.caution
        SourceKind.USER -> c.surface2 to c.ink
    }
    var m = Modifier.height(44.dp).clip(shape).background(bg)
    if (kind == SourceKind.ESTIMATE) {
        m = m.dashedBorder(c.inkMuted, Radius.sm)
    }
    Box(m.padding(horizontal = Space.s2), contentAlignment = Alignment.Center) {
        Text(label ?: kind.label, style = AudeoType.label, color = fg, maxLines = 1)
    }
}

/** 점선 테두리(추정 태그). */
private fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = drawBehind {
    val stroke = 2.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}

/** 픽업 여정 전체의 상태. 둥근 칩, 확정만 brand로 채움. */
@Composable
fun StatusPill(status: PlanStatus) {
    val c = Audeo.colors
    val (bg, fg) = when (status) {
        PlanStatus.DRAFT -> c.surface2 to c.inkMuted
        PlanStatus.CONFIRMED -> c.brand to c.onBrand
        PlanStatus.ACTIVE -> c.brandSoft to c.brandInk
        PlanStatus.DELAY -> c.cautionSoft to c.caution
        PlanStatus.DONE -> c.surface2 to c.ink
        PlanStatus.FAILED -> c.dangerSoft to c.danger
    }
    Box(
        Modifier.height(52.dp).clip(CircleShape).background(bg).padding(horizontal = Space.s3),
        contentAlignment = Alignment.Center,
    ) {
        Text(status.label, style = AudeoType.label, color = fg, maxLines = 1)
    }
}

private val BARS = listOf(0.45f, 0.8f, 1f, 0.7f, 0.4f)

/** 듣는 중·말하는 중·정리하는 중·대기를 보여 주는 음성 원. 파형은 가운데 막대 5개. */
@Composable
fun VoiceOrb(mode: VoiceMode, size: Dp = 72.dp, inStrip: Boolean = true) {
    val c = Audeo.colors
    val speaking = mode == VoiceMode.SPEAKING
    val bg = when {
        speaking -> c.brand
        inStrip -> c.surface
        else -> c.surface2
    }
    val fg = when {
        speaking -> c.onBrand
        mode == VoiceMode.IDLE -> c.inkMuted
        else -> c.ink
    }
    val barHeight = size * 0.39f
    val barWidth = if (size >= 100.dp) 8.dp else 5.dp
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        when (mode) {
            VoiceMode.LISTENING, VoiceMode.SPEAKING -> {
                val transition = rememberInfiniteTransition(label = "wave")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    BARS.forEachIndexed { i, h ->
                        val s by transition.animateFloat(
                            initialValue = 0.45f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                tween(550, easing = FastOutSlowInEasing),
                                RepeatMode.Reverse,
                                StartOffset(i * 90),
                            ),
                            label = "bar$i",
                        )
                        Box(
                            Modifier
                                .width(barWidth)
                                .height(barHeight * h)
                                .scale(1f, s)
                                .clip(CircleShape)
                                .background(fg),
                        )
                    }
                }
            }
            VoiceMode.THINKING -> {
                val transition = rememberInfiniteTransition(label = "dots")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(3) { i ->
                        val a by transition.animateFloat(
                            0.3f, 1f,
                            infiniteRepeatable(tween(600), RepeatMode.Reverse, StartOffset(i * 200)),
                            label = "dot$i",
                        )
                        Box(Modifier.size(10.dp).clip(CircleShape).background(fg.copy(alpha = a)))
                    }
                }
            }
            VoiceMode.IDLE -> Icon(AudeoIcon.MicOff.vector, contentDescription = null, tint = fg, modifier = Modifier.size(32.dp))
        }
    }
}

/** 다이얼로그 안의 음성 칸: Audeo가 지금 하는 말이나 듣고 있는 말 한 줄. */
@Composable
fun VoiceStrip(voice: VoiceUi, modifier: Modifier = Modifier) {
    val c = Audeo.colors
    val speaking = voice.mode == VoiceMode.SPEAKING
    val who = voice.who ?: when (voice.mode) {
        VoiceMode.LISTENING -> "듣는 중"
        VoiceMode.SPEAKING -> "Audeo가 말하는 중"
        VoiceMode.THINKING -> "정리하는 중"
        VoiceMode.IDLE -> "대기 중"
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(c.surface2)
            .padding(Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoiceOrb(voice.mode)
        Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
            Text(who, style = AudeoType.bodySm, color = if (speaking) c.brandInk else c.inkMuted)
            // 들은 말·할 말이 바뀌면 문장을 부드럽게 바꿔 끼웁니다.
            AnimatedContent(
                targetState = voice.caption,
                transitionSpec = {
                    (fadeIn(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) + slideInVertically(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) { it / 3 }) togetherWith
                        fadeOut(tween(Motion.EXIT_MS, easing = Motion.EaseIn))
                },
                contentAlignment = Alignment.TopStart,
                label = "caption",
            ) { caption -> caption?.let { Text(it, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink) } }
            voice.hint?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
        }
    }
}

/** Audeo 통화 중 지도 위 맨 위에 붙는 112dp 바. */
@Composable
fun CallBar(
    name: String,
    role: String,
    duration: String,
    aiOn: Boolean,
    aiLabel: String,
    muted: Boolean,
    onMute: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Audeo.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(Dimens.callbarH)
            .floatingSurface()
            .padding(horizontal = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(Dimens.touchMin).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                Text(name.take(1), style = AudeoType.title, color = c.ink)
            }
            Column {
                Text(name, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1)
                Text(role, style = AudeoType.bodySm, color = c.inkMuted, maxLines = 1)
            }
        }
        Text(duration, style = AudeoType.figureSm, color = c.inkMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
            BlinkDot(if (aiOn) c.brandInk else c.inkFaint, blink = aiOn)
            Text(aiLabel, style = AudeoType.bodySm, color = if (aiOn) c.brandInk else c.inkMuted, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        AudeoButton(
            text = null,
            onClick = onMute,
            size = ButtonSize.MD,
            icon = if (muted) AudeoIcon.MicOff else AudeoIcon.Mic,
            description = if (muted) "마이크 켜기" else "마이크 끄기",
        )
        AudeoButton("통화 종료", onEnd, variant = ButtonVariant.DANGER, size = ButtonSize.MD, icon = AudeoIcon.PhoneEnd)
    }
}

@Composable
private fun BlinkDot(color: Color, blink: Boolean) {
    val alpha = if (blink) {
        val t = rememberInfiniteTransition(label = "blink")
        val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
        a
    } else {
        1f
    }
    Box(Modifier.size(14.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

/** 승인한 일이 실제로 실행됐는지. 결과가 불분명하면 pending으로 두고 성공으로 보이지 않게 합니다. */
@Composable
fun ResultBanner(result: ActionResult, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = Audeo.colors
    val failed = result.state == ResultState.FAILED
    Row(
        modifier
            .fillMaxWidth()
            .floatingSurface(background = if (failed) c.dangerSoft else c.surface)
            .padding(start = Space.s3, top = Space.s3, bottom = Space.s3, end = Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (bg, fg, icon) = when (result.state) {
            ResultState.SUCCESS -> Triple(c.brand, c.onBrand, AudeoIcon.Check)
            ResultState.FAILED -> Triple(c.danger, c.onDanger, AudeoIcon.Close)
            ResultState.PENDING -> Triple(c.surface2, c.inkMuted, AudeoIcon.Refresh)
        }
        Box(Modifier.size(Dimens.touchMin).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
            if (result.state == ResultState.PENDING) {
                val t = rememberInfiniteTransition(label = "spin")
                val deg by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "deg")
                Icon(icon.vector, null, tint = fg, modifier = Modifier.size(40.dp).rotate(deg))
            } else {
                Icon(icon.vector, null, tint = fg, modifier = Modifier.size(40.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(result.title, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            result.detail?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
        }
        action?.invoke()
    }
}
