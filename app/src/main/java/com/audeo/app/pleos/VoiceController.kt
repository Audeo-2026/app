package com.audeo.app.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * 차 안 음성: Pleos TTS로 짧게 말하고, Pleos STT로 운전자의 짧은 답("그렇게 해 줘", "3번 출구", "차 탔어")을 듣습니다.
 * 통화 받아쓰기도 같은 STT를 씁니다([recognizePcm]). 한 앱 안에서 STT 인스턴스를 둘 만들면 결과가 한쪽으로만 와서,
 * 하나를 온디바이스 모드로 만들어 마이크 듣기와 PCM 받아쓰기(sendAudio, 온디바이스 전용)에 함께 씁니다.
 */
class VoiceController(context: Context) {

    interface Callbacks {
        fun onLog(message: String)
        fun onSpeechDone()
        fun onListening(active: Boolean)
        fun onPartial(text: String)
        fun onHeard(text: String)
    }

    private val app = context.applicationContext
    private val tts = TextToSpeech(app)
    private val stt = SpeechToText(app, Mode.ON_DEVICE)
    private var callbacks: Callbacks? = null

    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false
    @Volatile private var listening = false

    /**
     * 마이크로 운전자 답을 듣는 중(listen()부터 최종 결과까지). 통화 받아쓰기(PCM)가 시간을 넘겨
     * 결과가 늦게 오면 이 값이 false라서 운전자 말로 처리하지 않고 버려요.
     */
    @Volatile private var micSession = false

    /** PCM 받아쓰기를 기다리는 중이면 결과를 여기로 보냅니다(마이크 답으로 처리하지 않음). */
    @Volatile private var pcmWaiter: ((String?) -> Unit)? = null

    val sttAvailable: Boolean get() = sttReady

    fun start(callbacks: Callbacks) {
        this.callbacks = callbacks
        runCatching {
            tts.initialize(object : TtsInit {
                override fun onSuccess() {
                    ttsReady = true
                    runCatching { tts.setLocale(Locale.KOREA) }
                    tts.addEventListener(ttsEvents)
                    callbacks.onLog("TTS 준비됨")
                }

                override fun onFailure(errorType: TtsError) = callbacks.onLog("TTS 초기화 실패: $errorType")
            })
        }.onFailure { e -> callbacks.onLog("TTS 초기화 실패: ${e.message}") }

        runCatching {
            stt.initialize(object : SttInit {
                override fun onSuccess() {
                    sttReady = true
                    runCatching { stt.setLocale(Locale.KOREA) }
                    stt.addListener(sttEvents)
                    callbacks.onLog("STT 준비됨")
                }

                override fun onFailure(errorType: SttError) = callbacks.onLog("STT 초기화 실패: $errorType")
            })
        }.onFailure { e -> callbacks.onLog("STT 초기화 실패: ${e.message}") }
    }

    /** 말하기. TTS를 쓸 수 없으면 바로 끝난 것으로 알립니다. */
    fun speak(text: String) {
        stopListening()
        if (!ttsReady) {
            callbacks?.onSpeechDone()
            return
        }
        runCatching { tts.speak(text) }.onFailure {
            callbacks?.onLog("TTS 실패: ${it.message}")
            callbacks?.onSpeechDone()
        }
    }

    /** 한 번 듣기. 말이 끝나면(EPD) 최종 결과가 [Callbacks.onHeard]로 옵니다. */
    fun listen() {
        if (!sttReady || listening) return
        runCatching { stt.request() }
            .onSuccess {
                listening = true
                micSession = true
                callbacks?.onListening(true)
            }
            .onFailure { callbacks?.onLog("STT 요청 실패: ${it.message}") }
    }

    /**
     * 16kHz·16비트·모노 PCM 한 덩어리를 받아씁니다. 결과가 올 때까지 막으니 작업 스레드에서 부르세요.
     * 마이크로 듣는 중이면 먼저 멈춥니다.
     */
    fun recognizePcm(pcm: ByteArray, timeoutSeconds: Long = 10): String? = synchronized(this) {
        if (!sttReady) return null
        if (listening) runCatching { stt.stop() }.also { listening = false }
        micSession = false
        val latch = CountDownLatch(1)
        var result: String? = null
        pcmWaiter = { text ->
            result = text
            latch.countDown()
        }
        val sent = runCatching {
            stt.sendAudio(pcm)
            stt.completeAudioSend()
        }.onFailure { callbacks?.onLog("받아쓰기 전송 실패: ${it.message}") }.isSuccess
        if (sent && !latch.await(timeoutSeconds, TimeUnit.SECONDS)) callbacks?.onLog("받아쓰기 결과가 오지 않았어요 (${pcm.size / 32}ms)")
        pcmWaiter = null
        result?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun stopListening() {
        micSession = false
        if (!listening) return
        runCatching { stt.stop() }
        listening = false
        callbacks?.onListening(false)
    }

    fun stopAll() {
        runCatching { if (ttsReady) tts.stop() }
        stopListening()
    }

    fun release() {
        stopAll()
        callbacks = null
        runCatching { tts.release() }
        runCatching { stt.release() }
    }

    private val ttsEvents = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() {
            callbacks?.onSpeechDone()
        }

        override fun onError(errorType: TtsError) {
            callbacks?.onLog("TTS 오류: $errorType")
            callbacks?.onSpeechDone()
        }

        /** 다른 소리(내비 안내 등)에 밀려 멈춘 경우도 말하기가 끝난 것으로 봅니다. */
        override fun onStop() {
            callbacks?.onSpeechDone()
        }

        override fun onUpdatedRms(rms: Double) = Unit
    }

    private val sttEvents = object : ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            pcmWaiter?.let { waiter ->
                if (completed) waiter(stt)
                return
            }
            if (!micSession) {
                // 시간을 넘긴 통화 받아쓰기의 늦은 결과예요. 운전자 답이 아니니 버립니다.
                if (completed) callbacks?.onLog("늦게 온 통화 받아쓰기 결과는 버려요: $stt")
                return
            }
            if (completed) {
                micSession = false
                listening = false
                callbacks?.onListening(false)
                callbacks?.onHeard(stt)
            } else {
                callbacks?.onPartial(stt)
            }
        }

        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() {
            if (listening) {
                listening = false
                callbacks?.onListening(false)
            }
        }

        override fun onError(errorType: SttError) {
            pcmWaiter?.let { waiter ->
                callbacks?.onLog("받아쓰기 오류: $errorType")
                waiter(null)
                return
            }
            micSession = false
            listening = false
            callbacks?.onListening(false)
            callbacks?.onLog("STT 오류: $errorType")
        }

        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }
}
