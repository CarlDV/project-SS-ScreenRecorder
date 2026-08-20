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

    private companion object {
        const val KEY_SOUND = "sound_mode"
        const val KEY_PRESET = "preset"
    }
}
