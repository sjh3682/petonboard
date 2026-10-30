package ai.petonboard.app.voice

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit

/**
 * 주행 중 화면을 누르지 않도록 음성으로 묻고 답함.
 * - 말하기: Pleos TextToSpeech (차 안에서 처리, ON_DEVICE). 안 되면 안드로이드 기본 TTS.
 * - 듣기: Pleos SpeechToText (ON_DEVICE). 질문이 끝난 직후에만 마이크를 열고, 몇 초 뒤 닫음.
 * "좋아/맞아/그래/안내해"나 "응/네"만 → 예, "아니/괜찮/나중에" → 아니오, 그 외·무응답 → 답 없음(화면 버튼으로 답).
 */
class Voice(context: Context) {
    enum class Answer { YES, NO, NONE }

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private var platform: android.speech.tts.TextToSpeech? = null
    @Volatile private var platformReady = false

    private var stt: SpeechToText? = null
    @Volatile private var sttReady = false

    @Volatile private var maxRms = 0.0
    @Volatile private var afterSpeak: (() -> Unit)? = null
    @Volatile private var pendingAnswer: ((Answer) -> Unit)? = null
    private val listenTimeout = Runnable { finishListening(Answer.NONE) }

    /** 상태 알림 (디버그 줄에 표시) */
    var onStatus: (String) -> Unit = {}
    private fun status(m: String) { Log.i(TAG, m); onStatus(m) }

    /** 듣기 가능 여부 (마이크 권한 + STT 준비) */
    val canListen: Boolean get() = sttReady

    fun start() {
        runCatching {
            tts = TextToSpeech(ctx, Mode.ON_DEVICE).also { t ->
                t.initialize(object : TtsInit {
                    override fun onSuccess() {
                        runCatching { t.setLocale(Locale.KOREA); t.setAudioAttributes(attrs) }
                        ttsReady = true
                        status("[음성] Pleos TTS 준비됨")
                    }
                    override fun onFailure(type: TtsError) { status("[음성] Pleos TTS 실패($type) → 기본 TTS"); startPlatform() }
                })
                t.addEventListener(object : EventListener {
                    override fun onStart() {}
                    override fun onReady() {}
                    override fun onDone() { spoken() }
                    override fun onError(type: TtsError) { status("[음성] TTS 오류 $type"); spoken() }
                    override fun onStop() { spoken() }
                    override fun onUpdatedRms(rms: Double) {}
                })
            }
        }.onFailure { status("[음성] Pleos TTS 없음: ${it.message}"); startPlatform() }
        startStt()
    }

    /** 마이크 권한을 나중에 받았을 때 다시 시도 */
    fun startStt() {
        if (sttReady || stt != null) return
        runCatching {
            stt = SpeechToText(ctx, SttMode.ON_DEVICE).also { s ->
                s.initialize(object : SttInit {
                    override fun onSuccess() { runCatching { s.setLocale(Locale.KOREA) }; sttReady = true; status("[음성] Pleos STT 준비됨") }
                    override fun onFailure(type: SttError) { status("[음성] STT 실패($type)"); stt = null }
                })
                s.addListener(object : ResultListener {
                    override fun onUpdated(text: String, completed: Boolean) {
                        if (completed) main.post { finishListening(parse(text), text) }
                        else if (text.isNotBlank()) Log.i(TAG, "stt partial: $text")
                    }
                    override fun onUpdatedEpdData(start: Long, end: Long) {}
                    override fun onStartedRecognition() { status("[음성] 듣는 중") }
                    override fun onEndedRecognition() { status("[음성] 듣기 끝") }
                    override fun onError(type: SttError) { status("[음성] STT 오류 $type"); main.post { finishListening(Answer.NONE) } }
                    override fun onReady() { status("[음성] STT 대기") }
                    override fun onUpdatedRms(rms: Double) { if (rms > maxRms) maxRms = rms }
                })
            }
        }.onFailure { status("[음성] Pleos STT 없음: ${it.message}"); stt = null }
    }

