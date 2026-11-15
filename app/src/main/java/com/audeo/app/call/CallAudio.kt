package com.audeo.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import com.audeo.app.server.IceServerInfo
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.AudioTrackSink
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

/**
 * Audeo 통화 음성(WebRTC, 음성만). 차와 탑승자 휴대폰이 직접 연결되고, 서버는 신호만 전달합니다.
 *
 * - 신호 규칙: 통화가 연결되면(active) 건 쪽이 offer를 만들고, 받은 쪽이 answer를 돌려줍니다. ICE 후보는 서로 보냅니다.
 *   payload는 브라우저와 같은 모양입니다: offer/answer = {type, sdp}, ice = {candidate, sdpMid, sdpMLineIndex}.
 * - 받아쓰기용으로 내 마이크 소리([Listener.onLocalAudio])와 상대 소리([Listener.onRemoteAudio])를 PCM 16비트로 넘겨줍니다.
 * - 같은 연결에 데이터 채널("audeo")을 하나 엽니다. 탑승자 휴대폰이 통화 중 정밀 위치를 여기로 보내요
 *   ({"t":"loc","lat","lng","acc","at"}). 서버를 거치지 않고 차와 휴대폰 사이에서만 오가요.
 *   건 쪽이 offer 전에 채널을 만들고, 받은 쪽은 onDataChannel로 받습니다.
 */
class CallAudio(context: Context, private val listener: Listener) {

    interface Listener {
        /** 상대에게 보낼 신호. 서버 POST /calls/{id}/signal 로 보냅니다. */
        fun onLocalSignal(kind: String, payload: JSONObject)
        fun onConnectionState(state: String)
        fun onLocalAudio(pcm: ByteArray, sampleRate: Int, channels: Int)
        fun onRemoteAudio(pcm: ByteArray, sampleRate: Int, channels: Int)
        fun onLog(message: String)
        /** 상대 기기가 데이터 채널로 보낸 메시지(JSON). */
        fun onPeerMessage(message: JSONObject) = Unit
    }

    private val app = context.applicationContext
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var localTrack: AudioTrack? = null
    private var remoteTrack: AudioTrack? = null
    private var data: DataChannel? = null
    private var remoteDescriptionSet = false
    private val pendingIce = ArrayList<IceCandidate>()
    private var savedAudioMode = AudioManager.MODE_NORMAL

    @Volatile var active = false
        private set

