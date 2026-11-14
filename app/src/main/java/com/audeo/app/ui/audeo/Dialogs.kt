package com.audeo.app.ui.audeo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.audeo.app.audeo.ChangeRow
import com.audeo.app.audeo.Contact
import com.audeo.app.audeo.EtaLane
import com.audeo.app.audeo.LaneTone
import com.audeo.app.audeo.PlanField
import com.audeo.app.audeo.PlanStatus
import com.audeo.app.audeo.Reason
import com.audeo.app.audeo.Tone
import com.audeo.app.audeo.VoiceUi
import com.audeo.app.ui.theme.Audeo
import com.audeo.app.ui.theme.AudeoType
import com.audeo.app.ui.theme.Dimens
import com.audeo.app.ui.theme.Radius
import com.audeo.app.ui.theme.Space

/**
 * 지도 위 다이얼로그의 공통 틀. 구역 순서는 디자인 시스템과 같습니다:
 * 머리(kicker·제목·상태) → 핵심 숫자 → 근거 → 본문 → 음성 칸 → 버튼 → 음성 답 안내 → 각주.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudeoDialog(
    modifier: Modifier = Modifier,
    tone: Tone = Tone.NEUTRAL,
    kicker: String? = null,
    title: String? = null,
    subtitle: String? = null,
    status: PlanStatus? = null,
    figureLabel: String? = null,
    figure: String? = null,
    figureNote: String? = null,
    reasons: List<Reason> = emptyList(),
    voice: VoiceUi? = null,
    voiceHint: String? = null,
    footnote: String? = null,
    actions: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Audeo.colors
    Column(
        modifier
            .width(Dimens.dialogW)
            .floatingSurface()
            .padding(Space.s5),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        if (kicker != null || title != null || status != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    kicker?.let {
                        val kc = when (tone) {
                            Tone.BRAND -> c.brandInk
                            Tone.CAUTION -> c.caution
                            Tone.NEUTRAL -> c.inkMuted
                        }
                        Text(it, style = AudeoType.label, color = kc)
                    }
                    title?.let { Text(it, style = AudeoType.title, color = c.ink) }
                    subtitle?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
                }
                status?.let { StatusPill(it) }
            }
        }
        if (figure != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                figureLabel?.let { Text(it, style = AudeoType.bodyLg, color = c.inkMuted) }
                Text(figure, style = AudeoType.eta, color = c.ink)
                figureNote?.let { Text(it, style = AudeoType.bodyLg, color = c.ink) }
            }
        }
        if (reasons.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                reasons.forEach { r ->
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Space.s2),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        r.source?.let { SourceTag(it, r.label) }
                        Text(r.text, style = AudeoType.body, color = c.ink, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
            }
        }
        body?.invoke(this)
        voice?.let { VoiceStrip(it) }
        if (actions != null) {
            FlowRow(
                modifier = Modifier.padding(top = Space.s1),
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) { actions() }
        }
        voiceHint?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.offset(y = -Space.s2)) }
        footnote?.let {
            Column {
                HorizontalDivider(thickness = 2.dp, color = c.line)
                Text(it, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.padding(top = Space.s3))
            }
        }
    }
}

/** 약속 필드: 라벨 · 값 · 출처. 방금 바뀐 값은 파랑 점과 brand-ink. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanFields(fields: List<PlanField>) {
    val c = Audeo.colors
    Column {
        fields.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider(thickness = 2.dp, color = c.line)
            Row(
                Modifier.padding(top = if (i == 0) 0.dp else Space.s3, bottom = if (i == fields.lastIndex) 0.dp else Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(f.label, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.width(180.dp))
                val empty = f.value.isNullOrEmpty() && f.code == null
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Space.s2),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (f.updated) {
                        Box(Modifier.align(Alignment.CenterVertically).size(14.dp).clip(CircleShape).background(c.brandInk))
                    }
                    f.code?.let { Text(it, style = AudeoType.code, color = c.ink, modifier = Modifier.align(Alignment.CenterVertically)) }
                    if (empty || f.value != null) {
                        val color = when {
                            empty -> c.inkMuted
                            f.updated -> c.brandInk
                            else -> c.ink
                        }
                        val style = if (f.updated) AudeoType.body.copy(fontWeight = FontWeight.SemiBold) else AudeoType.body
                        Text(if (empty) f.placeholder else f.value!!, style = style, color = color, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    f.note?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.align(Alignment.CenterVertically)) }
                }
                f.source?.let { SourceTag(it) }
            }
        }
    }
}

/** 변경 전 → 변경 후. 기존 값은 취소선과 ink-muted, 새 값은 굵게. */
@Composable
fun ChangeRows(rows: List<ChangeRow>) {
    val c = Audeo.colors
    Column {
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(thickness = 2.dp, color = c.line)
            Row(
                Modifier.padding(top = if (i == 0) 0.dp else Space.s3, bottom = if (i == rows.lastIndex) 0.dp else Space.s3),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.label, style = AudeoType.bodySm, color = c.inkMuted, modifier = Modifier.width(160.dp))
                Text(
                    r.from,
                    style = AudeoType.body.copy(textDecoration = TextDecoration.LineThrough),
                    color = c.inkMuted,
                    modifier = Modifier.weight(1f),
                )
                Icon(AudeoIcon.ArrowRight.vector, contentDescription = null, tint = c.inkMuted, modifier = Modifier.size(32.dp))
                Text(r.to, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1.2f))
            }
        }
    }
}

