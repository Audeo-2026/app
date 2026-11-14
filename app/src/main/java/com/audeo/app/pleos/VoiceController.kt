package com.audeo.app.pleos

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * 차 안 음성: Pleos TTS로 짧게 말하고, Pleos STT로 운전자의 짧은 답("그렇게 해 줘", "3번 출구", "차 탔어")을 듣습니다.
 * 통화 음성 받아쓰기는 Audeo 서버가 맡으므로 여기서는 하지 않습니다.
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
    private val stt = SpeechToText(app)
    private var callbacks: Callbacks? = null

    @Volatile private var ttsReady = false
    @Volatile private var sttReady = false
    @Volatile private var listening = false

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
                callbacks?.onListening(true)
            }
            .onFailure { callbacks?.onLog("STT 요청 실패: ${it.message}") }
    }

    fun stopListening() {
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
            if (completed) {
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
            listening = false
            callbacks?.onListening(false)
            callbacks?.onLog("STT 오류: $errorType")
        }

        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }
}
