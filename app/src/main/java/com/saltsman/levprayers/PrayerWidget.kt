package com.saltsman.levprayers

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZonedDateTime

class PrayerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        refreshAsync(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Widget was resized: pick the compact or full layout again.
        refreshAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TICK) refreshAsync(context)
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(tickIntent(context))
    }

    private fun refreshAsync(context: Context) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                updateAll(app, PrayerRepository.load(app))
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_TICK = "com.saltsman.levprayers.TICK"

        /** Below this width (dp) the compact 1x1 layout is used. */
        private const val FULL_MIN_WIDTH_DP = 130
        private const val FULL_MIN_HEIGHT_DP = 90

        /** Push a schedule to every widget on the home screen and plan the next change. */
        fun updateAll(context: Context, schedule: DaySchedule?) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
            if (ids.isEmpty()) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+: hand the launcher both layouts; it picks by the widget's size.
                val small = buildViews(context, schedule, compact = true)
                val full = buildViews(context, schedule, compact = false)
                val responsive = RemoteViews(
                    mapOf(
                        SizeF(40f, 40f) to small,
                        SizeF(FULL_MIN_WIDTH_DP.toFloat(), FULL_MIN_HEIGHT_DP.toFloat()) to full,
                    )
                )
                mgr.updateAppWidget(ids, responsive)
            } else {
                for (id in ids) {
                    val opts = mgr.getAppWidgetOptions(id)
                    val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    val compact = w in 1 until FULL_MIN_WIDTH_DP
                    mgr.updateAppWidget(id, buildViews(context, schedule, compact))
                }
            }
            scheduleNextTick(context, schedule)
        }

        private fun buildViews(context: Context, schedule: DaySchedule?, compact: Boolean): RemoteViews {
            val v = RemoteViews(
                context.packageName,
                if (compact) R.layout.widget_prayer_small else R.layout.widget_prayer,
            )

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)

            val prayers = schedule?.prayers.orEmpty()
            if (prayers.isEmpty()) {
                v.setTextViewText(R.id.w_name, if (compact) "לב" else "אין נתונים")
                v.setTextViewText(R.id.w_location, "לחץ לטעינה")
                v.setViewVisibility(R.id.w_location, View.VISIBLE)
                v.setTextViewText(R.id.w_time, "--:--")
                if (!compact) {
                    v.setTextViewText(R.id.w_label, "תפילות · מכון לב")
                    v.setViewVisibility(R.id.w_after, View.GONE)
                }
                return v
            }

            val idx = PrayerRepository.nextIndex(prayers)
            val shown: Prayer
            val label: String
            val after: Prayer?
            if (idx >= 0) {
                shown = prayers[idx]
                label = if (shown.minutes <= PrayerRepository.nowMinutes()) "התחילה עכשיו" else "התפילה הבאה"
                after = prayers.getOrNull(idx + 1)
            } else {
                shown = prayers.first()
                label = "התפילות היום הסתיימו · מחר"
                after = null
            }

            v.setTextViewText(R.id.w_name, shown.name)
            v.setTextViewText(R.id.w_time, shown.timeText)
            if (shown.location != null) {
                v.setTextViewText(R.id.w_location, shown.location)
                v.setViewVisibility(R.id.w_location, View.VISIBLE)
            } else {
                v.setViewVisibility(R.id.w_location, View.GONE)
            }

            if (!compact) {
                val staleMark = if (schedule?.stale == true) " · לא עודכן" else ""
                v.setTextViewText(R.id.w_label, label + staleMark)
                if (after != null) {
                    val loc = if (after.location != null) " (${after.location})" else ""
                    v.setTextViewText(R.id.w_after, "אחריה: ${after.name}$loc ${after.timeText}")
                    v.setViewVisibility(R.id.w_after, View.VISIBLE)
                } else {
                    v.setViewVisibility(R.id.w_after, View.GONE)
                }
            }
            return v
        }

        private fun tickIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 1,
                Intent(context, PrayerWidget::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Wake at the shown prayer's start (label -> "התחילה עכשיו"), then GRACE_MINUTES later
         *  so the widget moves on to the next prayer. */
        private fun scheduleNextTick(context: Context, schedule: DaySchedule?) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val zone = PrayerRepository.ZONE
            val now = ZonedDateTime.now(zone)
            val prayers = schedule?.prayers.orEmpty()
            val idx = PrayerRepository.nextIndex(prayers)

            val trigger: ZonedDateTime = when {
                schedule == null || schedule.stale || prayers.isEmpty() -> now.plusMinutes(30)
                idx >= 0 -> {
                    val start = prayers[idx].minutes
                    val target = if (start > PrayerRepository.nowMinutes()) start
                    else start + PrayerRepository.GRACE_MINUTES
                    LocalDate.now(zone).atStartOfDay(zone).plusMinutes(target.toLong())
                }
                else -> LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(5)
            }
            // Inexact alarm with a 1-minute window: needs no special permission.
            am.setWindow(AlarmManager.RTC, trigger.toInstant().toEpochMilli(), 60_000L, tickIntent(context))
        }
    }
}
