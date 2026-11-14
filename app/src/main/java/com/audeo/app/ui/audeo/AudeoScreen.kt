package com.audeo.app.ui.audeo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audeo.app.audeo.AudeoUiState
import com.audeo.app.audeo.AudeoViewModel
import com.audeo.app.audeo.CallStatus
import com.audeo.app.audeo.LaneTone
import com.audeo.app.audeo.PlanField
import com.audeo.app.audeo.PlanStatus
import com.audeo.app.audeo.Proposal
import com.audeo.app.audeo.Reason
import com.audeo.app.audeo.ResultState
import com.audeo.app.audeo.Scene
import com.audeo.app.audeo.ServerState
import com.audeo.app.audeo.ServerStatus
import com.audeo.app.audeo.SourceKind
import com.audeo.app.audeo.Tone
import com.audeo.app.audeo.VoiceMode
import com.audeo.app.audeo.VoiceUi
import com.audeo.app.ui.PleosStatusScreen
import com.audeo.app.ui.theme.Audeo
import com.audeo.app.ui.theme.AudeoTheme
import com.audeo.app.ui.theme.AudeoType
import com.audeo.app.ui.theme.Dimens
import com.audeo.app.ui.theme.Space
import kotlin.math.ceil

private val IDLE_MAP = setOf(Scene.START, Scene.DIAL, Scene.INCOMING, Scene.CALL, Scene.ASK)

/** Audeo 차량 앱 화면: 앱 영역을 지도로 채우고, 그 위에 다이얼로그 하나를 올립니다. */
@Composable
fun AudeoApp(vm: AudeoViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    AudeoTheme(night = s.nightOverride ?: isSystemInDarkTheme()) {
        if (s.showStatus) {
            Box(Modifier.fillMaxSize().background(Audeo.colors.ground)) {
                PleosStatusScreen()
                AudeoButton(
                    "닫기",
                    vm::toggleStatus,
                    modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.s4),
                    size = ButtonSize.MD,
                )
            }
            return@AudeoTheme
        }
        Box(Modifier.fillMaxSize().background(Audeo.colors.mapLand)) {
            val idle = s.scene in IDLE_MAP
            val journey = s.journey ?: s.finished
            // 지도는 인천공항 2터미널 고정 그림이라, 만날 곳이 그 밖(예: 서울역)이면 그리지 않습니다.
            if (journey?.meetPlace?.let(::onMapImage) != false) RouteMap(
                route = if (idle) MapRoute.idleRoute else MapRoute.route,
                progress = s.progress,
                idle = idle,
                pickupLabel = if (idle) null else journey?.meetPlace?.name?.let(::shortPlace) ?: "만날 곳",
                etaLabel = etaChip(s),
                passenger = if (s.scene == Scene.DRIVE || s.scene == Scene.ARRIVE) {
                    MapRoute.passenger to (journey?.passengerCallName ?: "탑승자")
                } else {
                    null
                },
            )
            Column(
                Modifier
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(Space.s4),
                verticalArrangement = Arrangement.spacedBy(Space.s3),
            ) {
                s.call?.takeIf { it.status != CallStatus.ENDED }?.let { call ->
                    CallBar(
                        name = call.peerName,
                        role = when {
                            call.status == CallStatus.ACTIVE && s.callAudioState == "failed" -> "Audeo 통화 · 음성 연결 실패"
                            call.status == CallStatus.ACTIVE && s.callAudioState != "connected" -> "Audeo 통화 · 음성 연결 중"
                            call.analysisOn -> "Audeo 통화 · 분석 동의함"
                            call.status == CallStatus.RINGING && !call.outgoing -> "Audeo 전화 수신"
                            call.status == CallStatus.RINGING && call.analysisRequested -> "Audeo 통화 · 동의 요청 중"
                            else -> "Audeo 통화"
                        },
                        duration = if (call.status == CallStatus.RINGING) "연결 중" else s.callDuration,
                        aiOn = call.analysisOn,
                        aiLabel = when {
                            call.analysisOn -> "AI가 대화를 듣고 있어요"
                            call.status == CallStatus.RINGING && !call.outgoing ->
                                if (call.peerConsent == "granted") "받으면 AI 분석을 시작할 수 있어요" else "AI 분석 꺼짐"
                            call.status == CallStatus.RINGING && call.analysisRequested -> "${call.peerCallName} 동의 기다리는 중"
                            else -> "AI 분석 꺼짐"
                        },
                        muted = s.muted,
                        onMute = vm::toggleMute,
                        onEnd = vm::endCall,
                    )
                }
                s.result?.let { result ->
                    ResultBanner(result, Modifier.width(Dimens.dialogW)) {
                        if (result.state != ResultState.PENDING) {
                            AudeoButton(null, vm::dismissResult, size = ButtonSize.MD, variant = ButtonVariant.QUIET, icon = AudeoIcon.Close, description = "닫기")
                        }
                    }
                }
                SceneDialog(s, vm)
            }
            DevPanel(s, vm, Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.s3))
        }
    }
}

