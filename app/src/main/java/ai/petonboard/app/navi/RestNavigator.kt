package ai.petonboard.app.navi

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log

/**
 * 휴게소 길 안내.
 * - 위치·진행 방향: Pleos FusedLocation (값이 안 오면 안드로이드 GPS로 보완)
 * - 안내: Pleos NaviHelper. 이미 목적지가 있으면 휴게소를 '경유지'로 넣어 원래 목적지를 유지하고,
 *   목적지가 없으면 휴게소를 목적지로 안내함.
 */
class RestNavigator(context: Context) {
    private val ctx = context.applicationContext
    private val navi = NaviHelper(ctx)
    private val fused = FusedLocation(ctx)

    @Volatile var lat: Double? = null; private set
    @Volatile var lon: Double? = null; private set
    @Volatile var heading: Double? = null; private set
    @Volatile private var destination: String? = null
    @Volatile private var naviReady = false
    @Volatile private var guidedAtMs = 0L

    /** 안내 결과를 화면에 알리기 위한 콜백 (메인 스레드 아님) */
    var onMessage: (String) -> Unit = {}
    private var locLogged = false

    private val naviListener = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            val name = destinationInfo.name?.takeIf { it.isNotBlank() }
            // 경유지를 넣은 직후 경로를 다시 짜는 동안에는 목적지 이름이 비어서 올 수 있음 → 원래 목적지를 유지
            val justGuided = System.currentTimeMillis() - guidedAtMs < 120_000L
            if (name != null || !justGuided) destination = name
            onMessage("[내비] 목적지: ${destination ?: "없음"}")
            Log.i(TAG, "destinationInfo=$destinationInfo")
        }
        override fun onRoutingCompleted() {
            Log.i(TAG, "navi routing completed")
            runCatching { navi.getDestinationInfo() }
        }
        override fun onError(errorCode: NaviErrorCode) {
            Log.w(TAG, "navi error $errorCode")
            onMessage("[내비] 오류: $errorCode")
        }
    }

    private fun setLocation(la: Double, lo: Double, hd: Double?, from: String) {
        lat = la; lon = lo
        if (hd != null) heading = hd
        if (!locLogged) { locLogged = true; onMessage("[위치] %.4f, %.4f (%s)".format(la, lo, from)) }
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            setLocation(location.latitude.toDouble(), location.longitude.toDouble(), location.heading?.toDouble(), "Pleos")
        }
        override fun onError(exception: Exception) { onMessage("[위치] 오류: ${exception.message}") }
    }

    private val lm = ctx.getSystemService(LocationManager::class.java)
    private var platformStarted = false
    private val platformListener = LocationListener { l: Location ->
        setLocation(l.latitude, l.longitude, if (l.hasBearing()) l.bearing.toDouble() else null, "GPS")
    }

    /** 위치 권한을 받은 뒤 호출: 안드로이드 GPS도 함께 들음 (Pleos 위치가 안 올 때 대비) */
    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (platformStarted) return
        platformStarted = true
        runCatching {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")) {
                if (!lm.allProviders.contains(p)) continue
                lm.getLastKnownLocation(p)?.let { setLocation(it.latitude, it.longitude, null, "GPS") }
                lm.requestLocationUpdates(p, 5_000L, 10f, platformListener, Looper.getMainLooper())
            }
        }.onFailure { onMessage("[위치] GPS 실패: ${it.message}") }
    }

    fun start() {
        runCatching {
            navi.initialize({
                naviReady = true
                runCatching { navi.getDestinationInfo() }
                onMessage("[내비] 연결됨")
            }, { onMessage("[내비] 연결 실패: ${it.message}") })
            navi.addListener(naviListener)
        }.onFailure { Log.w(TAG, "navi: ${it.message}") }
        runCatching {
            fused.initialize()
            if (fused.isSupported()) fused.registerFusedLocationCallback(locationListener)
            else onMessage("[위치] 지원 안 됨")
        }.onFailure { Log.w(TAG, "location: ${it.message}") }
    }

    fun stop() {
        runCatching { navi.removeListener(naviListener); navi.release() }
        runCatching { fused.unregisterFusedLocationCallback(locationListener); fused.release() }
        runCatching { lm.removeUpdates(platformListener) }
    }

    /** 지금 위치 기준으로 고를 휴게소 (위치를 모르면 null) */
    fun pick(): RestStops.Pick? {
        val la = lat ?: return null
        val lo = lon ?: return null
        // 이미 그 휴게소로 가는 중이면 다른 곳을 고름
        return RestStops.pick(la, lo, heading, exclude = destination)
    }

    /** 휴게소로 안내 시작. 반환값: 화면/음성으로 알릴 문장 */
    fun guideTo(stop: RestStop): String {
        if (!naviReady) return "내비게이션과 연결되지 않아 안내를 시작하지 못했어요"
        val dest = destination
        guidedAtMs = System.currentTimeMillis()
        return runCatching {
            if (dest != null) {
                navi.addWaypoint(
                    RequestWaypointInfo(
                        latitude = stop.lat,
                        longitude = stop.lon,
                        waypointIndex = WaypointIndex.FIRST,
                        poiId = "0",
                        poiName = stop.name,
                        poiSubId = "0",
                        address = "${stop.road} ${stop.name}",
                    )
                )
                "${stop.name}를 경유지로 넣었어요. 쉬고 나면 ${dest}로 이어서 안내해요"
            } else {
                navi.requestRoute(
                    RouteInfo(
                        latitude = stop.lat,
                        longitude = stop.lon,
                        poiName = stop.name,
                        poiId = "0",
                        address = "${stop.road} ${stop.name}",
                        poiSubId = "0",
                    )
                )
                "${stop.name}로 안내를 시작해요"
            }
        }.getOrElse {
            onMessage("[내비] 안내 실패: ${it.message}")
            "길 안내를 시작하지 못했어요"
        }
    }

    companion object { private const val TAG = "PetOnboard" }
}
