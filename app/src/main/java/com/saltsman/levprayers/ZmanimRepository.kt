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

data class Zman(val name: String, val minutes: Int, val detail: String? = null) {
    val timeText: String
        get() = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
}

/** [slug] is the city's page on kipa.co.il: https://www.kipa.co.il/זמני-היום/<slug>/ */
data class City(val id: Int, val name: String, val slug: String, val shortName: String = name) {
    val pageUrl: String
        get() = java.net.URI("https", "www.kipa.co.il", "/זמני-היום/$slug/", null).toASCIIString()
}

data class ZmanimDay(
    val city: City,
    val date: String,
    val zmanim: List<Zman>,
    val fetchedAt: Long,
    /** true when the times are not for today. */
    val stale: Boolean = false,
    val failed: Boolean = false,
)

/**
 * Day times from kipa.co.il (https://www.kipa.co.il/זמני-היום/ירושלים/).
 * (yeshiva.org.il blocks use outside its own site, so it isn't used.)
 */
object ZmanimRepository {

    /** ids are only for remembering the choice on the phone. */
    val CITIES = listOf(
        City(156, "ירושלים", "ירושלים"),
        City(211, "קרני שומרון", "קרני-שומרון"),
        City(153, "טבריה", "טבריה"),
        City(130, "אילת", "אילת"),
        City(233, "רמת הגולן (קצרין)", "קצרין", "קצרין"),
    )
    private const val PREFS = "zmanim"
    private const val KEY_CITY = "city"

    private const val KEY_CUSTOM = "custom_cities"

    fun selectedCity(context: Context): City {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CITY, CITIES[0].id)
        return allCities(context).firstOrNull { it.id == id } ?: CITIES[0]
    }

    // ---- places the user added ----

    /** Built-in places + the ones added on this phone. */
    fun allCities(context: Context): List<City> = CITIES + customCities(context)

    fun customCities(context: Context): List<City> {
        val s = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CUSTOM, null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                customCity(o.getString("n"), o.getString("s"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun customCity(name: String, slug: String) = City(0x40000000 or (slug.hashCode() and 0x3FFFFFFF), name, slug)

    fun addCustom(context: Context, city: City): City {
        val list = customCities(context).filter { it.slug != city.slug } + city
        saveCustom(context, list)
        return city
    }

    fun removeCustom(context: Context, city: City) {
        val wasSelected = selectedCity(context).slug == city.slug
        saveCustom(context, customCities(context).filter { it.slug != city.slug })
        if (wasSelected) setCity(context, CITIES[0])
    }

    private fun saveCustom(context: Context, list: List<City>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("n", it.name).put("s", it.slug)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CUSTOM, arr.toString()).apply()
    }

    /**
     * Looks a place up on kipa.co.il by the name the user typed ("בית שמש" -> /זמני-היום/בית-שמש/).
     * Returns the place with Kipa's own name for it, or null if Kipa has no page for it.
     * Must be called off the main thread.
     */
    fun lookup(input: String): City? {
        val slug = input.trim().replace(Regex("[\\s_]+"), "-").trim('-')
        if (slug.isEmpty()) return null
        var url = URL(customCity(slug, slug).pageUrl)
        repeat(3) {
            val conn = open(url)
            try {
                when (conn.responseCode) {
                    in 200..299 -> {
                        val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        parse(html) // throws if there are no times on the page
                        val finalSlug = slugFromPath(url.toURI().path) ?: return null
                        val name = Regex("<h1>\\s*זמני היום ב(.+?)\\s*</h1>").find(html)?.groupValues?.get(1)
                            ?.let { clean(it) }?.ifEmpty { null } ?: input.trim()
                        return customCity(name, finalSlug)
                    }
                    301, 302, 307, 308 -> {
                        val loc = conn.getHeaderField("Location") ?: return null
                        val next = URL(url, loc)
                        // Kipa sends unknown places to the general page; only follow redirects to another place page.
                        if (slugFromPath(next.toURI().path) == null) return null
                        url = next
                    }
                    else -> return null
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun slugFromPath(path: String?): String? =
        path?.let { Regex("^/זמני-היום/([^/]+)/?$").find(it)?.groupValues?.get(1) }

    private fun open(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) LevPrayersWidget/1.0")
        return conn
    }

    fun setCity(context: Context, city: City) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_CITY, city.id).apply()
    }

    /** A zman stays "next" until this many minutes after it passes. */
    const val GRACE_MINUTES = 5

    /** Index of the next zman (not yet GRACE_MINUTES past), or -1 when today's are over. */
    fun nextIndex(zmanim: List<Zman>, now: Int = PrayerRepository.nowMinutes()): Int =
        zmanim.indexOfFirst { it.minutes + GRACE_MINUTES > now }

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
        // No redirects: an unknown place redirects to Kipa's general page, which isn't this place's times.
        val conn = open(URL(city.pageUrl))
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * The page has:
     * <div class="lz-list" ... data-date="2026-11-17">
     *   <div class="lz-row" data-m="293"><span class="lz-row__n">עלות השחר<span class="lz-row__d">16.1° לפני הזריחה</span></span>
     *   <span class="lz-row__t">04:53</span></div> ...
     */
    fun parse(html: String): Pair<String, List<Zman>> {
        val listStart = html.indexOf("class=\"lz-list\"")
        if (listStart < 0) throw IOException("No times list on page")
        val head = html.substring(listStart, minOf(html.length, listStart + 300))
        val date = Regex("data-date=\"(\\d{4}-\\d{2}-\\d{2})\"").find(head)?.groupValues?.get(1)
            ?: PrayerRepository.today()
        val end = html.indexOf("</section>", listStart).let { if (it > 0) it else html.length }
        val block = html.substring(listStart, end)

        val row = Regex(
            "class=\"lz-row\"[^>]*>\\s*<span class=\"lz-row__n\">(.*?)<span class=\"lz-row__t\">\\s*(\\d{1,2}):(\\d{2})",
            RegexOption.DOT_MATCHES_ALL,
        )
        val detailRe = Regex("<span class=\"lz-row__d\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL)
        val list = row.findAll(block).map { m ->
            val nameHtml = m.groupValues[1]
            val detail = detailRe.find(nameHtml)?.groupValues?.get(1)?.let { clean(it) }?.ifEmpty { null }
            val name = clean(nameHtml.replace(detailRe, ""))
            Zman(name, m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt(), detail)
        }.filter { it.name.isNotEmpty() }.toList()
        if (list.isEmpty()) throw IOException("No times in page")
        return date to list
    }

    private fun clean(s: String): String =
        android.text.Html.fromHtml(s, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            .replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun writeCache(context: Context, d: ZmanimDay) {
        val arr = JSONArray()
        d.zmanim.forEach { z ->
            val o = JSONObject().put("n", z.name).put("m", z.minutes)
            if (z.detail != null) o.put("d", z.detail)
            arr.put(o)
        }
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
                Zman(o.getString("n"), o.getInt("m"), if (o.has("d")) o.getString("d") else null)
            }
            ZmanimDay(city, json.getString("date"), list, json.getLong("fetchedAt"))
        } catch (e: Exception) {
            null
        }
    }
}
