package com.audeo.app.ui.audeo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audeo.app.ui.theme.AudeoType
import com.audeo.app.ui.theme.Space

/** 통화에 들어온 한 사람. key는 말소리 표시(talking)와 맞춥니다: 나는 "me", 상대는 "peer"(여럿이면 "peer:<id>"). */
data class CallPerson(val key: String, val name: String, val me: Boolean = false)

private val PanelBg = Color(0xF21B1D22)
private val PanelInk = Color(0xFFFFFFFF)
private val PanelMuted = Color(0xB3FFFFFF)
private val CtlBg = Color(0x29FFFFFF)
private val EndRed = Color(0xFFFF3B30)
private val TalkGreen = Color(0xFF4ADE80)

/** 이름마다 늘 같은 색이 나오도록 고른 프로필 배경. */
private val AVATAR_COLORS = listOf(
    Color(0xFF5B7BFF) to Color(0xFF2347F5),
    Color(0xFFFF9F6B) to Color(0xFFF2643A),
    Color(0xFF4FD1C5) to Color(0xFF1E9E93),
    Color(0xFFB794F6) to Color(0xFF7C4DDB),
    Color(0xFFF6C453) to Color(0xFFD9961A),
)

/**
 * 보이스톡처럼 어두운 통화 카드. 참가자 얼굴을 나란히 두고, 말하는 사람은 초록 고리가 퍼집니다.
 * 참가자는 몇 명이든 받을 수 있고 4명이 넘으면 "+N"으로 묶습니다.
 */
@Composable
fun CallPanel(
    people: List<CallPerson>,
    talking: Set<String>,
    title: String,
    time: String,
    detail: String,
    aiOn: Boolean,
    ringing: Boolean,
    muted: Boolean,
    onMute: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(36.dp)
    Row(
        modifier
            .shadow(24.dp, shape, clip = false)
            .clip(shape)
            .background(PanelBg)
            .padding(horizontal = Space.s4, vertical = Space.s3),
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.Top) {
            val shown = people.take(4)
            shown.forEach { p ->
                Avatar(p, talking = p.key in talking && !(p.me && muted), ringing = ringing && !p.me, size = 96.dp)
            }
            if (people.size > shown.size) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(CtlBg), contentAlignment = Alignment.Center) {
                    Text("+${people.size - shown.size}", color = PanelInk, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = PanelInk, fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(time, color = PanelMuted, style = AudeoType.figureSm.copy(fontSize = 30.sp, lineHeight = 38.sp), maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
                AiDot(aiOn)
                Text(detail, color = if (aiOn) Color(0xFF9DB4FF) else PanelMuted, fontSize = 22.sp, lineHeight = 28.sp, maxLines = 1)
            }
        }
        RoundControl(
            icon = if (muted) AudeoIcon.MicOff else AudeoIcon.Mic,
            label = if (muted) "마이크 꺼짐" else "마이크",
            active = muted,
            onClick = onMute,
        )
        RoundControl(icon = AudeoIcon.PhoneEnd, label = if (ringing) "끊기" else "종료", danger = true, onClick = onEnd)
    }
}

@Composable
private fun Avatar(p: CallPerson, talking: Boolean, ringing: Boolean, size: Dp) {
    val (top, bottom) = AVATAR_COLORS[(p.name.hashCode() and 0x7fffffff) % AVATAR_COLORS.size]
    val t = rememberInfiniteTransition(label = "avatar")
    val wave by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "wave")
    val ring by animateFloatAsState(if (talking) 1f else 0f, tween(180), label = "ring")
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(size + 16.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size + 16.dp)) {
                val r0 = (size / 2).toPx()
                if (ringing) {
                    // 거는 중·받는 중: 상대 얼굴에서 물결이 퍼집니다.
                    for (k in 0..1) {
                        val f = (wave + k * 0.5f) % 1f
                        drawCircle(Color.White.copy(alpha = (1f - f) * 0.35f), radius = r0 + f * 8.dp.toPx(), style = Stroke(3.dp.toPx()))
                    }
                }
                if (ring > 0f) drawCircle(TalkGreen.copy(alpha = ring), radius = r0 + 4.dp.toPx(), style = Stroke(5.dp.toPx()))
            }
            Box(
                Modifier.size(size).clip(CircleShape).background(Brush.verticalGradient(listOf(top, bottom))),
                contentAlignment = Alignment.Center,
            ) {
                Text(p.name.take(1), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            if (p.me) "나" else p.name,
            color = PanelMuted,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(size + 16.dp),
        )
    }
}

@Composable
private fun AiDot(on: Boolean) {
    val alpha = if (on) {
        val t = rememberInfiniteTransition(label = "ai")
        val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
        a
    } else {
        1f
    }
    Box(Modifier.size(12.dp).clip(CircleShape).background((if (on) Color(0xFF9DB4FF) else PanelMuted).copy(alpha = alpha)))
}

/** 보이스톡 아래 줄처럼 둥근 버튼과 그 아래 이름표. */
@Composable
private fun RoundControl(icon: AudeoIcon, label: String, onClick: () -> Unit, active: Boolean = false, danger: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val bg = when {
        danger -> EndRed
        active -> Color.White
        else -> CtlBg
    }
    val fg = if (active && !danger) Color(0xFF1B1D22) else Color.White
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(88.dp)
                .pressScale(interaction)
                .clip(CircleShape)
                .background(bg)
                .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon.vector, contentDescription = null, tint = fg, modifier = Modifier.size(40.dp))
        }
        Text(label, color = PanelMuted, fontSize = 20.sp, lineHeight = 24.sp, maxLines = 1)
    }
}