@Composable
private fun SceneDialog(s: AudeoUiState, vm: AudeoViewModel) {
    val c = Audeo.colors
    val call = s.call
    val peer = call?.peerCallName ?: s.journey?.passengerCallName ?: "상대방"
    when (s.scene) {
        Scene.START -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = "Audeo 통화",
            title = "누구에게 전화할까요?",
            footnote = if (s.server.status == ServerStatus.OFFLINE) {
                "Audeo 서버에 연결되지 않았어요. 연결되면 연락처와 통화를 다시 불러와요."
            } else {
                "상대가 AI 분석에 동의하면 통화 내용으로 픽업 약속을 정리해요."
            },
        ) {
            when {
                s.contacts.isNotEmpty() -> ContactRows(s.contacts, vm::callContact)
                s.contactsLoading -> Text("연락처를 불러오는 중이에요", style = AudeoType.body, color = c.inkMuted)
                else -> Text("Audeo를 쓰는 연락처가 아직 없어요", style = AudeoType.body, color = c.inkMuted)
            }
        }

        Scene.DIAL -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = if (call?.analysisRequested == true) "AI 분석 동의" else "Audeo 통화",
            title = if (call?.analysisRequested == true) "${peer}께 동의를 요청했어요" else "${peer}께 전화하고 있어요",
            voice = s.voice,
            footnote = "${peer}이 동의하면 바로 대화를 듣기 시작해요. 거절하면 일반 통화로 이어져요.",
            actions = if (call?.analysisRequested == true) {
                { AudeoButton("AI 없이 통화", vm::callWithoutAi) }
            } else {
                null
            },
        ) {
            PlanFields(
                listOf(
                    PlanField("나", value = if (call?.analysisRequested == true) "동의함" else "AI 없이", updated = true),
                    consentField(peer, call?.peerConsent),
                ),
            )
        }

        Scene.INCOMING -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = "Audeo 전화 수신",
            title = "$peer 전화예요",
            reasons = if (call?.peerConsent == "granted") listOf(Reason("${peer}은 AI 분석에 이미 동의했어요")) else emptyList(),
            voice = s.voice,
            voiceHint = "“받아”, “그냥 받아”, “거절해”라고 말해도 돼요",
            footnote = "AI 분석은 통화가 끝나면 멈춰요.",
            actions = {
                AudeoButton("동의하고 받기", { vm.acceptIncoming(true) }, variant = ButtonVariant.PRIMARY, icon = AudeoIcon.Phone)
                AudeoButton("그냥 받기", { vm.acceptIncoming(false) })
                AudeoButton(null, vm::declineIncoming, variant = ButtonVariant.DANGER, icon = AudeoIcon.PhoneEnd, description = "거절")
            },
        )

        Scene.CALL -> when {
            call == null -> AudeoDialog(
                tone = Tone.BRAND,
                kicker = "통화 종료",
                title = "통화 내용을 정리하고 있어요",
                subtitle = s.journey?.summary,
                status = PlanStatus.DRAFT,
                footnote = "정리가 끝나면 확인할 것과 계획을 보여 드려요.",
                actions = { AudeoButton("처음으로", vm::restart, variant = ButtonVariant.QUIET) },
            ) { s.journey?.fields?.takeIf { it.isNotEmpty() }?.let { PlanFields(it) } }

            call.analysisOn -> AudeoDialog(
                tone = Tone.BRAND,
                kicker = "통화 중 정리",
                title = s.journey?.title?.let { "$it 약속" } ?: "$peer 약속",
                status = PlanStatus.DRAFT,
                voice = s.voice,
                footnote = "확인되지 않은 내용은 확정하지 않아요.",
            ) {
                val fields = s.journey?.fields.orEmpty()
                if (fields.isEmpty()) {
                    Text("대화에서 약속을 찾는 중이에요", style = AudeoType.body, color = c.inkMuted)
                } else {
                    PlanFields(fields)
                }
            }

            else -> AudeoDialog(
                kicker = "일반 통화",
                title = "AI 분석 없이 통화 중이에요",
                voice = s.voice,
                footnote = "약속 정리가 필요하면 다음 통화에서 AI와 함께 받아 주세요.",
            )
        }

        Scene.ASK, Scene.PLAN, Scene.CHANGE, Scene.VEHICLE -> s.proposal?.let { ProposalDialog(it, s, vm) }

        Scene.DRIVE -> {
            val j = s.journey
            val co = s.coordination
            AudeoDialog(
                tone = Tone.BRAND,
                kicker = listOfNotNull(j?.meetPlace?.name, j?.meetAtLabel?.let { "$it 만남" }).joinToString(" · ").ifEmpty { null },
                title = j?.title ?: "픽업",
                status = PlanStatus.ACTIVE,
                voice = s.voice ?: VoiceUi(VoiceMode.IDLE, who = "마이크는 듣지 않아요", caption = "중요한 변화만 알려 드려요"),
                footnote = co?.nextAction,
            ) {
                if (co != null && co.lanes.isNotEmpty()) {
                    EtaCompareBody(co.start, co.end, co.now, co.lanes, co.ticks, co.verdict)
                } else {
                    Text("도착 시각을 계산하는 중이에요", style = AudeoType.body, color = c.inkMuted)
                }
            }
        }

        Scene.ARRIVE -> {
            val a = s.coordination?.arrival
            val passengerLane = s.coordination?.lanes?.firstOrNull { it.tone != LaneTone.VEHICLE }
            AudeoDialog(
                tone = Tone.BRAND,
                kicker = a?.kicker ?: "곧 도착",
                title = a?.title ?: "곧 도착해요",
                figureLabel = a?.figureLabel,
                figure = a?.figure,
                reasons = passengerLane?.let { listOf(Reason("${it.who} · ${it.note ?: it.at}", SourceKind.LOOKUP, "위치 공유")) }.orEmpty(),
                voice = s.voice,
                voiceHint = "“차 탔어”라고 말해도 돼요",
                footnote = "가까워진 신호나 정차만으로는 탑승을 확정하지 않아요.",
                actions = {
                    AudeoButton("탔어요 · 픽업 완료", vm::boarded, variant = ButtonVariant.PRIMARY, icon = AudeoIcon.Check)
                    AudeoButton("아직이에요", vm::notYet)
                },
            )
        }

        Scene.DONE -> {
            val name = s.finished?.passengerCallName
            AudeoDialog(
                tone = Tone.BRAND,
                kicker = "픽업 완료",
                title = if (name != null) "${name}이 탔어요" else "픽업을 마쳤어요",
                subtitle = "위치 공유와 조율 알림을 끝냈어요",
                actions = { AudeoButton("처음으로", vm::restart, variant = ButtonVariant.PRIMARY) },
            )
        }
    }
}

