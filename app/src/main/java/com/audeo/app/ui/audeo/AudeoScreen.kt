package com.audeo.app.ui.audeo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.audeo.app.BuildConfig
import com.audeo.app.audeo.AudeoUiState
import com.audeo.app.audeo.AudeoViewModel
import com.audeo.app.audeo.CallInfo
import com.audeo.app.audeo.CallStatus
import com.audeo.app.audeo.LaneTone
import com.audeo.app.audeo.PlanField
import com.audeo.app.audeo.PlanStatus
import com.audeo.app.audeo.Proposal
import com.audeo.app.audeo.ProposalOption
import com.audeo.app.audeo.Reason
import com.audeo.app.audeo.ResultState
import com.audeo.app.audeo.Scene
import com.audeo.app.audeo.ServerState
import com.audeo.app.audeo.ServerStatus
import com.audeo.app.audeo.SourceKind
import com.audeo.app.audeo.Tone
import com.audeo.app.audeo.VoiceMode
import com.audeo.app.ui.PleosStatusScreen
import com.audeo.app.ui.theme.Audeo
import com.audeo.app.ui.theme.AudeoTheme
import com.audeo.app.ui.theme.AudeoType
import com.audeo.app.ui.theme.Dimens
import com.audeo.app.ui.theme.Space
import kotlin.math.ceil

/** 통화 카드 높이(대략). 시트가 이만큼은 비켜 줍니다. */
private val CALL_PANEL_H = 170.dp
private val RESULT_H = 128.dp

/**
 * Audeo 차량 앱 화면: 앱 영역을 지도로 채우고, 위에는 통화 카드, 아래에는 오디가 말하는 비서 시트를 둡니다.
 * 시트는 장면(또는 제안)이 바뀔 때만 갈아끼우고, 오디는 시트 위에 계속 앉아 있어요.
 */
@Composable
fun AudeoApp(vm: AudeoViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    val talking by vm.talking.collectAsStateWithLifecycle()
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
        BoxWithConstraints(Modifier.fillMaxSize().background(Audeo.colors.mapLand)) {
            val screenH = maxHeight
            var sheetPx by remember { mutableIntStateOf(0) }
            MapLayer(s, sheetPx)

            val liveCall = s.call?.takeIf { it.status != CallStatus.ENDED }
            // 위: 통화 카드와 실행 결과
            Column(
                Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.s3),
                verticalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                val shownCall = rememberLast(liveCall)
                AnimatedVisibility(visible = liveCall != null, enter = Motion.barEnter(), exit = Motion.barExit()) {
                    val call = shownCall ?: return@AnimatedVisibility
                    CallCard(call, s, talking, vm)
                }
                val shownThinking = rememberLast(s.thinking)
                AnimatedVisibility(visible = s.thinking != null, enter = Motion.barEnter(), exit = Motion.barExit()) {
                    val t = shownThinking ?: return@AnimatedVisibility
                    ThinkingPanel(t, Modifier.width(860.dp))
                }
                val shownResult = rememberLast(s.result)
                AnimatedVisibility(visible = s.result != null, enter = Motion.barEnter(), exit = Motion.barExit()) {
                    val result = shownResult ?: return@AnimatedVisibility
                    ResultBanner(result, Modifier.width(860.dp)) {
                        if (result.state != ResultState.PENDING) {
                            AudeoButton(null, vm::dismissResult, size = ButtonSize.MD, variant = ButtonVariant.QUIET, icon = AudeoIcon.Close, description = "닫기")
                        }
                    }
                }
            }

            // 아래: 오디 + 비서 시트
            val reserved = (if (liveCall != null) CALL_PANEL_H else 0.dp) + (if (s.result != null) RESULT_H else 0.dp)
            val sheetMax = (screenH - reserved - ODI_RISE - Space.s3 * 3).coerceAtLeast(360.dp)
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = Space.s3, end = Space.s3, bottom = Space.s3)
                    .onSizeChanged { sheetPx = it.height },
            ) {
                val sheetEnter = Motion.sheetEnter()
                AnimatedContent(
                    targetState = DialogFrame(dialogKey(s), s),
                    contentKey = { it.key },
                    contentAlignment = Alignment.BottomCenter,
                    transitionSpec = { sheetEnter togetherWith Motion.sheetExit() using SizeTransform(clip = false) },
                    label = "sheet",
                ) { frame -> SceneSheet(frame.state, vm, sheetMax) }
                Odi(
                    mode = odiMode(s),
                    size = ODI_SIZE,
                    modifier = Modifier.align(Alignment.TopStart).offset(x = Space.s3, y = -ODI_RISE),
                )
            }
            DevPanel(s, vm, Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(Space.s3))
        }
    }
}

