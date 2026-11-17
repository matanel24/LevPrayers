package com.saltsman.levprayers

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Shared bits for the control-center tiles. */
abstract class BaseTile : TileService() {

    private val main = Handler(Looper.getMainLooper())

    /** Text for the tile: label (big line) and subtitle (small line, Android 10+). Runs off the main thread. */
    protected abstract fun compute(): Pair<String, String?>

    protected abstract val tab: String

    override fun onStartListening() {
        super.onStartListening()
        Thread {
            val (label, sub) = try { compute() } catch (e: Exception) { "תפילות לב" to null }
            main.post {
                val tile = qsTile ?: return@post
                tile.label = label
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = sub
                tile.state = Tile.STATE_ACTIVE
                tile.updateTile()
            }
        }.start()
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

    override fun compute(): Pair<String, String?> {
        val day = ZmanimRepository.load(applicationContext)
        val list = day?.zmanim.orEmpty()
        if (list.isEmpty()) return "זמני היום" to "לחץ לטעינה"
        val idx = ZmanimRepository.nextIndex(list)
        val z = if (idx >= 0) list[idx] else list.first()
        val city = day?.city?.shortName ?: ""
        return "${ZmanimWidget.shortZmanName(z.name)} ${z.timeText}" to (if (idx >= 0) city else "מחר · $city")
    }
}

/** "מנחה 14:10" / "אודיטוריום". */
class PrayerTileService : BaseTile() {
    override val tab = MainActivity.TAB_PRAYERS

    override fun compute(): Pair<String, String?> {
        val schedule = PrayerRepository.load(applicationContext)
        val list = schedule?.prayers.orEmpty()
        if (list.isEmpty()) return "התפילה הבאה" to "לחץ לטעינה"
        val idx = PrayerRepository.nextIndex(list)
        val p = if (idx >= 0) list[idx] else list.first()
        val sub = if (idx >= 0) p.location ?: "מכון לב" else "מחר"
        return "${p.name} ${p.timeText}" to sub
    }
}
