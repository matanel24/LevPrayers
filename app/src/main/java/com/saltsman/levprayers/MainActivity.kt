package com.saltsman.levprayers

import android.app.Activity
import android.content.ClipboardManager
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
    private lateinit var waStatus: TextView
    private lateinit var dateView: TextView
    private val hebrew: Locale = Locale.forLanguageTag("he")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        waStatus = findViewById(R.id.wa_status)
        dateView = findViewById(R.id.date)

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { load(force = true) }
        findViewById<Button>(R.id.btn_site).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PrayerRepository.PAGE_URL)))
        }
        val paste = findViewById<Button>(R.id.btn_paste)
        paste.setOnClickListener { pasteFromClipboard() }
        paste.setOnLongClickListener {
            WhatsAppSchedule.clear(applicationContext)
            Toast.makeText(this, "הלוח שהדבקת נמחק, טוען את הלוח המשותף", Toast.LENGTH_SHORT).show()
            load(force = true)
            true
        }

        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        dateView.text = LocalDate.now(PrayerRepository.ZONE)
            .format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", hebrew)) +
            (try { " · " + HebrewDate.full() } catch (e: Exception) { "" })
        load(force = false)
    }

    // ---- WhatsApp schedule input ----

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        intent.action = null // don't import again later
        importWhatsApp(text)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "הלוח ריק. העתק קודם את ההודעה בוואטסאפ (לחיצה ארוכה ← העתק)", Toast.LENGTH_LONG).show()
            return
        }
        importWhatsApp(text)
    }

    private fun importWhatsApp(text: String) {
        val entries = WhatsAppSchedule.parse(text)
        if (entries.isEmpty()) {
            Toast.makeText(this, "לא מצאתי מניינים בטקסט. ודא שהעתקת את הודעת \"זמני המניינים\"", Toast.LENGTH_LONG).show()
            return
        }
        WhatsAppSchedule.save(applicationContext, text)
        Toast.makeText(this, "נשמרו ${entries.size} מניינים מוואטסאפ ✓", Toast.LENGTH_SHORT).show()
        load(force = false)
    }

    // ---- loading & rendering ----

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
        val waSaved = WhatsAppSchedule.savedAt(this)
        waStatus.text = if (waSaved > 0) {
            val d = DateTimeFormatter.ofPattern("d/M")
                .format(Instant.ofEpochMilli(waSaved).atZone(PrayerRepository.ZONE))
            if (WhatsAppSchedule.isShared(this)) "לוח מניינים משותף · עודכן ב-$d"
            else "לוח מניינים שהדבקת ב-$d · לחיצה ארוכה מחזירה ללוח המשותף"
        } else {
            "אין לוח מניינים מוואטסאפ"
        }

        list.removeAllViews()
        if (schedule == null || schedule.prayers.isEmpty()) {
            status.text = "לא הצלחתי לטעון את הזמנים. בדוק חיבור לאינטרנט ולחץ רענון."
            return
        }
        if (force && schedule.failed) {
            Toast.makeText(this, "הרענון נכשל, מוצגים הזמנים השמורים", Toast.LENGTH_SHORT).show()
        }

        status.text = when {
            schedule.fetchedAt == 0L -> "⚠ אין חיבור לאתר, מוצג רק לוח הוואטסאפ"
            schedule.stale && schedule.hasWhatsApp -> "⚠ זמני האתר לא מהיום, מוצג רק לוח הוואטסאפ"
            schedule.stale -> "⚠ הזמנים לא מהיום (אין חיבור לאתר)"
            else -> {
                val updated = DateTimeFormatter.ofPattern("HH:mm")
                    .format(Instant.ofEpochMilli(schedule.fetchedAt).atZone(PrayerRepository.ZONE))
                "האתר עודכן היום ב-$updated" + if (schedule.hasWhatsApp) " · כולל מניינים מוואטסאפ" else ""
            }
        }

        val now = PrayerRepository.nowMinutes()
        val next = PrayerRepository.nextIndex(schedule.prayers, now)
        val inflater = LayoutInflater.from(this)

        schedule.prayers.forEachIndexed { i, p ->
            val row = inflater.inflate(R.layout.item_prayer, list, false)
            row.findViewById<TextView>(R.id.i_time).text = p.timeText
            row.findViewById<TextView>(R.id.i_name).text = p.name
            val loc = row.findViewById<TextView>(R.id.i_location)
            val details = listOfNotNull(p.location?.let { "📍 $it" }, p.note).joinToString(" · ")
            if (details.isNotEmpty()) {
                loc.text = details
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
                p.minutes + PrayerRepository.GRACE_MINUTES <= now -> row.alpha = 0.45f
            }
            list.addView(row)
        }
    }
}