/** 오디 표정: 말하는 중·듣는 중이면 그대로, 통화를 같이 듣는 중이면 듣는 얼굴, 서버 답을 기다리면 생각하는 얼굴. */
private fun odiMode(s: AudeoUiState): VoiceMode = when {
    s.voice != null -> s.voice!!.mode
    s.busy -> VoiceMode.THINKING
    s.call?.status == CallStatus.ACTIVE && s.call?.analysisOn == true -> VoiceMode.LISTENING
    else -> VoiceMode.IDLE
}

/** 보이스톡 같은 통화 카드에 넣을 내용. */
@Composable
private fun CallCard(call: CallInfo, s: AudeoUiState, talking: Set<String>, vm: AudeoViewModel) {
    val ringing = call.status == CallStatus.RINGING
    val me = BuildConfig.AUDEO_DRIVER_SHORT_NAME.ifBlank { BuildConfig.AUDEO_DRIVER_NAME }.ifBlank { "나" }
    CallPanel(
        people = listOf(CallPerson("me", me, me = true), CallPerson("peer", call.peerName)),
        talking = talking,
        title = call.peerName,
        time = when {
            ringing && call.outgoing -> "연결하는 중…"
            ringing -> "Audeo 통화가 왔어요"
            s.callAudioState == "failed" -> "${s.callDuration} · 음성 연결 실패"
            s.callAudioState != "connected" -> "${s.callDuration} · 음성 연결 중"
            else -> s.callDuration
        },
        detail = when {
            call.analysisOn -> "오디가 같이 듣고 있어요"
            ringing && !call.outgoing ->
                if (call.peerConsent == "granted") "받으면 오디가 같이 들을 수 있어요" else "오디는 듣지 않아요"
            ringing && call.analysisRequested -> "${call.peerCallName} 동의를 기다려요"
            else -> "오디는 듣지 않아요"
        },
        aiOn = call.analysisOn,
        ringing = ringing,
        muted = s.muted,
        onMute = vm::toggleMute,
        onEnd = if (ringing && !call.outgoing) vm::declineIncoming else vm::endCall,
    )
}

