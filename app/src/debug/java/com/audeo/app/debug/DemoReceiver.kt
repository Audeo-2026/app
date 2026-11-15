package com.audeo.app.debug

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.audeo.app.audeo.DemoSettings
import java.util.Locale
import ai.pleos.playground.tts.constant.ErrorType as TtsError

/**
 * 시연 녹화 도구(debug 빌드 전용, adb로만 부름).
 *
 *   # 글레오(Pleos) TTS로 한 줄 말하기. speaker 를 주면 그 목소리로(예: haeun, seoyoon, eunwoo, sanghyeok)
 *   adb shell am broadcast -n com.audeo.app/.debug.DemoReceiver -a com.audeo.app.DEMO_SPEAK --es text "안녕" --es speaker haeun --es id line1
 *   # 길안내를 시작해도 Audeo 지도를 앞에 두기(에뮬레이터 내비는 GPS를 못 따라와서)
 *   adb shell am broadcast -n com.audeo.app/.debug.DemoReceiver -a com.audeo.app.DEMO_FRONT --ez keep true
 *
 *   # 받아쓰기 시험: 앱 files/ 아래 16kHz 모노 PCM 을 Pleos STT(온디바이스)로 받아써 "stt <파일> <결과>"를 남겨요
 *   adb shell am broadcast -n com.audeo.app/.debug.DemoReceiver -a com.audeo.app.DEMO_STT --es file stt/x.pcm
 *
 * 말하기가 끝나면 logcat(AudeoDemo)에 "tts-done <id>"를 남겨요. 녹음은 컴퓨터에서 에뮬레이터 소리(gRPC)로 받아요.
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.audeo.app.DEMO_SPEAK" -> DemoVoice.speak(
                context.applicationContext,
                intent.getStringExtra("text").orEmpty(),
                intent.getStringExtra("speaker"),
                intent.getStringExtra("id") ?: "line",
            )
            "com.audeo.app.DEMO_STT" -> DemoStt.run(context.applicationContext, intent.getStringExtra("file").orEmpty())
            "com.audeo.app.DEMO_FRONT" -> {
                DemoSettings.keepAudeoInFront = intent.getBooleanExtra("keep", true)
                DemoSettings.scripted = intent.getBooleanExtra("scripted", DemoSettings.scripted)
                Log.d(TAG, "keep-front ${DemoSettings.keepAudeoInFront} scripted ${DemoSettings.scripted}")
            }
            // 시나리오 단계: --es step think|say|place|warm|finder|transport|stop|banner 와 값들
            "com.audeo.app.DEMO_STEP" -> {
                val args = intent.extras?.keySet()?.associateWith { intent.extras?.get(it)?.toString().orEmpty() }.orEmpty()
                val step = args["step"].orEmpty()
                Log.d(TAG, "step $step")
                android.os.Handler(android.os.Looper.getMainLooper()).post { DemoSettings.onStep?.invoke(step, args) }
            }
        }
    }

    companion object {
        const val TAG = "AudeoDemo"
    }
}

/** 시연 대사용 TTS. 앱 음성(오디)과 따로 한 개를 만들어 씁니다. */
private object DemoVoice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val queue = ArrayDeque<Triple<String, String?, String>>()
    private var current: String? = null

    fun speak(context: Context, text: String, speaker: String?, id: String) {
        if (text.isBlank()) return
        queue.addLast(Triple(text, speaker, id))
        val t = tts
        if (t == null) {
            val created = TextToSpeech(context)
            tts = created
            created.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    ready = true
                    runCatching { created.setLocale(Locale.KOREA) }
                    created.addEventListener(events)
                    Log.d(DemoReceiver.TAG, "tts-ready")
                    next()
                }

                override fun onFailure(errorType: TtsError) { Log.d(DemoReceiver.TAG, "tts-init-failed $errorType") }
            })
        } else if (ready && current == null) {
            next()
        }
    }

    private fun next() {
        val t = tts ?: return
        val (text, speaker, id) = queue.removeFirstOrNull() ?: return
        current = id
        speaker?.let { s -> runCatching { t.setSpeakerType(s) }.onFailure { Log.d(DemoReceiver.TAG, "speaker-failed $s ${it.message}") } }
        Log.d(DemoReceiver.TAG, "tts-start $id ${speaker ?: "-"} $text")
        runCatching { t.speak(text) }.onFailure {
            Log.d(DemoReceiver.TAG, "tts-failed $id ${it.message}")
            current = null
            next()
        }
    }

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = done("done")
        override fun onStop() = done("stop")
        override fun onError(errorType: TtsError) = done("error $errorType")
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun done(how: String) {
        Log.d(DemoReceiver.TAG, "tts-done ${current ?: "-"} $how")
        current = null
        next()
    }
}

/** 시연 대사가 차 받아쓰기로 잘 들리는지 미리 확인(통화 없이). */
private object DemoStt {
    private var stt: ai.pleos.playground.stt.SpeechToText? = null
    private var ready = false
    private var current: String? = null
    private val queue = ArrayDeque<String>()
    private var app: Context? = null

    fun run(context: Context, file: String) {
        app = context
        queue.addLast(file)
        if (stt == null) {
            val s = ai.pleos.playground.stt.SpeechToText(context, ai.pleos.playground.stt.constant.Mode.ON_DEVICE)
            stt = s
            s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                override fun onSuccess() {
                    ready = true
                    runCatching { s.setLocale(Locale.KOREA) }
                    s.addListener(listener)
                    next()
                }
                override fun onFailure(errorType: ai.pleos.playground.stt.constant.ErrorType) { Log.d(DemoReceiver.TAG, "stt-init-failed $errorType") }
            })
        } else if (ready && current == null) next()
    }

    private fun next() {
        val f = queue.removeFirstOrNull() ?: return
        current = f
        val pcm = runCatching { java.io.File(app!!.filesDir, f).readBytes() }.getOrNull()
        if (pcm == null) { Log.d(DemoReceiver.TAG, "stt $f <파일 없음>"); current = null; return next() }
        runCatching { stt!!.sendAudio(pcm); stt!!.completeAudioSend() }.onFailure { Log.d(DemoReceiver.TAG, "stt $f <실패 ${it.message}>"); current = null; next() }
    }

    private val listener = object : ai.pleos.playground.stt.listener.ResultListener {
        override fun onUpdated(stt: String, completed: Boolean) {
            if (!completed) return
            Log.d(DemoReceiver.TAG, "stt ${current ?: "-"} $stt")
            current = null
            next()
        }
        override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
        override fun onStartedRecognition() = Unit
        override fun onEndedRecognition() = Unit
        override fun onError(errorType: ai.pleos.playground.stt.constant.ErrorType) {
            Log.d(DemoReceiver.TAG, "stt ${current ?: "-"} <오류 $errorType>")
            current = null
            next()
        }
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
    }
}
