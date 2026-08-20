package dev.screenrec.service

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import dev.screenrec.tile.RecorderTileService

/**
 * Whether a session is live, shared between the recorder service and the Quick Settings tile.
 * They run in the same process but never hold a reference to each other, and the tile needs the
 * answer to decide whether a tap should start a recording or stop one.
 */
object RecorderState {

    @Volatile
    var active: Boolean = false
        private set

    fun set(context: Context, active: Boolean) {
        if (this.active == active) return
        this.active = active
        // Asks the system to bind the tile briefly so its label and state catch up even when
        // the shade is closed; without it the tile still shows "start" until next pulled down.
        // Never worth an exception: this runs on the recording's happy path.
        try {
            TileService.requestListeningState(
                context,
                ComponentName(context, RecorderTileService::class.java)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh the Quick Settings tile", e)
        }
    }

    private const val TAG = "RecorderState"
}
