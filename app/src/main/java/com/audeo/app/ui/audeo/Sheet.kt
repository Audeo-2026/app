package com.audeo.app.ui.audeo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/** 펼친 시트: 오디 크기, 시트 위로 올라온 높이, 시트 최대 크기. 운전 중 지도를 가리지 않도록 왼쪽 아래에만 둡니다. */
val ODI_OPEN = 120.dp
val ODI_RISE = 56.dp
val SHEET_MAX_W = 1080.dp
val SHEET_MAX_H = 540.dp

/** 접힌 줄: 오디가 줄 왼쪽에 앉아 조금 위로 튀어나옵니다. */
val ODI_BAR = 84.dp
val ODI_BAR_POP = 18.dp
val BAR_H = 88.dp
private val BAR_MAX_W = 760.dp
private val TREE_W = 420.dp

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

/** 접힌 줄 오른쪽에 붙는 짧은 상태(예: 도착 16:06, 확인할 게 있어요). */
data class Chip(val text: String, val tone: Tone = Tone.NEUTRAL)

/** 오디가 어디에 얼마나 크게 앉을지. 시트가 접히고 펼쳐질 때 같이 움직입니다. */
data class OdiSpot(val size: Dp, val x: Dp, val y: Dp)

@Composable
fun animateOdiSpot(expanded: Boolean): OdiSpot {
    val spec = tween<Dp>(Motion.ENTER_MS, easing = Motion.EaseOut)
    val size by animateDpAsState(if (expanded) ODI_OPEN else ODI_BAR, spec, label = "odiSize")
    val x by animateDpAsState(if (expanded) Space.s3 else Space.s1, spec, label = "odiX")
    val y by animateDpAsState(if (expanded) -ODI_RISE else -ODI_BAR_POP, spec, label = "odiY")
    return OdiSpot(size, x, y)
}

/**
 * 화면 왼쪽 아래의 Audeo 비서. 평소에는 오디와 한 줄(접힘)로만 보이고,
 * 운전자가 골라야 할 때만 펼쳐서 오디의 말(말풍선)과 근거, 고를 수 있는 길(결정 트리)을 보여 줍니다.
 * 접힌 줄을 누르면 펼치고, 펼친 시트 위의 '접기'를 누르면 다시 접어요.
 */
@Composable
fun AssistantSheet(
    expanded: Boolean,
    onToggle: () -> Unit,
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
    chip: Chip? = null,
    maxHeight: Dp = SHEET_MAX_H,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val radius by animateDpAsState(if (expanded) Radius.lg else BAR_H / 2, tween(Motion.ENTER_MS, easing = Motion.EaseOut), label = "radius")
    Box(modifier) {
        Box(Modifier.floatingSurface(RoundedCornerShape(radius))) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    fadeIn(tween(Motion.ENTER_MS, delayMillis = 80, easing = Motion.EaseOut)) togetherWith
                        fadeOut(tween(Motion.EXIT_MS, easing = Motion.EaseIn)) using
                        SizeTransform(clip = true) { _, _ -> tween(Motion.ENTER_MS, easing = Motion.EaseOut) }
                },
                contentAlignment = Alignment.BottomStart,
                label = "sheetOpen",
            ) { open ->
                if (open) {
                    OpenSheet(
                        tone, kicker, speech, subtitle, status, figureLabel, figure, figureNote, reasons, voice,
                        footnote, question, branches, waiting, voiceHint, maxHeight, body,
                    )
                } else {
                    Bar(tone, kicker, speech, voice, waiting, chip, onToggle)
                }
            }
        }
        if (expanded) FoldTab(onToggle, Modifier.align(Alignment.TopEnd).offset(x = -Space.s4, y = -FOLD_H / 2))
    }
}

