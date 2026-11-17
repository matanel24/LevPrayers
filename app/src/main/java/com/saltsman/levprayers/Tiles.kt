package com.saltsman.levprayers

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Shared bits for the control-center tiles. */
abstract class BaseTile : TileService() {

    private val main = Handler(Looper.getMainLooper())

    /** What the tile shows. Runs off the main thread. */
    /** [time] and [caption] are drawn into the icon: big time, small name under it. */
    protected data class TileText(val label: String, val subtitle: String?, val time: String?, val caption: String? = null)

    protected abstract fun compute(): TileText

    protected abstract val tab: String

    override fun onStartListening() {
        super.onStartListening()
        Thread {
            val t = try { compute() } catch (e: Exception) { TileText("תפילות לב", null, null) }
            val icon = t.time?.let { try { timeIcon(it, t.caption) } catch (e: Exception) { null } }
            main.post {
                val tile = qsTile ?: return@post
                tile.label = t.label
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = t.subtitle
                // Some control centers (e.g. HyperOS) show only the icon, so the icon is the time itself.
                if (icon != null) tile.icon = icon
                tile.state = Tile.STATE_ACTIVE
                tile.updateTile()
            }
        }.start()
    }

    /** "14:10" with a small caption ("מנחה קטנה") under it, white on transparent (the system tints it). */
    private fun timeIcon(time: String, caption: String?): Icon {
        val size = 128f
        val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f

        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 100f
        }
        // As wide as the icon, but leave room under it for the caption.
        val maxTimeHeight = if (caption.isNullOrEmpty()) size * 0.62f else size * 0.48f
        timePaint.textSize = minOf(100f * (size * 0.98f) / timePaint.measureText(time), maxTimeHeight / 0.72f)
        val tfm = timePaint.fontMetrics

        if (caption.isNullOrEmpty()) {
            canvas.drawText(time, cx, size / 2f - (tfm.ascent + tfm.descent) / 2f, timePaint)
        } else {
            val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textAlign = Paint.Align.CENTER
                textSize = 100f
            }
            capPaint.textSize = minOf(100f * (size * 0.98f) / capPaint.measureText(caption), size * 0.26f)
            val cfm = capPaint.fontMetrics
            val timeH = tfm.descent - tfm.ascent
            val capH = cfm.descent - cfm.ascent
            val top = (size - (timeH * 0.82f + capH)) / 2f
            canvas.drawText(time, cx, top - tfm.ascent * 0.9f, timePaint)
            canvas.drawText(caption, cx, top + timeH * 0.82f - cfm.ascent, capPaint)
        }
        return Icon.createWithBitmap(bmp)
    }

    companion object {
        /** Ask the system to refresh both tiles now (e.g. when the next time changes). */
        fun requestUpdate(context: android.content.Context) {
            for (cls in listOf(ZmanimTileService::class.java, PrayerTileService::class.java)) {
                try {
                    TileService.requestListeningState(context, android.content.ComponentName(context, cls))
                } catch (e: Exception) {
                }
            }
        }

        /** Very short names for the icon caption. */
        fun iconCaption(name: String): String {
            val n = ZmanimWidget.shortZmanName(name)
            return when {
                n.startsWith("עלות") -> "עלות השחר"
                n.startsWith("טלית") -> "טלית ותפילין"
                n.contains("נץ") -> "הנץ"
                n == "חצות הלילה" -> "חצות לילה"
                n.startsWith("צאה״כ ר״ת") -> "צאה״כ ר״ת"
                n.startsWith("צאת הכוכבים") -> "צאה״כ"
                else -> n
            }
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, tab.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** "נץ החמה 06:08" / "ירושלים". */
class ZmanimTileService : BaseTile() {
    override val tab = MainActivity.TAB_ZMANIM

    override fun compute(): TileText {
        val day = ZmanimRepository.load(applicationContext)
        val list = day?.zmanim.orEmpty()
        if (list.isEmpty()) return TileText("זמני היום", "לחץ לטעינה", null)
        val idx = ZmanimRepository.nextIndex(list)
        val z = if (idx >= 0) list[idx] else list.first()
        val city = day?.city?.shortName ?: ""
        return TileText(
            "${ZmanimWidget.shortZmanName(z.name)} ${z.timeText}",
            if (idx >= 0) city else "מחר · $city",
            z.timeText,
            iconCaption(z.name),
        )
    }
}

/** "מנחה 14:10" / "אודיטוריום". */
class PrayerTileService : BaseTile() {
    override val tab = MainActivity.TAB_PRAYERS

    override fun compute(): TileText {
        val schedule = PrayerRepository.load(applicationContext)
        val list = schedule?.prayers.orEmpty()
        if (list.isEmpty()) return TileText("התפילה הבאה", "לחץ לטעינה", null)
        val idx = PrayerRepository.nextIndex(list)
        val p = if (idx >= 0) list[idx] else list.first()
        val sub = if (idx >= 0) p.location ?: "מכון לב" else "מחר"
        return TileText("${p.name} ${p.timeText}", sub, p.timeText, p.name.substringBefore(" - "))
    }
}