@Composable
private fun SceneSheet(s: AudeoUiState, vm: AudeoViewModel, maxHeight: Dp) {
    val c = Audeo.colors
    val call = s.call
    val peer = call?.peerCallName ?: s.journey?.passengerCallName ?: "상대방"
    val me = BuildConfig.AUDEO_DRIVER_SHORT_NAME.ifBlank { null }
    when (s.scene) {
        Scene.START -> AssistantSheet(
            maxHeight = maxHeight,
            kicker = if (me != null) "${me}님, 안녕하세요" else "안녕하세요",
            speech = "오늘 누구를 만나러 가나요?",
            subtitle = "전화를 걸면 제가 대화를 같이 듣고 만날 곳과 시간을 정리해 드릴게요.",
            voice = s.voice,
            footnote = if (s.server.status == ServerStatus.OFFLINE) "Audeo 서버에 연결되지 않았어요. 연결되면 연락처를 다시 불러올게요." else null,
            question = "누구에게 전화할까요?",
            branches = s.contacts.take(5).mapIndexed { i, person ->
                Branch(
                    label = person.name,
                    outcome = "${person.note} · 전화하고 약속을 정리해요",
                    variant = if (i == 0) ButtonVariant.PRIMARY else ButtonVariant.SECONDARY,
                    icon = AudeoIcon.Phone,
                    onClick = { vm.callContact(person) },
                )
            },
        ) {
            when {
                s.contacts.isNotEmpty() -> {}
                s.contactsLoading -> Text("연락처를 불러오고 있어요", style = AudeoType.body, color = c.inkMuted)
                else -> Text("Audeo를 쓰는 연락처가 아직 없어요", style = AudeoType.body, color = c.inkMuted)
            }
        }

        Scene.DIAL -> AssistantSheet(
            maxHeight = maxHeight,
            kicker = if (call?.analysisRequested == true) "같이 들어도 될지 묻는 중" else "전화 거는 중",
            speech = "${peer}께 전화하고 있어요",
            subtitle = if (call?.analysisRequested == true) "${peer}이 동의하면 대화를 같이 듣고 약속을 정리할게요." else "이번 통화는 듣지 않을게요.",
            voice = s.voice,
            question = if (call?.analysisRequested == true) "기다리기 어렵다면" else null,
            branches = if (call?.analysisRequested == true) {
                listOf(Branch("AI 없이 통화", "이번 통화는 오디가 듣지 않아요", ButtonVariant.QUIET, onClick = vm::callWithoutAi))
            } else {
                emptyList()
            },
        ) {
            PlanFields(
                listOf(
                    PlanField("나", value = if (call?.analysisRequested == true) "동의함" else "AI 없이", updated = true),
                    consentField(peer, call?.peerConsent),
                ),
            )
        }

        Scene.INCOMING -> AssistantSheet(
            maxHeight = maxHeight,
            kicker = "전화가 왔어요",
            speech = "${peer} 전화예요. 제가 같이 들을까요?",
            reasons = if (call?.peerConsent == "granted") listOf(Reason("${peer}은 오디가 듣는 데 이미 동의했어요")) else emptyList(),
            voice = s.voice,
            footnote = "오디는 통화가 끝나면 바로 듣기를 멈춰요.",
            question = "어떻게 받을까요?",
            branches = listOf(
                Branch("같이 듣기", "받고 나서 약속을 정리해 드려요", ButtonVariant.PRIMARY, AudeoIcon.Phone) { vm.acceptIncoming(true) },
                Branch("그냥 받기", "일반 통화로 받아요") { vm.acceptIncoming(false) },
                Branch("거절", "나중에 다시 걸 수 있어요", ButtonVariant.DANGER, AudeoIcon.PhoneEnd, onClick = vm::declineIncoming),
            ),
            voiceHint = "“받아”, “그냥 받아”, “거절해”라고 말해도 돼요",
        )

        Scene.CALL -> when {
            call == null -> AssistantSheet(
                maxHeight = maxHeight,
                kicker = "통화가 끝났어요",
                speech = "들은 내용으로 약속을 정리하고 있어요",
                subtitle = s.journey?.summary,
                status = PlanStatus.DRAFT,
                voice = s.voice,
                footnote = "정리가 끝나면 확인할 것과 계획을 보여 드릴게요.",
                question = "기다리기 어렵다면",
                branches = listOf(Branch("처음으로", "정리를 멈추고 처음 화면으로 가요", ButtonVariant.QUIET, onClick = vm::restart)),
            ) { s.journey?.fields?.takeIf { it.isNotEmpty() }?.let { PlanFields(it) } }

            call.analysisOn -> {
                val fields = s.journey?.fields.orEmpty()
                AssistantSheet(
                    maxHeight = maxHeight,
                    kicker = "통화를 같이 듣고 있어요",
                    speech = if (fields.isEmpty()) "약속 이야기가 나오면 바로 정리할게요" else "${s.journey?.title ?: peer} 약속을 정리하고 있어요",
                    status = PlanStatus.DRAFT,
                    voice = s.voice,
                    footnote = "확인되지 않은 내용은 확정하지 않아요.",
                ) { if (fields.isNotEmpty()) PlanFields(fields) }
            }

            else -> AssistantSheet(
                maxHeight = maxHeight,
                tone = Tone.NEUTRAL,
                kicker = "일반 통화",
                speech = "이번 통화는 듣지 않을게요",
                subtitle = "약속 정리가 필요하면 다음엔 저와 함께 받아 주세요.",
                voice = s.voice,
            )
        }

        Scene.ASK, Scene.PLAN, Scene.CHANGE, Scene.VEHICLE -> s.proposal?.let { ProposalSheet(it, s, vm, maxHeight) }

        Scene.DRIVE -> {
            val j = s.journey
            val co = s.coordination
            val meet = j?.meetPlace?.name?.let { pl -> j.meetAtLabel?.let { "${it}에 ${pl}에서 만나요" } ?: "${pl}에서 만나요" }
            AssistantSheet(
                maxHeight = maxHeight,
                kicker = "${j?.passengerCallName ?: peer}께 가는 중이에요",
                speech = meet ?: "만날 곳으로 가고 있어요",
                status = PlanStatus.ACTIVE,
                voice = s.voice,
                footnote = co?.nextAction ?: "중요한 변화가 생기면 바로 알려 드릴게요.",
            ) {
                if (co != null && co.lanes.isNotEmpty()) {
                    EtaCompareBody(co.start, co.end, co.now, co.lanes, co.ticks, co.verdict)
                } else {
                    Text(co?.verdict ?: "도착 시각을 계산하고 있어요", style = AudeoType.body, color = c.inkMuted)
                }
            }
        }

        Scene.ARRIVE -> {
            val a = s.coordination?.arrival
            val passengerLane = s.coordination?.lanes?.firstOrNull { it.tone != LaneTone.VEHICLE }
            AssistantSheet(
                maxHeight = maxHeight,
                kicker = "거의 다 왔어요",
                speech = a?.title ?: "곧 도착해요",
                figureLabel = a?.figureLabel,
                figure = a?.figure,
                reasons = passengerLane?.let { listOf(Reason("${it.who} · ${it.note ?: it.at}", SourceKind.LOOKUP, "위치 공유")) }.orEmpty(),
                voice = s.voice,
                footnote = "가까워지거나 멈춘 것만으로는 탑승을 확정하지 않아요.",
                question = "${s.journey?.passengerCallName ?: peer}이 탔나요?",
                branches = listOf(
                    Branch("탔어요", "픽업을 마치고 위치 공유를 끝내요", ButtonVariant.PRIMARY, AudeoIcon.Check, onClick = vm::boarded),
                    Branch("아직이에요", "조금 더 기다릴게요", onClick = vm::notYet),
                ),
                voiceHint = "“차 탔어”라고 말해도 돼요",
            )
        }

        Scene.DONE -> {
            val name = s.finished?.passengerCallName
            AssistantSheet(
                maxHeight = maxHeight,
                kicker = "픽업 완료",
                speech = if (name != null) "오늘도 잘 만났어요. ${name}과 좋은 시간 보내세요!" else "오늘도 잘 만났어요. 좋은 시간 보내세요!",
                subtitle = "위치 공유와 조율 알림은 모두 껐어요.",
                voice = s.voice,
                question = "다음은",
                branches = listOf(Branch("처음으로", "다음 약속도 도와 드릴게요", ButtonVariant.PRIMARY, onClick = vm::restart)),
            )
        }
    }
}

