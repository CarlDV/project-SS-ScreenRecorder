package dev.screenrec.service

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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

/**
 * Owns the session for its whole life. Every transition runs on the main thread so the state
 * machine needs no locking.
 */
class RecorderService : Service(), RecordingController.Callbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val machine = RecordingStateMachine()
    private lateinit var notifications: RecorderNotifications
    private lateinit var controller: RecordingController
    private lateinit var overlay: OverlayController

    private var projection: MediaProjection? = null
    private var startedAtElapsedMs = 0L

    /**
     * The status bar chip shows a static string, so the notification is re-posted once a
     * second to make the counter advance. Cheap on a low-importance channel, and it is the
     * only way to get a live timer into the chip.
     */
    private val counterTick = object : Runnable {
        override fun run() {
            if (!machine.isActive) return
            notifications.refreshOngoing(
                startedAtElapsedMs,
                paused = machine.state == RecordingState.PAUSED
            )
            handler.postDelayed(this, COUNTER_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications = RecorderNotifications(this).also { it.ensureChannel() }
        controller = RecordingController(MediaStoreOutput(this))
        overlay = OverlayController(this)
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

        // Order is mandatory: foreground first, with the mediaProjection type, THEN the token.
        startForeground(
            RecorderNotifications.ONGOING_ID,
            notifications.ongoing(SystemClock.elapsedRealtime(), paused = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        val manager = getSystemService(MediaProjectionManager::class.java)
        val token = manager.getMediaProjection(Activity.RESULT_OK, resultData)
        if (token == null) {
            onError(getString(R.string.notif_error_title))
            return
        }
        projection = token
        machine.transitionTo(RecordingState.COUNTDOWN)

        overlay.showCountdown(COUNTDOWN_FROM) {
            if (machine.state != RecordingState.COUNTDOWN) return@showCountdown
            val started =
                controller.start(token, config, DisplayMetricsSnapshot.from(this), this)
            if (!started) {
                // Nothing downstream owns the token yet, so releasing it here is the only
                // thing standing between a failed start and the system believing the screen is
                // still being shared until the process dies.
                machine.transitionTo(RecordingState.IDLE)
                stopProjection()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun handlePause() {
        if (!machine.transitionTo(RecordingState.PAUSED)) return
        controller.pause()
        overlay.setPaused(true)
        startForeground(
            RecorderNotifications.ONGOING_ID,
            notifications.ongoing(startedAtElapsedMs, paused = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun handleResume() {
        if (!machine.transitionTo(RecordingState.RECORDING)) return
        controller.resume()
        overlay.setPaused(false)
        startForeground(
            RecorderNotifications.ONGOING_ID,
            notifications.ongoing(startedAtElapsedMs, paused = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    private fun handleStop() {
        if (!machine.transitionTo(RecordingState.STOPPING)) return
        handler.removeCallbacks(counterTick)
        overlay.hideAll()
        // Draining blocks briefly; keep it off the main thread.
        Thread({ controller.stop() }, "stop-session").start()
    }

    override fun onStarted() {
        handler.post {
            machine.transitionTo(RecordingState.RECORDING)
            startedAtElapsedMs = SystemClock.elapsedRealtime()
            handler.removeCallbacks(counterTick)
            handler.postDelayed(counterTick, COUNTER_INTERVAL_MS)
            overlay.showPill(
                onPauseToggle = {
                    if (machine.state == RecordingState.PAUSED) handleResume() else handlePause()
                },
                onStop = { handleStop() }
            )
        }
    }

    override fun onSaved(displayName: String) {
        handler.post {
            notifications.saved(displayName)
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
        stopProjection()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        stopProjection()
        super.onDestroy()
    }

    companion object {
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