/** 서버 제안(질문·계획·변경·차량 준비)을 하나의 다이얼로그로 그립니다. 선택지는 서버가 정합니다. */
@Composable
private fun ProposalDialog(p: Proposal, s: AudeoUiState, vm: AudeoViewModel) {
    val c = Audeo.colors
    val actions: @Composable () -> Unit = {
        p.options.forEach { o ->
            AudeoButton(
                o.label,
                { vm.choose(o) },
                variant = when (o.variant) {
                    "primary" -> ButtonVariant.PRIMARY
                    "quiet" -> ButtonVariant.QUIET
                    "danger" -> ButtonVariant.DANGER
                    else -> ButtonVariant.SECONDARY
                },
                icon = when {
                    o.id == "apply_navi" -> AudeoIcon.Navigate
                    o.variant == "primary" && p.kind == "change" -> AudeoIcon.Check
                    else -> null
                },
                enabled = !s.busy,
            )
        }
    }
    val primary = p.options.firstOrNull { it.variant == "primary" }?.label
    when (p.kind) {
        "ask" -> AudeoDialog(
            tone = Tone.CAUTION,
            kicker = "확인이 필요해요",
            title = p.question ?: p.title,
            voice = s.voice,
            voiceHint = primary?.let { "“$it”라고 말해도 돼요" },
            footnote = p.footnote,
            actions = actions,
        ) { p.context?.let { Text(it, style = AudeoType.body, color = c.inkMuted) } }

        "plan" -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = p.context,
            title = p.title,
            status = PlanStatus.DRAFT,
            figureLabel = p.figure?.label,
            figure = p.figure?.value,
            figureNote = p.figure?.note,
            reasons = p.reasons,
            voice = s.voice,
            footnote = p.footnote,
            actions = actions,
        ) { s.journey?.fields?.let(::planKeyFields)?.takeIf { it.isNotEmpty() }?.let { PlanFields(it) } }

        "change" -> AudeoDialog(
            tone = Tone.CAUTION,
            kicker = p.context ?: "계획 변경 제안",
            title = p.title,
            reasons = p.reasons,
            voice = s.voice,
            voiceHint = "“그렇게 해 줘”라고 말해도 돼요",
            footnote = p.footnote,
            actions = actions,
        ) { if (p.rows.isNotEmpty()) ChangeRows(p.rows) }

        "vehicle_action" -> AudeoDialog(
            tone = Tone.BRAND,
            kicker = p.context ?: "날씨에 맞춘 차량 준비",
            title = p.title,
            reasons = p.reasons,
            voice = s.voice,
            voiceHint = "“그렇게 해 줘”라고 말해도 돼요",
            footnote = p.footnote,
            actions = actions,
        ) { if (p.rows.isNotEmpty()) ChangeRows(p.rows) }

        else -> AudeoDialog(
            tone = Tone.NEUTRAL,
            kicker = p.context,
            title = p.title,
            reasons = p.reasons,
            voice = s.voice,
            footnote = p.footnote,
            actions = actions,
        ) { if (p.rows.isNotEmpty()) ChangeRows(p.rows) }
    }
}

