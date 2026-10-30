package ai.petonboard.app.logic

import ai.petonboard.app.vehicle.CarState
import ai.petonboard.app.vehicle.Gear
import ai.petonboard.app.vehicle.SeatSide
import ai.petonboard.app.vehicle.VehicleGateway
import ai.petonboard.app.voice.Voice
import ai.petonboard.app.navi.RestNavigator
import ai.petonboard.app.navi.RestStops
import ai.petonboard.app.sound.BarkDetector
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class Screen { PROFILE, HOME, BOARDING, DRIVING, EXIT, REPORT, HISTORY }
enum class ExitStep { LOCKED, UNLOCKED }

data class PetProfile(
    val name: String = "콩이",
    val breed: String = "골든 리트리버",
    val size: PetSize = PetSize.MEDIUM,
    val seat: SeatSide = SeatSide.RIGHT,
    val tempC: Float = 22f,
    /** 바람 쐬기 창문 열림 정도(%). 보호자가 프로필에서 개를 태우고 직접 맞춤 */
    val windPercent: Int = PetSize.MEDIUM.defaultWindPercent,
    /** 코가 짧은 견종(퍼그·불독 등). 더위에 약해 기본 온도를 낮게 제안 (Carter 2020, Hall 2020) */
    val brachy: Boolean = false,
    /** 휴식 알림 간격(분). 기본 2시간, 쌓인 기록에서 불안 소리가 일찍 나면 자동으로 당김 */
    val restIntervalMin: Int = 120,
) {
    val restIntervalMs: Long get() = restIntervalMin * 60_000L
}

/** 견종 이름으로 단두종인지 짐작 (보호자가 프로필에서 바꿀 수 있음) */
object Breeds {
    private val BRACHY = listOf("퍼그", "불독", "불도그", "프렌치", "시츄", "시추", "페키니즈", "보스턴", "복서", "카발리에", "킹찰스", "브뤼셀", "재패니즈 친", "친")
    fun isBrachy(breed: String): Boolean {
        val b = breed.replace(" ", "")
        return b.isNotEmpty() && BRACHY.any { b.contains(it.replace(" ", "")) }
    }
    const val BRACHY_TEMP_C = 21f
}

enum class TripEventKind { START, WIND, SHAKE, REST, BELT, SOUND, END }
data class TripEvent(val atMs: Long, val kind: TripEventKind, val title: String, val sub: String)

data class TripReport(
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val distanceKm: Float,
    val windCount: Int,
    val windMs: Long,
    val restCount: Int,
    val sharpTurns: Int,
    val hardBrakes: Int,
    val timeline: List<TripEvent>,
    val barkCount: Int = 0,           // F4 짖음 에피소드
    val whineCount: Int = 0,          // F4 낑낑거림 에피소드
    val soundAfterShake: Int = 0,     // 급커브·급제동 30초 안에 난 불안 소리
    val firstSoundMin: Double = -1.0, // 출발 후 처음 불안 소리가 난 시점(분), 없으면 -1
)

data class UiState(
    val screen: Screen = Screen.HOME,
    val car: CarState = CarState(),
    val profile: PetProfile = PetProfile(),
    val petSide: SeatSide = SeatSide.RIGHT,
    val petModeOn: Boolean = false,
    // 주행 중
    val tripStartMs: Long = 0L,
    val restRemainingMs: Long = Tuning.REST_INTERVAL_MS,
    val showRestCard: Boolean = false,
    val windSuggestLeft: Int = 0,      // >0 이면 바람 쐬기 제안 카드 표시(남은 초)
    val windOpen: Boolean = false,
    val shakeLevel: Int = 0,           // 0 부드러움, 1 조금 흔들림, 2 많이 흔들림 (최근 1분 급커브/급제동)
    val beltWarning: Boolean = false,  // 달리는데 반려견 자리 안전벨트 버클이 비어 있음
    val demoBeltOff: Boolean = false,  // 데모: 벨트 값이 없는 에뮬레이터에서 '풀림' 상황 재현
    val previewWindow: Boolean = false, // 프로필에서 창문 높이 맞춰 보는 중
    val firstRun: Boolean = false,      // 프로필을 아직 저장한 적 없음
    val listening: Boolean = false,     // 음성 답을 듣는 중 (마이크 표시)
    val restPick: RestStops.Pick? = null, // 휴식 때 추천할 반려견 놀이터 휴게소
    val anxietyRest: Boolean = false,   // 불안 소리가 잦아서 휴식을 앞당겨 제안 중
    val soundRecent: Int = 0,           // 최근 3분 동안 불안 소리 에피소드 수
    val soundLabel: String = "",        // 가장 최근 소리 종류 (짖음/낑낑거림)
    val soundListening: Boolean = false, // 소리 감지가 켜져 있음
    val adjustNote: String? = null,     // 기록으로 설정을 바꿨을 때 안내
    // 하차
    val exitStep: ExitStep = ExitStep.LOCKED,
    // 리포트
    val report: TripReport? = null,
    val lastReport: TripReport? = null,
    val trips: List<TripSummary> = emptyList(),  // 쌓인 주행 기록 (최신순)
    // 알림/기록
    val toast: String? = null,
    val log: List<String> = emptyList(),
    val showDebug: Boolean = false,
)