/** 접힌 줄: 오디 + 한 줄 말 + 짧은 상태. 줄 전체를 누르면 펼쳐요. */
@Composable
private fun Bar(tone: Tone, kicker: String?, speech: String?, voice: VoiceUi?, waiting: Waiting?, chip: Chip?, onToggle: () -> Unit) {
    val c = Audeo.colors
    val listening = voice?.mode == VoiceMode.LISTENING && voice.caption != null
    val line = if (listening) voice!!.caption!!.trim('“', '”', ' ') else speech ?: voice?.caption ?: ""
    Row(
        Modifier
            .height(BAR_H)
            .widthIn(max = BAR_MAX_W)
            .clickable(role = Role.Button, onClickLabel = "펼치기", onClick = onToggle)
            .padding(start = Space.s1, end = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(ODI_BAR))
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val k = if (listening) "듣고 있어요" else kicker
            k?.let { Text(it, style = AudeoType.label, color = kickerColor(tone), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(line, style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            waiting != null -> Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
                Spinner(28.dp)
                Text("${waiting.who} 고르는 중", style = AudeoType.label, color = c.brandInk, maxLines = 1)
            }
            chip != null -> ChipView(chip)
            listening -> ListeningDots(c.brandInk)
        }
        Icon(AudeoIcon.Chevron.vector, contentDescription = null, tint = c.inkMuted, modifier = Modifier.size(28.dp).rotate(-90f))
    }
}

@Composable
private fun ChipView(chip: Chip) {
    val c = Audeo.colors
    val (bg, fg) = when (chip.tone) {
        Tone.BRAND -> c.brandSoft to c.brandInk
        Tone.CAUTION -> c.cautionSoft to c.caution
        Tone.NEUTRAL -> c.surface2 to c.inkMuted
    }
    Box(Modifier.clip(RoundedCornerShape(Radius.md)).background(bg).padding(horizontal = Space.s2, vertical = 6.dp)) {
        Text(chip.text, style = AudeoType.label, color = fg, maxLines = 1)
    }
}

private val FOLD_H = 44.dp

/** 한국어 문장을 낱글자가 아니라 어절(띄어쓰기) 단위로 줄바꿈합니다(Android 13 이상). */
private val KoreanWords = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Phrase)

/** 펼친 시트 위 가장자리에 걸친 '접기' 손잡이. */
@Composable
private fun FoldTab(onClick: () -> Unit, modifier: Modifier) {
    val c = Audeo.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .height(FOLD_H)
            .pressScale(interaction)
            .floatingSurface(RoundedCornerShape(FOLD_H / 2))
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s2),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AudeoIcon.Chevron.vector, contentDescription = null, tint = c.inkMuted, modifier = Modifier.size(24.dp).rotate(90f))
        Text("접기", style = AudeoType.label, color = c.inkMuted)
    }
}

@Composable
private fun kickerColor(tone: Tone): Color {
    val c = Audeo.colors
    return when (tone) {
        Tone.BRAND -> c.brandInk
        Tone.CAUTION -> c.caution
        Tone.NEUTRAL -> c.inkMuted
    }
}

/** 펼친 시트: 왼쪽에는 오디의 말과 근거·내용, 오른쪽에는 고를 수 있는 길을 나뭇가지처럼 펼칩니다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenSheet(
    tone: Tone,
    kicker: String?,
    speech: String?,
    subtitle: String?,
    status: PlanStatus?,
    figureLabel: String?,
    figure: String?,
    figureNote: String?,
    reasons: List<Reason>,
    voice: VoiceUi?,
    footnote: String?,
    question: String?,
    branches: List<Branch>,
    waiting: Waiting?,
    voiceHint: String?,
    maxHeight: Dp,
    body: (@Composable ColumnScope.() -> Unit)?,
) {
    val c = Audeo.colors
    val hasTree = branches.isNotEmpty() || waiting != null
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .padding(start = Space.s3, end = Space.s3, top = Space.s3, bottom = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.s2),
        ) {
            // 머리줄: 오디 자리 + kicker + 말풍선 + 상태
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.Top) {
                Spacer(Modifier.width(ODI_OPEN - Space.s2))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    kicker?.let { Text(it, style = AudeoType.label, color = kickerColor(tone)) }
                    speech?.let { SpeechBubble(it, tone) }
                }
                status?.let { StatusPill(it) }
            }
            subtitle?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
            // 오디가 말풍선과 같은 말을 하고 있으면 줄을 하나 더 그리지 않습니다.
            VoiceLine(voice?.takeUnless { v -> v.mode == VoiceMode.SPEAKING && v.caption?.trim('“', '”', ' ') == speech?.trim() })
            if (figure != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    figureLabel?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
                    Text(figure, style = AudeoType.figureSm, color = c.ink)
                    figureNote?.let { Text(it, style = AudeoType.bodySm, color = c.ink) }
                }
            }
            if (reasons.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    reasons.take(3).forEach { r ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalArrangement = Arrangement.Center) {
                            r.source?.let { SourceTag(it, r.label) }
                            Text(r.text, style = AudeoType.bodySm, color = c.ink, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                }
            }
            body?.invoke(this)
            footnote?.let {
                Column {
                    HorizontalDivider(thickness = 2.dp, color = c.line)
                    Text(it, style = AudeoType.label, color = c.inkMuted, modifier = Modifier.padding(top = Space.s1))
                }
            }
        }
        if (hasTree) {
            DecisionTree(
                question = question,
                branches = branches,
                waiting = waiting,
                hint = voiceHint,
                modifier = Modifier.width(TREE_W).verticalScroll(rememberScrollState()),
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
                .padding(horizontal = Space.s3, vertical = Space.s1 + 4.dp),
        ) {
            Text(t, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold, lineBreak = KoreanWords), color = c.ink)
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
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (voice.mode) {
            VoiceMode.LISTENING -> ListeningDots(c.brandInk)
            VoiceMode.THINKING -> ListeningDots(c.inkMuted)
            else -> {}
        }
        who?.let { Text(it, style = AudeoType.label, color = if (voice.mode == VoiceMode.LISTENING) c.brandInk else c.inkMuted) }
        // 자막은 남는 폭만 써요(weight). 그래야 뒤의 안내('말씀하시면 바로 멈춰요')가 한 글자 폭으로 눌려 세로로 서지 않아요.
        AnimatedContent(
            targetState = voice.caption,
            transitionSpec = { fadeIn(tween(Motion.ENTER_MS, easing = Motion.EaseOut)) togetherWith fadeOut(tween(Motion.EXIT_MS, easing = Motion.EaseIn)) },
            label = "voiceLine",
            modifier = Modifier.weight(1f, fill = false),
        ) { cap -> cap?.let { Text(highlighted(it, voice.highlights, c.cautionSoft, c.caution), style = AudeoType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 2) } }
        voice.hint?.let { Text(it, style = AudeoType.label, color = c.inkMuted, maxLines = 1) }
    }
}

/** 듣는 중 점 세 개. 깜빡임 값은 그릴 때만 읽어서 화면을 다시 구성하지 않아요(접근성 트리가 계속 바뀌지 않게). */
@Composable
private fun ListeningDots(color: Color) {
    val t = rememberInfiniteTransition(label = "dots")
    val dots = List(3) { i -> t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse, StartOffset(i * 160)), label = "d$i") }
    Canvas(Modifier.size(width = 40.dp, height = 8.dp)) {
        val r = size.height / 2
        dots.forEachIndexed { i, a -> drawCircle(color.copy(alpha = a.value), radius = r, center = Offset(r + i * 12.dp.toPx(), r)) }
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
private fun Spinner(size: Dp) {
    val c = Audeo.colors
    val t = rememberInfiniteTransition(label = "spin")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sweep")
    Canvas(Modifier.size(size)) {
        val w = 3.dp.toPx()
        drawArc(c.brand, sweep, 100f, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w))
    }
}

