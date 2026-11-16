package com.saltsman.levprayers

import android.content.Context
import java.time.LocalDate

/**
 * Parses the "*זמני המניינים*" message from the WhatsApp group, e.g.
 *
 *   _שחרית_
 *   23 דק' לפני נץ החמה (בית מדרש, לוודא שמתקיים) https://...
 *   7:00 (תיכונית - ימים ב-ה)
 *   8:30 (אודיטוריום *רק בימי שישי*)
 *   _מנחה_
 *   16:30 *מנחה אחרונה* (אודיטוריום)
 *   מנחה ביום שישי ובערבי חג :
 *   12:30 (ליד המקווה)
 *
 * Days are 0 = Sunday ... 6 = Saturday.
 */
object WhatsAppSchedule {

    data class Entry(
        val name: String,
        val minutes: Int?,          // fixed time, or
        val beforeSunrise: Int?,    // minutes before sunrise
        val location: String?,
        val note: String?,
        val days: Set<Int>,
    )

    private const val PREFS = "whatsapp_schedule"
    private const val KEY_TEXT = "text"
    private const val KEY_SAVED_AT = "saved_at"

    private val URL_RE = Regex("https?://\\S+")
    private val INVISIBLE_RE = Regex("[\\u200E\\u200F\\u202A-\\u202E]")
    private val TIME_RE = Regex("^(\\d{1,2}):(\\d{2})")
    private val REL_RE = Regex("^(\\d{1,3})\\s*(?:דקות|דק['׳’]?)\\s*לפני\\s*ה?נץ(?:\\s*החמה)?")
    private val PAREN_RE = Regex("\\(([^)]*)\\)")
    private val FRIDAY_ONLY_RE = Regex("רק\\s+ב?(?:ימי|יום|ימים)\\s+שישי")
    private val RANGE_RE = Regex("(?:ימים|ימי)\\s*([א-ו])['׳]?\\s*[-–]\\s*([א-ו])['׳]?")
    private const val VERIFY = "לוודא שמתקיים"

    // ---- storage ----

    /**
     * Shared schedule everyone gets. Edit this file in the GitHub repo to update all phones:
     * https://github.com/matanel24/LevPrayers/edit/main/schedule/whatsapp.txt
     */
    const val SHARED_URL =
        "https://raw.githubusercontent.com/matanel24/LevPrayers/main/schedule/whatsapp.txt"

    private const val KEY_SOURCE = "source"            // "shared" | "local"
    private const val KEY_LAST_SHARED = "last_shared"  // last shared text seen
    private const val KEY_SHARED_CHECKED = "shared_checked_at"
    private const val SHARED_CHECK_INTERVAL_MS = 3 * 60 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A schedule pasted on this phone. */
    fun save(context: Context, text: String) {
        prefs(context).edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .putString(KEY_SOURCE, "local")
            .apply()
    }

    /** Removes the schedule; the shared one comes back on the next sync. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun savedAt(context: Context): Long = prefs(context).getLong(KEY_SAVED_AT, 0L)

    fun isShared(context: Context): Boolean = prefs(context).getString(KEY_SOURCE, null) == "shared"

    /**
     * Downloads the shared schedule (at most every 3 hours unless [force]).
     * Whenever the shared file changes it replaces whatever is on the phone,
     * so the newest of "pasted here" / "updated for everyone" wins.
     * Must be called off the main thread.
     */
    fun syncShared(context: Context, force: Boolean) {
        val p = prefs(context)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong(KEY_SHARED_CHECKED, 0L) < SHARED_CHECK_INTERVAL_MS) return

