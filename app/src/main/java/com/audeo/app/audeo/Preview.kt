package com.audeo.app.audeo

/**
 * 데모 메뉴의 미리보기. [TestData]를 서버가 주는 것과 같은 모델로 바꿔서 장면 하나를 그립니다.
 * 실제 상태(서버 연결, 로그, 낮/밤)는 그대로 두고 화면 내용만 바꿉니다.
 */
object Preview {

    private const val JOURNEY = "preview"
    private const val PEER = TestData.PASSENGER_SHORT

    fun state(scene: Scene, live: AudeoUiState): AudeoUiState {
        val base = live.copy(
            scene = scene,
            previewing = true,
            contacts = TestData.contacts,
            contactsLoading = false,
            call = null,
            callSeconds = 0,
            journey = null,
            proposal = null,
            coordination = null,
            finished = null,
            voice = null,
            result = null,
            busy = false,
        )
        return when (scene) {
            Scene.START -> base
            Scene.DIAL -> base.copy(
                call = call(CallStatus.RINGING, outgoing = true, analysisOn = false, peerConsent = "pending"),
                voice = VoiceUi(VoiceMode.SPEAKING, caption = "“${PEER}께 전화하고 있어요”"),
            )
            Scene.INCOMING -> base.copy(
                call = call(CallStatus.RINGING, outgoing = false, analysisOn = false, peerConsent = "granted"),
                voice = VoiceUi(VoiceMode.SPEAKING, caption = "“$PEER 전화예요. AI와 함께 받을까요?”"),
            )
            Scene.CALL -> base.copy(
                call = call(CallStatus.ACTIVE, outgoing = true, analysisOn = true, peerConsent = "granted"),
                callSeconds = 42,
                journey = journey("draft", TestData.callScript.last().fields),
                voice = VoiceUi(VoiceMode.LISTENING, who = "${PEER}이 말하는 중", caption = TestData.callScript.last().caption),
            )
            Scene.ASK -> base.copy(
                call = call(CallStatus.ACTIVE, outgoing = true, analysisOn = true, peerConsent = "granted"),
                callSeconds = 64,
                journey = journey("draft", TestData.fieldsWithExit(null)),
                proposal = proposal(
                    kind = "ask",
                    title = "몇 번 출구에서 만날까요?",
                    context = "통화에서 출구 이야기가 없었어요. 2터미널 입국장 기준이에요.",
                    options = listOf(
                        ProposalOption("opt0", "3번 출구", "primary"),
                        ProposalOption("opt1", "5번 출구", "secondary"),
                        ProposalOption("later", "나중에", "quiet"),
                    ),
                    footnote = "답이 없으면 출구는 ‘확인 필요’로 두고 통화 후에 다시 물어요.",
                ),
                voice = VoiceUi(VoiceMode.SPEAKING, caption = "“$PEER, 몇 번 출구로 나오세요?”", hint = "말씀하시면 바로 멈춰요"),
            )
            Scene.PLAN -> base.copy(
                journey = journey("draft", TestData.planFields("3번 출구")),
                proposal = proposal(
                    kind = "plan",
                    title = "$PEER 픽업",
                    context = "통화 종료 · 인천공항 T2",
                    figure = Figure("출발 권장", "15:05", "이때 출발하면 16:06에 도착해요"),
                    options = listOf(ProposalOption("apply_navi", "내비 적용", "primary"), ProposalOption("save_only", "저장만", "quiet")),
                    footnote = "내비게이션은 승인한 뒤에만 바꿔요.",
                ),
            )
            Scene.DRIVE -> base.copy(journey = journey("active"), coordination = coordination())
            Scene.CHANGE -> base.copy(
                call = call(CallStatus.ACTIVE, outgoing = false, analysisOn = true, peerConsent = "granted"),
                callSeconds = 18,
                journey = journey("active"),
                coordination = coordination(),
                proposal = proposal(
                    kind = "change",
                    title = "${PEER}이 10분 늦는대요",
                    reasons = listOf(Reason("“수하물이 늦게 나와서 10분 늦어”", SourceKind.CONVERSATION)),
                    rows = TestData.delayRows,
                    options = listOf(ProposalOption("accept", "그렇게 할게요", "primary"), ProposalOption("keep", "기존대로", "secondary")),
                    footnote = "답이 없으면 기존 확정 계획을 유지해요.",
                ),
                voice = VoiceUi(VoiceMode.SPEAKING, caption = "“16:20으로 바꾸고 P1에서 기다릴까요?”", hint = "말씀하시면 바로 멈춰요"),
            )
            Scene.VEHICLE -> base.copy(
                journey = journey("active"),
                coordination = coordination(),
                proposal = proposal(
                    kind = "vehicle_action",
                    title = "밖에 비가 오고 8°예요",
                    reasons = listOf(
                        Reason("통화에서 “좀 추워”라고 했어요", SourceKind.CONVERSATION),
                        Reason("2터미널 16:00 예보 · 비, 8°", SourceKind.LOOKUP),
                    ),
                    rows = TestData.climateRows,
                    options = listOf(
                        ProposalOption("accept", "그렇게 할게요", "primary"),
                        ProposalOption("hvac_only", "온도만", "secondary"),
                        ProposalOption("no", "아니요", "quiet"),
                    ),
                    footnote = "이 차에서 지원하지 않는 기능은 제어하지 않고 직접 켜도록 안내해요.",
                ),
                voice = VoiceUi(VoiceMode.SPEAKING, caption = "“$PEER 타기 전에 24°로 데워 둘까요?”"),
            )
            Scene.ARRIVE -> base.copy(
                journey = journey("active"),
                coordination = coordination().copy(arrival = Arrival("곧 도착", "3번 출구 앞 오른쪽 차로에 세우세요", "180 m", "3번 출구까지")),
            )
            Scene.DONE -> base.copy(
                finished = journey("completed"),
                result = ActionResult(ResultState.SUCCESS, "픽업을 마쳤어요", "위치 공유와 조율 알림을 끝냈어요"),
            )
        }
    }

