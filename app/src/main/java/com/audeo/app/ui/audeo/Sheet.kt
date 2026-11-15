package com.audeo.app.ui.audeo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.audeo.app.audeo.PlanStatus
import com.audeo.app.audeo.Reason
import com.audeo.app.audeo.Tone
import com.audeo.app.audeo.VoiceMode
import com.audeo.app.audeo.VoiceUi
import com.audeo.app.ui.theme.Audeo
import com.audeo.app.ui.theme.AudeoType
import com.audeo.app.ui.theme.Dimens
import com.audeo.app.ui.theme.Radius
import com.audeo.app.ui.theme.Space
import kotlinx.coroutines.delay

/** 오디 크기와, 오디가 시트 위로 얼마나 올라와 있는지. 시트 머리줄은 이만큼 비워 둡니다. */
val ODI_SIZE = 152.dp
val ODI_RISE = 92.dp

/** 결정 트리의 가지 하나: 고르면 무엇을 하고, 그러면 어떻게 되는지. */
data class Branch(
    val label: String,
    val outcome: String? = null,
    val variant: ButtonVariant = ButtonVariant.SECONDARY,
    val icon: AudeoIcon? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** 이 결정은 다른 사람(탑승자)이 고르고 있어요. 운전자 화면에는 버튼 대신 기다림을 보여 줍니다. */
data class Waiting(val who: String, val detail: String? = null)

/**
 * 화면 아래에 붙는 Audeo 비서 시트. 왼쪽 위에는 오디가 올라앉아 말하고,
 * 왼쪽에는 오디의 말(말풍선)과 근거·내용, 오른쪽에는 고를 수 있는 길을 나뭇가지처럼 펼칩니다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantSheet(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.BRAND,
    kicker: String? = null,
    speech: String? = null,
    subtitle: String? = null,
    status: PlanStatus? = null,
    figureLabel: String? = null,
    figure: String? = null,
    figureNote: String? = null,
    reasons: List<Reason> = emptyList(),
    voice: VoiceUi? = null,
    footnote: String? = null,
    question: String? = null,
    branches: List<Branch> = emptyList(),
    waiting: Waiting? = null,
    voiceHint: String? = null,
    maxHeight: androidx.compose.ui.unit.Dp = 720.dp,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Audeo.colors
    val hasTree = branches.isNotEmpty() || waiting != null
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .animateContentSize(tween(Motion.ENTER_MS, easing = Motion.EaseOut))
            .floatingSurface()
            .padding(Space.s4),
        horizontalArrangement = Arrangement.spacedBy(Space.s5),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            // 머리줄: 오디 자리 + kicker + 말풍선 + 상태
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.Top) {
                Spacer(Modifier.width(ODI_SIZE - Space.s2))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    kicker?.let {
                        val kc = when (tone) {
                            Tone.BRAND -> c.brandInk
                            Tone.CAUTION -> c.caution
                            Tone.NEUTRAL -> c.inkMuted
                        }
                        Text(it, style = AudeoType.label, color = kc)
                    }
                    speech?.let { SpeechBubble(it, tone) }
                    subtitle?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.padding(start = Space.s1)) }
                }
                status?.let { StatusPill(it) }
            }
            // 오디가 말풍선과 같은 말을 하고 있으면 줄을 하나 더 그리지 않습니다.
            VoiceLine(voice?.takeUnless { v -> v.mode == VoiceMode.SPEAKING && v.caption?.trim('“', '”', ' ') == speech?.trim() })
            if (figure != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    figureLabel?.let { Text(it, style = AudeoType.bodyLg, color = c.inkMuted) }
                    Text(figure, style = AudeoType.figure, color = c.ink)
                    figureNote?.let { Text(it, style = AudeoType.bodyLg, color = c.ink) }
                }
            }
            if (reasons.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    reasons.forEach { r ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.Center) {
                            r.source?.let { SourceTag(it, r.label) }
                            Text(r.text, style = AudeoType.body, color = c.ink, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
            }
            body?.invoke(this)
            footnote?.let {
                Column {
                    HorizontalDivider(thickness = 2.dp, color = c.line)
                    Text(it, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.padding(top = Space.s2))
                }
            }
        }
        if (hasTree) {
            DecisionTree(
                question = question,
                branches = branches,
                waiting = waiting,
                hint = voiceHint,
                modifier = Modifier.width(620.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}

/** 오디가 하는 말. 왼쪽 위 모서리만 덜 둥글게 해서 오디 쪽에서 나온 말풍선처럼 보이게 합니다. */
@Composable
private fun SpeechBubble(text: String, tone: Tone) {
    val c = Audeo.colors
    val bg = when (tone) {
        Tone.BRAND -> c.brandSoft
        Tone.CAUTION -> c.cautionSoft
        Tone.NEUTRAL -> c.surface2
    }
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            (fadeIn(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) + slideInVertically(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) { it / 4 }) togetherWith
                fadeOut(tween(Motion.EXIT_MS, easing = Motion.EaseIn))
        },
        contentAlignment = Alignment.TopStart,
        label = "speech",
    ) { t ->
        Box(
            Modifier
                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = Radius.lg, bottomEnd = Radius.lg, bottomStart = Radius.lg))
                .background(bg)
                .padding(horizontal = Space.s3, vertical = Space.s2),
        ) {
            Text(t, style = AudeoType.title, color = c.ink)
        }
    }
}

