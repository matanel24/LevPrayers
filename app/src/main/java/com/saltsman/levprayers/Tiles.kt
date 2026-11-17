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
    protected data class TileText(val label: String, val subtitle: String?, val time: String?)

    protected abstract fun compute(): TileText

    protected abstract val tab: String

    override fun onStartListening() {
        super.onStartListening()
        Thread {
            val t = try { compute() } catch (e: Exception) { TileText("תפילות לב", null, null) }
            val icon = t.time?.let { try { timeIcon(it) } catch (e: Exception) { null } }
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

    /** "14:10" drawn as a white-on-transparent icon (the system tints it like any tile icon). */
    private fun timeIcon(text: String): Icon {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = 100f
        }
        paint.textSize = 100f * (size * 0.98f) / paint.measureText(text)
        val fm = paint.fontMetrics
        Canvas(bmp).drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint)
        return Icon.createWithBitmap(bmp)
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
        return TileText("${p.name} ${p.timeText}", sub, p.timeText)
    }
}