/** 서버 제안(질문·계획·변경·차량 준비)을 비서 시트로 그립니다. 선택지는 서버가 정하고, 결과 설명은 제안 내용으로 만듭니다. */
@Composable
private fun ProposalSheet(p: Proposal, s: AudeoUiState, vm: AudeoViewModel, maxHeight: Dp) {
    val c = Audeo.colors
    val passenger = s.journey?.passengerCallName ?: s.call?.peerCallName ?: "탑승자"
    val mine = p.decidedByDriver
    val branches = if (!mine) {
        emptyList()
    } else {
        p.options.map { o ->
            Branch(
                label = o.label,
                outcome = outcomeOf(p, o, s),
                variant = when (o.variant) {
                    "primary" -> ButtonVariant.PRIMARY
                    "quiet" -> ButtonVariant.QUIET
                    "danger" -> ButtonVariant.DANGER
                    else -> ButtonVariant.SECONDARY
                },
                icon = when {
                    o.id == "apply_navi" -> AudeoIcon.Navigate
                    o.variant == "primary" && p.kind != "ask" -> AudeoIcon.Check
                    else -> null
                },
                enabled = !s.busy,
                onClick = { vm.choose(o) },
            )
        }
    }
    val waiting = if (mine) null else Waiting(passenger, "만날 곳이 바뀌는 일이라 ${passenger}께 물어봤어요. 고르면 바로 알려 드릴게요.")
    val primary = p.options.firstOrNull { it.variant == "primary" }?.label
    when (p.kind) {
        "ask" -> AssistantSheet(
            maxHeight = maxHeight,
            tone = Tone.CAUTION,
            kicker = "하나만 확인할게요",
            speech = p.question ?: p.title,
            subtitle = p.context,
            voice = s.voice,
            footnote = p.footnote,
            question = "답을 골라 주세요",
            branches = branches,
            waiting = waiting,
            voiceHint = primary?.takeIf { mine }?.let { "“$it”라고 말해도 돼요" },
        )

        "plan" -> AssistantSheet(
            maxHeight = maxHeight,
            kicker = "약속을 정리했어요",
            speech = "${p.title} 계획을 세웠어요",
            subtitle = p.context,
            status = PlanStatus.DRAFT,
            figureLabel = p.figure?.label,
            figure = p.figure?.value,
            figureNote = p.figure?.note,
            reasons = p.reasons,
            voice = s.voice,
            footnote = p.footnote,
            question = "이대로 출발할까요?",
            branches = branches,
            waiting = waiting,
            voiceHint = if (mine) "“그렇게 해 줘”라고 말해도 돼요" else null,
        ) { s.journey?.fields?.let(::planKeyFields)?.takeIf { it.isNotEmpty() }?.let { PlanFields(it) } }

        "change" -> AssistantSheet(
            maxHeight = maxHeight,
            tone = Tone.CAUTION,
            kicker = if (mine) "계획이 바뀔 것 같아요" else "${passenger}께 물어봤어요",
            speech = p.title,
            subtitle = p.context,
            reasons = p.reasons,
            voice = s.voice,
            footnote = p.footnote,
            question = if (mine) "바꿀까요?" else "누가 정하나요?",
            branches = branches,
            waiting = waiting,
            voiceHint = if (mine) "“그렇게 해 줘”라고 말해도 돼요" else null,
        ) { if (p.rows.isNotEmpty()) ChangeRows(p.rows) }

        "vehicle_action" -> AssistantSheet(
            maxHeight = maxHeight,
            kicker = "타기 전에 준비해 둘까요?",
            speech = p.title,
            subtitle = p.context,
            reasons = p.reasons,
            voice = s.voice,
            footnote = p.footnote,
            question = "${passenger} 타기 전에",
            branches = branches,
            waiting = waiting,
            voiceHint = if (mine) "“그렇게 해 줘”라고 말해도 돼요" else null,
        ) { if (p.rows.isNotEmpty()) ChangeRows(p.rows) }

        else -> AssistantSheet(
            maxHeight = maxHeight,
            tone = Tone.NEUTRAL,
            kicker = p.context,
            speech = p.title,
            reasons = p.reasons,
            voice = s.voice,
            footnote = p.footnote,
            branches = branches,
            waiting = waiting,
        ) { if (p.rows.isNotEmpty()) ChangeRows(p.rows) }
    }
}

