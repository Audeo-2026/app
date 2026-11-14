package com.audeo.app.server

import com.audeo.app.audeo.Arrival
import com.audeo.app.audeo.CallInfo
import com.audeo.app.audeo.CallStatus
import com.audeo.app.audeo.CarCommand
import com.audeo.app.audeo.ChangeRow
import com.audeo.app.audeo.Contact
import com.audeo.app.audeo.Coordination
import com.audeo.app.audeo.EtaLane
import com.audeo.app.audeo.Execution
import com.audeo.app.audeo.Figure
import com.audeo.app.audeo.Journey
import com.audeo.app.audeo.LaneTone
import com.audeo.app.audeo.Place
import com.audeo.app.audeo.PlanField
import com.audeo.app.audeo.Proposal
import com.audeo.app.audeo.ProposalOption
import com.audeo.app.audeo.Reason
import com.audeo.app.audeo.SourceKind
import org.json.JSONObject
import java.time.Instant

/** GET /events 로 오는 실시간 알림. */
sealed interface ServerEvent {
    val seq: Long
    val at: Instant?

    data class CallIncoming(override val seq: Long, override val at: Instant?, val call: CallInfo, val callerConsented: Boolean) : ServerEvent
    data class CallState(override val seq: Long, override val at: Instant?, val call: CallInfo) : ServerEvent
    data class JourneyUpdated(override val seq: Long, override val at: Instant?, val journey: Journey) : ServerEvent
    data class JourneyEnded(override val seq: Long, override val at: Instant?, val journeyId: String, val status: String) : ServerEvent
    data class ProposalChanged(override val seq: Long, override val at: Instant?, val proposal: Proposal) : ServerEvent
    data class ExecutionRequested(override val seq: Long, override val at: Instant?, val execution: Execution) : ServerEvent
    data class ExecutionReported(override val seq: Long, override val at: Instant?, val execution: Execution) : ServerEvent
    data class CoordinationUpdated(override val seq: Long, override val at: Instant?, val journeyId: String?, val coordination: Coordination) : ServerEvent

    /** Audeo가 운전자에게 말할 문장. priority: question이면 말한 뒤 답을 듣습니다. */
    data class Say(override val seq: Long, override val at: Instant?, val text: String, val priority: String?, val proposalId: String?) : ServerEvent

    /** 상대 기기가 보낸 WebRTC 신호(offer, answer, ice, hangup). WebSocket으로만 오고 seq가 없습니다. */
    data class RtcSignal(override val seq: Long, override val at: Instant?, val callId: String, val kind: String, val payload: JSONObject, val from: String?) : ServerEvent
    data class Other(override val seq: Long, override val at: Instant?, val type: String) : ServerEvent
}

/** 서버 JSON → 앱 모델. 응답 스키마가 문서에 없어서 실제 응답을 보고 맞췄습니다(2026-11). */
object Protocol {

    fun contact(o: JSONObject): Contact = Contact(
        name = o.str("name") ?: "이름 없음",
        note = o.str("note") ?: o.str("last_call_at")?.let { "최근 통화" } ?: "Audeo 사용자",
        userId = o.str("user_id"),
        callName = o.str("call_name") ?: o.str("name") ?: "",
    )

    fun call(o: JSONObject, myUserId: String?): CallInfo {
        val caller = o.optJSONObject("caller")
        val callee = o.optJSONObject("callee")
        val outgoing = myUserId != null && caller?.str("user_id") == myUserId
        val peer = if (outgoing) callee else caller
        val peerId = peer?.str("user_id")
        val peerConsent = o.optJSONArray("participants").objects().firstOrNull { it.str("user_id") == peerId }?.str("consent")
        val raw = o.str("status") ?: "ended"
        return CallInfo(
            id = o.getString("id"),
            status = when (raw) {
                "ringing", "dialing", "pending" -> CallStatus.RINGING
                "active", "answered" -> CallStatus.ACTIVE
                else -> CallStatus.ENDED
            },
            rawStatus = raw,
            outgoing = outgoing,
            peerName = peer?.str("name") ?: "상대방",
            peerCallName = peer?.str("call_name") ?: peer?.str("name") ?: "상대방",
            analysisRequested = o.optBoolean("analysis_requested"),
            analysisOn = o.optBoolean("analysis_on"),
            peerConsent = peerConsent,
            journeyId = o.str("journey_id"),
        )
    }

