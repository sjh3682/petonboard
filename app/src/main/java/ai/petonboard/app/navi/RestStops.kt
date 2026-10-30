package ai.petonboard.app.navi

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 반려견 놀이터가 있는 고속도로 휴게소.
 * 공공데이터(휴게소 편의시설)에는 반려견 놀이터 항목이 없어 직접 정리함.
 * - 목록: 한국도로공사 자료를 인용한 뉴시스 기사(2025.10, 2025년 6월 기준 20곳 중 이름이 공개된 8곳)
 * - 좌표: OpenStreetMap(Nominatim) 휴게소 위치
 * 방향이 있는 휴게소는 그 방향 차로에서만 들어갈 수 있음.
 */
data class RestStop(
    val name: String,
    val road: String,
    val direction: String,   // "양방향" 또는 "○○ 방향"
    val facility: String,
    val lat: Double,
    val lon: Double,
)

object RestStops {
    val ALL = listOf(
        RestStop("덕평자연휴게소", "영동고속도로", "양방향", "반려견 놀이터 '달려라 코코'", 37.2415059, 127.3894699),
        RestStop("용인휴게소", "영동고속도로", "강릉 방향", "반려견 놀이터", 37.2455373, 127.2423525),
        RestStop("죽암휴게소", "경부고속도로", "서울 방향", "반려견 놀이터 '멍멍파크'", 36.4970182, 127.4305107),
        RestStop("충주휴게소", "중부내륙고속도로", "양평 방향", "반려견 놀이터", 37.0227156, 127.8395231),
        RestStop("오수휴게소", "순천완주고속도로", "전주 방향", "펫 테마파크", 35.5405185, 127.3104631),
        RestStop("군산휴게소", "서해안고속도로", "서울 방향", "반려견 놀이터 '놀다가개'", 36.0191372, 126.7971342),
        RestStop("화서휴게소", "서산영덕고속도로", "영덕 방향", "반려견 놀이터", 36.4442747, 127.9249940),
        RestStop("진주휴게소", "남해고속도로", "부산 방향", "반려견 놀이터 '폴짝'", 35.1600171, 128.1220768),
    )

    /**
     * 지금 위치와 진행 방향을 기준으로 고름.
     * 1) 앞쪽(진행 방향 ±70°)에 있고 100km 안인 곳 중 가장 가까운 곳
     * 2) 없으면 150km 안에서 가장 가까운 곳
     * 3) 150km 안에 하나도 없으면 null (엉뚱한 곳을 권하지 않음)
     */
    fun pick(lat: Double, lon: Double, headingDeg: Double?, exclude: String? = null): Pick? {
        val all = ALL.filter { it.name != exclude }.map { Pick(it, distanceKm(lat, lon, it.lat, it.lon), bearing(lat, lon, it.lat, it.lon)) }
        val ahead = if (headingDeg == null) emptyList() else all.filter { angleDiff(it.bearingDeg, headingDeg) <= 70 && it.distanceKm <= 100 }
        return ahead.minByOrNull { it.distanceKm } ?: all.filter { it.distanceKm <= MAX_KM }.minByOrNull { it.distanceKm }
    }

    private const val MAX_KM = 150.0

    data class Pick(val stop: RestStop, val distanceKm: Double, val bearingDeg: Double)

    private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(Math.toRadians(lon2 - lon1)) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(Math.toRadians(lon2 - lon1))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}
