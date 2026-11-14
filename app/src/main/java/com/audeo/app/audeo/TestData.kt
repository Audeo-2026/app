package com.audeo.app.audeo

/**
 * 미리보기(데모 메뉴)와 통화 테스트 대본에 쓰는 데이터.
 * 시나리오: 운전자가 인천공항 2터미널에 도착하는 김민지(KE712)를 데리러 간다.
 */
object TestData {

    const val PASSENGER = "김민지"
    const val PASSENGER_SHORT = "민지님"

    val contacts = listOf(
        Contact("김민지", "최근 통화 · 어제 저녁"),
        Contact("박서준", "자주 픽업 · 판교역"),
        Contact("이하은", "최근 통화 · 11월 9일"),
    )

    /** 픽업 지점. OpenStreetMap의 제2여객터미널 도착층 도로 좌표입니다. */
    val pickup = Place("인천공항 제2여객터미널 3번 출구", 37.4683234, 126.4332635)

    /** 기다릴 곳. 단기주차장 근처의 대략 좌표라 실제 서비스에서는 주차장 API 값으로 바꿔야 합니다. */
    val waitingArea = Place("제2여객터미널 단기주차장", 37.4706, 126.4315)

    /**
     * 통화 테스트 대본. 통화 음성(WebRTC)과 받아쓰기가 붙기 전까지, 통화가 연결되면 이 줄들을
     * 받아쓰기 결과처럼 서버(POST /calls/{id}/utterances)에 보내서 실제 AI 분석을 돌립니다.
     * channel: tx = 운전자, rx = 상대방.
     */
    data class TranscriptLine(val channel: String, val text: String)

    val callTranscript = listOf(
        TranscriptLine("tx", "오늘 몇 시에 도착해?"),
        TranscriptLine("rx", "나 오늘 인천공항으로 들어가. 대한항공 KE712, 3시 40분 도착이야."),
        TranscriptLine("tx", "2터미널이지? 내가 데리러 갈게."),
        TranscriptLine("rx", "응 2터미널. 짐 찾고 나오면 4시 10분쯤 될 거야. 밖이 좀 춥대."),
    )

    /** 통화 중 Audeo가 듣는 말과, 그 말로 채워지는 약속 초안(미리보기용). */
    data class ScriptLine(val caption: String, val fields: List<PlanField>)

    private val person = PlanField("만날 사람", value = PASSENGER, source = SourceKind.CONVERSATION)
    private val flight = PlanField("교통편", code = "KE712", value = "항공편 · 15:40 도착", source = SourceKind.LOOKUP)
    private val exitUnknown = PlanField("출구", source = SourceKind.CONFIRM)

    val callScript = listOf(
        ScriptLine(
            caption = "“나 오늘 인천공항으로 들어가”",
            fields = listOf(
                person.copy(updated = true),
                PlanField("장소", value = "인천공항", source = SourceKind.CONVERSATION, updated = true),
            ),
        ),
        ScriptLine(
            caption = "“대한항공 KE712, 3시 40분 도착이야”",
            fields = listOf(
                person,
                flight.copy(updated = true),
                PlanField("장소", value = "인천공항", source = SourceKind.CONVERSATION),
            ),
        ),
        ScriptLine(
            caption = "“2터미널로 3시 40분에 도착해”",
            fields = listOf(
                person,
                flight,
                PlanField("장소", value = "인천공항 2터미널", source = SourceKind.CONVERSATION, updated = true),
                exitUnknown,
            ),
        ),
    )

    fun fieldsWithExit(exit: String?): List<PlanField> = listOf(
        person,
        flight,
        PlanField("장소", value = "인천공항 2터미널", source = SourceKind.CONVERSATION),
        if (exit == null) exitUnknown else PlanField("출구", value = exit, source = SourceKind.CONVERSATION, updated = true),
    )

    fun planFields(exit: String?): List<PlanField> = listOf(
        flight,
        PlanField("만남 장소", value = "2터미널 ${exit ?: "출구 미정"}", source = if (exit == null) SourceKind.CONFIRM else SourceKind.CONVERSATION, updated = exit != null),
        PlanField("만남 시각", value = "16:10", note = "수하물 25분 포함", source = SourceKind.ESTIMATE),
    )

    val etaLanes = listOf(
        EtaLane("내 차", "16:06", "2.3km 남음", LaneTone.VEHICLE),
        EtaLane(PASSENGER_SHORT, "16:10", "수하물 찾는 중", LaneTone.PASSENGER),
    )

    val delayRows = listOf(
        ChangeRow("만남 시각", "16:10", "16:20"),
        ChangeRow("기다릴 곳", "출구 앞 도로변", "단기주차장 P1"),
    )

    val climateRows = listOf(
        ChangeRow("실내 온도", "21°", "24°"),
        ChangeRow("뒷좌석 열선", "꺼짐", "2단"),
    )

    /** 차량 지도(map_t2_day/night)와 같은 좌표계의 실제 도로 경로. 지도 렌더링 때 함께 계산했습니다. */
    val route = listOf(
        MapPoint(0.74f, 1.04f), MapPoint(0.7581f, 1.0286f), MapPoint(0.7733f, 1.0137f), MapPoint(0.7872f, 0.9941f),
        MapPoint(0.7975f, 0.9741f), MapPoint(0.806f, 0.9498f), MapPoint(0.8108f, 0.9272f), MapPoint(0.815f, 0.8681f),
        MapPoint(0.8049f, 0.7587f), MapPoint(0.7568f, 0.4261f), MapPoint(0.7596f, 0.3993f), MapPoint(0.7848f, 0.3386f),
        MapPoint(0.7853f, 0.3f), MapPoint(0.7788f, 0.2801f), MapPoint(0.77f, 0.2709f), MapPoint(0.7585f, 0.2681f),
        MapPoint(0.74f, 0.27f),
    )

    /** 경로를 정하기 전(통화 중)에 차량이 있는 곳. */
    val idleRoute = listOf(MapPoint(0.8049f, 0.7587f), MapPoint(0.7568f, 0.4261f))

    val passengerOnMap = MapPoint(0.8f, 0.2f)
}