    fun journey(o: JSONObject): Journey {
        val plan = o.optJSONObject("plan") ?: o.optJSONObject("draft_plan")
        val fields = o.optJSONArray("fields").objects().map(::field)
        val known = o.optJSONArray("fields").objects().mapNotNull { it.str("key") }.toSet()
        val missing = o.optJSONArray("missing")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return Journey(
            id = o.getString("id"),
            status = o.str("status") ?: "draft",
            version = o.optInt("version"),
            title = o.str("title") ?: "픽업",
            fields = fields + missing.filter { it !in known }.mapNotNull(::missingField),
            summary = o.str("summary"),
            passengerCallName = o.optJSONObject("passenger")?.str("call_name"),
            meetPlace = plan?.optJSONObject("meet_place")?.let(::place),
            meetAtLabel = plan?.str("meet_at_label"),
            waitPlace = plan?.optJSONObject("wait_place")?.let(::place),
        )
    }

    private fun field(o: JSONObject): PlanField = PlanField(
        label = o.str("label") ?: o.str("key") ?: "",
        value = o.str("value"),
        code = o.str("code"),
        note = o.str("note"),
        source = if (o.has("confirmed") && !o.optBoolean("confirmed")) SourceKind.CONFIRM else source(o.str("source")),
        updated = o.optBoolean("updated"),
        key = o.str("key"),
    )

    /** 아직 모르는 항목은 '확인 필요'로 보여 줍니다. */
    private fun missingField(key: String): PlanField? {
        val label = when (key) {
            "person" -> "만날 사람"
            "transport" -> "교통편"
            "place" -> "장소"
            "exit" -> "출구"
            "meet_time" -> "만남 시각"
            "luggage" -> "짐"
            else -> return null
        }
        return PlanField(label, source = SourceKind.CONFIRM, placeholder = "확인 필요", key = key)
    }

    fun source(s: String?): SourceKind? = when (s) {
        "conversation" -> SourceKind.CONVERSATION
        "lookup" -> SourceKind.LOOKUP
        "estimate" -> SourceKind.ESTIMATE
        "confirm", "unconfirmed" -> SourceKind.CONFIRM
        "user" -> SourceKind.USER
        else -> null
    }

    private fun place(o: JSONObject): Place? {
        if (!o.has("lat") || !o.has("lng")) return null
        return Place(o.str("name") ?: o.str("address") ?: "목적지", o.getDouble("lat"), o.getDouble("lng"))
    }

    fun proposal(o: JSONObject): Proposal {
        val byRole = o.optJSONObject("question_by_role")
        return Proposal(
            id = o.getString("id"),
            journeyId = o.str("journey_id").orEmpty(),
            kind = o.str("kind") ?: "plan",
            status = o.str("status") ?: "pending",
            baseVersion = if (o.isNull("base_version")) null else o.optInt("base_version"),
            title = o.str("title").orEmpty(),
            question = byRole?.str("driver") ?: o.str("question"),
            context = o.str("context"),
            fieldKey = o.str("field_key"),
            reasons = o.optJSONArray("reasons").objects().map { Reason(it.str("text").orEmpty(), source(it.str("source")), it.str("label")) },
            rows = o.optJSONArray("rows").objects().map { ChangeRow(it.str("label").orEmpty(), it.str("from") ?: "-", it.str("to") ?: "-") },
            options = o.optJSONArray("options").objects().map {
                ProposalOption(it.getString("id"), it.str("label") ?: it.getString("id"), it.str("variant") ?: "secondary")
            },
            figure = o.optJSONObject("figure")?.let { f -> f.str("value")?.let { Figure(f.str("label"), it, f.str("note")) } },
            footnote = o.str("footnote"),
            voicePrompt = o.str("voice_prompt"),
            audience = o.optJSONArray("audience")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            approver = o.str("approver"),
        )
    }

