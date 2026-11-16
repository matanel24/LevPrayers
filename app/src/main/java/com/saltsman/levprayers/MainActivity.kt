package com.saltsman.levprayers

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var dateView: TextView
    private val hebrew: Locale = Locale.forLanguageTag("he")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        dateView = findViewById(R.id.date)

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { load(force = true) }
        findViewById<Button>(R.id.btn_site).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PrayerRepository.PAGE_URL)))
        }
    }

    override fun onResume() {
        super.onResume()
        dateView.text = LocalDate.now(PrayerRepository.ZONE)
            .format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", hebrew))
        load(force = false)
    }

    private fun load(force: Boolean) {
        status.text = "טוען…"
        val app = applicationContext
        Thread {
            val schedule = PrayerRepository.load(app, force)
            try {
                PrayerWidget.updateAll(app, schedule)
            } catch (e: Exception) {
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) render(schedule, force)
            }
        }.start()
    }

    private fun render(schedule: DaySchedule?, force: Boolean) {
        list.removeAllViews()
        if (schedule == null || schedule.prayers.isEmpty()) {
            status.text = "לא הצלחתי לטעון את הזמנים. בדוק חיבור לאינטרנט ולחץ רענון."
            return
        }
        if (force && schedule.failed) {
            Toast.makeText(this, "הרענון נכשל, מוצגים הזמנים השמורים", Toast.LENGTH_SHORT).show()
        }

        val updated = DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(schedule.fetchedAt).atZone(PrayerRepository.ZONE))
        status.text = if (schedule.stale) {
            "⚠ הזמנים לא מהיום (עודכנו לאחרונה ${schedule.date} $updated)"
        } else {
            "עודכן היום ב-$updated"
        }

        val now = PrayerRepository.nowMinutes()
        val next = PrayerRepository.nextIndex(schedule.prayers, now)
        val inflater = LayoutInflater.from(this)

        schedule.prayers.forEachIndexed { i, p ->
            val row = inflater.inflate(R.layout.item_prayer, list, false)
            row.findViewById<TextView>(R.id.i_time).text = p.timeText
            row.findViewById<TextView>(R.id.i_name).text = p.name
            val loc = row.findViewById<TextView>(R.id.i_location)
            if (p.location != null) {
                loc.text = "📍 ${p.location}"
                loc.visibility = View.VISIBLE
            } else {
                loc.visibility = View.GONE
            }
            val tag = row.findViewById<TextView>(R.id.i_tag)
            when {
                i == next -> {
                    row.setBackgroundResource(R.drawable.item_next_bg)
                    tag.text = "הבאה"
                    tag.visibility = View.VISIBLE
                }
                p.minutes < now -> row.alpha = 0.45f
            }
            list.addView(row)
        }
    }
}
