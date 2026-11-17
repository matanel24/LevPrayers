package com.saltsman.levprayers

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_PRAYERS = "prayers"
        const val TAB_ZMANIM = "zmanim"
    }

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var waStatus: TextView
    private lateinit var dateView: TextView
    private lateinit var title: TextView
    private lateinit var tabPrayers: TextView
    private lateinit var tabZmanim: TextView
    private lateinit var citySpinner: Spinner
    private lateinit var pasteButton: Button
    private val hebrew: Locale = Locale.forLanguageTag("he")
    private var tab = TAB_PRAYERS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        list = findViewById(R.id.list)
        status = findViewById(R.id.status)
        waStatus = findViewById(R.id.wa_status)
        dateView = findViewById(R.id.date)
        title = findViewById(R.id.title)
        tabPrayers = findViewById(R.id.tab_prayers)
        tabZmanim = findViewById(R.id.tab_zmanim)
        citySpinner = findViewById(R.id.city_spinner)
        pasteButton = findViewById(R.id.btn_paste)

        tab = savedInstanceState?.getString(EXTRA_TAB) ?: intent.getStringExtra(EXTRA_TAB) ?: TAB_PRAYERS

        tabPrayers.setOnClickListener { switchTab(TAB_PRAYERS) }
        tabZmanim.setOnClickListener { switchTab(TAB_ZMANIM) }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { load(force = true) }
        findViewById<Button>(R.id.btn_site).setOnClickListener {
            val url = if (tab == TAB_ZMANIM) ZmanimRepository.selectedCity(this).pageUrl else PrayerRepository.PAGE_URL
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
        pasteButton.setOnClickListener { pasteFromClipboard() }
        pasteButton.setOnLongClickListener {
            WhatsAppSchedule.clear(applicationContext)
            Toast.makeText(this, "הלוח שהדבקת נמחק, טוען את הלוח המשותף", Toast.LENGTH_SHORT).show()
            load(force = true)
            true
        }

        setupCitySpinner()
        handleShare(intent)
        applyTabUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_TAB, tab)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_TAB)?.let { tab = it; applyTabUi() }
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        dateView.text = LocalDate.now(PrayerRepository.ZONE)
            .format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", hebrew)) +
            (try { " · " + HebrewDate.full() } catch (e: Exception) { "" })
        load(force = false)
    }

    // ---- tabs ----

    private fun switchTab(newTab: String) {
        if (tab == newTab) return
        tab = newTab
        applyTabUi()
        load(force = false)
    }

    private fun applyTabUi() {
        val z = tab == TAB_ZMANIM
        title.text = if (z) "זמני היום" else "תפילות היום · מכון לב"
        styleTab(tabPrayers, !z)
        styleTab(tabZmanim, z)
        citySpinner.visibility = if (z) View.VISIBLE else View.GONE
        pasteButton.visibility = if (z) View.GONE else View.VISIBLE
        waStatus.visibility = if (z) View.GONE else View.VISIBLE
    }

    private fun styleTab(v: TextView, selected: Boolean) {
        v.setBackgroundResource(if (selected) R.drawable.tag_bg else R.drawable.item_bg)
        v.setTextColor(getColor(if (selected) R.color.on_accent else R.color.text_primary))
    }

    private fun setupCitySpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, ZmanimRepository.CITIES.map { "📍 " + it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        citySpinner.adapter = adapter
        val current = ZmanimRepository.CITIES.indexOf(ZmanimRepository.selectedCity(this)).coerceAtLeast(0)
        citySpinner.setSelection(current, false)
        citySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val city = ZmanimRepository.CITIES[position]
                if (city == ZmanimRepository.selectedCity(this@MainActivity)) return
                ZmanimRepository.setCity(applicationContext, city)
                load(force = false)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    // ---- WhatsApp schedule input ----

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        intent.action = null // don't import again later
        tab = TAB_PRAYERS
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

    // ---- loading ----

    private fun load(force: Boolean) {
        status.text = "טוען…"
        val app = applicationContext
        val forTab = tab
        Thread {
            if (forTab == TAB_ZMANIM) {
                val day = ZmanimRepository.load(app, force)
                try { ZmanimWidget.updateAll(app, day) } catch (e: Exception) { }
                runOnUiThread { if (!isFinishing && !isDestroyed && tab == forTab) renderZmanim(day, force) }
            } else {
                val schedule = PrayerRepository.load(app, force)
                try { PrayerWidget.updateAll(app, schedule) } catch (e: Exception) { }
                runOnUiThread { if (!isFinishing && !isDestroyed && tab == forTab) render(schedule, force) }
            }
        }.start()
    }

    // ---- zmanim ----

    private fun renderZmanim(day: ZmanimDay?, force: Boolean) {
        list.removeAllViews()
        if (day == null || day.zmanim.isEmpty()) {
            status.text = "לא הצלחתי לטעון את זמני היום. בדוק חיבור לאינטרנט ולחץ רענון."
            return
        }
        if (force && day.failed) {
            Toast.makeText(this, "הרענון נכשל, מוצגים הזמנים השמורים", Toast.LENGTH_SHORT).show()
        }
        val updated = DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(day.fetchedAt).atZone(PrayerRepository.ZONE))
        status.text = if (day.stale) "⚠ הזמנים לא מהיום (${day.date})"
        else "זמני היום ב${day.city.name} · מאתר כיפה · עודכן ב-$updated"

        val now = PrayerRepository.nowMinutes()
        val next = ZmanimRepository.nextIndex(day.zmanim, now)
        val inflater = LayoutInflater.from(this)
        day.zmanim.forEachIndexed { i, z ->
            val row = inflater.inflate(R.layout.item_prayer, list, false)
            row.findViewById<TextView>(R.id.i_time).text = z.timeText
            row.findViewById<TextView>(R.id.i_name).text = z.name
            val det = row.findViewById<TextView>(R.id.i_location)
            if (z.detail != null) {
                det.text = z.detail
                det.visibility = View.VISIBLE
            } else {
                det.visibility = View.GONE
            }
            val tag = row.findViewById<TextView>(R.id.i_tag)
            when {
                i == next -> {
                    row.setBackgroundResource(R.drawable.item_next_bg)
                    tag.text = "הבא"
                    tag.visibility = View.VISIBLE
                }
                z.minutes + ZmanimRepository.GRACE_MINUTES <= now -> row.alpha = 0.45f
            }
            list.addView(row)
        }
    }

    // ---- prayers ----

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
