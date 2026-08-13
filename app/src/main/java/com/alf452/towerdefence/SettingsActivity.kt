package com.alf452.towerdefence

import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.alf452.towerdefence.audio.SoundEngine

/**
 * Lets the player raise or lower this game's own audio (independent of the phone's system
 * volume) via a 0-100% slider, persisted through [AppSettings]. A short cannon-fire sound plays
 * on release so the chosen level can be heard immediately rather than only read as a percentage.
 * Uses its own single-sound [SoundPool] rather than a full [SoundEngine] — the latter eagerly
 * decodes every gameplay SFX plus the (much larger) looping music track, which would be wasted
 * work here just to preview one click.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var seekBar: SeekBar
    private val previewPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(1)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private var previewSoundId = 0
    // Written from SoundPool's loader-thread callback, read from the UI thread in
    // onStopTrackingTouch below.
    @Volatile
    private var previewLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        settings = AppSettings(this)
        // Listener must be registered before load() so a decode finishing in the brief window
        // before it's attached isn't missed. It doesn't compare the callback's sampleId against
        // previewSoundId (which load() itself hasn't returned/assigned yet at that point, the
        // same ordering hazard one level down) — since this pool only ever loads one sound, any
        // completed load is unambiguously the one being waited on.
        previewPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) previewLoaded = true
        }
        previewSoundId = previewPool.load(this, R.raw.cannon_fire, 1)

        val percentText = findViewById<TextView>(R.id.volumePercentText)
        seekBar = findViewById(R.id.volumeSeekBar)
        val initialPercent = Math.round(settings.volume * 100f)
        seekBar.progress = initialPercent
        percentText.text = getString(R.string.volume_percent, initialPercent)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                percentText.text = getString(R.string.volume_percent, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            // Persist and preview only on release, not on every intermediate drag tick — the
            // in-between values are never meaningful to save and don't need their own SFX.
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val volume = seekBar.progress / 100f
                settings.volume = volume
                if (previewLoaded) {
                    val v = SoundEngine.CANNON_VOLUME * volume
                    previewPool.play(previewSoundId, v, v, 1, 0, 1f)
                }
            }
        })

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }

    override fun onPause() {
        super.onPause()
        // Safety net alongside onStopTrackingTouch: onPause is guaranteed by the activity
        // lifecycle even if some back-navigation path pre-empts the touch stream mid-drag without
        // ever delivering ACTION_UP, so the slider's last-seen position is never silently lost.
        settings.volume = seekBar.progress / 100f
    }

    override fun onDestroy() {
        super.onDestroy()
        previewPool.release()
    }
}
