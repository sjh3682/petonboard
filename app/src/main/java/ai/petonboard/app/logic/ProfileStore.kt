package ai.petonboard.app.logic

import ai.petonboard.app.vehicle.SeatSide
import android.content.Context

/** 반려견 크기. 창문 기본 높이는 출발점일 뿐이고, 보호자가 직접 맞춰서 저장함. */
enum class PetSize(val label: String, val defaultWindPercent: Int) {
    SMALL("소형견", 15),
    MEDIUM("중형견", 25),
    LARGE("대형견", 35),
}

/** 반려견 프로필을 기기에 저장 (SharedPreferences) */
class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pet_profile", Context.MODE_PRIVATE)

    val saved: Boolean get() = prefs.getBoolean("saved", false)

    fun load(): PetProfile {
        val d = PetProfile()
        if (!saved) return d
        return PetProfile(
            name = prefs.getString("name", d.name) ?: d.name,
            breed = prefs.getString("breed", d.breed) ?: d.breed,
            size = runCatching { PetSize.valueOf(prefs.getString("size", d.size.name)!!) }.getOrDefault(d.size),
            seat = runCatching { SeatSide.valueOf(prefs.getString("seat", d.seat.name)!!) }.getOrDefault(d.seat),
            tempC = prefs.getFloat("tempC", d.tempC),
            windPercent = prefs.getInt("windPercent", d.windPercent),
            brachy = prefs.getBoolean("brachy", d.brachy),
            restIntervalMin = prefs.getInt("restIntervalMin", d.restIntervalMin),
        )
    }

    fun save(p: PetProfile) {
        prefs.edit()
            .putBoolean("saved", true)
            .putString("name", p.name)
            .putString("breed", p.breed)
            .putString("size", p.size.name)
            .putString("seat", p.seat.name)
            .putFloat("tempC", p.tempC)
            .putInt("windPercent", p.windPercent)
            .putBoolean("brachy", p.brachy)
            .putInt("restIntervalMin", p.restIntervalMin)
            .apply()
    }
}
