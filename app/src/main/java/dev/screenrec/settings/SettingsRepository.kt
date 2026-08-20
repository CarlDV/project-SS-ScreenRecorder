package dev.screenrec.settings

import android.content.Context
import dev.screenrec.record.QualityPreset
import dev.screenrec.record.SoundMode

/** Remembers the last choices, the way Samsung's recorder does. */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("screenrec", Context.MODE_PRIVATE)

    var soundMode: SoundMode
        get() = runCatching { SoundMode.valueOf(prefs.getString(KEY_SOUND, null) ?: "") }
            .getOrDefault(SoundMode.MEDIA)
        set(value) = prefs.edit().putString(KEY_SOUND, value.name).apply()

    var preset: QualityPreset
        get() = runCatching { QualityPreset.valueOf(prefs.getString(KEY_PRESET, null) ?: "") }
            .getOrDefault(QualityPreset.P1080)
        set(value) = prefs.edit().putString(KEY_PRESET, value.name).apply()

    /**
     * Whether the floating pill is drawn while capturing. On by default: the notification is
     * not reachable in every situation -- landscape, immersive games, and any device the
     * Android 16 status bar chip does not exist on. It is in the recording when it is on;
     * turning it off trades the on-screen Stop for clean video.
     */
    var onScreenControls: Boolean
        get() = prefs.getBoolean(KEY_ON_SCREEN_CONTROLS, true)
        set(value) = prefs.edit().putBoolean(KEY_ON_SCREEN_CONTROLS, value).apply()

    private companion object {
        const val KEY_SOUND = "sound_mode"
        const val KEY_PRESET = "preset"
        const val KEY_ON_SCREEN_CONTROLS = "on_screen_controls"
    }
}
