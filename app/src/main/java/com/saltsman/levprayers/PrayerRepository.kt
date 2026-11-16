package com.saltsman.levprayers

import android.content.Context
import android.text.Html
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

data class Prayer(
    val name: String,
    val location: String?,
    val minutes: Int,
    val note: String? = null,
) {
    val timeText: String
        get() = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
}

data class DaySchedule(
    val date: String,
    val prayers: List<Prayer>,
    /** When the website was last downloaded (0 = never). */
    val fetchedAt: Long,
    /** true when the website data is not from today (offline, couldn't refresh). */
    val stale: Boolean = false,
    /** true when the last network attempt failed. */
    val failed: Boolean = false,
    /** Sunrise in minutes after midnight, from the website. */
    val sunrise: Int? = null,
    /** true when a WhatsApp schedule was merged in. */
    val hasWhatsApp: Boolean = false,
)

object PrayerRepository {

    const val PAGE_URL =
        "https://midrash.jct.ac.il/%D7%96%D7%9E%D7%A0%D7%99-%D7%94%D7%99%D7%95%D7%9D?hideHeader=1"

    val ZONE: ZoneId = ZoneId.of("Asia/Jerusalem")
    private const val PREFS = "prayer_cache"
    private const val KEY = "schedule"

    /** A prayer stays "next" until this many minutes after it starts. */
    const val GRACE_MINUTES = 5

    fun today(): String = LocalDate.now(ZONE).toString()

    fun nowMinutes(): Int {
        val t = LocalTime.now(ZONE)
        return t.hour * 60 + t.minute
    }

    /** Index of the next prayer (not yet GRACE_MINUTES past its start), or -1 when today's are over. */
    fun nextIndex(prayers: List<Prayer>, now: Int = nowMinutes()): Int =
        prayers.indexOfFirst { it.minutes + GRACE_MINUTES > now }

    /**
     * Today's schedule: website + WhatsApp list, merged without duplicates.
     * Must be called off the main thread.
     */
    fun load(context: Context, forceRefresh: Boolean = false): DaySchedule? {
        try {
            WhatsAppSchedule.syncShared(context, forceRefresh)
        } catch (e: Exception) {
        }
        val site = loadSite(context, forceRefresh)
        val siteToday = site?.takeIf { !it.stale }
        val wa = WhatsAppSchedule.forToday(context, siteToday?.sunrise)

        if (site == null) {
            if (wa.isEmpty()) return null
            return DaySchedule(today(), sorted(wa), 0L, failed = true, hasWhatsApp = true)
        }
        if (wa.isEmpty()) return site
        // Stale website data is from another day: show today's WhatsApp list instead of mixing days.
        val base = if (site.stale) emptyList() else site.prayers
        return site.copy(prayers = merge(base, wa), hasWhatsApp = true)
    }

    // ---- merging ----

    /** Adds WhatsApp minyanim to the website list; same prayer + time + place is shown once. */
    fun merge(site: List<Prayer>, wa: List<Prayer>): List<Prayer> {
        val result = site.toMutableList()
        for (w in wa) {
            val i = result.indexOfFirst { s ->
                s.minutes == w.minutes &&
                    category(s.name) == category(w.name) &&
                    (s.location == null || w.location == null || sameLocation(s.location, w.location))
            }
            if (i >= 0) {
                val s = result[i]
                result[i] = s.copy(location = s.location ?: w.location, note = s.note ?: w.note)
            } else {
                result += w
            }
        }
        return sorted(result)
    }

    private fun sorted(list: List<Prayer>) =
        list.sortedWith(compareBy<Prayer>({ it.minutes }, { it.location ?: "" }))

    private fun category(name: String): Int = when {
        name.contains("מנחה") -> 1
        name.contains("ערבית") || name.contains("מעריב") -> 2
        else -> 0 // שחרית / הודו / קרבנות
    }

    /** "בית המדרש" == "בית מדרש", "אודיטוריום" == "באודיטוריום". */
    private fun sameLocation(a: String, b: String): Boolean = normLocation(a) == normLocation(b)

