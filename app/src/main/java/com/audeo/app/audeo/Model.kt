package com.audeo.app.audeo

/** 화면 흐름. 디자인 캔버스의 0-1 ~ 7번 화면과 같은 순서입니다. */
enum class Scene(val label: String) {
    START("시작"),
    DIAL("발신"),
    INCOMING("수신"),
    CALL("통화"),
    ASK("질문"),
    PLAN("계획"),
    DRIVE("주행"),
    CHANGE("변경"),
    VEHICLE("차량"),
    ARRIVE("도착"),
    DONE("완료"),
}

/** 값 하나가 어디서 왔는지. */
enum class SourceKind(val label: String) {
    CONVERSATION("대화"),
    LOOKUP("조회"),
    /** 실제 API가 아닌 시연 시간표·예시 값. 조회처럼 보이지 않게 따로 표시해요. */
    DEMO("시연 데이터"),
    ESTIMATE("추정"),
    CONFIRM("확인 필요"),
    USER("직접 입력"),
}

/** 픽업 여정 전체의 상태. */
enum class PlanStatus(val label: String) {
    DRAFT("초안"),
    CONFIRMED("확정"),
    ACTIVE("진행 중"),
    DELAY("지연"),
    DONE("완료"),
    FAILED("실패"),
}

/** 다이얼로그 톤: 안내·계획은 BRAND, 확인·지연·변경은 CAUTION. kicker 글자 색에만 쓰입니다. */
enum class Tone { BRAND, CAUTION, NEUTRAL }

data class PlanField(
    val label: String,
    val value: String? = null,
    /** 편명이나 열차번호. 고정폭 글꼴로 보여 줍니다. */
    val code: String? = null,
    val note: String? = null,
    val source: SourceKind? = null,
    /** 방금 대화로 바뀐 값. */
    val updated: Boolean = false,
    val placeholder: String = "아직 몰라요",
    /** 서버 필드 키(transport, place, exit, meet_time …). 미리보기 데이터에는 없습니다. */
    val key: String? = null,
)

data class Reason(val text: String, val source: SourceKind? = null, val label: String? = null)

data class ChangeRow(val label: String, val from: String, val to: String)

enum class LaneTone { VEHICLE, PASSENGER, LATE }

data class EtaLane(val who: String, val at: String, val note: String? = null, val tone: LaneTone)

enum class VoiceMode { LISTENING, SPEAKING, THINKING, IDLE }

/** 서버가 실제로 한 판단(생각 줄)과 자막에서 강조할 말. */
data class ThinkingUi(val steps: List<String>, val highlights: List<String>, val id: Long)

data class VoiceUi(
    val mode: VoiceMode,
    val who: String? = null,
    val caption: String? = null,
    val hint: String? = null,
    /** 자막에서 강조할 말(서버가 근거로 든 구절). */
    val highlights: List<String> = emptyList(),
)

enum class ResultState { SUCCESS, FAILED, PENDING }

data class ActionResult(val state: ResultState, val title: String, val detail: String? = null)

data class Contact(
    val name: String,
    val note: String,
    /** 서버 사용자 id. 미리보기 데이터에는 없습니다. */
    val userId: String? = null,
    /** 부를 때 쓰는 이름(예: 민지님). */
    val callName: String = name,
    /** 시연 탑승자(서버가 대신 받아 대본 대화를 진행). 이 사람과의 통화는 WebRTC·받아쓰기를 켜지 않아요. */
    val demo: Boolean = false,
)

data class Place(val name: String, val latitude: Double, val longitude: Double, val address: String? = null)

/** 지도에 그릴 한 점(위도, 경도). */
data class GeoPoint(val lat: Double, val lng: Double)

/** 탑승자 휴대폰이 통화 중 데이터 채널로 보낸 정밀 위치(서버를 거치지 않아요). [accuracy]는 m, [atMillis]는 받은 시각. */
data class PeerLocation(val lat: Double, val lng: Double, val accuracy: Double?, val atMillis: Long) {
    val point: GeoPoint get() = GeoPoint(lat, lng)
}

/**
 * 시연 녹화용 설정. 에뮬레이터의 Pleos 내비는 GPS를 따라오지 않고(NMEA 체크섬 오류로 버림) 지도도 비어 있어서,
 * 녹화할 때는 길안내를 시작해도 내비를 앞으로 띄우지 않고 Audeo 지도를 그대로 둡니다. debug 빌드의 시연 도구만 바꿔요.
 */
object DemoSettings {
    @Volatile var keepAudeoInFront: Boolean = false

    /** 시연 시나리오 모드: 서버 분석을 기다리지 않고 녹화 스크립트가 보낸 단계(debug 시연 도구)대로 화면을 움직여요. */
    @Volatile var scripted: Boolean = false

