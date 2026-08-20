package dev.screenrec.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import dev.screenrec.R

/**
 * The ongoing notification is the fallback control surface: if FLAG_SECURE turns out not to
 * exclude the pill from the capture, this is the only way to stop a recording, so pause and
 * stop actions live here regardless.
 */
class RecorderNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)
    private val promotion = ChipPromotion()

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_name),
            // Silent: a recorder must not beep into its own audio.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun ongoing(startedAtElapsedMs: Long, paused: Boolean): Notification {
        // Notification.setWhen takes wall-clock time, but the session is timed on
        // elapsedRealtime; convert rather than mixing the two clocks.
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtElapsedMs
        val startedAtWallMs = System.currentTimeMillis() - elapsedMs
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_recording_title))
            .setOngoing(true)
            .setWhen(startedAtWallMs)
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)
            // Text for the Android 16 status bar chip. The chip renders a static string, not a
            // Chronometer, so the service re-posts this once a second while recording.
            .setShortCriticalText(ElapsedText.of(elapsedMs))
            // Ask to be promoted to the chip. Notification.Builder#setRequestPromotedOngoing
            // is behind a build flag and absent from android-36.jar, but it only writes this
            // extra, so setting it directly is equivalent. The system still decides.
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })

        // See ChipPromotion: the pre-ui_rich_ongoing branch promotes a colorized ongoing
        // notification and rejects an uncolorized one, and the newer branch does the reverse.
        if (promotion.colorized) {
            builder.setColorized(true)
            builder.setColor(RECORDING_RED)
        }

        if (paused) {
            builder.setContentText(context.getString(R.string.notif_paused))
            builder.addAction(action(R.string.action_resume, RecorderService.resumeIntent(context), 1))
        } else {
            builder.addAction(action(R.string.action_pause, RecorderService.pauseIntent(context), 2))
        }
        builder.addAction(action(R.string.action_stop, RecorderService.stopIntent(context), 3))
        return builder.build()
    }

    /**
     * Re-posts the ongoing notification so the chip's counter advances, then checks whether the
     * system promoted it and switches form once if it did not.
     */
    fun refreshOngoing(startedAtElapsedMs: Long, paused: Boolean) {
        manager.notify(ONGOING_ID, ongoing(startedAtElapsedMs, paused))
        if (promotion.onPostResult(promoted = isOngoingPromoted())) {
            manager.notify(ONGOING_ID, ongoing(startedAtElapsedMs, paused))
        }
    }

    /**
     * FLAG_PROMOTED_ONGOING is set by the system, never by the app, but the posting app is
     * explicitly allowed to read it back -- which is the only way to find out whether the chip
     * was granted.
     */
    private fun isOngoingPromoted(): Boolean =
        manager.activeNotifications
            .firstOrNull { it.id == ONGOING_ID }
            ?.notification
            ?.let { it.flags and Notification.FLAG_PROMOTED_ONGOING != 0 }
            ?: false

    fun saved(displayName: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_saved_title))
            .setContentText(displayName)
            .setAutoCancel(true)
            .build()
        manager.notify(SAVED_ID, notification)
    }

    fun error(message: String) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(message)
            .setAutoCancel(true)
            .build()
        manager.notify(ERROR_ID, notification)
    }

    private fun action(labelRes: Int, intent: Intent, requestCode: Int): Notification.Action {
        val pending = PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, context.getString(labelRes), pending).build()
    }

    companion object {
        const val ONGOING_ID = 1
        private const val SAVED_ID = 2
        private const val ERROR_ID = 3
        private const val CHANNEL_ID = "recording"

        /** Matches the red One UI uses for its own recording indicator. */
        private const val RECORDING_RED = 0xFFE0342B.toInt()

        /**
         * Notification.EXTRA_REQUEST_PROMOTED_ONGOING, whose constant is not exposed in
         * android-36.jar. Value read from the platform source for API 36.
         */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
