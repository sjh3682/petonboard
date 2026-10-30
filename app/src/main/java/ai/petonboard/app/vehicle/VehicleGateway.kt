package ai.petonboard.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }
enum class SeatSide { LEFT, RIGHT }

/** 앱이 쓰는 차량 상태 한 묶음. */
data class CarState(
    val connected: Boolean = false,
    val speedKmh: Float = 0f,
    val gear: Gear = Gear.UNKNOWN,
    val rearLeftOccupied: Boolean = false,
    val rawRearLeft: String = "-",     // SDK가 준 원래 값 (확인용)
    val rawRearRight: String = "-",
    val rawGear: String = "-",
    val rearRightOccupied: Boolean = false,
    val steeringDeg: Float = 0f,
    val brakePressed: Boolean = false,
    val wiperOn: Boolean = false,
    val rearDoorsLocked: Boolean? = null,
    /** 이 차가 뒷좌석 착석 값을 주는지 (지원 목록에 있거나 실제 값이 들어오면 true) */
    val occupancySupported: Boolean = false,
    /** 이 차가 뒷좌석 안전벨트 값을 주는지 */
    val beltSupported: Boolean = false,
    val rearLeftBelted: Boolean? = null,
    val rearRightBelted: Boolean? = null,
    val rawBeltLeft: String = "-",
    val rawBeltRight: String = "-",
)

/**
 * Pleos Vehicle SDK와 Android Car API를 감싸는 차량 연결 모듈.
 * - 읽기: 상태를 [state]로 내보냄
 * - 제어: 기능마다 지원 여부(check…Capabilities)를 먼저 확인하고, 결과를 [events]로 알림
 * 모든 SDK 호출은 실패해도 앱이 죽지 않도록 감싸 둠.
 */
class VehicleGateway(context: Context) {

    private val app = context.applicationContext
    private val vehicle = Vehicle(app)
    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null

    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val events: SharedFlow<String> = _events

    // 지원 영역 (capability 확인 결과)
    @Volatile private var windowLockAreas: List<WindowArea> = emptyList()
    @Volatile private var windowPosBounds: Map<WindowArea, AreaBounds<Int>> = emptyMap()
    @Volatile private var doorLockAreas: List<DoorArea> = emptyList()
    @Volatile private var tempAreas: List<SeatArea> = emptyList()
    @Volatile private var fanBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    @Volatile private var ambientBounds: Map<SeatArea, AreaBounds<Int>> = emptyMap()
    @Volatile private var ambientColorAreas: List<SeatArea> = emptyList()

    // 펫 모드 전 값 (끝낼 때 복원)
    private val savedTemp = ConcurrentHashMap<SeatArea, Float>()
    private val savedFan = ConcurrentHashMap<SeatArea, Int>()
    private val savedAmbient = ConcurrentHashMap<SeatArea, Int>()
    private val savedAmbientColor = ConcurrentHashMap<SeatArea, Int>()
    private val doorLocks = ConcurrentHashMap<DoorArea, Boolean>()
    private val fanChanged = java.util.Collections.newSetFromMap(ConcurrentHashMap<SeatArea, Boolean>())