/** 데모용 시간 값. 실차 값은 주석 참고. */
object Tuning {
    const val REST_INTERVAL_MS = 2 * 60 * 60 * 1000L     // 2시간마다 휴식
    const val REST_SNOOZE_MS = 15 * 60 * 1000L
    const val REST_MIN_PARK_MS = 5 * 60 * 1000L          // 5분 이상 정차하면 휴식으로 인정
    const val WIND_HOLD_MS = 10_000L                     // 실차 60초. 10~40km/h가 이만큼 유지되면 제안
    const val WIND_COOLDOWN_MS = 3 * 60 * 1000L          // 실차 15분
    const val WIND_CARD_SECONDS = 20   // 질문(약 7초) + 듣기(8초)보다 길게
    const val WIND_MIN_KMH = 10f
    const val WIND_MAX_KMH = 40f
    const val WIND_CLOSE_KMH = 50f
    const val MOVING_KMH = 5f
    const val SHARP_TURN_DEG = 90f
    const val HARD_BRAKE_KMH = 30f
    // F4 소리
    const val SOUND_LINK_MS = 30_000L          // 급커브·급제동 뒤 이 시간 안의 소리는 '함께 일어난 일'로 기록
    const val SOUND_RECENT_MS = 3 * 60_000L    // 화면에 "최근" 소리로 보여 줄 시간
    const val SOUND_REST_WINDOW_MS = 10 * 60_000L
    const val SOUND_REST_COUNT = 3             // 10분 안에 3번이면 휴식을 앞당겨 제안
    const val SOUND_SPEAK_COOLDOWN_MS = 5 * 60_000L
    const val MIN_REST_INTERVAL_MIN = 40
}

class PetModeViewModel(app: Application) : AndroidViewModel(app) {

    private val gateway = VehicleGateway(app)
    private val store = ProfileStore(app)
    private val tripStore = TripStore(app)
    private val voice = Voice(app)
    private val restNav = RestNavigator(app)
    private val bark = BarkDetector(app)
    private var micGranted = false
    private var departReminded = false

    private val _ui = MutableStateFlow(
        UiState(profile = store.load(), firstRun = !store.saved, screen = if (store.saved) Screen.HOME else Screen.PROFILE, trips = tripStore.load())
    )
    val ui: StateFlow<UiState> = _ui

    // 내부 기록
    private var prevCar = CarState()
    private var boardingDismissedFor: SeatSide? = null
    private var movedSinceStart = false
    private var parkedAtMs = 0L
    private var windRangeSinceMs = 0L
    private var lastWindSuggestMs = -Tuning.WIND_COOLDOWN_MS
    private var windOpenedAtMs = 0L
    private var restSnoozeUntilMs = 0L
    private var lastTickMs = 0L
    private val shakeEvents = ArrayDeque<Long>()
    private var lastShakeMs = 0L
    private val soundEvents = ArrayDeque<Long>()
    private var lastSoundSpeakMs = 0L
    private var soundRestFromMs = 0L   // 휴식 제안을 미룬 뒤부터 다시 셈
    private var barkCount = 0
    private var whineCount = 0
    private var soundAfterShake = 0
    private var firstSoundMs = 0L

    // 리포트 집계
    private var distanceKm = 0f
    private var windCount = 0
    private var windMs = 0L
    private var restCount = 0
    private var sharpTurns = 0
    private var hardBrakes = 0
    private val timeline = mutableListOf<TripEvent>()
    private var beltWarnedThisTrip = false
    private var beltSeenBuckled = false

