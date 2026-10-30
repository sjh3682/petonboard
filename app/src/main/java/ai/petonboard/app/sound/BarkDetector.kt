package ai.petonboard.app.sound

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.concurrent.thread

/**
 * F4 반려견 소리 감지 (짖음·낑낑거림).
 * - 모델: YAMNet (구글, AudioSet 521개 소리 분류, Apache 2.0). 16kHz 소리 0.975초를 받아 점수를 냄.
 * - 차 안에서 처리하고 소리는 저장하지 않음 (점수만 씀).
 * - 한 번 들린 것으로는 반응하지 않고, [EPISODE_WINDOW_MS] 안에 [EPISODE_HITS]번 이상 들려야 '에피소드'로 알림.
 * 근거: 차 이동 중 낑낑거림 증가 (Hunt 2023, Flint 2024), 낑낑거림의 거칠기와 각성 (Marx 2021)
 */
class BarkDetector(context: Context) {
    enum class Kind(val label: String) { BARK("짖음"), WHINE("낑낑거림") }

    private val ctx = context.applicationContext
    @Volatile private var running = false
    @Volatile var paused = false   // 음성 답을 듣는 동안은 마이크를 비워 둠
    private var worker: Thread? = null

    /** 에피소드가 생기면 호출 (작업 스레드) */
    var onEpisode: (Kind, Float) -> Unit = { _, _ -> }
    /** 상태 알림 (디버그 줄) */
    var onStatus: (String) -> Unit = {}

    private val hits = ArrayDeque<Pair<Long, Kind>>()
    // 진단: 5초마다 들어온 음량과 개 소리 최고 점수를 상태 줄에 표시
    private var diagMaxRms = 0f
    private var diagMaxScore = 0f
    private var diagFromMs = 0L
    private var diagTop = -1
    private var diagTopScore = 0f
    private var classNames: List<String> = emptyList()

    // 테스트 입력: 녹음된 개 소리를 마이크 대신 같은 판단 과정에 넣음 (에뮬레이터 시연용)
    @Volatile private var testPcm: ShortArray? = null
    @Volatile private var testPos = 0