    // ---------- 읽기 ----------

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = update { it.copy(gear = gear.toGear(), rawGear = gear?.name ?: "null") }
        override fun onFailed(e: Exception) = fail("기어", e)
    }
    private val steerListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = update { it.copy(steeringDeg = angle ?: 0f) }
        override fun onFailed(e: Exception) = fail("핸들", e)
    }
    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) = update { it.copy(brakePressed = status == true) }
        override fun onFailed(e: Exception) = fail("브레이크", e)
    }
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) = update { it.copy(wiperOn = state == WiperState.ON) }
        override fun onFailed(e: Exception) = fail("와이퍼", e)
    }
    private val seatListeners = mapOf(
        SeatValue.ROW_2_LEFT to seatListener(SeatSide.LEFT),
        SeatValue.ROW_2_RIGHT to seatListener(SeatSide.RIGHT),
    )
    private val beltListeners = mapOf(
        SeatValue.ROW_2_LEFT to beltListener(SeatSide.LEFT),
        SeatValue.ROW_2_RIGHT to beltListener(SeatSide.RIGHT),
    )
    private val doorLockListeners = ConcurrentHashMap<DoorArea, DoorLockStateListener>()

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = (value.value as? Float) ?: return
            update { it.copy(speedKmh = abs(mps) * 3.6f) }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {}
    }

    fun start() {
        val ok = runCatching { vehicle.initialize() }.onFailure { fail("차량 연결", it) }.isSuccess
        update { it.copy(connected = ok) }
        if (!ok) return

        call("기어") {
            vehicle.getGear().getCurrentGearSelectionState({ g -> update { it.copy(gear = g.toGear()) } }, { fail("기어", it) })
            vehicle.getGear().registerGearSelection(gearListener)
        }
        call("핸들") {
            vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> update { it.copy(steeringDeg = a ?: 0f) } }, { fail("핸들", it) })
            vehicle.getSteeringWheel().registerSteeringWheelAngle(steerListener)
        }
        call("브레이크") {
            vehicle.getBrake().getCurrentBrakePedalStatus({ b -> update { it.copy(brakePressed = b == true) } }, { fail("브레이크", it) })
            vehicle.getBrake().registerBrakePedalStatus(brakeListener)
        }
        call("와이퍼") { vehicle.getWiper().registerWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener) }
        seatListeners.forEach { (value, listener) ->
            call("좌석") {
                val area = SeatArea(value)
                vehicle.getSeat().getCurrentSeatOccupancy(area, { o -> listener.onSeatOccupancyUpdated(o) }, { fail("좌석", it) })
                vehicle.getSeat().registerSeatOccupancy(area, listener)
            }
        }
        beltListeners.forEach { (value, listener) ->
            quiet("안전벨트") {
                val area = SeatArea(value)
                vehicle.getSeat().getCurrentSeatBeltBuckledState(area, { b -> listener.onSeatBeltBuckledStateUpdated(b) }, { Log.w(TAG, "belt: ${it.message}") })
                vehicle.getSeat().registerSeatBeltBuckledState(area, listener)
            }
        }
        loadCapabilities()
    }

    /**
     * 좌석·기어 값을 직접 다시 읽음 (에뮬레이터에서 변경 알림이 안 오는 경우 대비).
     * SDK 호출마다 권한 확인에 리플렉션을 써서 무거우므로 필요한 값만, 2초마다 부름.
     */
    fun pollSeats() {
        if (!_state.value.connected) return
        seatListeners.forEach { (value, listener) ->
            quiet("좌석 읽기") {
                vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(value), { o -> listener.onSeatOccupancyUpdated(o) }, { Log.w(TAG, "seat poll: ${it.message}") })
            }
        }
        quiet("기어 읽기") {
            vehicle.getGear().getCurrentGearSelectionState({ g -> update { it.copy(gear = g.toGear(), rawGear = g?.name ?: "null") } }, { Log.w(TAG, "gear poll: ${it.message}") })
        }
    }

    /** 속도는 android.car.permission.CAR_SPEED 허용 후에 시작. */
    fun startSpeed() {
        if (car != null) return
        call("속도") {
            car = Car.createCar(app)
            propertyManager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            propertyManager?.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }
    }

    fun stop() {
        call("정리") { vehicle.getGear().unregisterGearSelection(gearListener) }
        call("정리") { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steerListener) }
        call("정리") { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
        call("정리") { vehicle.getWiper().unregisterWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), wiperListener) }
        seatListeners.forEach { (v, l) -> call("정리") { vehicle.getSeat().unregisterSeatOccupancy(SeatArea(v), l) } }
        beltListeners.forEach { (v, l) -> call("정리") { vehicle.getSeat().unregisterSeatBeltBuckledState(SeatArea(v), l) } }
        doorLockListeners.forEach { (a, l) -> call("정리") { vehicle.getDoor().unregisterDoorLockState(a, l) } }
        call("정리") { propertyManager?.unregisterCallback(speedCallback) }
        call("정리") { car?.disconnect() }
        call("정리") { vehicle.release() }
        io.execute { io.shutdown() }
    }

    private fun loadCapabilities() {
        call("착석 지원 확인") {
            vehicle.getSeat().checkSeatOccupancyCapabilities({ cfg, areas ->
                val rear = areas.hasRearSeat()
                Log.i(TAG, "occupancy capability access=${cfg.accessType} rear=$rear")
                if (rear) update { it.copy(occupancySupported = true) }
            }, { Log.w(TAG, "occupancy capability: ${it.message}") })
        }
        call("벨트 지원 확인") {
            vehicle.getSeat().checkSeatBeltBuckledCapabilities({ cfg, areas ->
                val rear = areas.hasRearSeat()
                Log.i(TAG, "belt capability access=${cfg.accessType} rear=$rear")
                if (rear) update { it.copy(beltSupported = true) }
            }, { Log.w(TAG, "belt capability: ${it.message}") })
        }
        call("창문 잠금 확인") {
            vehicle.getWindow().checkWindowLockCapabilities({ _, areas ->
                windowLockAreas = areas
                emit("[정보] 창문 잠금 영역 ${areas.map { it.values.toList() }}")
            }, { fail("창문 잠금 확인", it) })
        }
        call("창문 위치 확인") {
            vehicle.getWindow().checkWindowPositionCapabilities({ _, bounds -> windowPosBounds = bounds }, { fail("창문 위치 확인", it) })
        }
        call("문 잠금 확인") {
            vehicle.getDoor().checkDoorLockCapabilities({ _, areas ->
                doorLockAreas = areas
                emit("[정보] 문 잠금 영역 ${areas.map { it.values.toList() }}")
                registerRearDoorLocks()
            }, { fail("문 잠금 확인", it) })
        }
        call("온도 확인") {
            vehicle.getHVAC().checkHvacTemperatureCapabilities({ _, bounds -> tempAreas = bounds.keys.toList() }, { fail("온도 확인", it) })
        }
        call("풍량 확인") {
            vehicle.getHVAC().checkHvacFanSpeedCapabilities({ _, bounds -> fanBounds = bounds }, { fail("풍량 확인", it) })
        }
        call("조명 확인") {
            vehicle.getLight().checkAmbientLightBrightnessCapabilities({ _, bounds -> ambientBounds = bounds }, { fail("조명 확인", it) })
        }
        call("조명 색 확인") {
            vehicle.getLight().checkAmbientLightBrightnessColorCapabilities({ _, areas -> ambientColorAreas = areas }, { fail("조명 색 확인", it) })
        }
    }

    private fun registerRearDoorLocks() {
        rearDoorAreas().forEach { area ->
            val listener = object : DoorLockStateListener {
                override fun onDoorLockStateUpdated(state: Boolean?) {
                    doorLocks[area] = state == true
                    update { it.copy(rearDoorsLocked = doorLocks.values.all { v -> v }) }
                }
                override fun onFailed(e: Exception) = fail("문 잠금 상태", e)
            }
            doorLockListeners[area] = listener
            call("문 잠금 상태") { vehicle.getDoor().registerDoorLockState(area, listener) }
        }
    }

    // ---------- 제어 ----------

    /** 펫 모드 켜기 전 값을 저장. */
    fun snapshotCabin() {
        savedTemp.clear(); savedFan.clear(); savedAmbient.clear(); savedAmbientColor.clear()
        hvacOffNoted = false
        tempTargets().forEach { a ->
            call("온도 읽기") {
                vehicle.getHVAC().getHvacTemperature(a, TemperatureUnit.CELSIUS, { v -> if (v != null) savedTemp[a] = v }, {
                    // 공조가 꺼져 있으면 설정 온도 값이 없음(PropertyNotAvailable) → 오류가 아니라 '되돌릴 값 없음'으로 처리
                    if (isNotAvailable(it)) {
                        if (!hvacOffNoted) { hvacOffNoted = true; emit("[정보] 공조가 꺼져 있어 원래 온도가 없어요. 끝낼 때 온도는 그대로 둘게요") }
                    } else fail("온도 읽기", it)
                })
            }
        }
        fanBounds.keys.forEach { a ->
            call("풍량 읽기") { vehicle.getHVAC().getHvacFanSpeed(a, { v -> if (v != null) savedFan[a] = v }, { fail("풍량 읽기", it) }) }
        }
        ambientBounds.keys.forEach { a ->
            call("조명 읽기") { vehicle.getLight().getAmbientLightBrightness(a, { v -> if (v != null) savedAmbient[a] = v }, { fail("조명 읽기", it) }) }
        }
        ambientColorAreas.forEach { a ->
            call("조명 색 읽기") { vehicle.getLight().getAmbientLightBrightnessColor(a, { v -> if (v != null) savedAmbientColor[a] = v }, { fail("조명 색 읽기", it) }) }
        }
    }

    /** F2 원터치 세팅: 뒷창문 잠금, 반려견 기준 온도·풍량, 차분한 조명. */
    fun applyPetCabin(tempC: Float) {
        if (io.isShutdown) return
        // 앞서 줄 세운 '현재 값 읽기'가 끝난 뒤에 실행되도록 같은 스레드에 줄 세움
        io.execute { applyPetCabinNow(tempC) }
    }

    private fun applyPetCabinNow(tempC: Float) {
        setRearWindowLock(true)
        tempTargets().forEach { a ->
            call("온도") {
                vehicle.getHVAC().setHvacTemperature(a, tempC, TemperatureUnit.CELSIUS,
                    { _, _ -> emit("실내 온도를 ${tempC.oneDecimal()}°C로 맞췄어요") }, { fail("온도", it) })
            }
        }
        // 풍량: 공조가 켜져 있을 때만 한 단계 올림 (꺼져 있으면 차가 거부함)
        fanChanged.clear()
        fanBounds.forEach { (a, b) ->
            val cur = savedFan[a] ?: return@forEach
            if (cur <= b.minValue || cur >= b.maxValue) return@forEach
            // 실제로 바뀐 경우에만 나중에 되돌림 (거부되면 되돌릴 것도 없음)
            call("풍량") { vehicle.getHVAC().setHvacFanSpeed(a, cur + 1, { _, _ -> fanChanged += a }, { Log.w(TAG, "fan: ${it.message}") }) }
        }
        setCalmLight(level = 0.3f)
    }

    /** 실내 조명을 차분하게. level = 0..1 (밝기 범위 안의 비율) */
    fun setCalmLight(level: Float) {
        ambientBounds.forEach { (a, b) ->
            val target = b.minValue + ((b.maxValue - b.minValue) * level).roundToInt()
            call("조명") {
                Log.i(TAG, "ambient set area=${a.values.toList()} bounds=$b target=$target")
                vehicle.getLight().setAmbientLightBrightness(a, target, { ar, v -> Log.i(TAG, "ambient ok ${ar.values.toList()}=$v") }, { fail("조명 밝기", it) })
            }
        }
        ambientColorAreas.forEach { a ->
            call("조명 색") {
                vehicle.getLight().setAmbientLightBrightnessColor(a, CALM_COLOR, { ar, v -> Log.i(TAG, "ambient color ok ${ar.values.toList()}=${Integer.toHexString(v)}") }, { fail("조명 색", it) })
            }
        }
        if (ambientBounds.isNotEmpty() || ambientColorAreas.isNotEmpty()) emit("실내 조명을 차분하게 바꿨어요")
        else emit("이 차는 실내 조명 조절을 지원하지 않아요")
    }

    /** 펫 모드를 끝낼 때 원래 값으로 되돌림. */
    fun restoreCabin() {
        setRearWindowLock(false)
        savedTemp.forEach { (a, v) -> call("온도 복원") { vehicle.getHVAC().setHvacTemperature(a, v, TemperatureUnit.CELSIUS, { _, _ -> }, { fail("온도 복원", it) }) } }
        savedFan.filterKeys { it in fanChanged }.forEach { (a, v) -> call("풍량 복원") { vehicle.getHVAC().setHvacFanSpeed(a, v, { _, _ -> }, { fail("풍량 복원", it) }) } }
        savedAmbient.forEach { (a, v) -> call("조명 복원") { vehicle.getLight().setAmbientLightBrightness(a, v, { _, _ -> }, { fail("조명 복원", it) }) } }
        savedAmbientColor.forEach { (a, v) -> call("조명 색 복원") { vehicle.getLight().setAmbientLightBrightnessColor(a, v, { _, _ -> }, { fail("조명 색 복원", it) }) } }
        emit("차량 설정을 원래대로 되돌렸어요")
    }

    fun setRearWindowLock(lock: Boolean) {
        val rear = windowLockAreas.filter { it.values.any { v -> v == WindowValue.ROW_2_LEFT || v == WindowValue.ROW_2_RIGHT } }
        val targets = if (lock) {
            rear.ifEmpty { windowLockAreas }.ifEmpty { listOf(WindowArea(WindowValue.ROW_2_LEFT), WindowArea(WindowValue.ROW_2_RIGHT)) }
        } else {
            // 풀 때는 차가 알려 준 잠금 영역 전부 (차마다 잠금 단위가 달라서 확실히 풀리게).
            // 지원하지 않는 영역에 명령을 보내면 오류만 쌓이므로, 영역 정보를 못 받았을 때만 창문 하나하나로 보냄
            windowLockAreas.ifEmpty { listOf(WindowValue.ROW_1_LEFT, WindowValue.ROW_1_RIGHT, WindowValue.ROW_2_LEFT, WindowValue.ROW_2_RIGHT).map { WindowArea(it) } }
        }
        targets.forEach { a ->
            call("창문 잠금") {
                vehicle.getWindow().setWindowLock(a, lock, { ar, v -> Log.i(TAG, "window lock ${ar.values.toList()} -> $v") }, { fail("창문 잠금", it) })
            }
        }
        emit(if (lock) "창문 버튼을 잠갔어요" else "창문 잠금을 풀었어요")
    }

    /** F3 바람 쐬기: 반려견 쪽 뒷창문을 percent(0..100)만큼만 엶. */
    fun openPetWindow(side: SeatSide, percent: Int) {
        val area = petWindow(side)
        val b = windowPosBounds[area] ?: AreaBounds(0, 10)
        call("창문 열기") {
            val value = (b.minValue + (b.maxValue - b.minValue) * percent / 100f).roundToInt().coerceAtLeast(b.minValue + 1).coerceAtMost(b.maxValue)
            Log.i(TAG, "wind open area=${area.values.toList()} bounds=$b value=$value")
            vehicle.getWindow().setWindowPosition(area, value, { _, _ -> emit("창문을 머리가 나가지 않는 높이까지만 열었어요") }, { fail("창문 열기", it) })
        }
    }

    fun closePetWindow(side: SeatSide) {
        val area = petWindow(side)
        val b = windowPosBounds[area] ?: AreaBounds(0, 10)
        call("창문 닫기") { vehicle.getWindow().setWindowPosition(area, b.minValue, { _, _ -> }, { fail("창문 닫기", it) }) }
    }

    /** F6 하차 안전: 뒷문 잠금 유지/해제. */
    fun setRearDoorLock(lock: Boolean) {
        val targets = if (lock) rearDoorAreas() else allDoorAreas()
        targets.forEach { a ->
            call("문 잠금") {
                vehicle.getDoor().setDoorLock(a, lock, { ar, v -> Log.i(TAG, "door lock ${ar.values.toList()} -> $v") }, { fail("문 잠금", it) })
            }
        }
        emit(if (lock) "문을 잠가 뒀어요" else "문 잠금을 풀었어요")
    }

    // ---------- 도우미 ----------

    private fun rearDoorAreas(): List<DoorArea> {
        val rear = doorLockAreas.filter { it.values.any { v -> v == DoorValue.ROW_2_LEFT || v == DoorValue.ROW_2_RIGHT } }
        return rear.ifEmpty { doorLockAreas }.ifEmpty { listOf(DoorArea(DoorValue.ROW_2_LEFT), DoorArea(DoorValue.ROW_2_RIGHT)) }
    }

    /**
     * 문을 풀 때: 차가 알려 준 잠금 영역 중 '좌석 문'이 들어 있는 것 전부 (중앙 잠금 차량도 확실히 풀리게).
     * 후드 같은 영역은 건드리지 않고, 지원하지 않는 영역에는 보내지 않음 (오류 방지).
     */
    private fun allDoorAreas(): List<DoorArea> {
        val seatDoors = setOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)
        return doorLockAreas.filter { a -> a.values.any { it in seatDoors } }
            .ifEmpty { seatDoors.map { DoorArea(it) } }
    }

    private fun tempTargets(): List<SeatArea> =
        tempAreas.ifEmpty { listOf(SeatArea(SeatValue.ROW_1_LEFT), SeatArea(SeatValue.ROW_1_RIGHT)) }

    private fun petWindow(side: SeatSide): WindowArea {
        val v = if (side == SeatSide.RIGHT) WindowValue.ROW_2_RIGHT else WindowValue.ROW_2_LEFT
        return windowPosBounds.keys.firstOrNull { it.values.contains(v) } ?: WindowArea(v)
    }

    private fun seatListener(side: SeatSide) = object : SeatOccupancyListener {
        override fun onSeatOccupancyUpdated(occupancy: Boolean?) {
            if (occupancy.toString() != (if (side == SeatSide.LEFT) _state.value.rawRearLeft else _state.value.rawRearRight)) Log.i(TAG, "seat $side sdk=$occupancy")
            val occupied = occupancy == SDK_OCCUPIED
            val raw = occupancy.toString()
            update {
                val sup = it.occupancySupported || occupancy != null
                if (side == SeatSide.LEFT) it.copy(rearLeftOccupied = occupied, rawRearLeft = raw, occupancySupported = sup)
                else it.copy(rearRightOccupied = occupied, rawRearRight = raw, occupancySupported = sup)
            }
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "seat: ${e.message}") }
    }

    private fun beltListener(side: SeatSide) = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(state: Boolean?) {
            val raw = state.toString()
            if (raw != (if (side == SeatSide.LEFT) _state.value.rawBeltLeft else _state.value.rawBeltRight)) Log.i(TAG, "belt $side sdk=$state")
            val belted = state?.let { v -> v == SDK_BUCKLED }
            update {
                val sup = it.beltSupported || state != null
                if (side == SeatSide.LEFT) it.copy(rearLeftBelted = belted, rawBeltLeft = raw, beltSupported = sup)
                else it.copy(rearRightBelted = belted, rawBeltRight = raw, beltSupported = sup)
            }
        }
        override fun onFailed(e: Exception) { Log.w(TAG, "belt: ${e.message}") }
    }

    private fun List<SeatArea>.hasRearSeat() =
        any { a -> a.values.any { v -> v == SeatValue.ROW_2_LEFT || v == SeatValue.ROW_2_RIGHT } }

    private fun update(f: (CarState) -> CarState) { _state.update(f) }
    private fun emit(msg: String) { _events.tryEmit(msg) }
    @Volatile private var hvacOffNoted = false

    /** 차가 그 값을 지금 줄 수 없음 (예: 공조가 꺼져 있을 때 설정 온도) */
    private fun isNotAvailable(e: Throwable): Boolean =
        e.javaClass.simpleName.contains("NotAvailable") || e.toString().contains("PropertyNotAvailable") || (e.cause?.toString()?.contains("PropertyNotAvailable") == true)

    private fun fail(what: String, e: Throwable) {
        Log.w(TAG, "$what 실패: ${e.message}", e)
        emit("$what 실패: ${e.message ?: e.javaClass.simpleName}")
    }
    /**
     * SDK 호출은 권한 확인에 리플렉션을 써서 무거움 → 화면(메인 스레드)이 멈추지 않게 전용 스레드에서 순서대로 실행.
     */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vehicle-io") }

    /** 자주 하는 읽기: 실패해도 화면에 알리지 않고 기록만 */
    private fun quiet(what: String, block: () -> Unit) {
        if (io.isShutdown) return
        io.execute { try { block() } catch (e: Throwable) { Log.w(TAG, "$what: ${e.message}") } }
    }
    private fun call(what: String, block: () -> Unit) {
        if (io.isShutdown) return
        io.execute { try { block() } catch (e: Throwable) { fail(what, e) } }
    }

    companion object {
        private const val TAG = "PetOnboard"

        /**
         * Vehicle SDK 2.3.2 + 에뮬레이터에서는 좌석 탑승 값이 반대로 들어옴
         * (Virtual Console에서 탑승=true로 바꾸면 SDK는 false를 줌. 공개 참가작 SoftRide에서 확인된 현상).
         * 실차나 새 SDK에서 반대로 동작하면 이 값을 true로 바꾸면 됨. 로그 "seat … sdk=" 로 확인.
         */
        const val SDK_OCCUPIED = false

        /** 안전벨트: true = 버클 꽂힘 (에뮬레이터에 벨트 값이 없어 아직 실측 못 함. 로그 "belt … sdk=" 로 확인) */
        const val SDK_BUCKLED = true

        /** 차분한 실내 조명 색 (연한 파랑, ARGB) */
        const val CALM_COLOR = 0xFF8FB6FF.toInt()
    }
}

private fun VehicleGear?.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.DRIVE
}

private fun Float.oneDecimal(): String = String.format(java.util.Locale.KOREA, "%.1f", this)
