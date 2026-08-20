package dev.screenrec.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.screenrec.R

/**
 * The ongoing notification is the fallback control surface: if FLAG_SECURE turns out not to
 * exclude the pill from the capture, this is the only way to stop a recording, so pause and
 * stop actions live here regardless.
 */
class RecorderNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

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
        val startedAtWallMs =
            System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtElapsedMs)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle(context.getString(R.string.notif_recording_title))
            .setOngoing(true)
            .setWhen(startedAtWallMs)
            .setShowWhen(!paused)
            .setUsesChronometer(!paused)

        if (paused) {
            builder.setContentText(context.getString(R.string.notif_paused))
            builder.addAction(action(R.string.action_resume, RecorderService.resumeIntent(context), 1))
        } else {
            builder.addAction(action(R.string.action_pause, RecorderService.pauseIntent(context), 2))
        }
        builder.addAction(action(R.string.action_stop, RecorderService.stopIntent(context), 3))
        return builder.build()
    }

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
    }
}