/** 고르면 어떻게 되는지 한 줄. 서버 선택지에는 설명이 없어서 제안 내용(바뀌는 값)으로 만듭니다. */
private fun outcomeOf(p: Proposal, o: ProposalOption, s: AudeoUiState): String? {
    val place = s.journey?.meetPlace?.name
    fun after() = p.rows.joinToString(" · ") { "${it.label} ${it.to}" }.ifEmpty { null }
    fun before() = p.rows.joinToString(" · ") { it.from }.ifEmpty { null }
    return when {
        o.id == "apply_navi" -> place?.let { "${ro(it)} 길안내를 시작해요" } ?: "내비 길안내를 시작해요"
        o.id == "save_only" -> "계획만 저장하고 내비는 그대로 둬요"
        o.id == "later" -> "확실해지면 다시 물어볼게요"
        p.kind == "ask" -> null
        o.id == "hvac_only" -> p.rows.firstOrNull { "온도" in it.label }?.let { "${it.label} ${it.to}만 맞춰요" } ?: "온도만 맞춰요"
        o.variant == "primary" -> after() ?: "이대로 진행해요"
        o.id in setOf("keep", "no", "skip", "reject") -> before()?.let { "지금 그대로 · $it" } ?: "지금 그대로 둬요"
        else -> null
    }
}

