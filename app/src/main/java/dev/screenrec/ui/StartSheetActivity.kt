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
import android.widget.Button
import android.widget.RadioGroup
import dev.screenrec.R
import dev.screenrec.output.MediaStoreOutput
import dev.screenrec.record.QualityPreset
import dev.screenrec.record.RecordingConfig
import dev.screenrec.record.SoundMode
import dev.screenrec.service.RecorderService
import dev.screenrec.settings.SettingsRepository

/**
 * Bottom sheet: pick sound and quality, clear the permission gates, consent to capture.
 * Each gate returns here and calls proceed() again, so the order is explicit and there is
 * exactly one path to starting the service.
 */
class StartSheetActivity : Activity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start_sheet)
        settings = SettingsRepository(this)

        // A previous session killed mid-recording leaves an invisible pending row behind.
        MediaStoreOutput(this).cleanUpOrphans()

        val soundGroup = findViewById<RadioGroup>(R.id.sound_group)
        val qualityGroup = findViewById<RadioGroup>(R.id.quality_group)

        soundGroup.check(
            when (settings.soundMode) {
                SoundMode.NONE -> R.id.sound_none
                SoundMode.MEDIA -> R.id.sound_media
                SoundMode.MEDIA_AND_MIC -> R.id.sound_media_mic
            }
        )
        qualityGroup.check(
            when (settings.preset) {
                QualityPreset.P1080 -> R.id.quality_1080
                QualityPreset.P720 -> R.id.quality_720
                QualityPreset.P480 -> R.id.quality_480
            }
        )

        soundGroup.setOnCheckedChangeListener { _, id ->
            settings.soundMode = when (id) {
                R.id.sound_none -> SoundMode.NONE
                R.id.sound_media_mic -> SoundMode.MEDIA_AND_MIC
                else -> SoundMode.MEDIA
            }
        }
        qualityGroup.setOnCheckedChangeListener { _, id ->
            settings.preset = when (id) {
                R.id.quality_720 -> QualityPreset.P720
                R.id.quality_480 -> QualityPreset.P480
                else -> QualityPreset.P1080
            }
        }

        findViewById<Button>(R.id.start).setOnClickListener { proceed() }
    }

    private fun config() = RecordingConfig(settings.soundMode, settings.preset)

    /** Advances to the next unmet requirement, or launches the consent dialog. */
    private fun proceed() {
        if (!granted(Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            return
        }
        if (config().soundMode != SoundMode.NONE && !granted(Manifest.permission.RECORD_AUDIO)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
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
                // Either way the sheet's work is done; consent denial simply dismisses it.
                finish()
            }
        }
    }

    private fun granted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQ_NOTIFICATIONS = 1
        const val REQ_MIC = 2
        const val REQ_OVERLAY = 3
        const val REQ_CONSENT = 4
    }
}