/** 오디가 지금 듣는 말·하는 말 한 줄. 말풍선과 겹치면 굳이 그리지 않습니다. */
@Composable
private fun VoiceLine(voice: VoiceUi?) {
    val c = Audeo.colors
    if (voice?.caption == null && voice?.who == null) return
    val who = voice.who ?: when (voice.mode) {
        VoiceMode.LISTENING -> "듣고 있어요"
        VoiceMode.SPEAKING -> "오디"
        VoiceMode.THINKING -> "확인하는 중"
        VoiceMode.IDLE -> null
    }
    Row(
        Modifier.padding(start = ODI_SIZE + Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (voice.mode) {
            VoiceMode.LISTENING -> ListeningDots(c.brandInk)
            VoiceMode.THINKING -> ListeningDots(c.inkMuted)
            else -> {}
        }
        who?.let { Text(it, style = AudeoType.bodySm, color = if (voice.mode == VoiceMode.LISTENING) c.brandInk else c.inkMuted) }
        AnimatedContent(
            targetState = voice.caption,
            transitionSpec = { fadeIn(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) togetherWith fadeOut(tween(Motion.EXIT_MS, easing = Motion.EaseIn)) },
            label = "voiceLine",
        ) { cap -> cap?.let { Text(highlighted(it, voice.highlights, c.cautionSoft, c.caution), style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 2) } }
        voice.hint?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
    }
}

/** 서버가 근거로 든 구절(예: '광명 지나', '오늘 좀 춥네')을 자막에서 형광펜처럼 칠합니다. */
internal fun highlighted(text: String, marks: List<String>, bg: Color, fg: Color): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        marks.filter { it.isNotBlank() }.forEach { m ->
            var i = text.indexOf(m)
            while (i >= 0) {
                addStyle(androidx.compose.ui.text.SpanStyle(background = bg, color = fg, fontWeight = FontWeight.Bold), i, i + m.length)
                i = text.indexOf(m, i + m.length)
            }
        }
    }

@Composable
private fun ListeningDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse, StartOffset(i * 160)), label = "d$i")
            Box(Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = a)))
        }
    }
}

private val TRUNK_W = 52.dp
private val TRUNK_X = 14.dp

/**
 * 의사결정 트리: 맨 위 뿌리(지금 정할 것)에서 줄기가 내려오고, 고를 수 있는 길마다 가지가 뻗어 카드에 닿습니다.
 * 카드에는 고르면 어떻게 되는지(결과)를 같이 적어 두어 운전 중에도 한눈에 비교할 수 있게 합니다.
 */
@Composable
private fun DecisionTree(question: String?, branches: List<Branch>, waiting: Waiting?, hint: String?, modifier: Modifier) {
    val c = Audeo.colors
    val count = branches.size + if (waiting != null) 1 else 0
    Column(modifier) {
        TreeRow(index = -1, last = count == 0, highlight = false) {
            Text(question ?: "어떻게 할까요?", style = AudeoType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = c.inkMuted, modifier = Modifier.padding(vertical = Space.s1))
        }
        branches.forEachIndexed { i, b ->
            TreeRow(index = i, last = i == count - 1, highlight = b.variant == ButtonVariant.PRIMARY) { BranchCard(b) }
        }
        waiting?.let { TreeRow(index = branches.size, last = true, highlight = true) { WaitingCard(it) } }
        hint?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.padding(start = TRUNK_W, top = Space.s1)) }
    }
}

