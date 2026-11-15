package com.audeo.app.audeo

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.audeo.app.BuildConfig
import com.audeo.app.call.CallAudio
import com.audeo.app.call.CallTranscriber
import com.audeo.app.map.PlaceFinder
import com.audeo.app.pleos.LocationController
import com.audeo.app.pleos.NaviController
import com.audeo.app.pleos.NaviEvent
import com.audeo.app.pleos.GearNotParkException
import com.audeo.app.pleos.UnsupportedFeatureException
import com.audeo.app.pleos.VehicleTimeoutException
import com.audeo.app.pleos.VehicleController
import com.audeo.app.pleos.VoiceController
import com.audeo.app.server.AudeoServer
import com.audeo.app.server.DeviceStore
import com.audeo.app.server.ServerError
import com.audeo.app.server.ServerEvent
import com.audeo.app.server.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
    /** null이면 차량의 낮/밤 모드를 따릅니다. 데모에서만 바꿉니다. */
    val nightOverride: Boolean? = null,
    val contacts: List<Contact> = emptyList(),
    val contactsLoading: Boolean = true,
    val call: CallInfo? = null,
    val callSeconds: Int = 0,
    val muted: Boolean = false,
    /** WebRTC 연결 상태(new, connecting, connected, failed …). 통화 음성이 없으면 null. */
    val callAudioState: String? = null,
    val journey: Journey? = null,
    /** 지금 운전자에게 보여 줄 제안(질문 > 변경 > 차량 > 계획 순). */
    val proposal: Proposal? = null,
    val coordination: Coordination? = null,
    /** 방금 끝난 여정. 완료 화면에 씁니다. */
    val finished: Journey? = null,
    val voice: VoiceUi? = null,
    /** 서버가 방금 실제로 한 판단(생각 줄). 몇 초 보여 주고 사라져요. */
    val thinking: ThinkingUi? = null,
    val result: ActionResult? = null,
    val busy: Boolean = false,
    val navi: NaviState = NaviState(),
    val location: String? = null,
    /** 차 위치(지도 표시용). 위치 권한이 있을 때 몇 초마다 새로 읽어요. */
    val car: GeoPoint? = null,
    /** 탑승자 위치(통화 중 휴대폰이 보낸 정밀 위치). 가까워지면 '탑승자 찾기'로 보여 줘요. */
    val passenger: PeerLocation? = null,
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
 * - 통화 음성은 WebRTC로 탑승자와 직접 잇고, 두 사람이 AI 분석에 동의하면 통화 음성을 Pleos STT로 받아써 서버에 올립니다.
 */
class AudeoViewModel(app: Application) : AndroidViewModel(app), VoiceController.Callbacks {

    private val live = MutableStateFlow(AudeoUiState())
    val state: StateFlow<AudeoUiState> = live.asStateFlow()

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
    private data class PendingNavi(
        val executionId: String,
        val stop: Boolean,
        val place: Place,
        val timedOut: Boolean = false,
        val reroute: Boolean = false,
        /** 재탐색 응답이 없어 다른 요청으로 다시 시도했으면 그 요청 이름. */
        val via: String? = null,
    )

    /** 이 여정에서 내비에 넣은 경유지(앞쪽이 먼저 들를 곳). 대체 경로를 다시 요청할 때 잃지 않으려고 기억해요. */
    private val naviStops = mutableListOf<Place>()
    private var pendingNavi: PendingNavi? = null
    private var naviTimeout: Job? = null

    private var lastLocation: Pair<Double, Double>? = null

    /** TTS가 끝나면 운전자의 답을 한 번 듣습니다. */
    private var expectAnswer = false

    // ---------- 시연 탑승자 ----------

    /** 시연 탑승자(연락처 demo=true)의 사용자 id. 서버가 대신 받아 대본 대화를 진행해요. */
    private val demoUserIds = HashSet<String>()
    /** 시연 탑승자에게 건 통화. 상대 기기가 없으니 WebRTC·받아쓰기를 켜지 않고 서버 자막(call.transcript)을 띄워요. */
    private var demoCallId: String? = null
    private val demoJourneyIds = HashSet<String>()

    private fun isDemoCall(c: CallInfo?): Boolean =
        c != null && (c.demo || c.id == demoCallId || c.peerUserId in demoUserIds)

    /**
     * 시연 탑승자와의 여정. 이 여정에서는 앱이 스스로 하던 판단(지도에서 정차할 곳 찾아 만날 곳 바꾸기,
     * 춥다는 말에 열선 바로 켜기)을 하지 않아요. 서버가 같은 일을 제안 → 승인 → 실행으로 보여 줘요.
     */
    private fun isDemoJourney(j: Journey?): Boolean =
        j != null && (j.id in demoJourneyIds || j.passengerUserId in demoUserIds)


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

    private var greeted = false

    /** 앱을 켜고 처음 시작 화면이 뜨면 오디가 한 번 인사합니다. */
    private fun greetOnce() {
        if (greeted || live.value.scene != Scene.START || live.value.contacts.isEmpty()) return
        greeted = true
        val me = BuildConfig.AUDEO_DRIVER_SHORT_NAME.ifBlank { null }
        say(if (me != null) "${me}님, 오늘 누구를 만나러 가나요?" else "오늘 누구를 만나러 가나요?", question = false)
    }

