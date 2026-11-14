package com.audeo.app.call

import com.audeo.app.pleos.VoiceController
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/**
 * 통화 받아쓰기. 통화 음성(PCM)을 말 단위로 자르고, Pleos STT 온디바이스 엔진에 sendAudio로 넣어 문장을 받습니다.
 *
 * - 서버 규칙: tx = 운전자(이 차) 목소리, rx = 상대 목소리. 받은 문장은 POST /calls/{id}/utterances 로 올립니다.
 * - Pleos STT의 sendAudio는 ON_DEVICE 모드에서만 됩니다. 문서 예시는 16kHz·16비트·모노 PCM이라 그 형식으로 바꿔 넣어요.
 * - 말 단위 자르기: 10ms 단위 음량으로 말 시작·끝을 찾고, 0.7초 조용하면 한 문장으로 끊습니다(최대 15초).
 * - STT는 차 안 음성과 같은 인스턴스([VoiceController])를 쓰고, 잘린 문장을 차례로 하나씩 보냅니다.
 */
class CallTranscriber(
    private val engine: VoiceController,
    private val onText: (channel: String, text: String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    val ready: Boolean get() = engine.sttAvailable

    @Volatile private var running = false
    private val queue = LinkedBlockingQueue<Pair<String, ByteArray>>()
    private var worker: Thread? = null
    private val segmenters = mapOf("tx" to Segmenter("tx"), "rx" to Segmenter("rx"))

    fun start() {
        if (!ready || running) return
        running = true
        segmenters.values.forEach { it.reset() }
        queue.clear()
        worker = Thread({ loop() }, "audeo-call-stt").apply { isDaemon = true; start() }
    }

    /** 통화 음성 조각. WebRTC 오디오 스레드에서 불립니다. */
    fun feed(channel: String, pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running) return
        segmenters[channel]?.add(pcm, sampleRate, channels)
    }

    /** 말하던 중이면 마지막 문장까지 넣고 멈춥니다. */
    fun stop() {
        if (!running) return
        segmenters.values.forEach { it.flush() }
        running = false
        queue.put(STOP)
    }

    /** 16kHz·16비트·모노 PCM 한 덩어리를 받아씁니다(엔진 시험용, 작업 스레드에서). */
    fun recognizeBlocking(pcm: ByteArray): String? = engine.recognizePcm(pcm, RESULT_TIMEOUT_S)

    private fun loop() {
        while (true) {
            val (channel, pcm) = queue.take()
            if (channel == STOP.first) return
            engine.recognizePcm(pcm, RESULT_TIMEOUT_S)?.let { onText(channel, it) }
        }
    }
    /** 한 채널의 말 단위 자르기. 16kHz 모노로 바꾼 뒤 10ms 프레임 음량으로 판단합니다. */
    private inner class Segmenter(private val channel: String) {
        private val frame = ShortArray(FRAME)
        private var frameLen = 0
        private val speech = ByteArrayOutputStream()
        private val preRoll = ArrayDeque<ShortArray>()
        private var speaking = false
        private var silentFrames = 0
        private var speechFrames = 0
        private var noise = 300.0
        private var resamplePos = 0.0

        fun reset() {
            frameLen = 0
            speech.reset()
            preRoll.clear()
            speaking = false
            silentFrames = 0
            speechFrames = 0
            resamplePos = 0.0
        }

        @Synchronized
        fun add(pcm: ByteArray, rate: Int, channels: Int) {
            val ch = channels.coerceAtLeast(1)
            val samples = pcm.size / 2 / ch
            if (samples == 0 || rate <= 0) return
            val step = rate / RATE.toDouble()
            // 모노로 섞으면서 16kHz로 줄입니다(선형 보간 없이 가까운 표본).
            var pos = resamplePos
            while (pos < samples) {
                val i = pos.toInt()
                var sum = 0
                for (c in 0 until ch) {
                    val o = (i * ch + c) * 2
                    sum += (pcm[o].toInt() and 0xFF) or (pcm[o + 1].toInt() shl 8)
                }
                frame[frameLen++] = (sum / ch).toShort()
                if (frameLen == FRAME) {
                    onFrame(frame.copyOf())
                    frameLen = 0
                }
                pos += step
            }
            resamplePos = pos - samples
        }

        @Synchronized
        fun flush() {
            if (speaking && speechFrames >= MIN_SPEECH_FRAMES) emit()
            reset()
        }

        private fun onFrame(f: ShortArray) {
            var acc = 0.0
            for (v in f) acc += v.toDouble() * v
            val rms = sqrt(acc / f.size)
            val loud = rms > maxOf(MIN_RMS, noise * 3)
            if (!speaking) noise = noise * 0.95 + rms * 0.05
            if (!speaking) {
                preRoll.addLast(f)
                if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                if (loud) {
                    speaking = true
                    silentFrames = 0
                    speechFrames = 0
                    preRoll.forEach(::write)
                    preRoll.clear()
                }
                return
            }
            write(f)
            if (loud) {
                speechFrames++
                silentFrames = 0
            } else {
                silentFrames++
            }
            val total = speech.size() / 2 / FRAME
            if (silentFrames >= END_SILENCE_FRAMES || total >= MAX_FRAMES) {
                if (speechFrames >= MIN_SPEECH_FRAMES) emit()
                speech.reset()
                speaking = false
                silentFrames = 0
                speechFrames = 0
            }
        }

        private fun write(f: ShortArray) {
            for (v in f) {
                speech.write(v.toInt() and 0xFF)
                speech.write((v.toInt() shr 8) and 0xFF)
            }
        }

        private fun emit() {
            queue.put(channel to speech.toByteArray())
        }
    }

    private companion object {
        const val RATE = 16_000
        const val FRAME = 160 // 10ms
        const val MIN_RMS = 500.0
        const val PRE_ROLL_FRAMES = 20 // 0.2초
        const val END_SILENCE_FRAMES = 70 // 0.7초
        const val MIN_SPEECH_FRAMES = 25 // 0.25초
        const val MAX_FRAMES = 1500 // 15초
        const val RESULT_TIMEOUT_S = 10L
        val STOP = "__stop__" to ByteArray(0)
    }
}