/** 받침에 맞춰 '로/으로'를 붙입니다(ㄹ 받침은 '로'). */
private fun ro(word: String): String {
    val ch = word.lastOrNull() ?: return word
    if (ch !in '가'..'힣') return "${word}로"
    val jong = (ch - '가') % 28
    return if (jong == 0 || jong == 8) "${word}로" else "${word}으로"
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

private class DialogFrame(val key: String, val state: AudeoUiState)

private fun dialogKey(s: AudeoUiState): String = "${s.scene}|${s.proposal?.id.orEmpty()}|${s.call?.status ?: "none"}"

/** 사라지는 애니메이션 동안 마지막 값을 계속 그리려고 기억해 둡니다(상태가 아니라 다시 그리기를 일으키지 않음). */
@Composable
private fun <T : Any> rememberLast(value: T?): T? {
    val holder = remember { arrayOfNulls<Any>(1) }
    if (value != null) holder[0] = value
    @Suppress("UNCHECKED_CAST")
    return holder[0] as T?
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


/**
 * 오디의 생각 줄: 서버가 이번에 실제로 한 판단(대화에서 들은 것 → 조회 → 제안)을 한 줄씩 차례로 보여 줍니다.
 * 연출용 문구가 아니라 서버 처리 기록(agent.thinking)이에요.
 */
@Composable
private fun ThinkingPanel(t: com.audeo.app.audeo.ThinkingUi, modifier: Modifier = Modifier) {
    val c = Audeo.colors
    var shown by remember(t.id) { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(t.id) {
        shown = 0
        repeat(t.steps.size) {
            kotlinx.coroutines.delay(450)
            shown = it + 1
        }
    }
    Column(
        modifier.floatingSurface().padding(horizontal = Space.s4, vertical = Space.s3),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        Text("오디가 확인한 것", style = AudeoType.label, color = c.inkMuted)
        t.steps.forEachIndexed { i, step ->
            AnimatedVisibility(visible = i < shown, enter = Motion.barEnter()) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (i == t.steps.lastIndex) c.brand else c.brandInk.copy(alpha = 0.5f)))
                    Text(step, style = AudeoType.body, color = c.ink, maxLines = 2)
                }
            }
        }
    }
}