    /** pcm: 16kHz 모노 16bit. 실제 시간처럼 0.5초씩 넣어 마이크와 똑같이 판단 */
    fun playTest(pcm: ShortArray) {
        if (!running) { onStatus("[소리] 펫 모드를 켠 뒤에 테스트할 수 있어요"); return }
        testPos = 0; testPcm = pcm
        onStatus("[소리] 테스트 입력: 녹음된 개 소리 ${pcm.size / SR}초를 마이크 대신 넣어요")
    }
    private var lastEpisodeMs = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "bark-detector", isDaemon = true) {
            val model = runCatching { Interpreter(loadModel(), Interpreter.Options().setNumThreads(2)) }
                .getOrElse { onStatus("[소리] 모델을 못 불러왔어요: ${it.message}"); running = false; return@thread }
            val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = runCatching {
                AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, HOP * 2 * 2))
            }.getOrNull()
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                onStatus("[소리] 마이크를 열지 못했어요"); running = false; model.close(); return@thread
            }
            onStatus("[소리] 감지 시작")
            if (classNames.isEmpty()) classNames = loadClassNames()
            val window = FloatArray(WIN)
            val chunk = ShortArray(HOP)
            val input = Array(1) { FloatArray(WIN) }
            val output = Array(1) { FloatArray(CLASSES) }
            var filled = 0
            var recording = false
            try {
                while (running) {
                    if (paused) {
                        if (recording) { rec.stop(); recording = false; filled = 0 }
                        Thread.sleep(200); continue
                    }
                    if (!recording) { rec.startRecording(); recording = true }
                    val tp = testPcm
                    val n: Int
                    if (tp != null) {
                        rec.read(chunk, 0, HOP)  // 마이크 버퍼는 비우고 버림 (0.5초 대기 효과)
                        val k = minOf(HOP, tp.size - testPos)
                        if (k <= 0) { testPcm = null; onStatus("[소리] 테스트 입력 끝"); continue }
                        System.arraycopy(tp, testPos, chunk, 0, k)
                        for (i in k until HOP) chunk[i] = 0
                        testPos += k; n = HOP
                    } else {
                        n = rec.read(chunk, 0, HOP)
                    }
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) { val v = chunk[i] / 32768.0; sum += v * v }
                    val rms = kotlin.math.sqrt(sum / n).toFloat()
                    if (rms > diagMaxRms) diagMaxRms = rms
                    // 창을 HOP만큼 밀고 새 소리를 뒤에 붙임
                    System.arraycopy(window, n, window, 0, WIN - n)
                    for (i in 0 until n) window[WIN - n + i] = chunk[i] / 32768f
                    filled = minOf(WIN, filled + n)
                    if (filled < WIN) continue
                    System.arraycopy(window, 0, input[0], 0, WIN)
                    model.run(input, output)
                    judge(output[0])
                    val o = output[0]
                    var bi = 0  // '조용함(Silence)'은 빼고 가장 비슷한 소리를 고름
                    for (i in 1 until CLASSES) if (i != SILENCE && o[i] > o[bi]) bi = i
                    if (rms > 0.005f && o[bi] > diagTopScore) { diagTopScore = o[bi]; diagTop = bi }
                    val nowMs = System.currentTimeMillis()
                    if (diagFromMs == 0L) diagFromMs = nowMs
                    if (nowMs - diagFromMs >= 5_000L) {
                        val top = classNames.getOrNull(diagTop) ?: "-"
                        onStatus("[소리] 5초: 음량 ${"%.3f".format(diagMaxRms)} · 개 점수 ${"%.2f".format(diagMaxScore)} · 가장 비슷한 소리 $top")
                        diagMaxRms = 0f; diagMaxScore = 0f; diagTop = -1; diagTopScore = 0f; diagFromMs = nowMs
                    }
                }
            } catch (t: Throwable) {
                onStatus("[소리] 오류: ${t.message}")
            } finally {
                runCatching { if (recording) rec.stop() }
                rec.release(); model.close()
            }
        }
    }

    fun stop() {
        running = false
        testPcm = null
        worker = null
        hits.clear()
    }

    private fun judge(s: FloatArray) {
        val whine = maxOf(s[WHIMPER_DOG], s[WHIMPER])
        val bark = BARK_CLASSES.maxOf { s[it] }
        val score = maxOf(whine, bark)
        if (score > diagMaxScore) diagMaxScore = score
        if (score < THRESHOLD) return
        val kind = if (s[WHIMPER_DOG] >= bark || (whine >= bark && s[DOG] > 0.1f)) Kind.WHINE else Kind.BARK
        val now = System.currentTimeMillis()
        // 창이 0.5초씩 겹치므로 같은 소리가 두 번 세어지지 않게 1.5초 안의 연속 감지는 하나로 봄
        if (hits.isNotEmpty() && now - hits.last().first < 1_500L) return
        hits.addLast(now to kind)
        while (hits.isNotEmpty() && now - hits.first().first > EPISODE_WINDOW_MS) hits.removeFirst()
        Log.i(TAG, "dog sound ${kind.label} score=$score hits=${hits.size}")
        if (hits.size >= EPISODE_HITS && now - lastEpisodeMs > EPISODE_COOLDOWN_MS) {
            lastEpisodeMs = now
            val main = hits.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key ?: kind
            hits.clear()
            onEpisode(main, score)
        }
    }

    /** yamnet_class_map.csv: index,mid,display_name */
    private fun loadClassNames(): List<String> = runCatching {
        ctx.assets.open("yamnet_class_map.csv").bufferedReader().readLines().drop(1).map { line ->
            line.split(",", limit = 3).getOrElse(2) { "" }.trim().trim('"')
        }
    }.getOrDefault(emptyList())

    private fun loadModel(): MappedByteBuffer {
        val fd = ctx.assets.openFd(MODEL)
        FileInputStream(fd.fileDescriptor).channel.use { ch ->
            return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    companion object {
        private const val TAG = "PetOnboard"
        private const val MODEL = "yamnet.tflite"
        private const val SR = 16_000
        private const val WIN = 15_600          // 0.975초 (YAMNet 입력)
        private const val HOP = 8_000           // 0.5초마다 판단
        private const val CLASSES = 521
        // yamnet_class_map.csv 번호
        private const val WHIMPER = 21
        private const val DOG = 69
        private const val WHIMPER_DOG = 75
        private const val SILENCE = 494
        private val BARK_CLASSES = intArrayOf(69, 70, 71, 72, 73, 74) // Dog, Bark, Yip, Howl, Bow-wow, Growling
        /** 잡음 섞인 개 소리 테스트(ESC-50, 0~10dB)에서 개 소리는 대부분 0.4 이상, 잡음만은 0.0 */
        // 에뮬레이터 실측(휴대폰 스피커 → 노트북 마이크)에서 개 소리 0.30~0.80, 조용할 때 0.00 → 0.25로 낮춤
        const val THRESHOLD = 0.25f
        const val EPISODE_HITS = 2
        const val EPISODE_WINDOW_MS = 30_000L
        const val EPISODE_COOLDOWN_MS = 60_000L
    }
}
