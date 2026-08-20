package dev.screenrec.ui

import android.app.Activity
import android.os.Bundle
import android.widget.CheckBox
import android.widget.RadioGroup
import dev.screenrec.R
import dev.screenrec.record.QualityPreset
import dev.screenrec.record.SoundMode
import dev.screenrec.settings.SettingsRepository

/**
 * Sound, quality and the floating controls, as a bottom sheet.
 *
 * Reached only from the launcher's long-press menu -- tapping the icon starts a recording, so
 * putting these in the way of that was making the common case cost an extra screen. There is no
 * Start button here on purpose: the icon is the start button.
 *
 * Every control writes straight through to [SettingsRepository] as it is touched, so there is
 * nothing to save and dismissing by tapping outside cannot lose a choice.
 */
class SettingsActivity : Activity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = SettingsRepository(this)

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

        findViewById<CheckBox>(R.id.on_screen_controls).apply {
            isChecked = settings.onScreenControls
            setOnCheckedChangeListener { _, checked -> settings.onScreenControls = checked }
        }
    }
}