    private fun normLocation(s: String): String =
        s.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }.joinToString("") { w ->
            if (w.length > 2 && (w.startsWith("ה") || w.startsWith("ב"))) w.substring(1) else w
        }

    // ---- website ----

    private fun loadSite(context: Context, forceRefresh: Boolean): DaySchedule? {
        val cached = readCache(context)
        if (!forceRefresh && cached != null && cached.date == today() && cached.prayers.isNotEmpty()) {
            return cached
        }
        return try {
            val (prayers, sunrise) = fetch()
            val schedule = DaySchedule(today(), prayers, System.currentTimeMillis(), sunrise = sunrise)
            writeCache(context, schedule)
            schedule
        } catch (e: Exception) {
            cached?.copy(stale = cached.date != today(), failed = true)
        }
    }

    private fun fetch(): Pair<List<Prayer>, Int?> {
        val conn = URL(PAGE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LevPrayersWidget/1.0")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val prayers = parse(html)
            if (prayers.isEmpty()) throw IOException("No prayers found on page")
            return prayers to parseSunrise(html)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The page has:
     * <div class="grayBoxText prayTimes"> <dl> <dt>מנחה - בבית המדרש</dt> <dd>12:45</dd> ... </dl>
     */
    fun parse(html: String): List<Prayer> {
        val start = Regex("class=\"[^\"]*\\bprayTimes\\b").find(html)?.range?.first
            ?: html.indexOf("prayTimes")
        if (start < 0) return emptyList()
        val end = html.indexOf("</dl>", start)
        val block = html.substring(start, if (end > start) end else html.length)

        val pair = Regex(
            "<dt[^>]*>(.*?)</dt>\\s*<dd[^>]*>\\s*(\\d{1,2}):(\\d{2})\\s*</dd>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val tooltip = Regex("<div[^>]*tooltip[^>]*>.*?</div>", RegexOption.DOT_MATCHES_ALL)

        return pair.findAll(block).map { m ->
            val rawName = m.groupValues[1].replace(tooltip, "")
            val text = Html.fromHtml(rawName, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace(' ', ' ').trim()
            val minutes = m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()
            splitName(text, minutes)
        }.toList().sortedBy { it.minutes }
    }

    /** "זריחה" in the day-times box, e.g. <dt>זריחה</dt><dd>06:07</dd>. */
    private fun parseSunrise(html: String): Int? {
        val m = Regex("זריחה(?:(?!</dl>).){0,200}?(\\d{1,2}):(\\d{2})", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    /** "מנחה - בבית המדרש" -> name "מנחה", location "בית המדרש". "שחרית - קרבנות" stays as is. */
    private fun splitName(text: String, minutes: Int): Prayer {
        val idx = text.indexOf(" - ")
        if (idx > 0) {
            val base = text.substring(0, idx).trim()
            val rest = text.substring(idx + 3).trim()
            if (rest.length > 2 && rest.startsWith("ב")) {
                return Prayer(base, rest.substring(1).trim(), minutes)
            }
        }
        return Prayer(text, null, minutes)
    }

    private fun writeCache(context: Context, s: DaySchedule) {
        val arr = JSONArray()
        s.prayers.forEach { p ->
            val o = JSONObject().put("n", p.name).put("m", p.minutes)
            if (p.location != null) o.put("l", p.location)
            arr.put(o)
        }
        val json = JSONObject()
            .put("date", s.date)
            .put("fetchedAt", s.fetchedAt)
            .put("prayers", arr)
        if (s.sunrise != null) json.put("sunrise", s.sunrise)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, json.toString()).apply()
    }

    private fun readCache(context: Context): DaySchedule? {
        val str = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return null
        return try {
            val json = JSONObject(str)
            val arr = json.getJSONArray("prayers")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Prayer(o.getString("n"), if (o.has("l")) o.getString("l") else null, o.getInt("m"))
            }
            DaySchedule(
                json.getString("date"), list, json.getLong("fetchedAt"),
                sunrise = if (json.has("sunrise")) json.getInt("sunrise") else null,
            )
        } catch (e: Exception) {
            null
        }
    }
}
