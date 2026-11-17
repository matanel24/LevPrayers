package com.saltsman.levprayers

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.ZonedDateTime

/** Second widget: the next zman of the day for the chosen city. */
class ZmanimWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
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
                updateAll(app, ZmanimRepository.load(app))
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        const val ACTION_TICK = "com.saltsman.levprayers.ZMANIM_TICK"

        fun updateAll(context: Context, day: ZmanimDay?) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, ZmanimWidget::class.java))
            if (ids.isEmpty()) return
            mgr.updateAppWidget(ids, buildViews(context, day))
            scheduleNextTick(context, day)
        }

        private fun buildViews(context: Context, day: ZmanimDay?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_prayer_small)
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_ZMANIM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)

            val city = day?.city ?: ZmanimRepository.selectedCity(context)
            v.setTextViewText(R.id.w_date, city.shortName)
            v.setViewVisibility(R.id.w_date, View.VISIBLE)

            val zmanim = day?.zmanim.orEmpty()
            if (zmanim.isEmpty()) {
                v.setTextViewText(R.id.w_name, "זמני היום")
                v.setTextViewText(R.id.w_time, "--:--")
                v.setTextViewText(R.id.w_location, "לחץ לטעינה")
                v.setViewVisibility(R.id.w_location, View.VISIBLE)
                return v
            }
            val idx = ZmanimRepository.nextIndex(zmanim)
            val z = if (idx >= 0) zmanim[idx] else zmanim.first()
            v.setTextViewText(R.id.w_name, shortZmanName(z.name))
            v.setTextViewText(R.id.w_time, z.timeText)
            v.setTextViewText(
                R.id.w_location,
                when {
                    idx < 0 -> "מחר"
                    z.minutes <= PrayerRepository.nowMinutes() -> "עכשיו"
                    else -> "הזמן הבא"
                },
            )
            v.setViewVisibility(R.id.w_location, View.VISIBLE)
            return v
        }

        /** Short names that fit a 1x1 widget. */
        fun shortZmanName(name: String): String = name
            .replace("סוף זמן קריאת שמע", "סו\"ז ק\"ש")
            .replace("סוף זמן תפילה", "סו\"ז תפילה")
            .replace("למגן אברהם", "מג\"א")
            .replace("לגר\"א", "גר\"א")
            .replace("צאת הכוכבים לרבינו תם", "צאה\"כ ר\"ת")
            .replace("עלות השחר 90 דקות(מעלות)", "עלות 90 דק'")
            .replace("עלות השחר 72 דקות", "עלות 72 דק'")

        private fun tickIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(
                context, 3,
                Intent(context, ZmanimWidget::class.java).setAction(ACTION_TICK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Wake at the shown zman (label -> "עכשיו"), then GRACE_MINUTES later to move to the next one. */
        private fun scheduleNextTick(context: Context, day: ZmanimDay?) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val zone = PrayerRepository.ZONE
            val now = ZonedDateTime.now(zone)
            val zmanim = day?.zmanim.orEmpty()
            val idx = ZmanimRepository.nextIndex(zmanim)
            val trigger: ZonedDateTime = when {
                day == null || day.stale || zmanim.isEmpty() -> now.plusMinutes(30)
                idx >= 0 -> {
                    val at = zmanim[idx].minutes
                    val target = if (at > PrayerRepository.nowMinutes()) at else at + ZmanimRepository.GRACE_MINUTES
                    LocalDate.now(zone).atStartOfDay(zone).plusMinutes(target.toLong())
                }
                else -> LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(5)
            }
            am.setWindow(AlarmManager.RTC, trigger.toInstant().toEpochMilli(), 60_000L, tickIntent(context))
        }
    }
}