        val conn = java.net.URL(SHARED_URL).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.useCaches = false
        val text = try {
            if (conn.responseCode !in 200..299) return
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
        p.edit().putLong(KEY_SHARED_CHECKED, now).apply()

        if (text.isBlank() || parse(text).isEmpty()) return
        if (text == p.getString(KEY_LAST_SHARED, null)) return
        p.edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_SAVED_AT, now)
            .putString(KEY_SOURCE, "shared")
            .putString(KEY_LAST_SHARED, text)
            .apply()
    }

    private fun text(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEXT, null)

    /** Today's WhatsApp minyanim as prayers. Sunrise-relative ones need [sunrise] (minutes). */
    fun forToday(context: Context, sunrise: Int?): List<Prayer> {
        val t = text(context) ?: return emptyList()
        val today = LocalDate.now(PrayerRepository.ZONE).dayOfWeek.value % 7 // Sunday -> 0
        return parse(t).filter { today in it.days }.mapNotNull { e ->
            val minutes = e.minutes ?: (if (sunrise != null && e.beforeSunrise != null) sunrise - e.beforeSunrise else null)
            minutes?.let { Prayer(e.name, e.location, it, e.note) }
        }
    }

    // ---- parsing ----

    fun parse(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var section: String? = null
        var sectionDays: Set<Int>? = null

        for (raw in text.lines()) {
            val line = raw.replace(INVISIBLE_RE, "").replace(URL_RE, "")
                .replace("*", "").replace("_", "").trim()
            if (line.isEmpty()) continue

            val timeM = TIME_RE.find(line)
            val relM = if (timeM == null) REL_RE.find(line) else null

            if (timeM == null && relM == null) {
                // Not a minyan line: maybe a section header.
                when {
                    line.startsWith("שחרית") -> { section = "שחרית"; sectionDays = null }
                    line.startsWith("מנחה") -> {
                        section = "מנחה"
                        sectionDays = if (line.contains("שישי")) setOf(5) else null
                    }
                    line.startsWith("ערבית") -> { section = "ערבית"; sectionDays = null }
                    line.startsWith("כללי") -> { section = null; sectionDays = null }
                }
                continue
            }
            val name = section ?: continue

            val notes = mutableListOf<String>()
            val minutes: Int?
            val before: Int?
            val head: IntRange
            if (timeM != null) {
                val h = timeM.groupValues[1].toInt()
                val m = timeM.groupValues[2].toInt()
                if (h > 23 || m > 59) continue
                minutes = h * 60 + m
                before = null
                head = timeM.range
            } else {
                minutes = null
                before = relM!!.groupValues[1].toInt()
                notes += "$before דק' לפני הנץ"
                head = relM.range
            }

            // Day rules can appear anywhere on the line.
            var days: Set<Int>? = null
            if (FRIDAY_ONLY_RE.containsMatchIn(line)) days = setOf(5)
            RANGE_RE.find(line)?.let { r ->
                val a = dayIndex(r.groupValues[1]); val b = dayIndex(r.groupValues[2])
                if (a != null && b != null && a <= b) days = (a..b).toSet()
            }
            val effectiveDays = days ?: sectionDays ?: defaultDays(name)

            // Location = what's in the parentheses, minus rules/notes.
            val paren = PAREN_RE.find(line)
            var location: String? = paren?.groupValues?.get(1)
            if (location != null) {
                if (location.contains(VERIFY)) notes += VERIFY
                location = location.replace(FRIDAY_ONLY_RE, "").replace(RANGE_RE, "").replace(VERIFY, "")
                location = cleanup(location)
            }

            // Leftover text outside the time and the parentheses (e.g. "מנחה אחרונה").
            var rest = line.removeRange(head)
            if (paren != null) rest = rest.replace(paren.value, "")
            val leftover = cleanup(rest.replace(FRIDAY_ONLY_RE, "").replace(RANGE_RE, ""))
            if (leftover != null) notes += leftover

            out += Entry(
                name = name,
                minutes = minutes,
                beforeSunrise = before,
                location = location,
                note = notes.distinct().joinToString(" · ").ifEmpty { null },
                days = effectiveDays,
            )
        }
        return out
    }

    private fun cleanup(s: String): String? =
        s.trim().trim(',', '-', '–', ':', '.', ' ').trim().replace(Regex("\\s{2,}"), " ").ifEmpty { null }

    /** Regular minyanim: שחרית Sunday–Friday, מנחה/ערבית Sunday–Thursday. */
    private fun defaultDays(name: String): Set<Int> =
        if (name == "שחרית") (0..5).toSet() else (0..4).toSet()

    private fun dayIndex(letter: String): Int? = "אבגדהוש".indexOf(letter).takeIf { it >= 0 }
}
