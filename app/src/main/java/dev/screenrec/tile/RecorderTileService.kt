package dev.screenrec.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.screenrec.R
import dev.screenrec.service.RecorderService
import dev.screenrec.service.RecorderState
import dev.screenrec.ui.StartActivity

/**
 * The entry point that makes this feel like a system feature: tap the tile, get a recording -- and
 * tap it again to stop, which on a device without the Android 16 status bar chip is the most
 * reliable Stop there is. The notification's own Stop needs the shade pulled down and the row
 * expanded, and neither is convenient in landscape or over a fullscreen game.
 */
class RecorderTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (RecorderState.active) {
            // The recorder is already a running foreground service, so this is a permitted
            // background start -- but if the flag were ever stale it would not be, and losing
            // the tile to an exception is worse than falling through to a start.
            try {
                startService(RecorderService.stopIntent(this))
                refresh()
                return
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Stop could not reach the recorder service", e)
            }
        }
        // StartActivity draws nothing; it runs the gates and the consent dialog. It has to be an
        // activity all the same -- the consent dialog needs one to return its result to.
        val intent = Intent(this, StartActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // startActivityAndCollapse takes a PendingIntent from API 34 and throws on the Intent
        // overload there; below 34 the PendingIntent overload does not exist at all, and minSdk
        // is 33 -- calling it unguarded is a NoSuchMethodError on One UI 5.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val recording = RecorderState.active
        qsTile?.apply {
            state = if (recording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(if (recording) R.string.tile_label_stop else R.string.tile_label)
            updateTile()
        }
    }

    private companion object {
        const val TAG = "RecorderTileService"
    }
}
