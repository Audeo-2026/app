package com.audeo.app.audeo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audeo.app.BuildConfig
import com.audeo.app.pleos.LocationController
import com.audeo.app.pleos.NaviController
import com.audeo.app.pleos.NaviEvent
import com.audeo.app.pleos.UnsupportedFeatureException
import com.audeo.app.pleos.VehicleController
import com.audeo.app.pleos.VoiceController
import com.audeo.app.server.AudeoServer
import com.audeo.app.server.DeviceStore
import com.audeo.app.server.ServerError
import com.audeo.app.server.ServerEvent
import com.audeo.app.server.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class NaviState(
    val connected: Boolean = false,
    val remainSeconds: Int? = null,
    val remainMeters: Int? = null,
)

enum class ServerStatus { CHECKING, ONLINE, OFFLINE }

data class ServerState(
    val status: ServerStatus = ServerStatus.CHECKING,
    val latencyMs: Long? = null,
    val message: String? = null,
    val checkedAt: String? = null,
    /** 서버에 등록된 이 차의 운전자 이름. */
    val registeredAs: String? = null,
)

data class AudeoUiState(
    val scene: Scene = Scene.START,
    /** 데모 메뉴의 미리보기(테스트 데이터)를 보는 중. 버튼을 눌러도 실행하지 않습니다. */
    val previewing: Boolean = false,
    /** null이면 차량의 낮/밤 모드를 따릅니다. 데모에서만 바꿉니다. */
    val nightOverride: Boolean? = null,
    val contacts: List<Contact> = emptyList(),
    val contactsLoading: Boolean = true,
    val call: CallInfo? = null,
    val callSeconds: Int = 0,
    val muted: Boolean = false,
    val journey: Journey? = null,
    /** 지금 운전자에게 보여 줄 제안(질문 > 변경 > 차량 > 계획 순). */
    val proposal: Proposal? = null,
    val coordination: Coordination? = null,
    /** 방금 끝난 여정. 완료 화면에 씁니다. */
    val finished: Journey? = null,
    val voice: VoiceUi? = null,
    val result: ActionResult? = null,
    val busy: Boolean = false,
    val navi: NaviState = NaviState(),
    val location: String? = null,
    val log: List<String> = emptyList(),
    val showStatus: Boolean = false,
    val server: ServerState = ServerState(),
) {
    val callDuration: String get() = "%02d:%02d".format(callSeconds / 60, callSeconds % 60)

    /** 지도 위 차 위치(그림용). 실제 위치가 아니라 흐름에 맞춘 값입니다. */
    val progress: Float
        get() = when (scene) {
            Scene.DRIVE -> 0.35f
            Scene.CHANGE -> 0.55f
            Scene.VEHICLE -> 0.7f
            Scene.ARRIVE -> 0.9f
            Scene.DONE -> 1f
            else -> 0f
        }
}

/**
 * Audeo 차량 앱의 흐름.
 *
 * - 통화·받아쓰기 분석·제안·조율은 Audeo 서버가 판단하고, 앱은 GET /events(long-poll)로 받아 화면을 바꿉니다.
 * - 운전자의 답(버튼·음성)은 서버에 결정으로 보냅니다. 서버가 실행을 요청하면 그때 Pleos SDK(내비·공조·시트)를 부르고,
 *   SDK 콜백으로 확인된 것만 성공으로 보고합니다.
 * - 통화 음성(WebRTC)은 아직 없어서, 통화가 연결되면 [TestData.callTranscript]를 받아쓰기처럼 서버에 보냅니다.
 */
class AudeoViewModel(app: Application) : AndroidViewModel(app), VoiceController.Callbacks {

    private val live = MutableStateFlow(AudeoUiState())
    private val previewScene = MutableStateFlow<Scene?>(null)

    val state: StateFlow<AudeoUiState> = combine(live, previewScene) { s, p -> if (p == null) s else Preview.state(p, s) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AudeoUiState())

    private val navi = NaviController(app)
    private val vehicle = VehicleController(app)
    private val voice = VoiceController(app)
    private val location = LocationController(app)
    private val store = DeviceStore(app)
    private val server = AudeoServer(store)

    private var connectJob: Job? = null
    private var eventJob: Job? = null
    private var statusJob: Job? = null
    private var statusJourneyId: String? = null
    private var scriptJob: Job? = null
    private var scriptCallId: String? = null
    private var callTimer: Job? = null
    private var thinkingJob: Job? = null

    /** 운전자에게 온 대기 중 제안. */
    private val proposals = LinkedHashMap<String, Proposal>()
    private var shownProposalId: String? = null
    private var dismissedDraftId: String? = null

    /** 실행 요청(execution) 처리 상태. 한 제안에서 나온 것끼리 결과 배너 하나로 묶습니다. */
    private enum class ExecState { PENDING, SUCCESS, FAILED, UNKNOWN }
    private data class ExecView(val state: ExecState, val title: String)

    private val handledExecutions = HashSet<String>()
    private val execViews = LinkedHashMap<String, ExecView>()
    private var execGroup: String? = null

