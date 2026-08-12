package com.alf452.towerdefence

import android.content.Context

/**
 * Thin wrapper over a SharedPreferences file tracking the player's best runs.
 * SharedPreferences instances are safe to hold onto (the framework caches them
 * per-file internally regardless of which Context flavor obtained them), so
 * this class never stores a Context itself.
 */
class HighScores(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var bestWave: Int
        get() = prefs.getInt(KEY_BEST_WAVE, 0)
        private set(value) { prefs.edit().putInt(KEY_BEST_WAVE, value).apply() }

    var bestKills: Int
        get() = prefs.getInt(KEY_BEST_KILLS, 0)
        private set(value) { prefs.edit().putInt(KEY_BEST_KILLS, value).apply() }

    /** Called once when a run ends; updates whichever records were beaten. */
    fun recordRun(waveReached: Int, kills: Int) {
        if (waveReached > bestWave) bestWave = waveReached
        if (kills > bestKills) bestKills = kills
    }

    companion object {
        private const val PREFS_NAME = "high_scores"
        private const val KEY_BEST_WAVE = "best_wave"
        private const val KEY_BEST_KILLS = "best_kills"
    }
}