    init {
        gateway.start()
        voice.onStatus = { msg -> addLog(msg) }
        voice.start()
        restNav.onMessage = { msg -> addLog(msg) }
        restNav.start()
        bark.onStatus = { msg -> addLog(msg) }
        bark.onEpisode = { kind, _ -> viewModelScope.launch { onDogSound(kind) } }
        viewModelScope.launch { gateway.state.collect { onCar(it) } }
        viewModelScope.launch { gateway.events.collect { addLog(it) } }
        viewModelScope.launch {
            lastTickMs = now()
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    fun onSpeedPermissionGranted() = gateway.startSpeed()
    fun onMicPermissionGranted() {
        micGranted = true
        voice.startStt()
        if (_ui.value.petModeOn) startSound()
    }
    fun onLocationPermissionGranted() = restNav.startLocation()

    // ---------- 음성 ----------

    private fun ask(question: String, onAnswer: (Voice.Answer) -> Unit) {
        _ui.update { it.copy(listening = true) }
        bark.paused = true   // 음성 답을 듣는 동안은 마이크를 음성 인식에 양보
        voice.ask(question) { a ->
            bark.paused = false
            _ui.update { it.copy(listening = false) }
            onAnswer(a)
        }
    }

    private fun stopListening() {
        voice.cancelListening()
        bark.paused = false
        _ui.update { it.copy(listening = false) }
    }

    // ---------- F4 소리 감지 ----------

    private fun startSound() {
        if (!micGranted) return
        bark.start()
        _ui.update { it.copy(soundListening = true) }
    }

    private fun stopSound() {
        bark.stop()
        _ui.update { it.copy(soundListening = false) }
    }

    /** 짖음·낑낑거림 에피소드 (30초 안에 2번 이상). 원인은 단정하지 않고 '그때 함께 일어난 일'만 기록 */
    fun onDogSound(kind: BarkDetector.Kind) {
        val s = _ui.value
        if (!s.petModeOn) return
        val t = now()
        if (kind == BarkDetector.Kind.WHINE) whineCount++ else barkCount++
        if (firstSoundMs == 0L) firstSoundMs = t
        soundEvents.addLast(t)
        while (soundEvents.isNotEmpty() && t - soundEvents.first() > Tuning.SOUND_REST_WINDOW_MS) soundEvents.removeFirst()
        val car = s.car
        val afterShake = lastShakeMs > 0 && t - lastShakeMs <= Tuning.SOUND_LINK_MS
        if (afterShake) soundAfterShake++
        val context = when {
            afterShake -> "급커브·급제동 ${(t - lastShakeMs) / 1000}초 뒤"
            car.speedKmh < 1f -> "정차 중"
            else -> "${car.speedKmh.toInt()}km/h로 달리는 중"
        }
        record(TripEventKind.SOUND, kind.label, context)
        _ui.update { it.copy(soundLabel = kind.label, soundRecent = soundEvents.count { e -> t - e <= Tuning.SOUND_RECENT_MS }) }

        val name = s.profile.name
        val moving = car.gear == Gear.DRIVE || car.gear == Gear.REVERSE
        val canSpeak = t - lastSoundSpeakMs > Tuning.SOUND_SPEAK_COOLDOWN_MS && !s.listening
        when {
            // 잦으면 휴식을 앞당겨 제안 (근거: 이동이 길수록 스트레스 증가, Herbel 2020)
            soundEvents.count { it > soundRestFromMs } >= Tuning.SOUND_REST_COUNT && s.screen == Screen.DRIVING && !s.showRestCard && t >= restSnoozeUntilMs -> {
                _ui.update { it.copy(anxietyRest = true) }
            }
            afterShake && moving && canSpeak -> {
                lastSoundSpeakMs = t
                voice.say("${name}가 불안해해요. 조금 부드럽게 가 볼까요?")
            }
            moving && canSpeak -> {
                lastSoundSpeakMs = t
                voice.say(if (kind == BarkDetector.Kind.WHINE) "${name}가 낑낑거려요. 안전한 곳에 세울 때 살펴봐 주세요." else "${name}가 짖고 있어요. 안전한 곳에 세울 때 살펴봐 주세요.")
            }
        }
    }

    // ---------- 차량 상태 변화 ----------

    private fun onCar(car: CarState) {
        val prev = prevCar
        prevCar = car
        _ui.update { it.copy(car = car) }
        val s = _ui.value

        // F1 탑승 감지: 펫 모드 꺼진 상태 + 정차 중 + 뒷좌석에 새로 앉음
        if (!s.petModeOn && s.screen == Screen.HOME && car.gear.isParkedOrUnknown()) {
            val side = when {
                car.rearRightOccupied && !prev.rearRightOccupied -> SeatSide.RIGHT
                car.rearLeftOccupied && !prev.rearLeftOccupied -> SeatSide.LEFT
                else -> null
            }
            if (side != null && side != boardingDismissedFor) {
                _ui.update { it.copy(screen = Screen.BOARDING, petSide = side) }
                val name = s.profile.name
                ask("뒷좌석에 누가 탔어요. ${name}면 '맞아'라고 말해 주세요.") { a ->
                    if (_ui.value.screen != Screen.BOARDING) return@ask
                    when (a) {
                        Voice.Answer.YES -> choosePet()
                        Voice.Answer.NO -> chooseNotPet()
                        Voice.Answer.NONE -> {}
                    }
                }
            }
        }
        // 좌석이 비면 '다시 묻지 않기' 기억을 지움
        if (!car.rearLeftOccupied && !car.rearRightOccupied) boardingDismissedFor = null
        if (s.screen == Screen.BOARDING && !car.occupied(s.petSide)) {
            _ui.update { it.copy(screen = Screen.HOME) }
        }

        if (!s.petModeOn) return

        // 흔들림 기록 (F4의 주행 연동 부분)
        val quiet = shakeEvents.isEmpty() || now() - shakeEvents.last() > 3000  // 3초 안에 같은 일 중복 기록 방지
        if (quiet && abs(car.steeringDeg) >= Tuning.SHARP_TURN_DEG && abs(prev.steeringDeg) < Tuning.SHARP_TURN_DEG && car.speedKmh > 15f) {
            sharpTurns++; shakeEvents.addLast(now()); lastShakeMs = now(); record(TripEventKind.SHAKE, "급커브", "핸들을 크게 돌렸어요")
        }
        if (quiet && car.brakePressed && !prev.brakePressed && car.speedKmh >= Tuning.HARD_BRAKE_KMH) {
            hardBrakes++; shakeEvents.addLast(now()); lastShakeMs = now(); record(TripEventKind.SHAKE, "급제동", "${car.speedKmh.toInt()}km/h에서 브레이크")
        }

        if (car.speedKmh > Tuning.MOVING_KMH) movedSinceStart = true

        // 바람 쐬기 자동 닫힘
        if (s.windOpen) {
            when {
                car.speedKmh > Tuning.WIND_CLOSE_KMH -> closeWind("속도가 빨라져서 창문을 닫았어요", speak = true)
                car.wiperOn -> closeWind("비가 와서 창문을 닫았어요", speak = true)
                car.gear == Gear.PARK -> closeWind(null)
            }
        }
        if (s.windSuggestLeft > 0 && (car.wiperOn || car.speedKmh > Tuning.WIND_CLOSE_KMH)) {
            stopListening()
            _ui.update { it.copy(windSuggestLeft = 0) }
        }

        // 출발할 때 한 번: 안전벨트 확인 안내 (벨트 센서가 없거나 못 믿는 차에서도 놓치지 않게)
        if (!departReminded && prev.gear == Gear.PARK && (car.gear == Gear.DRIVE || car.gear == Gear.REVERSE)) {
            departReminded = true
            voice.say("출발할게요. ${s.profile.name} 안전벨트를 채웠는지 확인해 주세요.")
        }

        // F6 하차 안전: 주행 후 P단
        if (s.screen == Screen.DRIVING && car.gear == Gear.PARK && prev.gear != Gear.PARK && movedSinceStart) {
            enterExit()
        }
        // 다시 출발
        if (s.screen == Screen.EXIT && car.gear != Gear.PARK && car.gear != Gear.UNKNOWN && prev.gear == Gear.PARK) {
            if (now() - parkedAtMs >= Tuning.REST_MIN_PARK_MS) countRest()
            if (s.exitStep == ExitStep.UNLOCKED) gateway.setRearDoorLock(true)
            movedSinceStart = false
            _ui.update { it.copy(screen = Screen.DRIVING) }
        }
        // 문 열린 뒤 반려견 자리가 비면 = 내렸음 → 주행 종료
        if (s.screen == Screen.EXIT && s.exitStep == ExitStep.UNLOCKED && prev.occupied(s.petSide) && !car.occupied(s.petSide)) {
            addLog("${s.profile.name}가 내렸어요")
            endPetMode()
        }
    }

    // ---------- 1초마다 ----------

    private var tickCount = 0

    private fun tick() {
        if (tickCount++ % 2 == 0) gateway.pollSeats()
        val t = now()
        val dt = t - lastTickMs
        lastTickMs = t
        val s = _ui.value
        if (!s.petModeOn) return
        val car = s.car

        distanceKm += car.speedKmh * dt / 3_600_000f
        while (shakeEvents.isNotEmpty() && t - shakeEvents.first() > 60_000) shakeEvents.removeFirst()
        val shake = when (shakeEvents.size) { 0 -> 0; 1, 2 -> 1; else -> 2 }

        // F5 휴식: 움직이는 동안만 시간 차감
        var rest = s.restRemainingMs
        if (s.screen == Screen.DRIVING && car.speedKmh > 0.5f) rest = (rest - dt).coerceAtLeast(0)
        val showRest = s.screen == Screen.DRIVING && (rest == 0L || s.anxietyRest) && t >= restSnoozeUntilMs
        val soundRecent = soundEvents.count { t - it <= Tuning.SOUND_RECENT_MS }

        // F3 바람 쐬기 제안
        var windLeft = s.windSuggestLeft
        if (windLeft > 0) {
            windLeft--
            if (windLeft == 0) lastWindSuggestMs = t
        } else if (s.screen == Screen.DRIVING && !s.windOpen && !showRest) {
            val inRange = car.speedKmh in Tuning.WIND_MIN_KMH..Tuning.WIND_MAX_KMH && !car.wiperOn
            if (!inRange) windRangeSinceMs = 0L
            else if (windRangeSinceMs == 0L) windRangeSinceMs = t
            else if (t - windRangeSinceMs >= Tuning.WIND_HOLD_MS && t - lastWindSuggestMs >= Tuning.WIND_COOLDOWN_MS) {
                windLeft = Tuning.WIND_CARD_SECONDS
                lastWindSuggestMs = t
                windRangeSinceMs = 0L
                askWind()
            }
        }

        // 안전벨트: 차가 벨트 값을 주거나(데모 포함), 달리는 중인데 반려견 자리 버클이 비어 있으면 경고
        // 벨트 값이 실제로 '채움'을 한 번이라도 보여 줬을 때만 믿음 (값이 고정된 에뮬레이터·센서 없는 좌석에서 오경보 방지)
        val beltRaw = car.belted(s.petSide)
        if (beltRaw == true) beltSeenBuckled = true
        val belted = if (s.demoBeltOff) false else beltRaw
        val moving = car.gear == Gear.DRIVE || car.gear == Gear.REVERSE
        val beltWarn = (s.demoBeltOff || (car.beltSupported && beltSeenBuckled)) && moving && belted == false
        if (beltWarn && !s.beltWarning && !beltWarnedThisTrip) {
            beltWarnedThisTrip = true
            record(TripEventKind.BELT, "안전벨트", "달리는 중 버클이 비어 있었어요")
            voice.say("${s.profile.name} 안전벨트가 풀려 있어요. 안전한 곳에 세운 뒤 채워 주세요.")
        }
        if (showRest && !s.showRestCard) {
            val pick = restNav.pick()
            _ui.update { it.copy(restPick = pick) }
            val where = pick?.let { "${it.stop.name}에 반려견 놀이터가 있어요. 안내할까요? '좋아' 또는 '나중에'라고 말해 주세요." } ?: "안전한 곳에 세워 물을 주고 잠깐 걷게 해 주세요."
            val why = if (s.anxietyRest) "${s.profile.name}가 계속 불안해해요." else "${s.profile.name}가 쉴 시간이에요."
            if (pick == null) voice.say("$why $where")
            else ask("$why $where") { a ->
                when (a) {
                    Voice.Answer.YES -> goRestStop()
                    Voice.Answer.NO -> snoozeRest()
                    Voice.Answer.NONE -> {}
                }
            }
        }
        if (!beltWarn) beltWarnedThisTrip = false

        _ui.update { it.copy(restRemainingMs = rest, showRestCard = showRest, windSuggestLeft = windLeft, shakeLevel = shake, beltWarning = beltWarn, soundRecent = soundRecent) }
    }

    // ---------- 사용자 동작 ----------

    /** F1 "누가 탔나요?" → 반려견 */
    fun choosePet() { stopListening(); startPetMode(_ui.value.petSide) }

    /** F1 → 아이/짐: 이번에는 묻지 않음 */
    fun chooseNotPet() {
        stopListening()
        boardingDismissedFor = _ui.value.petSide
        _ui.update { it.copy(screen = Screen.HOME) }
    }

    /** 홈에서 직접 펫 모드 켜기 */
    fun startFromHome() = startPetMode(_ui.value.profile.seat)

    private fun startPetMode(side: SeatSide) {
        resetTrip()
        record(TripEventKind.START, "출발", "펫 모드 켜짐")
        _ui.update {
            it.copy(screen = Screen.DRIVING, petModeOn = true, petSide = side, tripStartMs = now(),
                restRemainingMs = it.profile.restIntervalMs, report = null, toast = "펫 모드를 켰어요",
                anxietyRest = false, soundRecent = 0, soundLabel = "", adjustNote = null)
        }
        startSound()
        voice.say("펫 모드를 켰어요. 창문 잠금과 온도, 조명을 ${_ui.value.profile.name}에게 맞췄어요.")
        // F2 원터치 세팅: 현재 값 저장 → 반려견 기준으로 변경
        viewModelScope.launch {
            gateway.snapshotCabin()
            delay(1500)
            gateway.applyPetCabin(_ui.value.profile.tempC)
        }
    }

    private fun askWind() {
        ask("${_ui.value.profile.name}가 바람 쐬기 좋은 구간이에요. 창문을 조금 열까요? '좋아' 또는 '괜찮아'라고 말해 주세요.") { a ->
            if (_ui.value.windSuggestLeft <= 0) return@ask
            when (a) {
                Voice.Answer.YES -> acceptWind()
                Voice.Answer.NO -> dismissWind()
                Voice.Answer.NONE -> {}
            }
        }
    }

    fun acceptWind() {
        stopListening()
        val s = _ui.value
        gateway.openPetWindow(s.petSide, s.profile.windPercent)
        windCount++
        windOpenedAtMs = now()
        record(TripEventKind.WIND, "바람 쐬기", "창문을 ${s.profile.windPercent}%만 열었어요")
        _ui.update { it.copy(windSuggestLeft = 0, windOpen = true) }
    }

    fun dismissWind() {
        stopListening()
        lastWindSuggestMs = now()
        _ui.update { it.copy(windSuggestLeft = 0) }
    }

    fun closeWindManually() = closeWind("창문을 닫았어요")

    private fun closeWind(msg: String?, speak: Boolean = false) {
        if (!_ui.value.windOpen) return
        if (speak && msg != null) voice.say(msg)
        gateway.closePetWindow(_ui.value.petSide)
        windMs += now() - windOpenedAtMs
        _ui.update { it.copy(windOpen = false, toast = msg ?: it.toast) }
    }

    /** 반려견 놀이터 휴게소로 길 안내 (목적지가 있으면 경유지로) */
    fun goRestStop() {
        stopListening()
        snoozeRest()
        val pick = _ui.value.restPick ?: restNav.pick()
        val msg = if (pick == null) "근처 150km 안에 반려견 놀이터가 있는 휴게소를 찾지 못했어요" else restNav.guideTo(pick.stop)
        if (pick != null) record(TripEventKind.REST, "휴게소 안내", "${pick.stop.name} (${pick.stop.direction})")
        voice.say(msg)
        _ui.update { it.copy(toast = msg) }
    }

    fun snoozeRest() {
        stopListening()
        restSnoozeUntilMs = now() + Tuning.REST_SNOOZE_MS
        soundRestFromMs = now()
        _ui.update { it.copy(showRestCard = false, anxietyRest = false) }
    }

    /** F6 [리드줄 채웠어요, 뒷문 열기] */
    fun confirmLeash() {
        gateway.setRearDoorLock(false)
        _ui.update { it.copy(exitStep = ExitStep.UNLOCKED) }
    }

    fun endPetMode() {
        val s = _ui.value
        if (!s.petModeOn) return
        if (s.windOpen) closeWind(null)
        stopSound()
        gateway.restoreCabin()
        gateway.setRearDoorLock(false)
        record(TripEventKind.END, "도착", "펫 모드 종료")
        val end = now()
        val report = TripReport(
            startMs = s.tripStartMs,
            endMs = end,
            durationMs = end - s.tripStartMs,
            distanceKm = distanceKm,
            windCount = windCount,
            windMs = windMs,
            restCount = restCount,
            sharpTurns = sharpTurns,
            hardBrakes = hardBrakes,
            timeline = timeline.toList(),
            barkCount = barkCount,
            whineCount = whineCount,
            soundAfterShake = soundAfterShake,
            firstSoundMin = if (firstSoundMs > 0) (firstSoundMs - s.tripStartMs) / 60_000.0 else -1.0,
        )
        val trips = tripStore.add(report)
        // 쌓인 기록으로 휴식 간격 자동 조정
        val adj = TripInsights.suggestRestInterval(trips, s.profile.restIntervalMin)
        var note: String? = null
        if (adj != null) {
            val p = s.profile.copy(restIntervalMin = adj.newMin)
            store.save(p)
            _ui.update { it.copy(profile = p) }
            note = "${s.profile.name}는 보통 출발 ${adj.typicalMin}분쯤부터 불안해했어요. 다음 주행부터 휴식 알림을 ${adj.newMin}분마다 드릴게요"
        }
        _ui.update {
            it.copy(screen = Screen.REPORT, petModeOn = false, report = report, lastReport = report, trips = trips, adjustNote = note,
                anxietyRest = false, soundRecent = 0,
                windSuggestLeft = 0, showRestCard = false, toast = null)
        }
    }

    fun goHome() = _ui.update { it.copy(screen = Screen.HOME) }

    // ---------- 프로필 ----------

    fun openProfile() { if (!_ui.value.petModeOn) _ui.update { it.copy(screen = Screen.PROFILE) } }

    fun saveProfile(p: PetProfile) {
        if (_ui.value.previewWindow) stopWindowPreview()
        store.save(p)
        _ui.update { it.copy(profile = p, petSide = p.seat, firstRun = false, screen = Screen.HOME, toast = "${p.name} 프로필을 저장했어요") }
    }

    /** 정차(P) 중에만: 창문을 지금 높이로 열어 개를 태운 채 확인 */
    fun previewWindow(side: SeatSide, percent: Int): Boolean {
        val g = _ui.value.car.gear
        if (g != Gear.PARK && g != Gear.UNKNOWN) {
            _ui.update { it.copy(toast = "창문 높이는 정차(P) 중에만 맞출 수 있어요") }
            return false
        }
        gateway.openPetWindow(side, percent)
        _ui.update { it.copy(previewWindow = true) }
        return true
    }

    fun stopWindowPreview() {
        gateway.closePetWindow(SeatSide.LEFT)
        gateway.closePetWindow(SeatSide.RIGHT)
        _ui.update { it.copy(previewWindow = false) }
    }
    fun openHistory() { if (!_ui.value.petModeOn) _ui.update { it.copy(screen = Screen.HISTORY) } }
    fun showLastReport() { if (_ui.value.lastReport != null) _ui.update { it.copy(screen = Screen.REPORT, report = it.lastReport) } }
    fun clearToast() = _ui.update { it.copy(toast = null) }
    fun toggleDebug() = _ui.update { it.copy(showDebug = !it.showDebug) }

    // 데모 도우미
    fun demoRestNow() = _ui.update { it.copy(restRemainingMs = 0L) }.also { restSnoozeUntilMs = 0L }
    fun demoWindNow() {
        if (_ui.value.screen == Screen.DRIVING && !_ui.value.windOpen) {
            _ui.update { it.copy(windSuggestLeft = Tuning.WIND_CARD_SECONDS) }
            askWind()
        }
    }
    fun demoToggleBelt() {
        val on = !_ui.value.demoBeltOff
        val s = _ui.value
        val hint = when {
            !on -> "벨트 풀림 테스트를 껐어요"
            !s.petModeOn -> "펫 모드를 켜고 기어를 D로 두면 경고가 떠요"
            s.car.gear != Gear.DRIVE && s.car.gear != Gear.REVERSE -> "기어를 D로 바꾸면 경고가 떠요"
            else -> null
        }
        _ui.update { it.copy(demoBeltOff = on, toast = hint ?: it.toast) }
    }
    /** 테스트: 마이크 없이 소리 감지 결과를 넣어 화면·반응 확인 (실제 감지는 BarkDetector) */
    fun demoSound() = onDogSound(BarkDetector.Kind.WHINE)

    /** 테스트: 녹음된 실제 개 소리(ESC-50)를 마이크 대신 감지 모델에 넣음. 판단 과정은 실제 마이크와 같음 */
    fun demoSoundSample() {
        if (!_ui.value.petModeOn) { addLog("[소리] 펫 모드를 켠 뒤에 테스트할 수 있어요"); return }
        val pcm = runCatching {
            getApplication<Application>().assets.open("test_dog.wav").use { inp ->
                val b = inp.readBytes()
                // 표준 44바이트 헤더, data 크기(40~43바이트)만큼만 읽음
                val dataLen = (b[40].toInt() and 0xFF) or ((b[41].toInt() and 0xFF) shl 8) or
                    ((b[42].toInt() and 0xFF) shl 16) or ((b[43].toInt() and 0xFF) shl 24)
                val n = minOf(dataLen, b.size - 44) / 2
                ShortArray(n) { i -> ((b[44 + 2 * i].toInt() and 0xFF) or (b[45 + 2 * i].toInt() shl 8)).toShort() }
            }
        }.getOrNull()
        if (pcm == null) { addLog("[소리] 테스트 소리 파일을 못 읽었어요"); return }
        bark.playTest(pcm)
    }

    /** 테스트: 차량 조작 연결 없이 급제동 한 번을 기록 (흔들림 비교 문구 확인용) */
    fun demoHardBrake() {
        if (!_ui.value.petModeOn) return
        hardBrakes++; shakeEvents.addLast(now()); lastShakeMs = now()
        record(TripEventKind.SHAKE, "급제동", "테스트")
    }

    /** 테스트 기록을 지우고 휴식 간격을 기본(2시간)으로 되돌림 (시연·제출 전 정리용) */
    fun demoClearHistory() {
        if (_ui.value.petModeOn) return
        tripStore.clear()
        val p = _ui.value.profile.copy(restIntervalMin = 120)
        store.save(p)
        _ui.update { it.copy(trips = emptyList(), lastReport = null, profile = p, toast = "기록을 지웠어요") }
    }
    fun demoBoarding() { if (!_ui.value.petModeOn) _ui.update { it.copy(screen = Screen.BOARDING, petSide = it.profile.seat) } }

    // ---------- 내부 ----------

    private fun enterExit() {
        parkedAtMs = now()
        if (_ui.value.windOpen) closeWind(null)
        gateway.setRearDoorLock(true)
        voice.say("문을 잠가 둘게요. ${_ui.value.profile.name}에게 리드줄을 채운 뒤 화면의 버튼을 눌러 주세요.")
        _ui.update { it.copy(screen = Screen.EXIT, exitStep = ExitStep.LOCKED, windSuggestLeft = 0, showRestCard = false) }
    }

    private fun countRest() {
        restCount++
        restSnoozeUntilMs = 0L
        record(TripEventKind.REST, "휴식", "${(now() - parkedAtMs) / 60000}분 쉬었어요")
        val p = _ui.value.profile
        _ui.update { it.copy(restRemainingMs = p.restIntervalMs, toast = "휴식을 기록했어요. 다음 휴식까지 ${fmtMin(p.restIntervalMin)}") }
    }

    private fun resetTrip() {
        movedSinceStart = false
        distanceKm = 0f; windCount = 0; windMs = 0L; restCount = 0; sharpTurns = 0; hardBrakes = 0
        windRangeSinceMs = 0L; lastWindSuggestMs = -Tuning.WIND_COOLDOWN_MS; restSnoozeUntilMs = 0L
        shakeEvents.clear()
        lastShakeMs = 0L
        soundEvents.clear(); lastSoundSpeakMs = 0L; soundRestFromMs = 0L
        barkCount = 0; whineCount = 0; soundAfterShake = 0; firstSoundMs = 0L
        timeline.clear()
        beltWarnedThisTrip = false
        beltSeenBuckled = false
        departReminded = false
    }

    private fun record(kind: TripEventKind, title: String, sub: String) {
        timeline += TripEvent(now(), kind, title, sub)
    }

    private fun addLog(msg: String) {
        val quiet = msg.contains("실패") || msg.startsWith("[")
        _ui.update { it.copy(log = (listOf(msg) + it.log).take(30), toast = if (quiet) it.toast else msg) }
    }

    private fun fmtMin(min: Int) = if (min % 60 == 0) "${min / 60}시간" else if (min > 60) "${min / 60}시간 ${min % 60}분" else "${min}분"

    override fun onCleared() {
        voice.stop()
        bark.stop()
        restNav.stop()
        gateway.stop()
        super.onCleared()
    }

    private fun now() = System.currentTimeMillis()
}

private fun Gear.isParkedOrUnknown() = this == Gear.PARK || this == Gear.UNKNOWN
fun CarState.occupied(side: SeatSide) = if (side == SeatSide.LEFT) rearLeftOccupied else rearRightOccupied
fun CarState.belted(side: SeatSide) = if (side == SeatSide.LEFT) rearLeftBelted else rearRightBelted
