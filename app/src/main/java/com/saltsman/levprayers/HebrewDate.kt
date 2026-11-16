package com.saltsman.levprayers

import android.icu.util.Calendar
import android.icu.util.HebrewCalendar
import android.icu.util.TimeZone
import android.icu.util.ULocale

/** Hebrew date like "ו׳ כסלו תשפ״ז" (civil day, Jerusalem time). */
object HebrewDate {

    data class Parts(val day: String, val month: String, val year: String, val weekday: String)

    fun today(): Parts {
        val cal = HebrewCalendar(TimeZone.getTimeZone("Asia/Jerusalem"), ULocale("he"))
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val dow = cal.get(Calendar.DAY_OF_WEEK) // 1 = Sunday
        return Parts(
            day = gematria(day),
            month = monthName(month, isLeap(year)),
            year = gematria(year % 1000),
            weekday = "יום " + gematria(dow).let { if (dow == 7) "שבת" else it },
        )
    }

    /** "יום ב׳ · ו׳ כסלו" */
    fun short(): String = today().let { "${it.weekday} · ${it.day} ${it.month}" }.replace("יום שבת", "שבת")

    /** "ו׳ כסלו תשפ״ז" */
    fun full(): String = today().let { "${it.day} ${it.month} ${it.year}" }

    /** "ו׳ כסלו" */
    fun dayMonth(): String = today().let { "${it.day} ${it.month}" }

    private fun isLeap(year: Int) = ((7 * year + 1) % 19) < 7

    private fun monthName(m: Int, leap: Boolean): String = when (m) {
        0 -> "תשרי"; 1 -> "חשוון"; 2 -> "כסלו"; 3 -> "טבת"; 4 -> "שבט"
        5 -> "אדר א׳"
        6 -> if (leap) "אדר ב׳" else "אדר"
        7 -> "ניסן"; 8 -> "אייר"; 9 -> "סיוון"; 10 -> "תמוז"; 11 -> "אב"; 12 -> "אלול"
        else -> ""
    }

    /** 6 -> "ו׳", 15 -> "ט״ו", 787 -> "תשפ״ז" */
    fun gematria(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x >= 400) { sb.append('ת'); x -= 400 }
        val hundreds = mapOf(300 to 'ש', 200 to 'ר', 100 to 'ק')
        for ((v, c) in hundreds) if (x >= v) { sb.append(c); x -= v }
        when (x) {
            15 -> { sb.append("טו"); x = 0 }
            16 -> { sb.append("טז"); x = 0 }
        }
        val tens = "יכלמנסעפצ"
        if (x >= 10) { sb.append(tens[x / 10 - 1]); x %= 10 }
        val units = "אבגדהוזחט"
        if (x > 0) sb.append(units[x - 1])
        val s = sb.toString()
        return if (s.length == 1) "$s׳" else s.dropLast(1) + "״" + s.last()
    }
}
