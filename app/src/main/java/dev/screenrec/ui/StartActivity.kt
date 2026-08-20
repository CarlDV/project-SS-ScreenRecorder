package dev.screenrec.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import dev.screenrec.R
import dev.screenrec.output.MediaStoreOutput
import dev.screenrec.record.RecordingConfig
import dev.screenrec.record.SoundMode
import dev.screenrec.service.RecorderService
import dev.screenrec.service.RecorderState
import dev.screenrec.settings.SettingsRepository

/**
 * The launcher entry point, and the only path to starting a recording. Draws nothing at all: it
 * clears the permission gates, asks for capture consent, hands the token to the service, and
 * finishes.
 *
 * It used to be a bottom sheet with the sound and quality choices on it, which made tapping the
 * icon a two-screen affair and -- because the sheet was a wrap_content column with no scroll
 * view -- put its Start button off the bottom of a landscape display, where it could not be
 * reached at all. The choices now live in [SettingsActivity], behind the launcher's long-press
 * menu, and are read from [SettingsRepository] here.
 *
 * Each gate returns here and calls proceed() again, so the order is explicit and there is
 * exactly one path to starting the service.
 */
class StartActivity : Activity() {

    private lateinit var settings: SettingsRepository

    /** Gates already put to the user, so a decline dismisses this rather than looping. */
    private val asked = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository(this)

        // Tapping the icon during a session is a stop, the same as tapping the tile: with the
        // sheet gone there is nothing else the icon could usefully do, and it is one more place
        // to stop from when the shade is awkward to reach.
        if (RecorderState.active) {
            stopSession()
            finish()
            return
        }

        // A previous session killed mid-recording leaves an invisible pending row behind.
        MediaStoreOutput(this).cleanUpOrphans()
        proceed()
    }

    private fun stopSession() {
        // The recorder is already a running foreground service, so this is a permitted background
        // start -- but a stale flag would make it a violation, and crashing the launcher icon is
        // worse than a stop that does nothing.
        try {
            startService(RecorderService.stopIntent(this))
        } catch (e: IllegalStateException) {
            Toast.makeText(this, R.string.could_not_stop, Toast.LENGTH_SHORT).show()
        }
    }

    private fun config() = RecordingConfig(settings.soundMode, settings.preset)

    /**
     * Advances to the next unmet requirement, or launches the consent dialog.
     *
     * Each gate is asked at most once per attempt. Asking again on the way back is what turned
     * a declined permission into a loop: a permanently denied POST_NOTIFICATIONS returns from
     * requestPermissions instantly, and re-requesting from the result callback spun the main
     * thread; backing out of the overlay screen re-opened it forever.
     */
    private fun proceed() {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
            if (asked(REQ_NOTIFICATIONS)) {
                // Without it there is no notification, and the foreground service the recording
                // needs cannot show one -- so this is the one gate worth refusing to pass.
                toast(R.string.needs_notifications)
                finish()
                return
            }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        if (config().soundMode != SoundMode.NONE &&
            !granted(Manifest.permission.RECORD_AUDIO) &&
            !asked(REQ_MIC)
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!Settings.canDrawOverlays(this) && !asked(REQ_OVERLAY)) {
            // Optional: only the countdown and the floating pill use it, and both degrade to
            // nothing. canDrawOverlays also lies for a moment after being granted, so a second
            // trip through here would bounce the user back into Settings for no reason.
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ),
                REQ_OVERLAY
            )
            return
        }
        requestCapture()
    }

    /** True if this gate has already been put to the user during this attempt. */
    private fun asked(requestCode: Int): Boolean = !asked.add(requestCode)

    private fun requestCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        // Forcing the default display suppresses the system's "single app" choice, so the
        // consent dialog matches the one Samsung's own recorder shows.
        val intent = manager.createScreenCaptureIntent(
            MediaProjectionConfig.createConfigForDefaultDisplay()
        )
        startActivityForResult(intent, REQ_CONSENT)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // A denied mic falls back to no sound rather than blocking the recording outright.
        if (requestCode == REQ_MIC &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            settings.soundMode = SoundMode.NONE
        }
        proceed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> proceed()
            REQ_CONSENT -> {
                if (resultCode == RESULT_OK && data != null) {
                    startForegroundService(RecorderService.startIntent(this, data, config()))
                }
                // Either way this activity's work is done; consent denial simply dismisses it.
                finish()
            }
        }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(messageRes: Int) {
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQ_NOTIFICATIONS = 1
        const val REQ_MIC = 2
        const val REQ_OVERLAY = 3
        const val REQ_CONSENT = 4
    }
}