    fun coordination(o: JSONObject): Coordination {
        val window = o.optJSONObject("window")
        val start = window?.str("start") ?: "00:00"
        val end = window?.str("end") ?: start
        val ticks = window?.optJSONArray("ticks")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        return Coordination(
            start = start,
            end = end,
            now = window?.str("now"),
            ticks = thinTicks(ticks),
            // 시각이 없는 줄(아직 위치를 못 받은 차 등)은 시간축에 놓을 수 없어 뺍니다. verdict가 이유를 말해 줘요.
            lanes = o.optJSONArray("lanes").objects().filter { !it.str("at").isNullOrBlank() }.map { l ->
                EtaLane(
                    who = l.str("who").orEmpty(),
                    at = l.str("at").orEmpty(),
                    note = l.str("note")?.let { if (l.optBoolean("stale")) "$it · 오래된 정보" else it },
                    tone = when (l.str("tone")) {
                        "vehicle" -> LaneTone.VEHICLE
                        "late" -> LaneTone.LATE
                        else -> LaneTone.PASSENGER
                    },
                )
            },
            verdict = o.str("verdict"),
            nextAction = o.str("next_action"),
            etaLabel = o.str("eta_label"),
            arrival = o.optJSONObject("arrival")?.let { a ->
                Arrival(a.str("kicker"), a.str("title").orEmpty(), a.str("figure"), a.str("figure_label"))
            },
        )
    }

    /** 눈금이 많으면 처음·가운데·끝만 남깁니다. */
    private fun thinTicks(ticks: List<String>): List<String> =
        if (ticks.size <= 3) ticks else listOf(ticks.first(), ticks[ticks.size / 2], ticks.last())

    fun execution(o: JSONObject): Execution {
        val target = o.str("target").orEmpty()
        val cmd = o.optJSONObject("command") ?: JSONObject()
        val op = cmd.str("op").orEmpty()
        val command = when {
            target == "navi" && (op == "requestRoute" || op == "addWaypoint") -> {
                val d = cmd.optJSONObject("destination") ?: cmd.optJSONObject("waypoint")
                val place = d?.let { Place(it.str("poi_name") ?: it.str("address") ?: "목적지", it.optDouble("lat"), it.optDouble("lng")) }
                when {
                    place == null || place.latitude.isNaN() || place.longitude.isNaN() -> CarCommand.Unsupported(target, op)
                    op == "addWaypoint" -> CarCommand.AddStop(place)
                    else -> CarCommand.Route(place)
                }
            }
            op == "setHvacTemperature" -> CarCommand.CabinTemperature(cmd.str("area"), cmd.optDouble("celsius", 24.0).toFloat())
            op == "setHvacSeatTemperature" -> CarCommand.SeatHeat(cmd.str("area"), cmd.optInt("level", 1))
            else -> CarCommand.Unsupported(target, op)
        }
        return Execution(
            id = o.getString("execution_id"),
            proposalId = o.str("proposal_id"),
            target = target,
            command = command,
            status = o.str("status") ?: "requested",
        )
    }

    fun event(o: JSONObject, myUserId: String?): ServerEvent {
        val seq = o.optLong("seq")
        val at = o.str("at")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val type = o.str("type").orEmpty()
        val d = o.optJSONObject("data") ?: JSONObject()
        return runCatching {
            when (type) {
                "call.incoming" -> ServerEvent.CallIncoming(seq, at, call(d, myUserId), d.optBoolean("from_consented"))
                "call.state" -> ServerEvent.CallState(seq, at, call(d, myUserId))
                "journey.updated" -> ServerEvent.JourneyUpdated(seq, at, journey(d))
                "journey.ended" -> ServerEvent.JourneyEnded(seq, at, d.str("journey_id") ?: o.str("journey_id").orEmpty(), d.str("status") ?: "completed")
                "proposal.created", "proposal.updated" -> ServerEvent.ProposalChanged(seq, at, proposal(d))
                "execution.requested" -> ServerEvent.ExecutionRequested(seq, at, execution(d))
                "execution.result" -> ServerEvent.ExecutionReported(seq, at, execution(d))
                "coordination.updated" -> ServerEvent.CoordinationUpdated(seq, at, o.str("journey_id"), coordination(d))
                "rtc.signal" -> ServerEvent.RtcSignal(
                    seq, at, d.str("call_id").orEmpty(), d.str("kind").orEmpty(), d.optJSONObject("payload") ?: JSONObject(), d.str("from"),
                )
                "agent.say" -> if (d.str("role") == null || d.str("role") == "driver") {
                    ServerEvent.Say(seq, at, d.str("text").orEmpty(), d.str("priority"), d.str("proposal_id"))
                } else {
                    ServerEvent.Other(seq, at, type)
                }
                else -> ServerEvent.Other(seq, at, type)
            }
        }.getOrElse { ServerEvent.Other(seq, at, "$type(읽기 실패: ${it.message})") }
    }
}

/** JSON null과 빈 문자열을 null로 읽습니다(optString은 null을 "null"로 돌려줘서). */
internal fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }
