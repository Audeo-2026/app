package com.audeo.app.server

import com.audeo.app.BuildConfig
import com.audeo.app.audeo.CallInfo
import com.audeo.app.audeo.Contact
import com.audeo.app.audeo.Coordination
import com.audeo.app.audeo.Journey
import com.audeo.app.audeo.Place
import com.audeo.app.audeo.Proposal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Audeo 서버 호출이 실패한 이유. 화면에는 [message]를 그대로 보여 줍니다. */
sealed class ServerError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConfigured(what: String) : ServerError("$what 설정이 없어요 (local.properties)")
    class Network(cause: IOException) : ServerError("서버에 닿지 않아요: ${cause.message ?: cause.javaClass.simpleName}", cause)
    class BadResponse(detail: String) : ServerError("서버 응답을 읽지 못했어요: $detail")

    /** 서버가 `{"error": {"code", "message"}}`로 거절한 경우. */
    class Api(val status: Int, val code: String?, message: String) : ServerError(message)
}

val Throwable.isUnauthorized: Boolean get() = this is ServerError.Api && status == 401

/** 사람이 말한 한 마디 또는 통화 받아쓰기 한 줄. channel: tx = 운전자, rx = 상대방. */
data class Utterance(val clientSeq: Long, val channel: String, val text: String)

data class EventBatch(val events: List<ServerEvent>, val lastSeq: Long)

/** WebRTC ICE 서버(GET /rtc/config). */
data class IceServerInfo(val urls: List<String>, val username: String?, val credential: String?)

/**
 * Audeo 서버 v1 클라이언트(통화·받아쓰기·AI 판단·조율).
 *
 * 인증: 처음 켤 때 초대 코드로 차량 기기를 등록하고(POST /devices), 받은 기기 토큰을
 * `Authorization: Bearer`로 붙입니다. 토큰이 거절되면 한 번 다시 등록합니다.
 * 실시간 알림은 long-poll(GET /events)로 받습니다.
 */
class AudeoServer(
    private val store: DeviceStore,
    baseUrl: String = BuildConfig.AUDEO_API_BASE_URL,
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val registerLock = Mutex()

    val identity: DeviceIdentity? get() = store.identity()

    // ---------- 기기 ----------

    /** 서버 상태 확인(인증 없음). 성공하면 왕복 시간(ms)을 돌려줍니다. */
    suspend fun ping(): Result<Long> = runCatching {
        val started = System.nanoTime()
        val body = request("GET", "ping", null, token = null)
        if (!body.optBoolean("pong")) throw ServerError.BadResponse("pong이 없어요")
        (System.nanoTime() - started) / 1_000_000
    }

    /** 등록된 기기가 없으면 초대 코드로 등록합니다. */
    suspend fun ensureRegistered(): DeviceIdentity = registerLock.withLock {
        store.identity() ?: register()
    }

    private suspend fun register(): DeviceIdentity {
        val invite = BuildConfig.AUDEO_INVITE_CODE
        if (invite.isBlank()) throw ServerError.NotConfigured("초대 코드(audeo.inviteCode)")
        val body = JSONObject()
            .put("invite_code", invite)
            .put("kind", "car")
            .put("display_name", BuildConfig.AUDEO_DRIVER_NAME)
            .putOpt("short_name", BuildConfig.AUDEO_DRIVER_SHORT_NAME.ifBlank { null })
            .putOpt("phone", BuildConfig.AUDEO_DRIVER_PHONE.ifBlank { null })
            .putOpt("vehicle_label", BuildConfig.AUDEO_VEHICLE_LABEL.ifBlank { null })
            .putOpt("plate_number", BuildConfig.AUDEO_PLATE_NUMBER.ifBlank { null })
        val r = request("POST", "devices", body, token = null)
        val user = r.getJSONObject("user")
        return DeviceIdentity(
            deviceId = r.getString("device_id"),
            token = r.getString("device_token"),
            userId = user.getString("user_id"),
            name = user.optString("name"),
            callName = user.optString("call_name"),
        ).also(store::save)
    }

    suspend fun me(): JSONObject = authed("GET", "devices/me")

    /** 차량 이름·번호판을 설정값에 맞춥니다(등록 뒤 바뀐 경우). */
    suspend fun updateVehicle(label: String, plate: String) {
        val body = JSONObject().putOpt("vehicle_label", label.ifBlank { null }).putOpt("plate_number", plate.ifBlank { null })
        if (body.length() > 0) authed("PATCH", "devices/me", body)
    }

    /** Vehicle SDK의 지원 범위. 서버는 지원되는 조치만 제안합니다. */
    suspend fun putCapabilities(capabilities: JSONObject) {
        authed("PUT", "devices/me/capabilities", capabilities)
    }

    /**
     * 이 차가 아는 장소(내비 즐겨찾기·최근 목적지, 지도에서 찾은 곳). 서버는 대화에 나온 장소 이름을
     * 이 목록에서 찾아 좌표를 붙여요(목록에 없으면 '어디서 만날까요?'로 되묻습니다).
     */
    suspend fun putSavedPlaces(bookmarks: List<Place>, recent: List<Place>) {
        fun arr(list: List<Place>) = JSONArray().apply {
            list.forEach { p -> put(JSONObject().put("name", p.name).put("lat", p.latitude).put("lng", p.longitude).putOpt("address", p.address)) }
        }
        authed("PUT", "devices/me/saved-places", JSONObject().put("bookmarks", arr(bookmarks)).put("recent", arr(recent)))
    }

    suspend fun contacts(): List<Contact> =
        authed("GET", "contacts").optJSONArray("contacts").objects().map(Protocol::contact)

    // ---------- 통화 ----------

    suspend fun startCall(calleeId: String, analysis: Boolean): CallInfo =
        call(authed("POST", "calls", JSONObject().put("callee_id", calleeId).put("analysis_requested", analysis).put("caller_role", "driver")))

    suspend fun answer(callId: String, analysisConsent: Boolean): CallInfo =
        call(authed("POST", "calls/$callId/answer", JSONObject().put("analysis_consent", analysisConsent)))

    suspend fun reject(callId: String): CallInfo = call(authed("POST", "calls/$callId/reject"))

    suspend fun consent(callId: String, granted: Boolean): CallInfo =
        call(authed("PUT", "calls/$callId/consent", JSONObject().put("granted", granted)))

    suspend fun endCall(callId: String): CallInfo = call(authed("POST", "calls/$callId/end"))

    suspend fun utterances(callId: String, items: List<Utterance>) {
        val array = JSONArray()
        items.forEach { u -> array.put(JSONObject().put("client_seq", u.clientSeq).put("channel", u.channel).put("text", u.text)) }
        authed("POST", "calls/$callId/utterances", JSONObject().put("items", array))
    }

    // ---------- 여정·제안·실행 ----------

    suspend fun journeys(status: String? = null): List<Journey> =
        authed("GET", "journeys" + (status?.let { "?status=${enc(it)}" } ?: ""))
            .optJSONArray("journeys").objects().map(Protocol::journey)

    suspend fun journey(id: String): Journey = Protocol.journey(authed("GET", "journeys/$id"))

    suspend fun proposals(journeyId: String, status: String? = "pending"): List<Proposal> =
        authed("GET", "journeys/$journeyId/proposals" + (status?.let { "?status=${enc(it)}" } ?: ""))
            .optJSONArray("proposals").objects().map(Protocol::proposal)

    suspend fun decide(proposalId: String, optionId: String, viaVoice: Boolean, baseVersion: Int?): Proposal {
        val body = JSONObject().put("option_id", optionId).put("via", if (viaVoice) "voice" else "button").putOpt("base_version", baseVersion)
        return Protocol.proposal(authed("POST", "proposals/$proposalId/decision", body))
    }

    /** '차 탔어요'. 당사자가 누른 것이므로 서버가 바로 완료합니다. */
    /** '수정': 운전자가 직접 정한 값(source=user, 확정). 장소는 저장 장소 목록에 있는 이름이어야 좌표가 붙어요. */
    suspend fun patchField(journeyId: String, key: String, value: String, baseVersion: Int): Journey =
        Protocol.journey(authed("PATCH", "journeys/$journeyId/fields/$key", JSONObject().put("value", value).put("base_version", baseVersion)))

    /** 수정한 뒤 계획 카드를 다시 만들어 달라고 합니다. */
    suspend fun planNow(journeyId: String) {
        authed("POST", "journeys/$journeyId/plan")
    }

    suspend fun complete(journeyId: String): Journey = Protocol.journey(authed("POST", "journeys/$journeyId/complete"))

    /** 픽업 취소. 서버가 위치 공유와 조율을 끝내고 journey.ended(cancelled)를 보내요. */
    suspend fun cancel(journeyId: String) {
        authed("POST", "journeys/$journeyId/cancel")
    }

    suspend fun coordination(journeyId: String): Coordination? =
        authed("GET", "journeys/$journeyId/coordination").optJSONObject("coordination")?.let(Protocol::coordination)

    /** 차량 위치·내비 상태 보고. client_seq가 같으면 서버가 중복으로 무시합니다. */
    suspend fun reportStatus(journeyId: String, status: JSONObject) {
        authed("POST", "journeys/$journeyId/status", status)
    }

    /** 통화 밖에서 들은 운전자의 한 마디("그렇게 해 줘", "차 탔어"). */
    suspend fun voice(journeyId: String, text: String) {
        authed("POST", "journeys/$journeyId/voice", JSONObject().put("text", text.take(500)))
    }

    /** NaviHelper·Vehicle 실행 결과. succeeded일 때만 서버가 적용 완료로 표시합니다. */
    suspend fun executionResult(executionId: String, status: String, detail: String? = null, observed: JSONObject? = null) {
        val body = JSONObject().put("status", status).putOpt("detail", detail).putOpt("observed", observed)
        authed("POST", "executions/$executionId/result", body)
    }

    /** WebRTC 신호를 상대 기기에 보냅니다. kind: offer, answer, ice, hangup. 상대는 WebSocket의 rtc.signal로 받아요. */
    suspend fun signal(callId: String, kind: String, payload: JSONObject) {
        authed("POST", "calls/$callId/signal", JSONObject().put("kind", kind).put("payload", payload))
    }

    suspend fun rtcConfig(): List<IceServerInfo> =
        authed("GET", "rtc/config").optJSONArray("ice_servers").objects().map { o ->
            val urls = when (val u = o.opt("urls")) {
                is JSONArray -> (0 until u.length()).map { u.optString(it) }
                is String -> listOf(u)
                else -> emptyList()
            }
            IceServerInfo(urls, o.str("username"), o.str("credential"))
        }

    // ---------- 실시간 이벤트 ----------

    /**
     * WebSocket(/ws)으로 이벤트를 받습니다. 연결이 끊기면 돌아옵니다(정상 종료는 null, 실패는 예외).
     * 이벤트는 이 함수를 부른 코루틴에서 [onEvent]로 넘겨요. WebRTC 신호(rtc.signal)는 WebSocket으로만 옵니다.
     */
    suspend fun streamEvents(afterSeq: Long, onOpen: () -> Unit, onEvent: (ServerEvent) -> Unit) {
        val token = ensureRegistered().token
        val wsUrl = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws?token=$token&after_seq=$afterSeq"
        val inbox = Channel<Any>(Channel.UNLIMITED)
        val socket = wsClient.newWebSocket(
            Request.Builder().url(wsUrl).header("User-Agent", USER_AGENT).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    inbox.trySend(OPEN)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    inbox.trySend(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    inbox.trySend(if (code == 4401 || code == 1008) ServerError.Api(401, "ws_closed", "WebSocket 인증 거절($code)") else CLOSED)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val code = response?.code
                    inbox.trySend(
                        when {
                            code == 401 || code == 403 -> ServerError.Api(code, "ws_rejected", "WebSocket 연결이 거절됐어요($code)")
                            t is IOException -> ServerError.Network(t)
                            else -> ServerError.BadResponse(t.message ?: t.javaClass.simpleName)
                        },
                    )
                }
            },
        )
        val myUserId = store.identity()?.userId
        try {
            for (item in inbox) {
                when (item) {
                    OPEN -> onOpen()
                    CLOSED -> return
                    is ServerError -> throw item
                    is String -> {
                        val o = try {
                            JSONObject(item)
                        } catch (e: JSONException) {
                            continue
                        }
                        when (o.str("type")) {
                            "pong" -> Unit
                            "error" -> throw ServerError.BadResponse("WebSocket: ${o.optJSONObject("data")?.str("message")}")
                            else -> onEvent(Protocol.event(o, myUserId))
                        }
                    }
                }
            }
        } finally {
            socket.cancel()
        }
    }

    /** WebSocket 연결을 살려 두는 ping. 서버가 pong으로 답해요. */
    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    /** [afterSeq] 이후 이벤트. 없으면 서버가 [waitSeconds]초까지 기다렸다가 돌려줍니다. */
    suspend fun events(afterSeq: Long, waitSeconds: Int): EventBatch {
        val r = authed("GET", "events?after_seq=$afterSeq&wait_s=$waitSeconds", readTimeoutMs = (waitSeconds + 15) * 1000)
        val myUserId = store.identity()?.userId
        return EventBatch(
            events = r.optJSONArray("events").objects().map { Protocol.event(it, myUserId) },
            lastSeq = r.optLong("last_seq", afterSeq),
        )
    }

    // ---------- HTTP ----------

    private fun call(json: JSONObject): CallInfo = Protocol.call(json, store.identity()?.userId)

    /** 기기 토큰으로 부르고, 토큰이 거절되면 다시 등록해서 한 번 더 부릅니다. */
    private suspend fun authed(method: String, path: String, body: JSONObject? = null, readTimeoutMs: Int = READ_TIMEOUT_MS): JSONObject {
        val first = ensureRegistered()
        return try {
            request(method, path, body, first.token, readTimeoutMs)
        } catch (e: ServerError.Api) {
            if (e.status != 401) throw e
            val again = registerLock.withLock {
                val current = store.identity()
                if (current == null || current.token == first.token) {
                    store.clear()
                    register()
                } else {
                    current
                }
            }
            request(method, path, body, again.token, readTimeoutMs)
        }
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject?,
        token: String?,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): JSONObject = withContext(Dispatchers.IO) {
        if (base.isEmpty()) throw ServerError.NotConfigured("서버 주소(audeo.api.baseUrl)")
        val url = URL("$base/${path.trimStart('/')}")
        val conn = try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                token?.let { setRequestProperty("Authorization", "Bearer $it") }
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    outputStream.use { it.write(body.toString().toByteArray()) }
                }
            }
        } catch (e: IOException) {
            throw ServerError.Network(e)
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            val json = text.takeIf { it.isNotBlank() }?.let {
                try {
                    JSONObject(it)
                } catch (e: JSONException) {
                    if (code in 200..299) throw ServerError.BadResponse(e.message ?: "JSON 아님") else null
                }
            }
            if (code in 200..299) return@withContext json ?: JSONObject()
            throw apiError(code, json)
        } catch (e: IOException) {
            throw ServerError.Network(e)
        } finally {
            conn.disconnect()
        }
    }

    /** v1 `{"error": {"code", "message"}}`, 예전 `{"error": "..."}`, FastAPI 검증 오류 `{"detail": [...]}`를 모두 읽습니다. */
    private fun apiError(status: Int, json: JSONObject?): ServerError.Api {
        val error = json?.opt("error")
        val (code, message) = when (error) {
            is JSONObject -> error.optString("code").ifEmpty { null } to error.optString("message").ifEmpty { null }
            is String -> error to null
            else -> (if (json?.has("detail") == true) "validation" else null) to null
        }
        val fallback = when (status) {
            401 -> "기기 인증이 거절됐어요"
            404 -> "서버에 없는 요청이에요"
            409 -> "이미 처리된 요청이에요"
            422 -> "서버가 요청 형식을 받지 않았어요"
            else -> "서버 오류 $status"
        }
        return ServerError.Api(status, code, message ?: fallback)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
        const val USER_AGENT = "Audeo-Car/${BuildConfig.VERSION_NAME}"
        val OPEN = Any()
        val CLOSED = Any()
    }
}

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
