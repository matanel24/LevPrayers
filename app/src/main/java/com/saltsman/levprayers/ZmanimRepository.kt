package com.saltsman.levprayers

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class Zman(val name: String, val minutes: Int) {
    val timeText: String
        get() = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
}

data class City(val id: Int, val name: String, val shortName: String = name)

data class ZmanimDay(
    val city: City,
    val date: String,
    val zmanim: List<Zman>,
    val fetchedAt: Long,
    /** true when the times are not for today. */
    val stale: Boolean = false,
    val failed: Boolean = false,
)

/** Day times from yeshiva.org.il (the same data as https://www.yeshiva.org.il/calendar/timesday/). */
object ZmanimRepository {

    /** Place ids from yeshiva.org.il's place picker. */
    val CITIES = listOf(
        City(156, "ירושלים"),
        City(211, "קרני שומרון"),
        City(436, "עזה"),
        City(153, "טבריה"),
        City(130, "אילת"),
        City(233, "רמת הגולן (קצרין)", "קצרין"),
    )

    const val SITE_URL = "https://www.yeshiva.org.il/calendar/timesday/"
    private const val API_URL = "https://www.yeshiva.org.il/api/times/AllDailyTimes?place="
    private const val PREFS = "zmanim"
    private const val KEY_CITY = "city"

    fun selectedCity(context: Context): City {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CITY, CITIES[0].id)
        return CITIES.firstOrNull { it.id == id } ?: CITIES[0]
    }

    fun setCity(context: Context, city: City) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CITY, city.id).apply()
    }

    /** Index of the next zman (from this minute on), or -1 when today's are over. */
    fun nextIndex(zmanim: List<Zman>, now: Int = PrayerRepository.nowMinutes()): Int =
        zmanim.indexOfFirst { it.minutes >= now }

    /** Today's times for [city]; cached per city per day. Must be called off the main thread. */
    fun load(context: Context, force: Boolean = false, city: City = selectedCity(context)): ZmanimDay? {
        val today = PrayerRepository.today()
        val cached = readCache(context, city)
        if (!force && cached != null && cached.date == today && cached.zmanim.isNotEmpty()) return cached
        return try {
            val (date, zmanim) = fetch(city)
            val day = ZmanimDay(city, date, zmanim, System.currentTimeMillis(), stale = date != today)
            // Don't let an older answer (server cache around midnight) replace today's cached times.
            if (!(day.stale && cached?.date == today)) writeCache(context, day)
            if (day.stale && cached?.date == today) cached else day
        } catch (e: Exception) {
            cached?.copy(stale = cached.date != today, failed = true)
        }
    }

    private fun fetch(city: City): Pair<String, List<Zman>> {
        val conn = URL(API_URL + city.id).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LevPrayersWidget/1.0")
        conn.setRequestProperty("Accept", "application/json")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /** {"standardTimes": {"date": {"eng": {"ldate": "17/11/2026"}}, "times": [{"name": "...", "value": "4:37"}, ...]}} */
    fun parse(body: String): Pair<String, List<Zman>> {
        val st = JSONObject(body).getJSONObject("standardTimes")
        val ldate = st.getJSONObject("date").getJSONObject("eng").getString("ldate")
        val date = LocalDate.parse(ldate.trim(), DateTimeFormatter.ofPattern("d/M/yyyy")).toString()
        val arr = st.getJSONArray("times")
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val m = Regex("^(\\d{1,2}):(\\d{2})").find(o.optString("value").trim()) ?: return@mapNotNull null
            Zman(o.getString("name").trim(), m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt())
        }
        if (list.isEmpty()) throw IOException("No times in response")
        return date to list
    }

    private fun writeCache(context: Context, d: ZmanimDay) {
        val arr = JSONArray()
        d.zmanim.forEach { arr.put(JSONObject().put("n", it.name).put("m", it.minutes)) }
        val json = JSONObject().put("date", d.date).put("fetchedAt", d.fetchedAt).put("z", arr)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("cache_${d.city.id}", json.toString()).apply()
    }

    private fun readCache(context: Context, city: City): ZmanimDay? {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cache_${city.id}", null) ?: return null
        return try {
            val json = JSONObject(s)
            val arr = json.getJSONArray("z")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Zman(o.getString("n"), o.getInt("m"))
            }
            ZmanimDay(city, json.getString("date"), list, json.getLong("fetchedAt"))
        } catch (e: Exception) {
            null
        }
    }
}