    private fun call(status: CallStatus, outgoing: Boolean, analysisOn: Boolean, peerConsent: String) = CallInfo(
        id = "preview",
        status = status,
        rawStatus = status.name.lowercase(),
        outgoing = outgoing,
        peerName = TestData.PASSENGER,
        peerCallName = PEER,
        analysisRequested = true,
        analysisOn = analysisOn,
        peerConsent = peerConsent,
        journeyId = JOURNEY,
    )

    private fun journey(status: String, fields: List<PlanField> = TestData.planFields("3번 출구")) = Journey(
        id = JOURNEY,
        status = status,
        version = 1,
        title = "$PEER 픽업",
        fields = fields,
        summary = "민지 · KE712 15:40 도착 · 인천공항 2터미널 3번 출구 · 16:10 만남",
        passengerCallName = PEER,
        meetPlace = TestData.pickup.copy(name = "2터미널 3번 출구"),
        meetAtLabel = "16:10",
        waitPlace = null,
    )

    private fun coordination() = Coordination(
        start = "16:00",
        end = "16:20",
        now = "16:02",
        ticks = listOf("16:00", "16:10", "16:20"),
        lanes = TestData.etaLanes,
        verdict = "4분 먼저 도착해요. 단기주차장 P1에서 기다리세요.",
        nextAction = null,
        etaLabel = "4분",
        arrival = null,
    )

    private fun proposal(
        kind: String,
        title: String,
        context: String? = null,
        reasons: List<Reason> = emptyList(),
        rows: List<ChangeRow> = emptyList(),
        options: List<ProposalOption>,
        figure: Figure? = null,
        footnote: String? = null,
    ) = Proposal(
        id = "preview-$kind",
        journeyId = JOURNEY,
        kind = kind,
        status = "pending",
        baseVersion = 1,
        title = title,
        question = if (kind == "ask") title else null,
        context = context,
        fieldKey = null,
        reasons = reasons,
        rows = rows,
        options = options,
        figure = figure,
        footnote = footnote,
        voicePrompt = null,
        audience = listOf("driver"),
    )
}