private fun minutesOf(t: String): Int? {
    val parts = t.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    return h * 60 + m
}

/** 차량 도착과 탑승자 준비를 한 시간축에 놓고 비교합니다. */
@Composable
fun EtaCompareBody(start: String, end: String, now: String?, lanes: List<EtaLane>, ticks: List<String>, verdict: String?) {
    val c = Audeo.colors
    val s = minutesOf(start) ?: 0
    val e = minutesOf(end) ?: (s + 30)
    val span = (e - s).coerceAtLeast(1)
    fun pct(t: String?): Float = (((t?.let(::minutesOf) ?: s) - s).toFloat() / span).coerceIn(0f, 1f)
    val nowPct = pct(now)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            lanes.forEach { lane ->
                val tone = when (lane.tone) {
                    LaneTone.VEHICLE -> c.brandInk
                    LaneTone.PASSENGER -> c.ink
                    LaneTone.LATE -> c.caution
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s4), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.width(220.dp).padding(bottom = 4.dp)) {
                        Text(
                            lane.who,
                            style = AudeoType.body.copy(fontWeight = FontWeight.SemiBold),
                            color = if (lane.tone == LaneTone.VEHICLE) c.brandInk else c.ink,
                        )
                        lane.note?.let { Text(it, style = AudeoType.bodySm, color = c.inkMuted) }
                    }
                    BoxWithConstraints(Modifier.weight(1f).height(104.dp).padding(end = Space.s4)) {
                        val w = maxWidth
                        val p = pct(lane.at)
                        // 레일
                        Box(Modifier.align(Alignment.BottomStart).padding(bottom = 28.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(c.line))
                        // 지금부터 도착까지
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(bottom = 28.dp)
                                .offset(x = w * nowPct)
                                .width(w * (p - nowPct).coerceAtLeast(0f))
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(tone),
                        )
                        if (now != null) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(bottom = 12.dp)
                                    .offset(x = w * nowPct - 2.dp)
                                    .width(4.dp)
                                    .height(40.dp)
                                    .clip(CircleShape)
                                    .background(c.inkMuted),
                            )
                        }
                        // 도착 표시점과 시각
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(bottom = 20.dp)
                                .offset(x = w * p - 12.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(tone)
                                .padding(6.dp)
                                .clip(CircleShape)
                                .background(c.surface),
                        )
                        Text(
                            lane.at,
                            style = AudeoType.figureSm,
                            color = if (lane.tone == LaneTone.PASSENGER) c.ink else tone,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = w * p - 60.dp)
                                .width(120.dp),
                        )
                    }
                }
            }
        }
        if (ticks.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s4)) {
                Spacer(Modifier.width(220.dp))
                BoxWithConstraints(Modifier.weight(1f).height(34.dp).padding(end = Space.s4)) {
                    val w = maxWidth
                    ticks.forEachIndexed { i, t ->
                        val x = w * pct(t)
                        val align = when (i) {
                            0 -> TextAlign.Start
                            ticks.lastIndex -> TextAlign.End
                            else -> TextAlign.Center
                        }
                        val left = when (i) {
                            0 -> x
                            ticks.lastIndex -> x - 120.dp
                            else -> x - 60.dp
                        }
                        Text(t, style = AudeoType.bodySm, color = c.inkMuted, textAlign = align, modifier = Modifier.offset(x = left).width(120.dp))
                    }
                }
            }
        }
        verdict?.let { Text(it, style = AudeoType.bodyLg, color = c.ink) }
    }
}

/** Audeo 통화를 걸 상대. 줄 전체가 버튼입니다. */
@Composable
fun ContactRows(contacts: List<Contact>, onCall: (Contact) -> Unit) {
    val c = Audeo.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        contacts.forEach { person ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.controlH)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(c.surface2)
                    .clickable(role = Role.Button) { onCall(person) }
                    .padding(start = Space.s3, end = Space.s2, top = Space.s2, bottom = Space.s2),
                horizontalArrangement = Arrangement.spacedBy(Space.s3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(Dimens.touchMin).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                    Text(person.name.take(1), style = AudeoType.title, color = c.ink)
                }
                Column(Modifier.weight(1f)) {
                    Text(person.name, style = AudeoType.bodyLg.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                    Text(person.note, style = AudeoType.bodySm, color = c.inkMuted)
                }
                Box(Modifier.size(Dimens.touchMin).clip(CircleShape).background(c.brand), contentAlignment = Alignment.Center) {
                    Icon(AudeoIcon.Phone.vector, contentDescription = "${person.name}에게 Audeo 통화 걸기", tint = c.onBrand, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}