    /** 응답을 기다리는 내비 요청. 대기 시간이 지나 unknown으로 보고한 뒤에도, 늦게 확인되면 성공으로 고쳐 보고합니다. */
    private data class PendingNavi(val executionId: String, val stop: Boolean, val place: Place, val timedOut: Boolean = false)
    private var pendingNavi: PendingNavi? = null
    private var naviTimeout: Job? = null

    private var lastLocation: Pair<Double, Double>? = null

    /** TTS가 끝나면 운전자의 답을 한 번 듣습니다. */
    private var expectAnswer = false

    init {
        navi.connect { event -> viewModelScope.launch(Dispatchers.Main) { onNaviEvent(event) } }
        val vehicleError = vehicle.start()
        log(if (vehicleError == null) "Vehicle 준비됨" else "Vehicle 초기화 실패: $vehicleError")
        voice.start(this)
        connectServer()
    }

    // ---------- 서버 연결 ----------

    /** 서버 확인 → 기기 등록 → 지원 범위·연락처·진행 중 여정 → 실시간 이벤트. 실패하면 점점 늦게 다시 시도합니다. */
    private fun connectServer() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            var attempt = 0
            while (isActive) {
                setServer(ServerStatus.CHECKING)
                val ping = server.ping()
                val ms = ping.getOrNull()
                if (ms != null) {
                    try {
                        val me = server.ensureRegistered()
                        setServer(ServerStatus.ONLINE, latency = ms, registeredAs = me.name)
                        log("Audeo 서버 연결됨 (${ms}ms) · ${me.name}")
                        launch { uploadCapabilities() }
                        launch {
                            runCatching { server.updateVehicle(BuildConfig.AUDEO_VEHICLE_LABEL, BuildConfig.AUDEO_PLATE_NUMBER) }
                                .onFailure { log("차량 정보 갱신 실패: ${it.message}") }
                        }
                        loadContacts()
                        syncJourney()
                        startEvents()
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        setServer(ServerStatus.OFFLINE, message = e.message)
                        log("Audeo 서버 준비 실패: ${e.message}")
                    }
                } else {
                    setServer(ServerStatus.OFFLINE, message = ping.exceptionOrNull()?.message)
                    log("Audeo 서버 연결 실패: ${ping.exceptionOrNull()?.message}")
                }
                attempt++
                delay(minOf(30_000L, 3_000L * attempt))
            }
        }
    }

    /** 데모 메뉴의 '서버 다시 확인'. */
    fun checkServer() {
        if (eventJob?.isActive != true) {
            connectJob?.cancel()
            connectServer()
            return
        }
        viewModelScope.launch {
            server.ping()
                .onSuccess { ms -> setServer(ServerStatus.ONLINE, latency = ms, registeredAs = live.value.server.registeredAs) }
                .onFailure { e -> setServer(ServerStatus.OFFLINE, message = e.message) }
            loadContacts()
        }
    }

    private fun setServer(status: ServerStatus, latency: Long? = null, message: String? = null, registeredAs: String? = null) {
        live.update {
            it.copy(
                server = ServerState(
                    status = status,
                    latencyMs = latency ?: it.server.latencyMs.takeIf { status == ServerStatus.ONLINE },
                    message = message,
                    checkedAt = LocalTime.now().format(TIME),
                    registeredAs = registeredAs ?: it.server.registeredAs,
                ),
            )
        }
    }

    private suspend fun uploadCapabilities() {
        val caps = vehicle.capabilities()
        if (caps.length() == 0) return
        try {
            server.putCapabilities(caps)
            log("차량 지원 범위를 서버에 올렸어요")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("지원 범위 업로드 실패: ${e.message}")
        }
    }

    private suspend fun loadContacts() {
        try {
            val me = server.identity?.userId
            val contacts = server.contacts().filter { it.userId != me }
            live.update { it.copy(contacts = contacts, contactsLoading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            live.update { it.copy(contactsLoading = false) }
            log("연락처를 못 불러왔어요: ${e.message}")
        }
    }

    /** 앱을 다시 켰을 때 진행 중인 여정과 대기 중인 제안을 다시 불러옵니다. */
    private suspend fun syncJourney() {
        try {
            val current = server.journeys().firstOrNull { !it.ended } ?: return
            val journey = server.journey(current.id)
            val pending = server.proposals(journey.id, "pending")
            val coordination = if (journey.status in DRIVING_STATUSES) server.coordination(journey.id) else null
            proposals.clear()
            pending.filter(::forDriver).forEach { proposals[it.id] = it }
            live.update { it.copy(journey = journey, coordination = coordination) }
            if (journey.status in DRIVING_STATUSES) startStatusReports(journey.id)
            publish()
            log("진행 중인 여정을 불러왔어요: ${journey.title}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("여정을 못 불러왔어요: ${e.message}")
        }
    }

    private fun startEvents() {
        eventJob?.cancel()
        eventJob = viewModelScope.launch {
            var after = store.lastSeq
            if (after == 0L) {
                // 새 기기: 등록 전 이벤트는 건너뛰고 지금부터 받습니다.
                after = runCatching { server.events(0, 0).lastSeq }.getOrDefault(0L)
                store.lastSeq = after
            }
            var failures = 0
            while (isActive) {
                try {
                    val batch = server.events(after, LONG_POLL_SECONDS)
                    if (failures > 0 || live.value.server.status != ServerStatus.ONLINE) {
                        setServer(ServerStatus.ONLINE)
                    }
                    failures = 0
                    batch.events.forEach(::handle)
                    after = maxOf(after, batch.lastSeq)
                    store.lastSeq = after
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    setServer(ServerStatus.OFFLINE, message = e.message)
                    if (failures == 1) log("실시간 연결 끊김: ${e.message}")
                    delay(minOf(30_000L, 2_000L * failures))
                }
            }
        }
    }

    // ---------- 서버 이벤트 ----------

    private fun handle(e: ServerEvent) {
        when (e) {
            is ServerEvent.CallIncoming -> if (fresh(e, 60)) onCall(e.call)
            is ServerEvent.CallState -> onCall(e.call)
            is ServerEvent.JourneyUpdated -> onJourney(e.journey)
            is ServerEvent.JourneyEnded -> onJourneyEnded(e.journeyId, e.status)
            is ServerEvent.ProposalChanged -> onProposal(e.proposal)
            is ServerEvent.ExecutionRequested ->
                if (fresh(e, 120)) execute(e.execution) else log("오래된 실행 요청은 건너뛰어요: ${e.execution.target}")
            is ServerEvent.ExecutionReported -> log("서버 확인: ${e.execution.target} ${e.execution.status}")
            is ServerEvent.CoordinationUpdated -> {
                val j = live.value.journey
                if (j == null || e.journeyId == null || e.journeyId == j.id) {
                    live.update { it.copy(coordination = e.coordination) }
                    publish()
                }
            }
            is ServerEvent.Say -> if (fresh(e, 30)) say(e.text, question = e.priority == "question" && e.proposalId != null)
            is ServerEvent.Other -> log("이벤트: ${e.type}")
        }
    }

    private fun fresh(e: ServerEvent, seconds: Long): Boolean =
        e.at?.let { Duration.between(it, Instant.now()).seconds <= seconds } ?: true

    private fun onCall(call: CallInfo) {
        val prev = live.value.call
        if (prev != null && prev.id != call.id && call.status == CallStatus.ENDED) return
        when (call.status) {
            CallStatus.RINGING -> {
                live.update { it.copy(call = call) }
                if (prev?.id != call.id) {
                    if (call.outgoing) {
                        live.update { it.copy(voice = VoiceUi(VoiceMode.SPEAKING, caption = "“${call.peerCallName}께 전화하고 있어요”")) }
                        voice.speak("${call.peerCallName}께 전화할게요")
                    } else {
                        val ask = "${call.peerCallName} 전화예요. AI와 함께 받을까요?"
                        live.update { it.copy(voice = VoiceUi(VoiceMode.SPEAKING, caption = "“$ask”")) }
                        speakThenListen(ask)
                    }
                }
            }
            CallStatus.ACTIVE -> {
                live.update { it.copy(call = call, voice = if (prev?.status != CallStatus.ACTIVE) null else it.voice) }
                if (prev?.id != call.id || prev.status != CallStatus.ACTIVE) startCallTimer()
                if (call.analysisOn) startScript(call) else stopScript()
            }
            CallStatus.ENDED -> {
                stopCallTimer()
                stopScript()
                val missed = prev?.status == CallStatus.RINGING
                live.update {
                    it.copy(
                        call = null,
                        voice = null,
                        result = if (missed && call.outgoing) {
                            ActionResult(ResultState.FAILED, "${call.peerCallName}이 받지 않았어요", "통화 상태: ${call.rawStatus}")
                        } else {
                            it.result
                        },
                    )
                }
            }
        }
        val jid = call.journeyId
        if (jid != null && live.value.journey?.id != jid) {
            viewModelScope.launch { runCatching { server.journey(jid) }.onSuccess(::onJourney) }
        }
        publish()
    }

    private fun onJourney(j: Journey) {
        val cur = live.value.journey
        if (j.ended) {
            if (cur == null || cur.id == j.id) onJourneyEnded(j.id, j.status, j)
            return
        }
        if (cur != null && cur.id != j.id && cur.version > 0 && !cur.ended && live.value.call == null) {
            // 다른 여정이 새로 시작됐어요(새 통화). 이전 제안은 버립니다.
            proposals.clear()
        }
        live.update { it.copy(journey = j, finished = null) }
        if (j.status in DRIVING_STATUSES) {
            startStatusReports(j.id)
            if (live.value.coordination == null) {
                viewModelScope.launch { runCatching { server.coordination(j.id) }.getOrNull()?.let { c -> live.update { it.copy(coordination = c) }; publish() } }
            }
        }
        publish()
    }

    private fun onJourneyEnded(journeyId: String, status: String, ended: Journey? = null) {
        val cur = live.value.journey
        if (cur != null && cur.id != journeyId) return
        // 완료 응답과 journey.ended 이벤트가 둘 다 오므로, 이미 끝낸 여정이면 완료 화면을 지우지 않습니다.
        if (cur == null && (live.value.finished?.id == journeyId || ended == null)) return
        stopStatusReports()
        proposals.clear()
        live.update {
            it.copy(
                journey = null,
                coordination = null,
                finished = if (status == "completed") (ended ?: cur) else null,
                result = if (status == "cancelled") ActionResult(ResultState.SUCCESS, "픽업을 취소했어요", "위치 공유를 끝냈어요") else it.result,
            )
        }
        publish()
    }

    private fun onProposal(p: Proposal) {
        if (!forDriver(p)) return
        if (p.status == "pending") proposals[p.id] = p else proposals.remove(p.id)
        publish()
    }

    private fun forDriver(p: Proposal) = p.audience.isEmpty() || "driver" in p.audience

    /** 대기 중 제안과 통화·여정 상태로 지금 보여 줄 장면을 정합니다. */
    private fun publish() {
        val top = proposals.values.sortedBy { PROPOSAL_ORDER.indexOf(it.kind).let { i -> if (i < 0) PROPOSAL_ORDER.size else i } }.firstOrNull()
        if (top?.id != shownProposalId) {
            shownProposalId = top?.id
            expectAnswer = false
        }
        live.update { s ->
            val withProposal = s.copy(proposal = top, busy = if (top?.id != s.proposal?.id) false else s.busy)
            withProposal.copy(scene = resolveScene(withProposal))
        }
    }

    private fun resolveScene(s: AudeoUiState): Scene {
        val call = s.call
        val top = s.proposal
        val journey = s.journey
        return when {
            call?.status == CallStatus.RINGING -> if (call.outgoing) Scene.DIAL else Scene.INCOMING
            top?.kind == "ask" -> Scene.ASK
            call?.status == CallStatus.ACTIVE -> Scene.CALL
            top != null -> when (top.kind) {
                "change" -> Scene.CHANGE
                "vehicle_action" -> Scene.VEHICLE
                else -> Scene.PLAN
            }
            journey != null && journey.status == "draft" && journey.id != dismissedDraftId -> Scene.CALL
            journey != null && journey.status in DRIVING_STATUSES -> if (s.coordination?.arrival != null) Scene.ARRIVE else Scene.DRIVE
            s.finished != null -> Scene.DONE
            else -> Scene.START
        }
    }

    // ---------- 운전자 행동 ----------

    fun callContact(contact: Contact) {
        if (previewOnly()) return
        val userId = contact.userId ?: return
        viewModelScope.launch {
            live.update { it.copy(busy = true, result = null) }
            try {
                onCall(server.startCall(userId, analysis = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("전화를 걸지 못했어요", e)
            } finally {
                live.update { it.copy(busy = false) }
            }
        }
    }

    /** 발신 중 'AI 없이 통화': 운전자 쪽 분석 동의를 거둡니다. */
    fun callWithoutAi() = callAction("AI 분석을 끄지 못했어요") { server.consent(it.id, granted = false) }

    fun acceptIncoming(withAi: Boolean) = callAction("전화를 받지 못했어요") {
        expectAnswer = false
        voice.stopAll()
        server.answer(it.id, analysisConsent = withAi)
    }

    fun declineIncoming() = callAction("전화를 거절하지 못했어요") {
        expectAnswer = false
        voice.stopAll()
        server.reject(it.id)
    }

    fun endCall() = callAction("통화를 끝내지 못했어요") { server.endCall(it.id) }

    fun toggleMute() = live.update { it.copy(muted = !it.muted) }

    private fun callAction(failure: String, block: suspend (CallInfo) -> CallInfo) {
        if (previewOnly()) return
        val call = live.value.call ?: return
        viewModelScope.launch {
            try {
                onCall(block(call))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(failure, e)
            }
        }
    }

    /** 제안의 선택지 하나를 고릅니다. 실행은 서버가 execution으로 다시 요청할 때 합니다. */
    fun choose(option: ProposalOption, viaVoice: Boolean = false) {
        if (previewOnly()) return
        val p = live.value.proposal ?: return
        expectAnswer = false
        voice.stopAll()
        live.update { it.copy(busy = true, voice = null) }
        viewModelScope.launch {
            try {
                onProposal(server.decide(p.id, option.id, viaVoice, baseVersion = null))
                log("${p.kind}: ${option.label}${if (viaVoice) " (음성)" else ""}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: ServerError.Api) {
                if (e.status == 409) {
                    log("이미 처리된 제안이에요: ${e.message}")
                    refreshProposals(p.journeyId)
                } else {
                    fail("답을 보내지 못했어요", e)
                }
            } catch (e: Exception) {
                fail("답을 보내지 못했어요", e)
            } finally {
                live.update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun refreshProposals(journeyId: String) {
        runCatching { server.proposals(journeyId, "pending") }.onSuccess { list ->
            proposals.clear()
            list.filter(::forDriver).forEach { proposals[it.id] = it }
            publish()
        }
    }

    /** '탔어요 · 픽업 완료'. 당사자가 누른 것이므로 서버가 바로 완료합니다. */
    fun boarded() {
        if (previewOnly()) return
        val j = live.value.journey ?: return
        expectAnswer = false
        voice.stopAll()
        viewModelScope.launch {
            try {
                val done = server.complete(j.id)
                onJourneyEnded(done.id, done.status, done)
                voice.speak("픽업을 마쳤어요. 위치 공유를 끝냈어요.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("픽업 완료를 보내지 못했어요", e)
            }
        }
    }

    fun notYet() {
        if (previewOnly()) return
        expectAnswer = false
        voice.speak("알겠어요. 기다릴게요.")
    }

    /** 완료 화면이나 정리 중 화면에서 처음으로. */
    fun restart() {
        if (previewOnly()) return
        live.value.journey?.takeIf { it.status == "draft" }?.let { dismissedDraftId = it.id }
        live.update { it.copy(finished = null, result = null, voice = null) }
        publish()
        viewModelScope.launch { loadContacts() }
    }

    fun dismissResult() = live.update { it.copy(result = null) }

    private fun fail(title: String, e: Exception) {
        log("$title: ${e.message}")
        live.update { it.copy(result = ActionResult(ResultState.FAILED, title, e.message)) }
    }

    // ---------- 실행 요청(내비·공조·시트) ----------

    private fun execute(x: Execution) {
        if (x.status != "requested" || !handledExecutions.add(x.id)) return
        if (execGroup != x.proposalId) {
            execGroup = x.proposalId
            execViews.clear()
        }
        when (val c = x.command) {
            is CarCommand.Route -> runNavi(x, c.place, stop = false)
            is CarCommand.AddStop -> runNavi(x, c.place, stop = true)
            is CarCommand.CabinTemperature -> {
                val t = "%.0f".format(c.celsius)
                showExec(x.id, ExecState.PENDING, "실내 온도 $t°")
                log("Vehicle HVAC.setHvacTemperature ${t}°C ${c.area ?: ""}")
                vehicle.setCabinTemperature(c.celsius, c.area) { r ->
                    viewModelScope.launch(Dispatchers.Main) {
                        finishVehicle(x, r, "실내 온도를 $t°로 맞췄어요", "공조 온도를 바꾸지 못했어요", "이 차는 공조 온도 조절을 지원하지 않아요")
                    }
                }
                vehicleTimeout(x)
            }
            is CarCommand.SeatHeat -> {
                val seat = seatLabel(c.area)
                showExec(x.id, ExecState.PENDING, "$seat 열선 ${c.level}단")
                log("Vehicle Seat.setHvacSeatTemperature ${c.level}단 ${c.area ?: ""}")
                vehicle.setSeatHeat(c.level, c.area) { r ->
                    viewModelScope.launch(Dispatchers.Main) {
                        finishVehicle(x, r, "$seat 열선을 ${c.level}단으로 켰어요", "$seat 열선을 켜지 못했어요", "$seat 열선은 지원하지 않아 직접 켜 주세요")
                    }
                }
                vehicleTimeout(x)
            }
            is CarCommand.Unsupported -> {
                showExec(x.id, ExecState.FAILED, "이 앱이 모르는 명령이에요 (${c.target} ${c.op})")
                report(x, "failed", "unsupported command: ${c.target}.${c.op}")
            }
        }
    }

    private fun runNavi(x: Execution, place: Place, stop: Boolean) {
        val ok = if (stop) navi.addStop(place) else navi.routeTo(place)
        log("NaviHelper.${if (stop) "addWaypoint" else "requestRoute"}: ${place.name}")
        if (!ok) {
            showExec(x.id, ExecState.FAILED, if (navi.connected) "내비게이션에 요청하지 못했어요" else "내비와 아직 연결되지 않았어요")
            report(x, "failed", if (navi.connected) "request rejected" else "navi not connected")
            return
        }
        showExec(x.id, ExecState.PENDING, "내비게이션 응답을 기다리는 중")
        pendingNavi = PendingNavi(x.id, stop, place)
        naviTimeout?.cancel()
        naviTimeout = viewModelScope.launch {
            delay(NAVI_TIMEOUT_MS)
            val p = pendingNavi
            if (p?.executionId == x.id) {
                pendingNavi = p.copy(timedOut = true)
                showExec(x.id, ExecState.UNKNOWN, "내비게이션 응답을 확인하지 못했어요")
                report(x, "unknown", "no navi callback in ${NAVI_TIMEOUT_MS / 1000}s")
                delay(NAVI_LATE_MS)
                if (pendingNavi?.executionId == x.id) pendingNavi = null
            }
        }
    }

    private fun naviConfirmed() {
        val p = pendingNavi ?: return
        pendingNavi = null
        naviTimeout?.cancel()
        if (p.timedOut) log("내비 응답이 늦게 왔어요: 성공으로 다시 보고해요")
        val n = live.value.navi
        val observed = JSONObject().putOpt("remain_s", n.remainSeconds).putOpt("remain_m", n.remainMeters)
        showExec(p.executionId, ExecState.SUCCESS, if (p.stop) "${p.place.name}을 경유지로 넣었어요" else "${p.place.name}로 길안내를 시작했어요")
        report(p.executionId, "succeeded", if (p.stop) "onWaypointChanged" else "onRouteStarted", observed)
    }

    private fun <T> finishVehicle(x: Execution, r: Result<T>, success: String, failure: String, unsupported: String) {
        if (execViews[x.id]?.state != ExecState.PENDING) return
        val e = r.exceptionOrNull()
        when {
            e == null -> {
                showExec(x.id, ExecState.SUCCESS, success)
                report(x, "succeeded", null, JSONObject().put("value", r.getOrNull().toString()))
            }
            e is UnsupportedFeatureException -> {
                showExec(x.id, ExecState.FAILED, unsupported)
                report(x, "failed", "unsupported: ${e.message}")
            }
            else -> {
                showExec(x.id, ExecState.FAILED, failure)
                report(x, "failed", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun vehicleTimeout(x: Execution) {
        viewModelScope.launch {
            delay(VEHICLE_TIMEOUT_MS)
            if (execViews[x.id]?.state == ExecState.PENDING) {
                showExec(x.id, ExecState.UNKNOWN, "차량 응답을 확인하지 못했어요")
                report(x, "unknown", "no vehicle callback in ${VEHICLE_TIMEOUT_MS / 1000}s")
            }
        }
    }

    private fun report(x: Execution, status: String, detail: String?, observed: JSONObject? = null) = report(x.id, status, detail, observed)

    private fun report(executionId: String, status: String, detail: String?, observed: JSONObject? = null) {
        viewModelScope.launch {
            try {
                server.executionResult(executionId, status, detail, observed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("실행 결과 보고 실패: ${e.message}")
            }
        }
    }

    private fun showExec(id: String, state: ExecState, title: String) {
        execViews[id] = ExecView(state, title)
        val banner = execBanner()
        live.update { it.copy(result = banner) }
        if (banner?.state == ResultState.SUCCESS) {
            bannerJob?.cancel()
            bannerJob = viewModelScope.launch {
                delay(BANNER_MS)
                if (live.value.result == banner) live.update { it.copy(result = null) }
            }
        }
    }

    private var bannerJob: Job? = null

    /** 성공은 SDK 콜백으로 확인된 것만, 확실하지 않으면 확인 필요로 둡니다. */
    private fun execBanner(): ActionResult? {
        val views = execViews.values.toList().ifEmpty { return null }
        if (views.any { it.state == ExecState.PENDING }) {
            return ActionResult(
                ResultState.PENDING,
                if (views.size == 1) views[0].title else "차량에 요청하는 중",
                "응답을 확인하기 전에는 바뀌었다고 표시하지 않아요",
            )
        }
        val ok = views.filter { it.state == ExecState.SUCCESS }
        val bad = views.filter { it.state != ExecState.SUCCESS }
        return when {
            bad.isEmpty() -> ActionResult(ResultState.SUCCESS, ok.joinToString(" · ") { it.title }, "차량 응답을 확인했어요")
            ok.isEmpty() && bad.all { it.state == ExecState.UNKNOWN } ->
                ActionResult(ResultState.PENDING, bad.joinToString(" · ") { it.title }, "확실하지 않아 성공으로 표시하지 않아요")
            ok.isEmpty() -> ActionResult(ResultState.FAILED, bad.joinToString(" · ") { it.title }, "현재 설정을 그대로 유지해요")
            else -> ActionResult(ResultState.SUCCESS, ok.joinToString(" · ") { it.title }, bad.joinToString(" · ") { it.title })
        }
    }

    private fun seatLabel(area: String?) = when (area) {
        "ROW_1_LEFT" -> "운전석"
        "ROW_1_RIGHT" -> "조수석"
        "ROW_2_LEFT" -> "뒷좌석 왼쪽"
        "ROW_2_RIGHT" -> "뒷좌석 오른쪽"
        "ROW_2_CENTER" -> "뒷좌석 가운데"
        else -> "좌석"
    }

    // ---------- 상태 보고 ----------

    private fun startStatusReports(journeyId: String) {
        if (statusJourneyId == journeyId && statusJob?.isActive == true) return
        statusJob?.cancel()
        statusJourneyId = journeyId
        statusJob = viewModelScope.launch {
            while (isActive) {
                location.latest { d -> lastLocation = d.latitude to d.longitude }
                reportStatus(journeyId)
                delay(STATUS_INTERVAL_MS)
            }
        }
    }

    private fun stopStatusReports() {
        statusJob?.cancel()
        statusJob = null
        statusJourneyId = null
    }

    /** 상태 보고 번호. 서버가 같은 번호를 중복으로 무시하므로 늘 커지게, 32비트 안에서 씁니다. */
    private var statusSeq = 0L

    private fun nextStatusSeq(): Long {
        statusSeq = maxOf(statusSeq + 1, System.currentTimeMillis() / 1000)
        return statusSeq
    }

    private suspend fun reportStatus(journeyId: String) {
        val n = live.value.navi
        val body = JSONObject()
            .put("client_seq", nextStatusSeq())
            .put("observed_at", Instant.now().toString())
        lastLocation?.let { (lat, lng) -> body.put("location", JSONObject().put("lat", lat).put("lng", lng)) }
        if (n.remainMeters != null || n.remainSeconds != null) {
            body.put("navi", JSONObject().putOpt("remain_m", n.remainMeters).putOpt("remain_s", n.remainSeconds))
        }
        try {
            server.reportStatus(journeyId, body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("상태 보고 실패: ${e.message}")
        }
    }

    // ---------- 통화(테스트 대본) ----------

    /** 통화 음성이 붙기 전까지, 대본을 3초 간격으로 받아쓰기 결과처럼 서버에 보냅니다. */
    private fun startScript(call: CallInfo) {
        if (scriptCallId == call.id) return
        scriptCallId = call.id
        scriptJob?.cancel()
        scriptJob = viewModelScope.launch {
            // client_seq는 통화마다 1부터 셉니다(서버가 32비트 정수로 저장해요).
            TestData.callTranscript.forEachIndexed { i, line ->
                delay(3_000)
                val who = if (line.channel == "rx") "${call.peerCallName}이 말하는 중" else "내가 말하는 중"
                live.update { it.copy(voice = VoiceUi(VoiceMode.LISTENING, who = who, caption = "“${line.text}”")) }
                try {
                    server.utterances(call.id, listOf(Utterance(i + 1L, line.channel, line.text)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("받아쓰기 전송 실패: ${e.message}")
                }
            }
            delay(1_500)
            live.update { it.copy(voice = VoiceUi(VoiceMode.IDLE, who = "테스트 대본을 모두 보냈어요", caption = "통화를 끝내면 계획을 만들어요")) }
        }
    }

    private fun stopScript() {
        scriptJob?.cancel()
        scriptJob = null
    }

    private fun startCallTimer() {
        callTimer?.cancel()
        live.update { it.copy(callSeconds = 0) }
        callTimer = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                live.update { it.copy(callSeconds = it.callSeconds + 1) }
            }
        }
    }

    private fun stopCallTimer() {
        callTimer?.cancel()
        callTimer = null
    }

    // ---------- 미리보기·데모 ----------

    /** 데모 메뉴: 테스트 데이터로 장면을 미리 봅니다. 서버 상태는 그대로 이어집니다. */
    fun goTo(scene: Scene) {
        previewScene.value = scene
    }

    fun exitPreview() {
        previewScene.value = null
    }

    private fun previewOnly(): Boolean {
        if (previewScene.value == null) return false
        log("미리보기 화면이에요. 실제로 하려면 '실제 화면'으로 돌아가세요")
        return true
    }

    fun toggleNight() = live.update {
        it.copy(nightOverride = when (it.nightOverride) {
            null -> true
            true -> false
            false -> null
        })
    }

    fun toggleStatus() = live.update { it.copy(showStatus = !it.showStatus) }

    // ---------- 위치 ----------

    fun onLocationPermission(granted: Boolean) {
        if (!granted) {
            log("위치 권한 없음: 서버에는 내비 남은 거리만 보내요")
            return
        }
        location.start(
            onLocation = { d ->
                lastLocation = d.latitude to d.longitude
                live.update { it.copy(location = "%.5f, %.5f".format(d.latitude, d.longitude)) }
            },
            onError = ::log,
        )
    }

    // ---------- 내비 이벤트 ----------

    private fun onNaviEvent(event: NaviEvent) {
        when (event) {
            NaviEvent.Connected -> {
                live.update { it.copy(navi = it.navi.copy(connected = true)) }
                log("NaviHelper 연결됨")
            }
            is NaviEvent.ConnectFailed -> log("NaviHelper 연결 실패: ${event.message}")
            is NaviEvent.RouteStarted -> {
                live.update { it.copy(navi = it.navi.copy(remainSeconds = event.seconds, remainMeters = event.meters)) }
                log("onRouteStarted: ${event.name ?: "-"}, ${event.seconds / 60}분, ${event.meters}m")
                if (pendingNavi?.stop == false) naviConfirmed()
            }
            NaviEvent.RoutingCompleted -> {
                log("onRoutingCompleted")
                if (pendingNavi?.stop == false) naviConfirmed()
            }
            NaviEvent.WaypointChanged -> {
                log("onWaypointChanged")
                if (pendingNavi?.stop == true) naviConfirmed()
            }
            is NaviEvent.Driving -> live.update {
                it.copy(navi = it.navi.copy(remainSeconds = event.seconds ?: it.navi.remainSeconds, remainMeters = event.meters ?: it.navi.remainMeters))
            }
            NaviEvent.Arrived -> {
                log("onDestinationArrived")
                live.value.journey?.let { j -> viewModelScope.launch { reportStatus(j.id) } }
            }
            NaviEvent.Cancelled -> log("onRouteCancelled")
            is NaviEvent.Error -> {
                log("내비 오류: ${event.code}")
                pendingNavi?.let { p ->
                    pendingNavi = null
                    naviTimeout?.cancel()
                    showExec(p.executionId, ExecState.FAILED, "내비게이션 요청이 실패했어요 (${event.code})")
                    report(p.executionId, "failed", "navi error ${event.code}")
                }
            }
        }
    }

    // ---------- 음성 ----------

    private var speakingJob: Job? = null

    private fun say(text: String, question: Boolean) {
        if (text.isBlank()) return
        val v = VoiceUi(VoiceMode.SPEAKING, caption = "“$text”", hint = if (question) "말씀하시면 바로 멈춰요" else null)
        live.update { it.copy(voice = v) }
        // TTS 끝 알림이 안 오는 경우(다른 앱 소리에 밀림 등)를 대비해 말할 시간이 지나면 표시를 내립니다.
        speakingJob?.cancel()
        speakingJob = viewModelScope.launch {
            delay(3_000L + text.length * 180L)
            if (live.value.voice == v) live.update { it.copy(voice = null) }
        }
        if (question) speakThenListen(text) else {
            expectAnswer = false
            voice.speak(text)
        }
    }

    private fun speakThenListen(text: String) {
        expectAnswer = true
        voice.speak(text)
    }

    override fun onLog(message: String) = log(message)

    override fun onSpeechDone() {
        viewModelScope.launch(Dispatchers.Main) {
            if (expectAnswer) {
                voice.listen()
            } else if (live.value.voice?.mode == VoiceMode.SPEAKING) {
                live.update { it.copy(voice = null) }
            }
        }
    }

    override fun onListening(active: Boolean) {
        viewModelScope.launch(Dispatchers.Main) {
            live.update { s ->
                val v = s.voice ?: return@update s
                if (active) s.copy(voice = v.copy(mode = VoiceMode.LISTENING, who = "듣는 중 · 말씀하세요", hint = null)) else s
            }
        }
    }

    override fun onPartial(text: String) {
        viewModelScope.launch(Dispatchers.Main) {
            live.update { s -> s.voice?.let { s.copy(voice = it.copy(caption = "“$text”")) } ?: s }
        }
    }

    override fun onHeard(text: String) {
        viewModelScope.launch(Dispatchers.Main) {
            log("들은 말: $text")
            handleVoice(text)
        }
    }

    /**
     * 들은 말 처리. 전화 수신은 앱에서 바로 판단하고, 제안에는 선택지 이름을 그대로 말했으면 바로 고르고,
     * 그 밖의 말("그렇게 해 줘", "차 탔어")은 서버(POST /journeys/{id}/voice)가 해석합니다.
     */
    private fun handleVoice(text: String) {
        expectAnswer = false
        val t = text.replace(" ", "")
        fun has(vararg words: String) = words.any { t.contains(it) }
        val s = live.value
        if (s.scene == Scene.INCOMING) {
            when {
                has("그냥") -> acceptIncoming(false)
                has("거절", "끊어") -> declineIncoming()
                has("받아", "응", "네") -> acceptIncoming(true)
            }
            return
        }
        s.proposal?.options?.firstOrNull { o -> t.contains(o.label.replace(" ", "")) }?.let { option ->
            choose(option, viaVoice = true)
            return
        }
        val journey = s.journey ?: return
        live.update { it.copy(voice = VoiceUi(VoiceMode.THINKING, who = "Audeo가 확인하는 중", caption = "“$text”")) }
        viewModelScope.launch {
            try {
                server.voice(journey.id, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("음성 답을 보내지 못했어요", e)
            }
        }
        thinkingJob?.cancel()
        thinkingJob = viewModelScope.launch {
            delay(8_000)
            if (live.value.voice?.mode == VoiceMode.THINKING) live.update { it.copy(voice = null) }
        }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        val line = "${LocalTime.now().format(TIME)}  $message"
        live.update { it.copy(log = (listOf(line) + it.log).take(30)) }
    }

    override fun onCleared() {
        connectJob?.cancel()
        eventJob?.cancel()
        statusJob?.cancel()
        scriptJob?.cancel()
        callTimer?.cancel()
        voice.release()
        navi.release()
        vehicle.release()
        location.release()
    }

    private companion object {
        const val TAG = "Audeo"
        const val LONG_POLL_SECONDS = 25
        const val STATUS_INTERVAL_MS = 15_000L
        /** 내비 앱이 경로를 계산하고 안내 시작 카운트다운(약 5초)까지 끝내는 데 8~10초가 걸립니다. */
        const val NAVI_TIMEOUT_MS = 20_000L
        const val NAVI_LATE_MS = 40_000L
        const val VEHICLE_TIMEOUT_MS = 8_000L
        const val BANNER_MS = 10_000L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val DRIVING_STATUSES = setOf("planned", "active")

        /** 한 번에 하나만 보여 줍니다. 답이 필요한 질문이 먼저입니다. */
        val PROPOSAL_ORDER = listOf("ask", "change", "vehicle_action", "plan")
    }
}