    private suspend fun loadContacts() {
        try {
            val me = server.identity?.userId
            // 시연 탑승자는 맨 앞에 둬요(첫 화면은 5명까지만 보여요).
            val contacts = server.contacts().filter { it.userId != me }.sortedByDescending { it.demo }
            contacts.filter { it.demo }.mapNotNullTo(demoUserIds) { it.userId }
            live.update { it.copy(contacts = contacts, contactsLoading = false) }
            greetOnce()
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
                    // WebSocket: 이벤트와 통화 신호(rtc.signal)를 함께 받습니다. 끊기면 아래로 내려와 다시 붙어요.
                    server.streamEvents(
                        afterSeq = after,
                        onOpen = {
                            if (failures > 0 || live.value.server.status != ServerStatus.ONLINE) setServer(ServerStatus.ONLINE)
                            if (failures > 0) log("실시간 연결 다시 붙음")
                            failures = 0
                        },
                    ) { e ->
                        // 접속하면 서버가 지난 이벤트를 다시 보내기도 해서, 이미 처리한 번호는 건너뜁니다(통화 신호는 번호가 없어요).
                        if (e.seq in 1..after) return@streamEvents
                        handle(e)
                        if (e.seq > after) {
                            after = e.seq
                            store.lastSeq = after
                        }
                    }
                    delay(1_000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    setServer(ServerStatus.OFFLINE, message = e.message)
                    if (failures == 1) log("실시간 연결 끊김: ${e.message}")
                    // WebSocket이 안 되면 long-poll로 놓친 이벤트를 따라잡습니다(통화 신호는 WebSocket으로만 와요).
                    runCatching { server.events(after, 0) }.onSuccess { batch ->
                        batch.events.forEach(::handle)
                        after = maxOf(after, batch.lastSeq)
                        store.lastSeq = after
                    }
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
            is ServerEvent.Thinking -> if (fresh(e, 30)) onThinking(e)
            is ServerEvent.CallTranscript -> if (fresh(e, 60)) onDemoTranscript(e)
            is ServerEvent.Say -> if (fresh(e, 30)) say(e.text, question = e.priority == "question" && e.proposalId != null)
            is ServerEvent.RtcSignal -> when {
                e.callId != live.value.call?.id -> log("다른 통화의 신호: ${e.kind}")
                callAudio.active -> callAudio.onRemoteSignal(e.kind, e.payload)
                // 통화 음성을 준비하는 중(ICE 설정을 받는 중)에 온 신호는 모아 뒀다가 준비되면 넣습니다.
                else -> earlySignals.add(e.kind to e.payload)
            }
            is ServerEvent.Other -> log("이벤트: ${e.type}")
        }
    }

    private val thinkingQueue = ArrayDeque<ThinkingUi>()
    private var thinkingShow: Job? = null

    /** 생각 줄은 하나씩 차례로 보여 줍니다(겹치면 줄을 서요). 자막 강조는 지금 보이는 자막에 그 말이 있을 때만. */
    private fun onThinking(e: ServerEvent.Thinking) {
        if (e.highlights.isNotEmpty()) {
            live.update { s ->
                val v = s.voice
                if (v?.caption != null && e.highlights.any { v.caption.contains(it) }) s.copy(voice = v.copy(highlights = e.highlights)) else s
            }
        }
        if (e.steps.isEmpty()) return
        log("생각: ${e.steps.joinToString(" → ")}")
        thinkingQueue.addLast(ThinkingUi(e.steps, e.highlights, e.seq))
        if (thinkingShow?.isActive == true) return
        thinkingShow = viewModelScope.launch {
            while (thinkingQueue.isNotEmpty()) {
                val t = thinkingQueue.removeFirst()
                live.update { it.copy(thinking = t) }
                delay(THINKING_BASE_MS + t.steps.size * THINKING_STEP_MS)
            }
            live.update { it.copy(thinking = null) }
        }
    }

    private fun fresh(e: ServerEvent, seconds: Long): Boolean =
        e.at?.let { Duration.between(it, Instant.now()).seconds <= seconds } ?: true

    private fun onCall(callIn: CallInfo) {
        val call = if (isDemoCall(callIn)) callIn.copy(demo = true) else callIn
        if (call.demo) call.journeyId?.let(demoJourneyIds::add)
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
                        val ask = "${call.peerCallName} 전화예요. 제가 같이 들을까요?"
                        live.update { it.copy(voice = VoiceUi(VoiceMode.SPEAKING, caption = "“$ask”")) }
                        speakThenListen(ask)
                    }
                }
            }
            CallStatus.ACTIVE -> {
                live.update { it.copy(call = call, voice = if (prev?.status != CallStatus.ACTIVE) null else it.voice) }
                if (prev?.id != call.id || prev.status != CallStatus.ACTIVE) {
                    startCallTimer()
                    // 시연 통화는 상대 기기가 없어요. 음성(WebRTC)과 운전자 마이크 받아쓰기를 켜지 않고 서버 자막만 띄워요.
                    if (call.demo) log("시연 통화: 음성 연결·받아쓰기 없이 서버 대본 자막을 보여 줘요") else startCallAudio(call)
                }
                if (call.demo) transcriber.stop() else updateTranscription(call)
            }
            CallStatus.ENDED -> {
                stopCallTimer()
                stopCallAudio()
                live.value.journey?.let { j -> viewModelScope.launch { delay(PLACE_AFTER_CALL_MS); live.value.journey?.takeIf { it.id == j.id }?.let(::maybeFindPlace) } }
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

    private fun onJourney(jIn: Journey) {
        // 시나리오 모드: 직접 정한 만날 곳이 서버 값보다 먼저예요.
        val j = demoMeet?.takeIf { DemoSettings.scripted && !jIn.ended }?.let { jIn.copy(meetPlace = it) } ?: jIn
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
        maybeFindPlace(j)
        maybeWarmCabin(j)
        maybeFindStopSpot(j)
        if (j.status in DRIVING_STATUSES) {
            startStatusReports(j.id)
            watchMeetPlace(j)
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
        foundPlaces.clear()
        naviStops.clear()
        demoMeet = null
        lookedUp = null
        warmedFor = null
        stopSpotFor = null
        finderAnnounced = null
        if (status == "cancelled") expectAnswer = false
        live.update {
            it.copy(
                journey = null,
                coordination = null,
                passenger = null,
                // 취소되면 대기 중이던 질문 말풍선도 내려요(첫 화면에 남지 않게).
                voice = if (status == "cancelled") null else it.voice,
                finished = if (status == "completed") (ended ?: cur) else null,
                result = if (status == "cancelled") ActionResult(ResultState.SUCCESS, "픽업을 취소했어요", "위치 공유를 끝냈어요") else it.result,
            )
        }
        publish()
        // 첫 화면으로 돌아가니 연락처를 새로 받아요(최근 통화 순서·시연 탑승자가 바뀌었을 수 있어요).
        viewModelScope.launch { loadContacts() }
    }

    private fun onProposal(p: Proposal) {
        if (!forDriver(p)) return
        // 지도에서 만날 곳(정문·정차할 곳)을 이미 정했으면 '어디서/몇 번 출구에서 만날까요?'는 답이 된 질문이라 닫아요.
        if (p.status == "pending" && p.kind == "ask" && p.fieldKey in setOf("place", "exit") && foundPlaces.isNotEmpty() && live.value.journey?.meetPlace != null) {
            p.options.firstOrNull { it.id == "later" }?.let { later ->
                log("이미 지도에서 정한 만날 곳이 있어 질문을 닫아요: ${p.title}")
                viewModelScope.launch { runCatching { server.decide(p.id, later.id, viaVoice = false, baseVersion = null) }.onSuccess(::onProposal) }
                return
            }
        }
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
                // 경유지(stop)·대체 경로(route)도 근거·바뀌는 값·선택지가 있는 변경 카드예요(문구는 종류별로 달라요).
                "change", "stop", "route" -> Scene.CHANGE
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
        val userId = contact.userId ?: return
        if (contact.demo) demoUserIds.add(userId)
        viewModelScope.launch {
            live.update { it.copy(busy = true, result = null) }
            try {
                val call = server.startCall(userId, analysis = true)
                if (contact.demo) demoCallId = call.id
                onCall(call)
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

    fun toggleMute() {
        live.update { it.copy(muted = !it.muted) }
        callAudio.setMuted(live.value.muted)
    }

    private fun callAction(failure: String, block: suspend (CallInfo) -> CallInfo) {
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
        choose(live.value.proposal ?: return, option, viaVoice)
    }

    /**
     * 화면에서 누른 버튼은 그 버튼이 그려진 제안에 답합니다. 지금 맨 위 제안으로 답하면, 누르는 순간
     * 새 제안이 들어왔을 때 엉뚱한 제안에 답이 가요(예: '아니요'가 막 들어온 변경 제안의 답이 됨).
     */
    fun choose(p: Proposal, option: ProposalOption, viaVoice: Boolean = false) {
        if (p.options.none { it.id == option.id }) return
        // 만날 곳·경유지처럼 탑승자가 정할 일은 운전자가 대신 고르지 않습니다(서버도 403으로 막아요).
        if (!p.decidedByDriver) {
            log("${p.kind}: 탑승자가 결정할 제안이라 운전자 답은 보내지 않아요")
            return
        }
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
        val j = live.value.journey ?: return
        expectAnswer = false
        voice.stopAll()
        viewModelScope.launch {
            try {
                val done = server.complete(j.id)
                onJourneyEnded(done.id, done.status, done)
                val name = done.passengerCallName
                say(if (name != null) "${name}이 탔어요. 좋은 시간 보내세요!" else "픽업을 마쳤어요. 좋은 시간 보내세요!", question = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("픽업 완료를 보내지 못했어요", e)
            }
        }
    }

    /** 주행 중 '픽업 취소'. 도착 전에는 여정을 끝낼 방법이 없어서(탔어요는 도착 화면에만 있어요) 처음으로 돌아가는 길이에요. */
    fun cancelJourney() {
        val j = live.value.journey ?: return
        expectAnswer = false
        voice.stopAll()
        viewModelScope.launch {
            try {
                server.cancel(j.id)
                onJourneyEnded(j.id, "cancelled")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("픽업을 취소하지 못했어요", e)
            }
        }
    }

    fun notYet() {
        expectAnswer = false
        say("알겠어요. 천천히 기다릴게요.", question = false)
    }

    /** 완료 화면이나 정리 중 화면에서 처음으로. */
    fun restart() {
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
            is CarCommand.Reroute -> runNavi(x, c.place ?: live.value.journey?.meetPlace ?: Place("목적지", Double.NaN, Double.NaN), stop = false, rerouteOption = c.option ?: "")
            is CarCommand.VehicleOp -> {
                showExec(x.id, ExecState.PENDING, c.label)
                log("Vehicle ${c.op} ${c.args.optString("area")}")
                viewModelScope.launch {
                    val r = vehicle.execute(c.op, c.args)
                    withContext(Dispatchers.Main) {
                        finishVehicle(x, r, c.done ?: "${c.label}을 바꿨어요", "${c.label} 설정이 적용되지 않았어요", "${c.label}은 이 차에서 지원하지 않아 직접 바꿔 주세요")
                    }
                }
            }
            is CarCommand.Unsupported -> {
                showExec(x.id, ExecState.FAILED, "이 앱이 모르는 명령이에요 (${c.target} ${c.op})")
                report(x, "failed", "unsupported command: ${c.target}.${c.op}")
            }
        }
    }

    /** [rerouteOption]이 null이 아니면 대체 경로: 목적지·경유지는 그대로 두고 재탐색해요(빈 문자열이면 지금 옵션으로). */
    private fun runNavi(x: Execution, place: Place, stop: Boolean, rerouteOption: String? = null) {
        val reroute = rerouteOption != null
        val ok = when {
            stop -> navi.addStop(place)
            reroute -> navi.reroute(rerouteOption?.ifBlank { null })
            else -> navi.routeTo(place)
        }
        log(
            when {
                stop -> "NaviHelper.addWaypoint: ${place.name}"
                reroute -> rerouteOption?.ifBlank { null }?.let { "NaviHelper.changeRouteOption: $it" } ?: "NaviHelper.requestReRoute"
                else -> "NaviHelper.requestRoute: ${place.name}"
            },
        )
        if (!ok) {
            showExec(x.id, ExecState.FAILED, if (navi.connected) "내비게이션에 요청하지 못했어요" else "내비와 아직 연결되지 않았어요")
            report(x, "failed", if (navi.connected) "request rejected" else "navi not connected")
            return
        }
        showExec(x.id, ExecState.PENDING, "내비게이션 응답을 기다리는 중")
        pendingNavi = PendingNavi(x.id, stop, place, reroute = reroute)
        naviTimeout?.cancel()
        // 재탐색은 응답이 없으면 경유지를 넣은 경로 요청으로 한 번 더 시도하니 그만큼 더 기다려요.
        val timeoutMs = if (reroute) NAVI_TIMEOUT_MS + REROUTE_FALLBACK_MS else NAVI_TIMEOUT_MS
        naviTimeout = viewModelScope.launch {
            if (reroute) {
                delay(REROUTE_FALLBACK_MS)
                val p = pendingNavi
                if (p?.executionId == x.id && !p.place.latitude.isNaN()) {
                    val stops = naviStops.toList()
                    log("재탐색 응답이 없어 경유지 ${stops.size}곳을 넣은 경로로 다시 요청해요(NaviHelper.requestRouteWithWaypoints)")
                    pendingNavi = p.copy(via = "requestRouteWithWaypoints")
                    if (!navi.routeWithStops(p.place, stops)) log("경로 다시 요청 실패")
                }
                delay(timeoutMs - REROUTE_FALLBACK_MS)
            } else {
                delay(timeoutMs)
            }
            val p = pendingNavi
            if (p?.executionId == x.id) {
                pendingNavi = p.copy(timedOut = true)
                showExec(x.id, ExecState.UNKNOWN, "내비게이션 응답을 확인하지 못했어요")
                report(x, "unknown", "no navi callback in ${timeoutMs / 1000}s")
                delay(NAVI_LATE_MS)
                if (pendingNavi?.executionId == x.id) pendingNavi = null
            }
        }
    }

    /** [callback]은 확인에 쓴 SDK 콜백 이름(서버 실행 결과 detail에 남겨요). */
    private fun naviConfirmed(callback: String) {
        val p = pendingNavi ?: return
        pendingNavi = null
        naviTimeout?.cancel()
        if (p.timedOut) log("내비 응답이 늦게 왔어요: 성공으로 다시 보고해요")
        when {
            p.stop -> naviStops.add(0, p.place) // addWaypoint(FIRST)는 맨 앞에 들어가요
            !p.reroute -> naviStops.clear() // 새 목적지로 경로를 잡으면 경유지가 없어져요
        }
        val n = live.value.navi
        val observed = JSONObject().putOpt("remain_s", n.remainSeconds).putOpt("remain_m", n.remainMeters)
        showExec(
            p.executionId,
            ExecState.SUCCESS,
            when {
                p.stop -> "${p.place.name}을 경유지로 넣었어요"
                p.reroute -> "빠른 길로 다시 찾았어요 · 경유지는 그대로예요"
                else -> "${p.place.name}로 길안내를 시작했어요"
            },
        )
        report(p.executionId, "succeeded", p.via?.let { "$it → $callback" } ?: callback, observed)
        // 길안내가 시작되면 진짜 내비를 주 화면으로 둡니다. 오디는 내비 위에 떠서 필요할 때만 펼쳐요.
        if (DemoSettings.keepAudeoInFront) log("시연 녹화: 내비를 앞으로 띄우지 않고 Audeo 지도를 둬요") else showNavi()
    }

    /** 내비 화면으로 돌아가기(운전 중 주 화면). 오디는 그 위에 떠 있어요. */
    fun showNavi() {
        if (!navi.showScreen()) log("내비 화면을 띄우지 못했어요")
    }

    /** 다른 앱 위에 그리기 권한이 없어 내비 위에 오디를 띄우지 못할 때. */
    fun onOverlayDenied() = log("다른 앱 위에 표시 권한이 없어 내비 위에 오디를 띄우지 못해요")

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
            e is GearNotParkException -> {
                showExec(x.id, ExecState.FAILED, "차가 P단일 때만 문을 열어요")
                report(x, "failed", "GEAR_NOT_PARK")
            }
            e is VehicleTimeoutException -> {
                showExec(x.id, ExecState.UNKNOWN, "차량 응답을 확인하지 못했어요")
                report(x, "unknown", "no vehicle callback")
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

    private var watchedArrival: String? = null

    /** FusedLocation 근접 알림: 만날 곳 300m 안에 들어오면 바로 상태를 보내 서버가 '곧 도착'을 띄우게 합니다. */
    private fun watchMeetPlace(j: Journey) {
        val place = j.meetPlace ?: return
        val key = "${j.id}:${place.latitude},${place.longitude}"
        if (watchedArrival == key) return
        watchedArrival = key
        location.watchArrival(
            place,
            ARRIVAL_RADIUS_M,
            onEnter = {
                viewModelScope.launch {
                    log("FusedLocation 근접 알림: ${place.name} ${ARRIVAL_RADIUS_M.toInt()}m 안")
                    reportStatus(j.id, trigger = "proximity_enter")
                }
            },
            onError = ::log,
        )
    }

    private fun stopStatusReports() {
        watchedArrival = null
        location.clearArrival()
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

    private suspend fun reportStatus(journeyId: String, trigger: String? = null) {
        val n = live.value.navi
        val body = JSONObject()
            .put("client_seq", nextStatusSeq())
            .put("observed_at", Instant.now().toString())
        // Vehicle SDK로 읽은 차 상태(실내 온도·공조·열선·창문·문·기어·착석·벨트). 서버가 상황 규칙에 씁니다.
        runCatching { vehicle.state() }.getOrNull()?.takeIf { it.length() > 0 }?.let { body.put("vehicle", it) }
        trigger?.let { body.put("extra", JSONObject().put("trigger", it)) }
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

    // ---------- 통화 음성(WebRTC)·받아쓰기 ----------

    private val loudAt = LongArray(2)
    private val talkingNow = MutableStateFlow(emptySet<String>())

    /** 통화 중 지금 말하고 있는 사람("me", "peer"). 통화 카드의 초록 고리에 씁니다. 화면 상태와 따로 둬서 자주 바뀌어도 가볍습니다. */
    val talking: StateFlow<Set<String>> = talkingNow.asStateFlow()

    /** 10ms 음성 조각의 크기를 재서 말하는 중인지 정합니다(0.4초 동안 이어 봅니다). */
    private fun hearLevel(channel: Int, pcm: ByteArray) {
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toInt()
            sum += v.toDouble() * v
            n++
            i += 8
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (n > 0 && kotlin.math.sqrt(sum / n) > 900.0) loudAt[channel] = now
        val next = buildSet {
            if (now - loudAt[0] < 400) add("me")
            if (now - loudAt[1] < 400) add("peer")
        }
        if (next != talkingNow.value) talkingNow.value = next
    }

    private val callAudio = CallAudio(app, object : CallAudio.Listener {
        override fun onLocalSignal(kind: String, payload: JSONObject) {
            val id = live.value.call?.id ?: return
            viewModelScope.launch {
                runCatching { server.signal(id, kind, payload) }.onFailure { log("통화 신호 전송 실패($kind): ${it.message}") }
            }
        }

        override fun onConnectionState(state: String) {
            viewModelScope.launch(Dispatchers.Main) {
                log("통화 음성 연결: $state")
                live.update { it.copy(callAudioState = state) }
            }
        }

        override fun onLocalAudio(pcm: ByteArray, sampleRate: Int, channels: Int) {
            if (!live.value.muted) hearLevel(0, pcm)
            transcriber.feed("tx", pcm, sampleRate, channels)
        }

        override fun onRemoteAudio(pcm: ByteArray, sampleRate: Int, channels: Int) {
            hearLevel(1, pcm)
            transcriber.feed("rx", pcm, sampleRate, channels)
        }
        override fun onLog(message: String) {
            viewModelScope.launch(Dispatchers.Main) { log(message) }
        }

        override fun onPeerMessage(message: JSONObject) {
            if (message.optString("t") != "loc" || !message.has("lat") || !message.has("lng")) return
            val loc = PeerLocation(
                message.optDouble("lat"),
                message.optDouble("lng"),
                message.optDouble("acc").takeUnless { it.isNaN() },
                System.currentTimeMillis(),
            )
            viewModelScope.launch(Dispatchers.Main) { onPassengerLocation(loc) }
        }
    })

    // ---------- 탑승자 위치(통화 중 데이터 채널) ----------

    private var paxLoggedAt = 0L

    private fun onPassengerLocation(loc: PeerLocation) {
        val first = live.value.passenger == null
        live.update { it.copy(passenger = loc) }
        val car = live.value.car
        val d = car?.let { PlaceFinder.distanceM(it, loc.point) }
        if (first || loc.atMillis - paxLoggedAt > PAX_LOG_MS) {
            paxLoggedAt = loc.atMillis
            log("탑승자 위치(데이터 채널): ${"%.5f, %.5f".format(loc.lat, loc.lng)}${d?.let { " · 차에서 ${it.roundToInt()}m" } ?: ""}")
        }
        live.value.journey?.let(::maybeFindPlace)
        checkFinder()
    }

    // ---------- 시연 시나리오 단계(debug 시연 도구가 영상 시각에 맞춰 보냄) ----------

    private fun onDemoStep(step: String, a: Map<String, String>) {
        when (step) {
            "think" -> think(a["steps"].orEmpty().split("|").filter { it.isNotBlank() })
            "say" -> say(a["text"].orEmpty(), question = false)
            "banner" -> live.update { it.copy(result = ActionResult(ResultState.SUCCESS, a["title"].orEmpty(), a["detail"])) }
            "warm" -> { warmedFor = null; warmCabin(a["heard"] ?: "‘오늘 좀 춥네’", quiet = true) }
            "finder" -> location.latest { d ->
                onCarMoved(d.latitude, d.longitude)
                val name = live.value.journey?.passengerCallName ?: live.value.call?.peerCallName ?: "탑승자"
                live.value.passenger?.let { p ->
                    val dist = live.value.car?.let { PlaceFinder.distanceM(it, p.point) }
                    log("탑승자 찾기: $name ${dist?.roundToInt() ?: "-"}m (정밀 위치 · 데이터 채널)")
                    callAudio.send(JSONObject().put("t", "note").put("text", a["note"] ?: "차가 ${dist?.let(::distanceText) ?: "가까이"} 앞에 있어요. 화살표를 따라오세요."))
                }
            }
            "place", "stop" -> {
                val name = a["name"] ?: return
                val lat = a["lat"]?.toDoubleOrNull() ?: return
                val lng = a["lng"]?.toDoubleOrNull() ?: return
                val p = Place(name, lat, lng, a["address"] ?: name)
                foundPlaces[name] = p
                log("지도 검색 결과: $name ${"%.5f, %.5f".format(lat, lng)} (시나리오)")
                demoSetPlace(p, a["meet"])
            }
            "transport" -> demoPatch("transport", a["value"].orEmpty())
        }
    }

    private fun demoJourneyId(): String? = live.value.journey?.id ?: live.value.call?.journeyId

    private fun demoPatch(key: String, value: String) {
        val id = demoJourneyId() ?: return log("시나리오: 여정이 없어 $key 를 못 넣었어요")
        viewModelScope.launch {
            runCatching {
                val cur = server.journey(id)
                onJourney(server.patchField(id, key, value, cur.version))
                log("시나리오: $key = $value")
            }.onFailure { log("시나리오 $key 실패: ${it.message}") }
        }
    }

    /** 만날 곳(+만남 시각)을 정해 서버가 계획을 만들게 해요. 두 화면에 같은 곳이 떠요. */
    /** 시나리오에서 정한 만날 곳. 서버 응답이 늦거나 실패해도 두 화면에 바로 떠요. */
    private var demoMeet: Place? = null

    private fun demoSetPlace(p: Place, meet: String?) {
        demoMeet = p
        live.update { s -> s.copy(journey = s.journey?.copy(meetPlace = p)) }
        publish()
        callAudio.send(JSONObject().put("t", "meet").put("name", p.name).put("lat", p.latitude).put("lng", p.longitude))
        val id = demoJourneyId() ?: return log("시나리오: 여정이 없어 만날 곳을 못 정했어요")
        viewModelScope.launch {
            runCatching {
                server.putSavedPlaces(bookmarks = emptyList(), recent = foundPlaces.values.toList())
                var j = server.journey(id)
                j = server.patchField(id, "place", p.name, j.version)
                log("만날 곳을 지도에서 찾은 곳으로 정했어요: ${p.name}")
                // meet="skip": 만남 시각은 비워 둬서 서버가 '몇 시쯤 나오세요?'를 묻게 해요(질문 화면 시안용).
                val at = if (meet == "skip") null else meet ?: if ("meet_time" in j.missing) LocalTime.now().plusMinutes(3).format(DateTimeFormatter.ofPattern("HH:mm")) else null
                if (at != null) j = server.patchField(id, "meet_time", at, j.version)
                onJourney(j)
                server.planNow(id)
                refreshProposals(id)
            }.onFailure { log("시나리오 만날 곳 실패: ${it.message}") }
        }
    }

    // ---------- 탑승자 찾기(가까워지면) ----------

    private var finderAnnounced: String? = null

    /** 탑승자와 300m 안이 되면 한 번 알려요(화면의 '탑승자 찾기'는 UI가 거리로 그려요). */
    private fun checkFinder() {
        if (DemoSettings.scripted) return
        val s = live.value
        val pax = s.passenger ?: return
        val car = s.car ?: return
        if (System.currentTimeMillis() - pax.atMillis > 3 * 60_000L) return
        val key = s.journey?.id ?: s.call?.id ?: return
        val d = PlaceFinder.distanceM(car, pax.point)
        if (d > FINDER_M || finderAnnounced == key) return
        finderAnnounced = key
        val name = s.journey?.passengerCallName ?: s.call?.peerCallName ?: "탑승자"
        val where = s.journey?.meetPlace?.name
        log("탑승자 찾기: $name ${d.roundToInt()}m (정밀 위치 · 데이터 채널)")
        think(listOfNotNull("$name 정밀 위치 · 차에서 ${distanceText(d)}", where?.let { "$it 근처에서 서로 찾는 중" }, "차와 휴대폰에 ‘찾기’ 화면을 띄워요"))
        callAudio.send(JSONObject().put("t", "note").put("text", "차가 ${distanceText(d)} 앞에 있어요. 화살표를 따라오세요."))
    }

    // ---------- 탑승자가 춥다고 하면 자리 데우기 ----------

    private var warmedFor: String? = null

    /**
     * 서버가 대화에서 '차 안 따뜻하게' 같은 선호를 알아들으면(통화 중에도 와요) 탑승자 자리(조수석) 열선을 켭니다.
     * 서버는 통화 중에는 선호만 기록하고 차량 준비 제안은 도착 무렵에야 보내서, 짧은 픽업에서는 늦어요.
     * 되돌리기 쉬운 편의 기능이라 바로 켜고 결과를 화면·음성으로 알려 줘요(끄려면 차 버튼으로).
     */
    private fun maybeWarmCabin(j: Journey) {
        if (DemoSettings.scripted || isDemoJourney(j)) return
        val pref = j.fields.firstOrNull { it.key == "preference" }?.value ?: return
        if (!WARM_WORDS.any { pref.contains(it) } || j.ended) return
        warmCabin("‘$pref’")
    }

    /**
     * 통화 받아쓰기에서 탑승자가 '춥네·추워'라고 하면 서버 분석(5초쯤 걸려요)을 기다리지 않고 바로 데워요.
     * 서버가 같은 선호를 알아들으면 생각 줄로 다시 확인돼요. 한 픽업(통화)에 한 번만.
     */
    private fun warmCabin(heard: String, quiet: Boolean = false) {
        val j = live.value.journey
        val key = j?.id ?: live.value.call?.id ?: return
        if (warmedFor == key || (j != null && warmedFor == live.value.call?.id)) return
        warmedFor = key
        val name = j?.passengerCallName ?: live.value.call?.peerCallName ?: "탑승자"
        val eta = j?.let(::etaMinutes)
        log("Vehicle setHvacSeatTemperature ROW_1_RIGHT 2단 · 들은 말: $heard")
        showExec("warm-$key", ExecState.PENDING, "조수석 열선 2단")
        viewModelScope.launch {
            val r = vehicle.execute("setHvacSeatTemperature", JSONObject().put("area", "ROW_1_RIGHT").put("level", 2))
            withContext(Dispatchers.Main) {
                val ok = r.isSuccess
                showExec("warm-$key", if (ok) ExecState.SUCCESS else ExecState.FAILED, if (ok) "${name} 자리(조수석) 열선을 켰어요" else "조수석 열선을 켜지 못했어요")
                log(if (ok) "Vehicle 확인: 조수석 열선 ${r.getOrNull()}단" else "Vehicle 실패: ${r.exceptionOrNull()?.message}")
                if (!quiet) think(listOfNotNull("$heard · ${name}이 춥대요", eta?.let { "${it}분 후 도착 · ${name} 자리는 조수석" }, if (ok) "조수석 열선 2단 켜기 · Pleos Vehicle" else "조수석 열선을 켜지 못했어요"))
                if (ok) {
                    if (!quiet) say("${eta?.let { "${it}분 후 도착해요. " } ?: ""}조수석 열선을 켜 둘게요.", question = false)
                    callAudio.send(JSONObject().put("t", "note").put("text", "자리를 따뜻하게 데워 두고 있어요(조수석 열선)."))
                }
            }
        }
    }

    /** 만날 곳까지 남은 분. 내비 값이 있으면 그걸, 없으면 직선거리를 시내 속도(20km/h)로 어림해요. */
    private fun etaMinutes(j: Journey): Int? {
        live.value.navi.remainSeconds?.takeIf { it > 0 }?.let { return ((it + 59) / 60) }
        val car = live.value.car ?: return null
        val dest = j.meetPlace?.let { GeoPoint(it.latitude, it.longitude) } ?: foundPlaces.values.lastOrNull()?.let { GeoPoint(it.latitude, it.longitude) } ?: return null
        val m = PlaceFinder.distanceM(car, dest) * 1.3
        return maxOf(1, kotlin.math.ceil(m / (20_000.0 / 60)).toInt())
    }

    // ---------- 기차역 픽업: 도착 시각에 맞춰 잠깐 세울 곳 ----------

    private var stopSpotFor: String? = null

    /**
     * 서버가 열차와 도착 시각을 알아내고 만날 곳이 역이면, 역 근처에서 잠깐 정차할 곳(택시 하차장·승하차장)을
     * 지도에서 찾아 만날 곳으로 정합니다. 차가 열차보다 먼저 닿으면 오래 서 있을 수 없는 곳이라 하차장을 골라요.
     */
    private fun maybeFindStopSpot(j: Journey) {
        // 시연 여정은 서버가 승하차장 변경을 제안으로 물어봐요. 앱이 먼저 만날 곳을 바꾸면 그 제안이 어긋나요.
        if (DemoSettings.scripted || isDemoJourney(j)) return
        val ride = j.fields.firstOrNull { it.key == "transport" && it.code != null } ?: return
        val station = j.meetPlace ?: return
        if (!station.name.contains("역") || station.name.contains("하차") || j.ended) return
        if (stopSpotFor == j.id) return
        stopSpotFor = j.id
        val name = j.passengerCallName ?: "탑승자"
        val arrive = ride.value?.substringAfter("·", "")?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            log("지도 검색(OpenStreetMap): ${station.name} 근처 정차할 곳")
            val spot = withContext(Dispatchers.IO) { placeFinder.findStopSpot(GeoPoint(station.latitude, station.longitude)) }
            val eta = etaMinutes(j)
            if (spot == null) {
                think(listOfNotNull("${ride.value}", "${station.name} 근처 정차할 곳 찾기 · OpenStreetMap", "찾지 못했어요 · ${station.name}으로 갈게요"))
                return@launch
            }
            val label = "${station.name} ${spot.label}"
            log("지도 검색 결과: $label ${"%.5f, %.5f".format(spot.lat, spot.lng)} · 역에서 ${spot.distanceM?.roundToInt()}m")
            think(
                listOfNotNull(
                    "${ride.code?.let { prettyTrain(it) } ?: ride.value} · ${arrive ?: ""}".trim(' ', '·'),
                    eta?.let { "차는 ${it}분 후 도착 · 오래 서 있을 수 없어요" },
                    "도착 시각에 맞춰 정차할 곳 찾기 · OpenStreetMap",
                    "$label · 여기 잠깐 세우면 돼요",
                ),
            )
            val place = Place(label, spot.lat, spot.lng, spot.label)
            foundPlaces[label] = place
            say("${prettyTrain(ride.code ?: "")}${arrive?.let { ", $it" } ?: ""}. ${label}에 세우면 돼요.", question = false)
            callAudio.send(JSONObject().put("t", "note").put("text", "${label}에서 기다릴게요."))
            try {
                server.putSavedPlaces(bookmarks = emptyList(), recent = foundPlaces.values.toList())
                val cur = server.journey(j.id)
                onJourney(server.patchField(j.id, "place", label, cur.version))
                server.planNow(j.id)
                log("만날 곳을 정차할 곳으로 정했어요: $label")
                refreshProposals(j.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("정차할 곳을 정하지 못했어요: ${e.message}")
            }
        }
    }

    /** "KTX356" → "KTX 356" */
    private fun prettyTrain(code: String) = code.replace(Regex("^([A-Za-z가-힣-]+)(\\d+)$"), "$1 $2")

    // ---------- 대화에 나온 장소를 지도에서 찾기 ----------

    private val placeFinder = PlaceFinder()
    /** 지도에서 찾은 곳(들은 이름 → 좌표). 서버에 저장 장소로 올려서 서버가 이름으로 좌표를 붙이게 해요. */
    private val foundPlaces = LinkedHashMap<String, Place>()
    private var placeLookup: Job? = null
    private var lookedUp: String? = null
    private var confirming = false

    /**
     * 서버가 대화에서 장소 이름은 들었는데 좌표를 모를 때(처음 듣는 곳) 지도에서 찾아 봅니다.
     * 탑승자 위치가 있으면 그 근처를, 없으면 차 근처를 먼저 찾아요. 찾으면 서버에 저장 장소로 알려 주고,
     * 통화가 끝났는데도 좌표가 없으면 운전자 쪽 '수정'처럼 장소를 다시 정해 계획을 만들게 합니다.
     */
    private fun maybeFindPlace(j: Journey) {
        if (DemoSettings.scripted || isDemoJourney(j)) return
        val name = j.heardPlace ?: return
        if (j.ended || !j.placeUnlocated) return
        (foundPlaces[name] ?: foundPlaces.values.firstOrNull { it.name == name })?.let {
            confirmPlace(j.id, it.name)
            return
        }
        // 서버가 아직 모를 때 넣는 자리표시('대화 중 (장소 미확인)', '미정')는 찾지 않아요.
        if (PLACEHOLDER.containsMatchIn(name)) return
        val key = "${j.id}|$name"
        if (lookedUp == key) return
        // 더 새 장소 이름이 들어오면 하던 검색은 그만두고 새로 찾아요.
        placeLookup?.cancel()
        val pax = live.value.passenger?.takeIf { System.currentTimeMillis() - it.atMillis < PAX_FRESH_MS }
        val center = pax?.point ?: live.value.car
        // 탑승자 위치가 곧 올 수 있으니(통화가 막 이어진 참) 잠깐 기다렸다가 찾습니다.
        if (pax == null && live.value.call?.status == CallStatus.ACTIVE && live.value.callSeconds < PAX_WAIT_S) return
        lookedUp = key
        val nearWho = when {
            pax != null -> "${j.passengerCallName ?: "탑승자"} 위치"
            center != null -> "차 위치"
            else -> null
        }
        placeLookup = viewModelScope.launch {
            log("지도 검색(OpenStreetMap): ‘$name’${nearWho?.let { " · $it 근처" } ?: ""}")
            val hit = withContext(Dispatchers.IO) { placeFinder.find(name, center) }
            if (hit == null) {
                log("지도에서 ‘$name’을 찾지 못했어요")
                think(listOf("지도에서 ‘$name’ 찾기 · OpenStreetMap", "찾지 못했어요 · 운전자에게 물어볼게요"))
                return@launch
            }
            // 서버가 들은 이름('정문')보다 지도 이름('은마아파트 정문')이 알아보기 쉬워서 그 이름으로 정해요.
            val place = Place(hit.label, hit.lat, hit.lng, hit.label)
            foundPlaces[name] = place
            log("지도 검색 결과: ${hit.label} ${"%.5f, %.5f".format(hit.lat, hit.lng)}${hit.distanceM?.let { " · ${it.roundToInt()}m" } ?: ""}")
            think(
                listOfNotNull(
                    "지도에서 ‘$name’ 찾기 · OpenStreetMap",
                    if (hit.distanceM != null && nearWho != null) "$nearWho 근처 ${distanceText(hit.distanceM)} · ${hit.label}" else hit.label,
                    "만날 곳 좌표 확인 · 내비에 넣을 수 있어요",
                ),
            )
            try {
                server.putSavedPlaces(bookmarks = emptyList(), recent = foundPlaces.values.toList())
                log("찾은 장소를 서버 저장 장소로 올렸어요: $name")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("저장 장소 올리기 실패: ${e.message}")
                return@launch
            }
            // 통화 중이어도 바로 정해요. 그래야 차 지도와 탑승자 휴대폰에 만날 곳이 곧바로 떠요(계획 승인은 운전자가 해요).
            confirmPlace(j.id, place.name)
        }
    }

    private fun confirmPlace(journeyId: String, name: String) {
        if (confirming) return
        confirming = true
        viewModelScope.launch {
            try {
                val cur = server.journey(journeyId)
                if (!cur.placeUnlocated) return@launch
                var j2 = server.patchField(journeyId, "place", name, cur.version)
                log("만날 곳을 지도에서 찾은 곳으로 정했어요: $name")
                // 서버는 만남 시각까지 있어야 계획(지도에 띄울 만날 곳)을 만들어요. 대화에 시각이 없으면
                // 지금 데리러 가는 길이니 차가 도착하는 시각으로 정해요(운전자 쪽 수정과 같아요).
                if ("meet_time" in j2.missing) {
                    val eta = etaMinutes(j2) ?: foundPlaces.values.lastOrNull()?.let { p ->
                        live.value.car?.let { c -> maxOf(1, kotlin.math.ceil(PlaceFinder.distanceM(c, GeoPoint(p.latitude, p.longitude)) * 1.3 / 333.0).toInt()) }
                    } ?: 5
                    val at = LocalTime.now().plusMinutes(eta.toLong()).format(DateTimeFormatter.ofPattern("HH:mm"))
                    j2 = server.patchField(journeyId, "meet_time", at, j2.version)
                    log("만남 시각을 차 도착 시각으로 정했어요: $at (${eta}분 뒤)")
                }
                onJourney(j2)
                server.planNow(journeyId)
                // 장소를 정했으니 '어디서 만날까요?' 질문은 닫아요.
                proposals.values.filter { it.kind == "ask" && it.fieldKey == "place" }.toList().forEach { p ->
                    val later = p.options.firstOrNull { it.id == "later" } ?: return@forEach
                    runCatching { server.decide(p.id, later.id, viaVoice = false, baseVersion = null) }.onSuccess(::onProposal)
                }
                refreshProposals(journeyId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("만날 곳을 정하지 못했어요: ${e.message}")
            } finally {
                confirming = false
            }
        }
    }

    private fun distanceText(m: Double) = if (m < 1000) "${(m / 10).roundToInt() * 10}m" else "%.1fkm".format(m / 1000)

    private var localThinkingId = -1L

    /** 앱이 직접 한 판단(지도 검색 등)도 서버 생각 줄과 같은 자리에 차례로 보여 줘요. */
    private fun think(steps: List<String>) {
        onThinking(ServerEvent.Thinking(localThinkingId--, null, steps, emptyList()))
    }

    private val transcriber = CallTranscriber(
        voice,
        onText = { channel, text -> viewModelScope.launch(Dispatchers.Main) { onTranscribed(channel, text) } },
        onLog = { m -> viewModelScope.launch(Dispatchers.Main) { log(m) } },
    )
    private var utteranceSeq = 0L
    private var iceServers: List<com.audeo.app.server.IceServerInfo>? = null
    private val earlySignals = ArrayList<Pair<String, JSONObject>>()

    /** 통화가 연결되면 음성을 잇습니다. 건 쪽이 offer를 보냅니다. */
    private fun startCallAudio(call: CallInfo) {
        if (call.id == "preview") return
        utteranceSeq = 0
        viewModelScope.launch {
            val ice = iceServers ?: runCatching { server.rtcConfig() }.getOrElse {
                log("ICE 설정을 못 받아 기본 STUN을 써요: ${it.message}")
                listOf(com.audeo.app.server.IceServerInfo(listOf("stun:stun.l.google.com:19302"), null, null))
            }.also { iceServers = it }
            if (live.value.call?.id != call.id) return@launch
            callAudio.start(ice, caller = call.outgoing)
            callAudio.setMuted(live.value.muted)
            earlySignals.forEach { (kind, payload) -> callAudio.onRemoteSignal(kind, payload) }
            earlySignals.clear()
        }
    }

    private fun stopCallAudio() {
        earlySignals.clear()
        callAudio.close()
        transcriber.stop()
        talkingNow.value = emptySet()
        live.update { it.copy(callAudioState = null) }
    }

    /** 두 사람 모두 AI 분석에 동의했을 때만 받아씁니다. 받아쓰기를 못 쓰면 테스트 대본으로 대신해요. */
    private fun updateTranscription(call: CallInfo) {
        when {
            !call.analysisOn -> transcriber.stop()
            transcriber.ready -> transcriber.start()
            else -> log("받아쓰기 엔진이 준비되지 않아 이번 통화는 받아쓰지 않아요")
        }
    }

    private var demoTalkJob: Job? = null

    /**
     * 시연 통화의 대본 한 줄을 통화 카드 자막으로 띄워요(받아쓰기 결과와 같은 모양). 서버가 이미 대화로 넣었으니
     * utterances로 다시 보내지 않고, 춥다는 말에 열선을 바로 켜지도 않아요(서버가 제안으로 물어봐요).
     */
    private fun onDemoTranscript(e: ServerEvent.CallTranscript) {
        val call = live.value.call
        if (call == null || call.id != e.callId || e.text.isBlank()) return log("시연 자막(다른 통화): ${e.text}")
        val passenger = e.role != "driver"
        val who = if (passenger) "${call.peerCallName}이 말하는 중" else "내가 말하는 중"
        live.update { it.copy(voice = VoiceUi(VoiceMode.LISTENING, who = who, caption = "“${e.text}”")) }
        log("시연 자막(${e.role ?: "-"}): ${e.text}")
        // 통화 카드에서 말하는 사람 고리를 잠깐 켜요(음성이 없으니 자막이 올 때 기준).
        talkingNow.value = setOf(if (passenger) "peer" else "me")
        demoTalkJob?.cancel()
        demoTalkJob = viewModelScope.launch {
            delay(DEMO_TALK_MS)
            talkingNow.value = emptySet()
        }
    }

    private fun onTranscribed(channel: String, text: String) {
        val call = live.value.call ?: return
        if (!call.analysisOn || call.demo) return
        val who = if (channel == "rx") "${call.peerCallName}이 말하는 중" else "내가 말하는 중"
        live.update { it.copy(voice = VoiceUi(VoiceMode.LISTENING, who = who, caption = "“$text”")) }
        log("받아씀($channel): $text")
        if (!DemoSettings.scripted && channel == "rx" && COLD_WORDS.any { text.contains(it) }) {
            // 받아쓰기가 앞말과 붙어 올 수 있어요("어 나왔어 오늘 좀 춥네"). 추위 말이 든 끝 세 낱말만 보여 줘요.
            val words = text.trim().trimEnd('.', '!', '?').split(Regex("\\s+"))
            val i = words.indexOfLast { w -> COLD_WORDS.any { w.contains(it) } }.coerceAtLeast(0)
            warmCabin("‘${words.subList(maxOf(0, i - 2), i + 1).joinToString(" ")}’")
        }
        val seq = ++utteranceSeq
        viewModelScope.launch {
            runCatching { server.utterances(call.id, listOf(Utterance(seq, channel, text))) }
                .onFailure { log("받아쓰기 전송 실패: ${it.message}") }
        }
    }

    // ---------- 통화 시간 ----------

    private fun startCallTimer() {
        callTimer?.cancel()
        live.update { it.copy(callSeconds = 0) }
        callTimer = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                live.update { it.copy(callSeconds = it.callSeconds + 1) }
                if (live.value.callSeconds == PAX_WAIT_S) live.value.journey?.let(::maybeFindPlace)
            }
        }
    }

    private fun stopCallTimer() {
        callTimer?.cancel()
        callTimer = null
    }

    // ---------- 개발 도구 ----------

    fun toggleNight() = live.update {
        it.copy(nightOverride = when (it.nightOverride) {
            null -> true
            true -> false
            false -> null
        })
    }

    fun toggleStatus() = live.update { it.copy(showStatus = !it.showStatus) }

    // ---------- 위치·마이크 ----------

    fun onMicDenied() = log("마이크 권한 없음: 통화 음성을 보낼 수 없어요")

    /** 데모 메뉴의 '받아쓰기 시험': debug 빌드에 넣은 한국어 음성(16kHz PCM)을 Pleos STT 온디바이스로 받아써 봅니다. */
    fun testTranscription() {
        val pcm = runCatching { getApplication<Application>().assets.open("stt_selftest.pcm").use { it.readBytes() } }.getOrNull()
            ?: return log("시험 음성이 없어요(debug 빌드에만 있어요)")
        if (!transcriber.ready) return log("받아쓰기 엔진이 준비되지 않았어요")
        log("받아쓰기 시험 중… (${pcm.size / 32}ms)")
        viewModelScope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val text = transcriber.recognizeBlocking(pcm)
            val ms = System.currentTimeMillis() - started
            launch(Dispatchers.Main) { log(if (text != null) "받아쓰기 시험 결과(${ms}ms): $text" else "받아쓰기 시험: 결과 없음") }
        }
    }

    fun onLocationPermission(granted: Boolean) {
        if (!granted) {
            log("위치 권한 없음: 서버에는 내비 남은 거리만 보내요")
            return
        }
        location.start(onLocation = { d -> onCarMoved(d.latitude, d.longitude) }, onError = ::log)
        // 지도 위 차 표시를 따라가도록 몇 초마다 위치를 다시 읽습니다.
        carWatch?.cancel()
        carWatch = viewModelScope.launch {
            while (isActive) {
                delay(CAR_POLL_MS)
                location.latest { d -> onCarMoved(d.latitude, d.longitude) }
                // 차가 서 있어도 통화 중에는 차 위치를 탑승자 휴대폰에 계속 보내요(탑승자 나침반).
                lastLocation?.let { (la, ln) -> if (callAudio.active) callAudio.send(JSONObject().put("t", "loc").put("lat", la).put("lng", ln).put("at", System.currentTimeMillis())) }
            }
        }
    }

    private var carWatch: Job? = null

    private fun onCarMoved(lat: Double, lng: Double) {
        lastLocation = lat to lng
        if (callAudio.active) callAudio.send(JSONObject().put("t", "loc").put("lat", lat).put("lng", lng).put("at", System.currentTimeMillis()))
        checkFinder()
        val p = GeoPoint(lat, lng)
        if (live.value.car == p) return
        live.update { it.copy(car = p, location = "%.5f, %.5f".format(lat, lng)) }
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
                if (pendingNavi?.stop == false) naviConfirmed("onRouteStarted")
            }
            NaviEvent.RoutingCompleted -> {
                log("onRoutingCompleted")
                if (pendingNavi?.stop == false) naviConfirmed("onRoutingCompleted")
            }
            NaviEvent.WaypointChanged -> {
                log("onWaypointChanged")
                if (pendingNavi?.stop == true) naviConfirmed("onWaypointChanged")
            }
            NaviEvent.ReRouteEnded -> {
                log("onReRouteEnded")
                if (pendingNavi?.reroute == true) naviConfirmed("onReRouteEnded")
            }
            is NaviEvent.RouteOptionChanged -> {
                log("onRouteOptionChanged: ${event.option}")
                if (pendingNavi?.reroute == true) naviConfirmed("onRouteOptionChanged")
            }
            is NaviEvent.WaypointArrived -> {
                log("onWayPointArrived: ${event.name ?: "-"}")
                // 지난 경유지는 빼요(대체 경로를 다시 요청할 때 다시 넣지 않게).
                (naviStops.firstOrNull { it.name == event.name } ?: naviStops.firstOrNull())?.let(naviStops::remove)
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
        log("TTS 말하기: $text")
        val v = VoiceUi(VoiceMode.SPEAKING, caption = "“$text”", hint = if (question) "말씀하시면 바로 멈춰요" else null)
        live.update { it.copy(voice = v) }
        // TTS 끝 알림이 안 오는 경우(다른 앱 소리에 밀림 등)를 대비해 말할 시간이 지나면 표시를 내립니다.
        speakingJob?.cancel()
        speakingJob = viewModelScope.launch {
            delay(3_000L + text.length * 180L)
            if (live.value.voice == v) live.update { it.copy(voice = null) }
        }
        if (question && live.value.call?.status != CallStatus.ACTIVE) speakThenListen(text) else {
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

    // 초기화는 맨 끝에 둡니다. 위의 프로퍼티(통화 음성, 받아쓰기 등)가 모두 만들어진 뒤에 실행돼야 해요.
    init {
        DemoSettings.onStep = { step, args -> onDemoStep(step, args) }
        navi.connect { event -> viewModelScope.launch(Dispatchers.Main) { onNaviEvent(event) } }
        val vehicleError = vehicle.start()
        log(if (vehicleError == null) "Vehicle 준비됨" else "Vehicle 초기화 실패: $vehicleError")
        voice.start(this)
        connectServer()
    }

    override fun onCleared() {
        callAudio.release()
        transcriber.stop()
        connectJob?.cancel()
        eventJob?.cancel()
        statusJob?.cancel()
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
        const val ARRIVAL_RADIUS_M = 300f
        const val THINKING_BASE_MS = 1_400L
        const val THINKING_STEP_MS = 700L
        const val CAR_POLL_MS = 3_000L
        /** 탑승자와 이 거리(m) 안이면 '탑승자 찾기'. */
        const val FINDER_M = 300.0
        val WARM_WORDS = listOf("따뜻", "춥", "추워", "추운", "히터", "열선")
        /** 탑승자 말에서 바로 알아듣는 추위 표현(받아쓰기 그대로). */
        val PLACEHOLDER = Regex("미확인|미정|모름|알 수 없|^대화 중")
        val COLD_WORDS = listOf("춥네", "추워", "춥다", "춥대", "너무 춥")
        /** 탑승자 위치를 로그에 남기는 간격(기능 표시용). */
        const val PAX_LOG_MS = 20_000L
        /** 이보다 오래된 탑승자 위치는 장소 찾기 기준으로 쓰지 않아요. */
        const val PAX_FRESH_MS = 10 * 60_000L
        /** 통화가 이어지고 탑승자 위치를 이만큼(초) 기다린 뒤에는 차 위치로 찾아요. */
        const val PAX_WAIT_S = 8
        /** 시연 자막 한 줄이 오면 말하는 사람 고리를 켜 두는 시간. */
        const val DEMO_TALK_MS = 1_800L
        /** 통화가 끝나고 서버가 정리할 시간을 준 뒤 장소를 다시 확인해요. */
        const val PLACE_AFTER_CALL_MS = 6_000L
        /** 내비 앱이 경로를 계산하고 안내 시작 카운트다운(약 5초)까지 끝내는 데 8~10초가 걸립니다. */
        const val NAVI_TIMEOUT_MS = 20_000L
        const val NAVI_LATE_MS = 40_000L
        /** 재탐색(requestReRoute) 콜백을 이만큼 기다리고, 없으면 경유지를 넣은 경로로 다시 요청해요. */
        const val REROUTE_FALLBACK_MS = 8_000L
        const val VEHICLE_TIMEOUT_MS = 8_000L
        const val BANNER_MS = 10_000L
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val DRIVING_STATUSES = setOf("planned", "active")

        /** 한 번에 하나만 보여 줍니다. 답이 필요한 질문이 먼저입니다. */
        val PROPOSAL_ORDER = listOf("ask", "change", "stop", "route", "vehicle_action", "plan")
    }
}
