package dev.screenrec.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.screenrec.R
import dev.screenrec.output.MediaStoreOutput
import dev.screenrec.overlay.OverlayController
import dev.screenrec.record.DisplayMetricsSnapshot
import dev.screenrec.record.QualityPreset
import dev.screenrec.record.RecordingConfig
import dev.screenrec.record.RecordingController
import dev.screenrec.record.RecordingState
import dev.screenrec.record.RecordingStateMachine
import dev.screenrec.record.SoundMode
import dev.screenrec.settings.SettingsRepository

/**
 * Owns the session for its whole life. Every transition runs on the main thread so the state
 * machine needs no locking.
 */
class RecorderService : Service(), RecordingController.Callbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val machine = RecordingStateMachine()
    private lateinit var notifications: RecorderNotifications
    private lateinit var overlay: OverlayController
    private lateinit var settings: SettingsRepository

    /**
     * One per session, built in [handleStart] rather than in [onCreate]. A controller that has
     * finalised can never publish or stop again, and the service outlives a stopSelf() long
     * enough to receive another start command -- which used to hand the next recording a spent
     * controller, leaving the notification and the projection up until the process was killed.
     */
    private var controller: RecordingController? = null

    private var projection: MediaProjection? = null
    private var startedAtElapsedMs = 0L
    private var foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

    /**
     * The status bar chip shows a static string, so the notification is re-posted once a second
     * to make the counter advance. That stops as soon as it is settled that this device has no
     * chip: rebuilding the row every second is what made the notification's Stop button hard to
     * hit, and setUsesChronometer animates the notification's own timer without any re-posting.
     */
    private val counterTick = object : Runnable {
        override fun run() {
            if (!machine.isActive) return
            val keepTicking = notifications.refreshOngoing(
                startedAtElapsedMs,
                paused = machine.state == RecordingState.PAUSED
            )
            if (keepTicking) handler.postDelayed(this, COUNTER_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = RecorderNotifications(this).also { it.ensureChannels() }
        overlay = OverlayController(this)
        settings = SettingsRepository(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (machine.state != RecordingState.IDLE) return
        val resultData: Intent =
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java) ?: return
        val config = RecordingConfig(
            soundMode = SoundMode.valueOf(
                intent.getStringExtra(EXTRA_SOUND_MODE) ?: SoundMode.MEDIA.name
            ),
            preset = QualityPreset.valueOf(
                intent.getStringExtra(EXTRA_PRESET) ?: QualityPreset.P1080.name
            )
        )

        // Order is mandatory: foreground first, with the right type set, THEN the token.
        foregroundTypes = desiredForegroundTypes(config)
        postForeground(notifications.ongoing(SystemClock.elapsedRealtime(), paused = false))

        val manager = getSystemService(MediaProjectionManager::class.java)
        val token = manager.getMediaProjection(Activity.RESULT_OK, resultData)
        if (token == null) {
            onError(getString(R.string.notif_error_title))
            return
        }
        projection = token
        machine.transitionTo(RecordingState.COUNTDOWN)
        // Live from here, not from onStarted: the countdown is part of the session, and both the
        // tile and the launcher icon treat "active" as "a tap should stop this". Without it, a tap
        // during the countdown ran the whole consent flow again for a service that would ignore it.
        RecorderState.set(this, true)
        overlay.showPillDuringCapture = settings.onScreenControls

        overlay.showCountdown(COUNTDOWN_FROM) {
            if (machine.state != RecordingState.COUNTDOWN) return@showCountdown
            beginCapture(token, config)
        }
    }

    private fun beginCapture(token: MediaProjection, config: RecordingConfig) {
        val session = RecordingController(MediaStoreOutput(this))
        controller = session
        val started = try {
            session.start(token, config, DisplayMetricsSnapshot.from(this), this)
        } catch (e: Exception) {
            // Encoder, muxer and MediaStore failures are already reported as a false return.
            // Anything that throws instead used to escape onto the main looper and take the
            // process down mid-session, which left the projection running.
            Log.w(TAG, "Session failed to start", e)
            session.abandon()
            notifications.error(getString(R.string.notif_start_failed))
            false
        }
        if (!started) {
            // Nothing downstream owns the token yet, so tearing the session down here is the only
            // thing standing between a failed start and the system believing the screen is still
            // being shared until the process dies. finish() is also what clears the shared
            // "a session is live" flag the tile and the launcher icon read.
            finish()
        }
    }

    private fun handlePause() {
        if (!machine.transitionTo(RecordingState.PAUSED)) return
        controller?.pause()
        overlay.setPaused(true)
        postForeground(notifications.ongoing(startedAtElapsedMs, paused = true))
    }

    private fun handleResume() {
        if (!machine.transitionTo(RecordingState.RECORDING)) return
        controller?.resume()
        overlay.setPaused(false)
        postForeground(notifications.ongoing(startedAtElapsedMs, paused = false))
    }

    private fun handleStop() {
        // Stop during the countdown is a cancel: nothing is encoding, so there is nothing to
        // finalise. Ignoring it, as this used to, left the only visible control doing nothing.
        if (machine.state == RecordingState.COUNTDOWN) {
            machine.transitionTo(RecordingState.IDLE)
            finish()
            return
        }
        if (!machine.transitionTo(RecordingState.STOPPING)) return
        handler.removeCallbacks(counterTick)
        overlay.hideAll()
        val session = controller
        if (session == null) {
            finish()
            return
        }
        // Draining blocks briefly; keep it off the main thread.
        Thread({ session.stop() }, "stop-session").start()
    }

    override fun onStarted() {
        handler.post {
            machine.transitionTo(RecordingState.RECORDING)
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            RecorderState.set(this, true)
            handler.removeCallbacks(counterTick)
            handler.postDelayed(counterTick, COUNTER_INTERVAL_MS)
            overlay.showPill(
                startedAtElapsedMs = startedAtElapsedMs,
                onPauseToggle = {
                    if (machine.state == RecordingState.PAUSED) handleResume() else handlePause()
                },
                onStop = { handleStop() }
            )
        }
    }

    override fun onSaved(uri: Uri, displayName: String, warning: String?) {
        handler.post {
            notifications.saved(uri, displayName, warning)
            finish()
        }
    }

    override fun onError(message: String) {
        handler.post {
            notifications.error(message)
            finish()
        }
    }

    override fun onProjectionLost() {
        handler.post {
            machine.transitionTo(RecordingState.STOPPING)
            overlay.hideAll()
        }
    }

    private fun finish() {
        machine.transitionTo(RecordingState.IDLE)
        handler.removeCallbacks(counterTick)
        overlay.hideAll()
        controller = null
        RecorderState.set(this, false)
        stopProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Playback capture and the mic both hold the RECORD_AUDIO app-op, and from Android 14 a
     * foreground service that holds it has to say so or the op is dropped as soon as the app
     * stops counting as in-use -- which is exactly when the start sheet closes. The type is only
     * claimed when the permission is actually granted, because claiming it without would be a
     * SecurityException on the startForeground call.
     */
    private fun desiredForegroundTypes(config: RecordingConfig): Int {
        val wantsAudio = config.soundMode != SoundMode.NONE &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        return if (wantsAudio) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
    }

    private fun postForeground(notification: Notification) {
        try {
            startForeground(RecorderNotifications.ONGOING_ID, notification, foregroundTypes)
        } catch (e: Exception) {
            if (foregroundTypes == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) throw e
            // A device that will not grant the microphone type must not cost us the recording.
            Log.w(TAG, "Foreground type set refused; continuing without microphone", e)
            foregroundTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(RecorderNotifications.ONGOING_ID, notification, foregroundTypes)
        }
    }

    /**
     * The service owns the projection token from getMediaProjection to stop(), because every
     * other candidate owner has exit paths where it was never constructed. Leaving it running
     * makes the system keep showing "You're currently sharing your entire screen", and the
     * next recording cannot start until the process is killed.
     */
    private fun stopProjection() {
        projection?.stop()
        projection = null
    }

    override fun onDestroy() {
        handler.removeCallbacks(counterTick)
        overlay.hideAll()
        RecorderState.set(this, false)
        stopProjection()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val ACTION_START = "dev.screenrec.START"
        private const val ACTION_PAUSE = "dev.screenrec.PAUSE"
        private const val ACTION_RESUME = "dev.screenrec.RESUME"
        private const val ACTION_STOP = "dev.screenrec.STOP"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_SOUND_MODE = "sound_mode"
        private const val EXTRA_PRESET = "preset"
        private const val COUNTDOWN_FROM = 3
        private const val COUNTER_INTERVAL_MS = 1_000L

        fun startIntent(context: Context, resultData: Intent, config: RecordingConfig): Intent =
            Intent(context, RecorderService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_SOUND_MODE, config.soundMode.name)
                putExtra(EXTRA_PRESET, config.preset.name)
            }

        fun pauseIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).apply { action = ACTION_PAUSE }

        fun resumeIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).apply { action = ACTION_RESUME }

        fun stopIntent(context: Context): Intent =
            Intent(context, RecorderService::class.java).apply { action = ACTION_STOP }
    }
}
