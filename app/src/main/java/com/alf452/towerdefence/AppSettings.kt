package com.alf452.towerdefence

import android.content.Context

/**
 * Thin wrapper over a SharedPreferences file tracking player-configurable app settings — for now
 * just the in-game volume multiplier applied on top of [com.alf452.towerdefence.audio.SoundEngine]'s
 * base sound levels, independent of the phone's system media volume.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var volume: Float
        get() = prefs.getFloat(KEY_VOLUME, 1f)
        set(value) { prefs.edit().putFloat(KEY_VOLUME, value.coerceIn(0f, 1f)).apply() }

    companion object {
        private const val PREFS_NAME = "app_settings"
        private const val KEY_VOLUME = "volume"
    }
}
