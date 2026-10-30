package ai.petonboard.app.logic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** 저장해 두는 주행 한 번의 요약 (기록 화면·패턴 계산용) */
data class TripSummary(
    val startMs: Long,
    val durationMs: Long,
    val distanceKm: Float,
    val shakes: Int,      // 급커브 + 급제동
    val restCount: Int,
    val windCount: Int,
    val barkCount: Int = 0,
    val whineCount: Int = 0,
    val soundAfterShake: Int = 0,
    val firstSoundMin: Double = -1.0,
) {
    val soundCount: Int get() = barkCount + whineCount
    val minutes: Double get() = durationMs / 60_000.0

    /** 시간당 흔들림. 아주 짧은 주행이 값을 부풀리지 않게 최소 10분으로 계산 */
    val shakesPerHour: Double get() = shakes / max(minutes, 10.0) * 60.0
}

/** 주행 기록을 기기에 쌓아 둠 (최근 50번) */
class TripStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("trips", Context.MODE_PRIVATE)

    fun load(): List<TripSummary> = runCatching {
        val arr = JSONArray(prefs.getString("list", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TripSummary(
                startMs = o.getLong("start"),
                durationMs = o.getLong("dur"),
                distanceKm = o.getDouble("km").toFloat(),
                shakes = o.getInt("shakes"),
                restCount = o.getInt("rest"),
                windCount = o.getInt("wind"),
                barkCount = o.optInt("bark", 0),
                whineCount = o.optInt("whine", 0),
                soundAfterShake = o.optInt("soundShake", 0),
                firstSoundMin = o.optDouble("firstSound", -1.0),
            )
        }
    }.getOrDefault(emptyList())

    /** 새 주행을 맨 앞에 추가하고 전체 목록(최신순)을 돌려줌 */
    fun add(r: TripReport): List<TripSummary> {
        val t = TripSummary(r.startMs, r.durationMs, r.distanceKm, r.sharpTurns + r.hardBrakes, r.restCount, r.windCount,
            r.barkCount, r.whineCount, r.soundAfterShake, r.firstSoundMin)
        val list = (listOf(t) + load()).take(MAX)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("start", it.startMs); put("dur", it.durationMs); put("km", it.distanceKm.toDouble())
                put("shakes", it.shakes); put("rest", it.restCount); put("wind", it.windCount)
                put("bark", it.barkCount); put("whine", it.whineCount); put("soundShake", it.soundAfterShake); put("firstSound", it.firstSoundMin)
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
        return list
    }

    fun clear() = prefs.edit().remove("list").apply()

    companion object { private const val MAX = 50 }
}

/**
 * 쌓인 기록에서 '우리 개' 패턴을 뽑음.
 * - 흔들림 비교: 이번 주행 vs 이전 평균 (시간당 횟수)
 * - 긴 주행 휴식: 90분 넘게 달린 주행 중 쉬지 않은 횟수
 *   (근거: Herbel et al. 2020, 2시간 이동에서 둘째 시간 스트레스가 첫 시간의 2배 이상)
 */
object TripInsights {
    const val LONG_TRIP_MIN = 90.0

    data class Result(
        val count: Int,
        val avgMinutes: Double,
        val avgShakesPerHour: Double,
        val lines: List<String>,
    )

    /** trips: 최신순. 첫 번째가 방금 끝난(또는 가장 최근) 주행 */
    fun of(trips: List<TripSummary>, name: String): Result? {
        if (trips.isEmpty()) return null
        val lines = mutableListOf<String>()
        val latest = trips.first()
        val prev = trips.drop(1)
        if (prev.isNotEmpty()) {
            val before = prev.map { it.shakesPerHour }.average()
            val now = latest.shakesPerHour
            lines += when {
                before < 0.5 && now < 0.5 -> "흔들림 없이 부드럽게 달리고 있어요"
                now <= before * 0.8 -> "이번엔 지난 평균보다 흔들림이 줄었어요 (시간당 ${fmt(before)} → ${fmt(now)}회)"
                now >= before * 1.2 + 0.5 -> "이번엔 지난 평균보다 흔들림이 많았어요 (시간당 ${fmt(before)} → ${fmt(now)}회)"
                else -> "흔들림은 평소와 비슷했어요 (시간당 ${fmt(now)}회)"
            }
        } else {
            lines += "첫 기록이에요. 다음 주행부터 ${name}의 평소와 비교해 드릴게요"
        }
        // F4: 불안 소리
        if (latest.soundCount > 0) {
            lines += "이번 주행에서 낑낑거림 ${latest.whineCount}번 · 짖음 ${latest.barkCount}번이 들렸어요"
        }
        val allSounds = trips.sumOf { it.soundCount }
        val linked = trips.sumOf { it.soundAfterShake }
        if (allSounds >= 3 && linked * 10 >= allSounds * 4) {
            lines += "${name}의 불안 소리 ${allSounds}번 중 ${linked}번이 급커브·급제동 30초 안에 났어요"
        }
        val longTrips = trips.filter { it.minutes >= LONG_TRIP_MIN }
        val noRest = longTrips.count { it.restCount == 0 }
        if (noRest > 0) {
            lines += "1시간 30분 넘게 달린 ${longTrips.size}번 중 ${noRest}번은 쉬지 않았어요. 이동이 길어질수록 개의 스트레스가 커져요"
        }
        return Result(
            count = trips.size,
            avgMinutes = trips.map { it.minutes }.average(),
            avgShakesPerHour = trips.map { it.shakesPerHour }.average(),
            lines = lines,
        )
    }

    data class Adjust(val newMin: Int, val typicalMin: Int)

    /**
     * 불안 소리가 처음 난 시점이 기록된 주행이 2번 이상이면, 그 중간값보다 10분 일찍 쉬도록 휴식 간격을 제안.
     * 지금 간격보다 짧아질 때만 바꿈 (최소 40분). 근거: 개마다 스트레스 반응이 크게 다름 (Flint 2025, ICC 0.58)
     */
    fun suggestRestInterval(trips: List<TripSummary>, currentMin: Int): Adjust? {
        val firsts = trips.take(10).map { it.firstSoundMin }.filter { it >= 0 }.sorted()
        if (firsts.size < 2) return null
        val median = firsts[firsts.size / 2]
        val candidate = maxOf(Tuning.MIN_REST_INTERVAL_MIN, (((median - 10) / 10).toInt()) * 10)
        if (candidate >= currentMin) return null
        return Adjust(candidate, maxOf(1, median.toInt()))
    }

    private fun fmt(v: Double) = if (v >= 10) v.toInt().toString() else String.format(java.util.Locale.KOREA, "%.1f", v)
}