/** 계획 카드는 버튼이 화면 안에 보이도록 출발 판단에 필요한 항목만 보여 줍니다. */
private val PLAN_KEYS = setOf("transport", "place", "exit", "meet_time")

private fun planKeyFields(fields: List<PlanField>): List<PlanField> =
    fields.filter { it.key == null || it.key in PLAN_KEYS }.ifEmpty { fields }

private fun consentField(peer: String, consent: String?) = when (consent) {
    "granted" -> PlanField(peer, value = "동의함", updated = true)
    "declined", "denied" -> PlanField(peer, value = "동의 안 함 · 일반 통화")
    else -> PlanField(peer, placeholder = "응답 기다리는 중", source = SourceKind.CONFIRM)
}

/** "인천공항 2터미널 3번 출구" → "3번 출구"처럼 지도 칩에 맞게 줄입니다. */
/** 지도 그림이 담은 곳(인천공항 2터미널 주변 약 5km) 안인지. */
private fun onMapImage(p: com.audeo.app.audeo.Place): Boolean {
    val dLat = (p.latitude - 37.4602) * 111.0
    val dLng = (p.longitude - 126.4407) * 88.0
    return dLat * dLat + dLng * dLng < 25.0
}

private fun shortPlace(name: String): String = name.split(" ").takeLast(2).joinToString(" ").take(12)

private fun etaChip(s: AudeoUiState): String? {
    if (s.scene !in setOf(Scene.DRIVE, Scene.CHANGE, Scene.VEHICLE, Scene.ARRIVE)) return null
    s.coordination?.etaLabel?.let { return it }
    return s.navi.remainSeconds?.let { "${ceil(it / 60.0).toInt()}분" }
}

/**
 * 개발 도구(낮/밤, SDK 상태, 서버 확인, 받아쓰기 시험, 최근 기록). 화면에 버튼은 두지 않고,
 * 오른쪽 아래 모서리를 길게 누르면 열려요.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevPanel(s: AudeoUiState, vm: AudeoViewModel, modifier: Modifier = Modifier) {
    val c = Audeo.colors
    var open by remember { mutableStateOf(false) }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        if (open) {
            Column(
                Modifier.width(620.dp).floatingSurface().padding(Space.s3),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                Text("개발 도구", style = AudeoType.label, color = c.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    val mode = when (s.nightOverride) {
                        null -> "낮/밤: 차량 따름"
                        true -> "낮/밤: 밤"
                        false -> "낮/밤: 낮"
                    }
                    DevChip(mode, vm::toggleNight)
                    DevChip("SDK 상태", vm::toggleStatus)
                    DevChip("서버 다시 확인", vm::checkServer)
                    DevChip("받아쓰기 시험", vm::testTranscription)
                    DevChip("닫기") { open = false }
                }
                Text(
                    serverLine(s.server),
                    style = AudeoType.bodySm,
                    color = if (s.server.status == ServerStatus.OFFLINE) c.danger else c.inkMuted,
                )
                Text(
                    "내비 ${if (s.navi.connected) "연결됨" else "연결 안 됨"} · 위치 ${s.location ?: "-"}" +
                        (s.callAudioState?.let { " · 통화 음성 $it" } ?: ""),
                    style = AudeoType.bodySm,
                    color = c.inkMuted,
                )
                s.log.take(5).forEach { Text(it, style = AudeoType.label, color = c.inkMuted, maxLines = 1) }
            }
        } else {
            // 보이지 않는 모서리 영역: 길게 누르면 개발 도구를 엽니다.
            Box(Modifier.size(Dimens.touchMin).pointerInput(Unit) { detectTapGestures(onLongPress = { open = true }) })
        }
    }
}

private fun serverLine(server: ServerState): String = when (server.status) {
    ServerStatus.CHECKING -> "서버 확인 중"
    ServerStatus.ONLINE -> "서버 연결됨 · ${server.registeredAs ?: "-"} · ${server.latencyMs ?: "-"}ms · ${server.checkedAt}"
    ServerStatus.OFFLINE -> "서버 연결 안 됨 · ${server.message ?: "-"}"
}

@Composable
private fun DevChip(text: String, onClick: () -> Unit) {
    val c = Audeo.colors
    Box(
        Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(Dimens.touchMin / 2))
            .background(c.surface2)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AudeoType.label, color = c.ink)
    }
}