    /** 통화 음성을 준비합니다. [caller]면 바로 offer를 만들어 보냅니다. */
    fun start(iceServers: List<IceServerInfo>, caller: Boolean) {
        if (active) return
        active = true
        ensureFactory()
        val f = factory ?: return
        val config = PeerConnection.RTCConfiguration(
            iceServers.flatMap { s ->
                s.urls.map { url ->
                    PeerConnection.IceServer.builder(url).apply {
                        s.username?.let { setUsername(it) }
                        s.credential?.let { setPassword(it) }
                    }.createIceServer()
                }
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = f.createPeerConnection(config, observer)
        val source = f.createAudioSource(MediaConstraints())
        localTrack = f.createAudioTrack("audeo-mic", source).also { pc?.addTrack(it, listOf("audeo")) }
        savedAudioMode = audioManager.mode
        runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
        listener.onLog("통화 음성 준비 (${if (caller) "offer를 보냄" else "offer를 기다림"})")
        if (caller) {
            pc?.createDataChannel("audeo", DataChannel.Init())?.let(::useDataChannel)
            createOffer()
        }
    }

    fun onRemoteSignal(kind: String, payload: JSONObject) {
        val peer = pc ?: return listener.onLog("통화 음성이 준비되기 전 신호: $kind")
        when (kind) {
            "offer" -> {
                val sdp = SessionDescription(SessionDescription.Type.OFFER, payload.optString("sdp"))
                peer.setRemoteDescription(sdpObserver("setRemote(offer)") {
                    remoteDescriptionSet = true
                    flushIce()
                    peer.createAnswer(sdpObserver("createAnswer") { answer ->
                        peer.setLocalDescription(sdpObserver("setLocal(answer)") {
                            listener.onLocalSignal("answer", JSONObject().put("type", "answer").put("sdp", answer!!.description))
                        }, answer)
                    }, audioConstraints())
                }, sdp)
            }
            "answer" -> {
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp"))
                peer.setRemoteDescription(sdpObserver("setRemote(answer)") {
                    remoteDescriptionSet = true
                    flushIce()
                }, sdp)
            }
            "ice" -> {
                val c = payload.optString("candidate")
                if (c.isBlank()) return
                val candidate = IceCandidate(payload.optString("sdpMid", "0"), payload.optInt("sdpMLineIndex", 0), c)
                if (remoteDescriptionSet) peer.addIceCandidate(candidate) else pendingIce.add(candidate)
            }
            "hangup" -> listener.onConnectionState("hangup")
        }
    }

    fun setMuted(muted: Boolean) {
        localTrack?.setEnabled(!muted)
    }

    fun close() {
        if (!active) return
        active = false
        remoteTrack?.removeSink(remoteSink)
        remoteTrack = null
        data?.let { runCatching { it.unregisterObserver(); it.close(); it.dispose() } }
        data = null
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
        localTrack = null
        remoteDescriptionSet = false
        pendingIce.clear()
        runCatching { audioManager.mode = savedAudioMode }
        listener.onLog("통화 음성 끝")
    }

    fun release() {
        close()
        runCatching { factory?.dispose() }
        runCatching { adm?.release() }
        factory = null
        adm = null
    }

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val module = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setSamplesReadyCallback { s -> listener.onLocalAudio(s.data, s.sampleRate, s.channelCount) }
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) = listener.onLog("마이크 초기화 실패: $errorMessage")
                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String) =
                    listener.onLog("마이크 시작 실패: $errorMessage")
                override fun onWebRtcAudioRecordError(errorMessage: String) = listener.onLog("마이크 오류: $errorMessage")
            })
            .createAudioDeviceModule()
        adm = module
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
    }

    /** 데이터 채널로 상대에게 보냅니다(열려 있을 때만). */
    fun send(message: JSONObject): Boolean {
        val ch = data?.takeIf { it.state() == DataChannel.State.OPEN } ?: return false
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        return ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
    }

    private fun useDataChannel(ch: DataChannel) {
        data = ch
        ch.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (ch.state() == DataChannel.State.OPEN) listener.onLog("데이터 채널 열림(탑승자 위치)")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.onSuccess(listener::onPeerMessage)
            }
        })
    }

    private fun createOffer() {
        val peer = pc ?: return
        peer.createOffer(sdpObserver("createOffer") { offer ->
            peer.setLocalDescription(sdpObserver("setLocal(offer)") {
                listener.onLocalSignal("offer", JSONObject().put("type", "offer").put("sdp", offer!!.description))
            }, offer)
        }, audioConstraints())
    }

    private fun flushIce() {
        val peer = pc ?: return
        pendingIce.forEach { peer.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun audioConstraints() = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    /** 상대 목소리(디코딩된 PCM). 받아쓰기로 넘깁니다. */
    private val remoteSink = AudioTrackSink { data: ByteBuffer, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int, _: Long ->
        if (bitsPerSample != 16) return@AudioTrackSink
        val bytes = ByteArray(frames * channels * 2)
        data.duplicate().get(bytes, 0, minOf(bytes.size, data.remaining()))
        listener.onRemoteAudio(bytes, sampleRate, channels)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onLocalSignal(
                "ice",
                JSONObject().put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex),
            )
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            listener.onConnectionState(newState.name.lowercase())
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is AudioTrack) {
                remoteTrack = track
                track.addSink(remoteSink)
                listener.onLog("상대 목소리 트랙 연결")
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = useDataChannel(channel)
        override fun onRenegotiationNeeded() = Unit
    }

    private fun sdpObserver(step: String, onSuccess: (SessionDescription?) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) = onSuccess(sdp)
        override fun onSetSuccess() = onSuccess(null)
        override fun onCreateFailure(error: String) = listener.onLog("$step 실패: $error")
        override fun onSetFailure(error: String) = listener.onLog("$step 실패: $error")
    }
}
