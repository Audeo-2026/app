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

data class VoiceUi(
    val mode: VoiceMode,
    val who: String? = null,
    val caption: String? = null,
    val hint: String? = null,
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
)

data class Place(val name: String, val latitude: Double, val longitude: Double)

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
)

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
    val meetPlace: Place?,
    val meetAtLabel: String?,
    val waitPlace: Place?,
) {
    val ended: Boolean get() = status == "completed" || status == "cancelled"
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
    data class CabinTemperature(val area: String?, val celsius: Float) : CarCommand
    data class SeatHeat(val area: String?, val level: Int) : CarCommand
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