private val TRUNK_W = 44.dp
private val TRUNK_X = 12.dp

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
            Text(question ?: "어떻게 할까요?", style = AudeoType.label, color = c.inkMuted, modifier = Modifier.padding(vertical = 4.dp))
        }
        branches.forEachIndexed { i, b ->
            TreeRow(index = i, last = i == count - 1, highlight = b.variant == ButtonVariant.PRIMARY) { BranchCard(b) }
        }
        waiting?.let { TreeRow(index = branches.size, last = true, highlight = true) { WaitingCard(it) } }
        hint?.let { Text(it, style = AudeoType.label, color = c.inkMuted, modifier = Modifier.padding(start = TRUNK_W, top = 4.dp)) }
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
            val r = 12.dp.toPx()
            val g = grow.value
            if (index < 0) {
                // 뿌리: 동그란 점에서 줄기가 아래로
                if (!last) drawLine(lineColor, Offset(x, cy), Offset(x, size.height), strokeWidth = w)
                drawCircle(c.brand, radius = 8.dp.toPx(), center = Offset(x, cy))
                drawCircle(c.surface, radius = 3.5.dp.toPx(), center = Offset(x, cy))
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
            if (!last) drawCircle(branchColor, radius = 4.5.dp.toPx(), center = Offset(x, cy))
        }
        Box(
            Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
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
            .heightIn(min = Dimens.touchMin)
            .alpha(if (b.enabled) 1f else 0.45f)
            .pressScale(interaction)
            .clip(shape)
            .background(bg)
            .then(if (b.variant == ButtonVariant.QUIET) Modifier.border(2.dp, c.line, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, enabled = b.enabled, role = Role.Button, onClick = b.onClick)
            .padding(horizontal = Space.s2, vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        b.icon?.let { Icon(it.vector, contentDescription = null, tint = fg, modifier = Modifier.size(32.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.label, style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            b.outcome?.let { Text(it, style = AudeoType.label, color = sub, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Icon(AudeoIcon.Chevron.vector, contentDescription = null, tint = sub, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun WaitingCard(w: Waiting) {
    val c = Audeo.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchMin)
            .clip(RoundedCornerShape(Radius.md))
            .border(2.dp, c.brand.copy(alpha = 0.5f), RoundedCornerShape(Radius.md))
            .padding(horizontal = Space.s2, vertical = Space.s1),
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Spinner(48.dp)
            Text(w.who.take(1), style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("${w.who}이 고르고 있어요", style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            w.detail?.let { Text(it, style = AudeoType.label, color = c.inkMuted) }
        }
    }
}