    private fun startPlatform() {
        if (platform != null) return
        main.post {
            platform = android.speech.tts.TextToSpeech(ctx) { st ->
                if (st == android.speech.tts.TextToSpeech.SUCCESS) {
                    platform?.language = Locale.KOREA
                    platform?.setAudioAttributes(attrs)
                    platform?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { spoken() }
                        @Deprecated("Deprecated in Java") override fun onError(id: String?) { spoken() }
                    })
                    platformReady = true
                    status("[음성] 기본 TTS 준비됨")
                } else status("[음성] 기본 TTS 실패($st)")
            }
        }
    }

    /** 말만 하기 */
    fun say(text: String) = speak(text, null)

    /**
     * 묻고 답 듣기. 질문이 끝나면 [listenMs] 동안만 마이크를 엶. 결과는 메인 스레드에서 [onAnswer]로.
     * 듣기를 못 하는 상황(권한 없음 등)이면 NONE을 줌 → 화면 버튼으로 대신 답할 수 있음.
     */
    fun ask(question: String, listenMs: Long = 8_000, onAnswer: (Answer) -> Unit) {
        cancelListening()
        pendingAnswer = onAnswer
        speak(question) {
            if (!sttReady) { status("[음성] STT 준비 안 됨"); finishListening(Answer.NONE); return@speak }
            maxRms = 0.0
            status("[음성] 듣기 요청")
            runCatching { stt?.request() }.onFailure { Log.w(TAG, "stt request: ${it.message}"); finishListening(Answer.NONE); return@speak }
            main.postDelayed(listenTimeout, listenMs)
        }
    }

    /** 화면 버튼으로 먼저 답했을 때: 듣기를 멈추고 결과를 버림 */
    fun cancelListening() {
        main.removeCallbacks(listenTimeout)
        if (pendingAnswer != null) runCatching { stt?.stop() }
        pendingAnswer = null
    }

    private fun speak(text: String, then: (() -> Unit)?) {
        main.post {
            afterSpeak = then
            am.requestAudioFocus(focus)
            val ok = when {
                ttsReady -> runCatching { tts?.speak(text, true) }.isSuccess
                platformReady -> platform?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "pet") == android.speech.tts.TextToSpeech.SUCCESS
                else -> false
            }
            status(if (ok) "[음성] 말함: ${text.take(18)}…" else "[음성] 말하기 실패")
            if (!ok) spoken()
            // 완료 알림이 안 오는 경우 대비 (한국어 약 6글자/초)
            else main.postDelayed({ spoken() }, 1500L + text.length * 170L)
        }
    }

    private fun spoken() {
        main.post {
            am.abandonAudioFocusRequest(focus)
            val next = afterSpeak ?: return@post
            afterSpeak = null
            next()
        }
    }

    private fun finishListening(answer: Answer, heard: String = "") {
        main.removeCallbacks(listenTimeout)
        val cb = pendingAnswer ?: return
        pendingAnswer = null
        runCatching { stt?.stop() }
        status("[음성] 답: '$heard' → $answer (소리 크기 최대 ${"%.1f".format(maxRms)})")
        cb(answer)
    }

    fun stop() {
        cancelListening()
        runCatching { tts?.release() }
        runCatching { stt?.release() }
        platform?.shutdown()
    }

    companion object {
        private const val TAG = "PetOnboard"
        // 한 글자 답(응/네/예)은 다른 말에 섞여 잘못 잡히기 쉬워서, 그 말만 했을 때만 인정
        private val YES_SHORT = listOf("응", "네", "예", "어")
        private val YES = listOf("그래", "좋아", "맞아", "열어", "켜 줘", "켜줘", "부탁", "안내해", "가자", "오케이")
        private val NO = listOf("아니", "괜찮", "싫어", "됐어", "안 해", "닫아", "나중", "그만")

        fun parse(text: String): Answer {
            val t = text.trim().replace(" ", "").trimEnd('.', '!', '?', '요')
            return when {
                t.isEmpty() -> Answer.NONE
                NO.any { t.contains(it.replace(" ", "")) } -> Answer.NO
                t in YES_SHORT || YES_SHORT.any { t == it + it } -> Answer.YES
                YES.any { t.contains(it.replace(" ", "")) } -> Answer.YES
                else -> Answer.NONE
            }
        }
    }
}