    /** 시연 도구 → 화면 상태(AudeoViewModel)로 단계를 넘기는 다리. 앱이 켜져 있을 때만 채워져요. */
    @Volatile var onStep: ((String, Map<String, String>) -> Unit)? = null
}

// ---------- 서버에서 오는 상태 ----------

enum class CallStatus { RINGING, ACTIVE, ENDED }

/** Audeo 통화 하나. 상대방은 이 차 기준입니다. */
data class CallInfo(
    val id: String,
    val status: CallStatus,
    /** 서버 상태 문자열(ringing, active, ended, rejected 등). */
    val rawStatus: String,
    val outgoing: Boolean,
    val peerName: String,
    val peerCallName: String,
    val analysisRequested: Boolean,
    val analysisOn: Boolean,
    /** 상대방의 AI 분석 동의: pending, granted, declined. */
    val peerConsent: String?,
    val journeyId: String?,
    val peerUserId: String? = null,
    /** 시연 탑승자와의 통화. 음성은 서버가 대본 자막(call.transcript)으로 보내요. */
    val demo: Boolean = false,
)

data class Figure(val label: String?, val value: String, val note: String?)

data class ProposalOption(val id: String, val label: String, val variant: String)

/** 서버가 운전자에게 보내는 제안·질문. 승인하기 전에는 아무것도 실행하지 않습니다. */
data class Proposal(
    val id: String,
    val journeyId: String,
    /** plan, ask, change, vehicle_action … */
    val kind: String,
    val status: String,
    val baseVersion: Int?,
    val title: String,
    val question: String?,
    val context: String?,
    val fieldKey: String?,
    val reasons: List<Reason>,
    val rows: List<ChangeRow>,
    val options: List<ProposalOption>,
    val figure: Figure?,
    val footnote: String?,
    val voicePrompt: String?,
    val audience: List<String>,
    /** 이 제안을 결정할 사람(driver, passenger). 서버가 정하고, 다른 역할이 결정하면 403이 납니다. */
    val approver: String? = null,
) {
    /** 운전자가 고르는 제안인지. 서버가 결정자를 탑승자로 정한 제안은 차에서는 지켜보기만 합니다. */
    val decidedByDriver: Boolean get() = approver == null || approver == "driver"
}

/** 픽업 여정(서버의 journey). */
data class Journey(
    val id: String,
    /** draft, planned, active, completed, cancelled */
    val status: String,
    val version: Int,
    val title: String,
    val fields: List<PlanField>,
    val summary: String?,
    val passengerCallName: String?,
    val passengerUserId: String? = null,
    val meetPlace: Place?,
    val meetAtLabel: String?,
    val waitPlace: Place?,
    /** 서버가 아직 정하지 못한 항목 키(place, meet_time …). */
    val missing: List<String> = emptyList(),
) {
    val ended: Boolean get() = status == "completed" || status == "cancelled"

    /** 대화에서 들은 장소 이름. 좌표가 아직 없으면(서버가 모르는 곳) 지도에서 찾아야 해요. */
    val heardPlace: String? get() = fields.firstOrNull { it.key == "place" }?.value?.takeIf { it.isNotBlank() }
    val placeUnlocated: Boolean get() = heardPlace != null && meetPlace == null
}

data class Arrival(val kicker: String?, val title: String, val figure: String?, val figureLabel: String?)

/** 차와 탑승자의 도착 시각 비교(서버가 계산). */
data class Coordination(
    val start: String,
    val end: String,
    val now: String?,
    val ticks: List<String>,
    val lanes: List<EtaLane>,
    val verdict: String?,
    val nextAction: String?,
    val etaLabel: String?,
    val arrival: Arrival?,
)

/** 서버가 승인된 제안을 차에서 실행해 달라고 보낸 명령. */
sealed interface CarCommand {
    data class Route(val place: Place) : CarCommand
    data class AddStop(val place: Place) : CarCommand
    /** 같은 목적지로 경로 재탐색(경유지는 그대로). [option]은 RouteOption 이름(SHORT_TIME …), 없으면 지금 옵션으로 재탐색. */
    data class Reroute(val place: Place?, val option: String?) : CarCommand
    /** 공조·시트·창문·문. [op]는 Vehicle SDK 메서드 이름 그대로(setHvacAc, setDoorPosition …), [args]는 area·on·level·position 등. */
    data class VehicleOp(val target: String, val op: String, val args: org.json.JSONObject, val label: String, val done: String?) : CarCommand
    data class Unsupported(val target: String, val op: String) : CarCommand
}

data class Execution(
    val id: String,
    val proposalId: String?,
    val target: String,
    val command: CarCommand,
    val status: String,
)

/** 지도 위 좌표(0–1, 지도 이미지 기준). */
data class MapPoint(val x: Float, val y: Float)
