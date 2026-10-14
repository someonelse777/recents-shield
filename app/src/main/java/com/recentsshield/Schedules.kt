package com.recentsshield

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

/**
 * A rule: hide [apps] on the given [days] between [startMin] and [endMin] (minutes after
 * midnight, phone's local time).
 *  - startMin == endMin means all day.
 *  - startMin > endMin runs past midnight: e.g. Fri 22:00–02:00 also covers early Saturday.
 * Days use Calendar constants: SUNDAY = 1 … SATURDAY = 7.
 */
data class Schedule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val days: Set<Int> = (1..7).toSet(),
    val startMin: Int = 9 * 60,
    val endMin: Int = 17 * 60,
    val apps: Set<String> = emptySet(),
) {
    val allDay get() = startMin == endMin

    fun isActive(cal: Calendar): Boolean {
        if (!enabled || days.isEmpty()) return false
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val min = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val yesterday = if (day == Calendar.SUNDAY) Calendar.SATURDAY else day - 1
        return when {
            allDay -> day in days
            startMin < endMin -> day in days && min >= startMin && min < endMin
            else -> (day in days && min >= startMin) || (yesterday in days && min < endMin)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("enabled", enabled)
        .put("days", JSONArray(days.sorted()))
        .put("start", startMin)
        .put("end", endMin)
        .put("apps", JSONArray(apps.sorted()))

    companion object {
        fun fromJson(o: JSONObject): Schedule {
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: emptySet()
            val apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            return Schedule(
                id = o.optString("id", UUID.randomUUID().toString()),
                name = o.optString("name", ""),
                enabled = o.optBoolean("enabled", true),
                days = days,
                startMin = o.optInt("start", 0),
                endMin = o.optInt("end", 0),
                apps = apps,
            )
        }
    }
}

object Schedules {
    const val KEY = "schedules"

    fun load(c: Context): List<Schedule> {
        val raw = Prefs.get(c).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { Schedule.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(c: Context, list: List<Schedule>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        Prefs.get(c).edit().putString(KEY, a.toString()).apply()
    }

    fun upsert(c: Context, s: Schedule) {
        val list = load(c).toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        if (i >= 0) list[i] = s else list.add(s)
        save(c, list)
    }

    fun delete(c: Context, id: String) = save(c, load(c).filter { it.id != id })

    /** Packages that should be hidden right now: the "always" list plus any active schedule. */
    fun activePackages(always: Set<String>, schedules: List<Schedule>, cal: Calendar = Calendar.getInstance()): Set<String> {
        val out = HashSet(always)
        for (s in schedules) if (s.isActive(cal)) out.addAll(s.apps)
        return out
    }

    // ---- formatting for the UI

    private val DAY_SHORT = mapOf(1 to "Sun", 2 to "Mon", 3 to "Tue", 4 to "Wed", 5 to "Thu", 6 to "Fri", 7 to "Sat")
    val WEEK_ORDER = listOf(2, 3, 4, 5, 6, 7, 1) // Mon … Sun

    fun dayShort(d: Int) = DAY_SHORT[d] ?: "?"

    fun daysText(days: Set<Int>): String = when {
        days.size == 7 -> "Every day"
        days.isEmpty() -> "No days"
        days == setOf(2, 3, 4, 5, 6) -> "Weekdays"
        days == setOf(1, 7) -> "Weekends"
        else -> WEEK_ORDER.filter { it in days }.joinToString(", ") { dayShort(it) }
    }

    fun timeText(c: Context, min: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, min / 60)
            set(Calendar.MINUTE, min % 60)
        }
        return android.text.format.DateFormat.getTimeFormat(c).format(cal.time)
    }

    fun rangeText(c: Context, s: Schedule): String =
        if (s.allDay) "All day"
        else "${timeText(c, s.startMin)} – ${timeText(c, s.endMin)}" + if (s.startMin > s.endMin) " (next day)" else ""
}