/** 트리 한 줄: 왼쪽에 줄기·가지를 그리고, 오른쪽에 내용을 둡니다. 줄마다 조금씩 늦게 자라납니다. */
@Composable
private fun TreeRow(index: Int, last: Boolean, highlight: Boolean, content: @Composable () -> Unit) {
    val c = Audeo.colors
    val grow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(60L + (index + 1) * 70L)
        grow.animateTo(1f, tween(Motion.ENTER_MS, easing = Motion.EaseOut))
    }
    val lineColor = c.line
    val branchColor = if (highlight) c.brand else c.inkFaint
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Canvas(Modifier.width(TRUNK_W).fillMaxHeight()) {
            val x = TRUNK_X.toPx()
            val cy = size.height / 2
            val w = 3.dp.toPx()
            val r = 14.dp.toPx()
            val g = grow.value
            if (index < 0) {
                // 뿌리: 동그란 점에서 줄기가 아래로
                if (!last) drawLine(lineColor, Offset(x, cy), Offset(x, size.height), strokeWidth = w)
                drawCircle(c.brand, radius = 9.dp.toPx(), center = Offset(x, cy))
                drawCircle(c.surface, radius = 4.dp.toPx(), center = Offset(x, cy))
                return@Canvas
            }
            // 줄기(위에서 내려옴). 마지막 가지는 둥글게 꺾여 끝납니다.
            if (last) {
                drawLine(lineColor, Offset(x, 0f), Offset(x, cy - r), strokeWidth = w)
                val p = Path().apply {
                    moveTo(x, cy - r)
                    arcTo(Rect(x, cy - 2 * r, x + 2 * r, cy), 180f, -90f, false)
                }
                drawPath(p, lineColor, style = Stroke(w))
            } else {
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = w)
            }
            // 가지: 줄기에서 카드까지 자라납니다.
            val from = if (last) x + r else x
            val to = from + (size.width - from) * g
            drawLine(branchColor, Offset(from, cy), Offset(to, cy), strokeWidth = w, cap = StrokeCap.Round)
            if (!last) drawCircle(branchColor, radius = 5.dp.toPx(), center = Offset(x, cy))
        }
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = Space.s1)
                .graphicsLayer {
                    alpha = grow.value
                    translationX = (1f - grow.value) * 24.dp.toPx()
                },
        ) { content() }
    }
}

@Composable
private fun BranchCard(b: Branch) {
    val c = Audeo.colors
    val interaction = remember { MutableInteractionSource() }
    val (bg, fg, sub) = when (b.variant) {
        ButtonVariant.PRIMARY -> Triple(c.brand, c.onBrand, c.onBrand.copy(alpha = 0.82f))
        ButtonVariant.DANGER -> Triple(c.dangerSoft, c.danger, c.inkMuted)
        ButtonVariant.QUIET -> Triple(Color.Transparent, c.ink, c.inkMuted)
        ButtonVariant.SECONDARY -> Triple(c.surface2, c.ink, c.inkMuted)
    }
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.controlH)
            .alpha(if (b.enabled) 1f else 0.45f)
            .pressScale(interaction)
            .clip(shape)
            .background(bg)
            .then(if (b.variant == ButtonVariant.QUIET) Modifier.border(2.dp, c.line, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = b.enabled, role = Role.Button, onClick = b.onClick)
            .padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        b.icon?.let { Icon(it.vector, contentDescription = null, tint = fg, modifier = Modifier.size(36.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.label, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
            b.outcome?.let { Text(it, style = AudeoType.bodySm, color = sub, maxLines = 2) }
        }
        Icon(AudeoIcon.Chevron.vector, contentDescription = null, tint = sub, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun WaitingCard(w: Waiting) {
    val c = Audeo.colors
    val t = rememberInfiniteTransition(label = "wait")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.controlH)
            .clip(RoundedCornerShape(Radius.md))
            .border(2.dp, c.brand.copy(alpha = 0.5f), RoundedCornerShape(Radius.md))
            .padding(horizontal = Space.s3, vertical = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(Space.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(56.dp)) {
                drawArc(c.brand, sweep, 100f, false, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round), topLeft = Offset(2.dp.toPx(), 2.dp.toPx()), size = Size(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()))
            }
            Text(w.who.take(1), style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("${w.who}이 고르고 있어요", style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            w.detail?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
        }
    }
}
