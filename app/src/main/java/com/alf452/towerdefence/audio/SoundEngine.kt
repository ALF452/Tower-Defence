package com.alf452.towerdefence.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.alf452.towerdefence.R
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns gameplay audio: short sound effects and the looping background track are both played
 * through [SoundPool] rather than splitting SFX (SoundPool) from music (MediaPlayer) as is more
 * typical — MediaPlayer's `setLooping()` re-seeks and re-primes its decoder on every repeat,
 * which produces an audible gap between loops even for uncompressed audio. SoundPool loops a
 * fully-decoded in-memory buffer with sample-accurate timing instead, so the background track
 * (short enough to load entirely into memory) restarts with no gap. All assets under res/raw are
 * procedurally synthesized chiptune WAVs — the game has no external art or audio assets,
 * everything is built in code, and audio follows the same rule.
 */
class SoundEngine(context: Context) {

    companion object {
        // SoundPool priority: 0 is lowest — when the stream pool is full, the lowest-priority
        // active stream is evicted to make room for a new one. Music is ranked above SFX so a
        // burst of gameplay sounds drops an SFX instead of silently killing the music loop
        // (which, unlike a dropped SFX, has no way to notice it was evicted and restart itself).
        private const val SFX_PRIORITY = 1
        private const val MUSIC_PRIORITY = 2

        // All levels here are 70% of their original value (a flat 30% volume cut across the
        // board), kept as one named constant per sound rather than a literal at each play() call
        // so the music volume in particular — needed in two places below — can't drift out of
        // sync between them.
        private const val CANNON_VOLUME = 0.35f
        private const val BOW_VOLUME = 0.315f
        private const val ZOMBIE_DEATH_VOLUME = 0.385f
        private const val CASTLE_HIT_VOLUME = 0.455f
        private const val MUSIC_VOLUME = 0.245f
    }

    private val appContext = context.applicationContext

    private val soundPool: SoundPool = SoundPool.Builder()
        // Headroom for a busy wave (up to 4 cannons + 4 archers firing, several zombies dying,
        // and castle hits, all in the same frame) plus the looping music stream, so a burst of
        // gameplay SFX doesn't force SoundPool to start evicting active streams.
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    // SoundPool.load() only kicks off an async background decode and returns immediately, so a
    // sound fired before its decode finishes (realistically only possible in the first instant
    // after SoundEngine is constructed) would otherwise be a silent no-op with no feedback.
    // Tracking completed loads here means play() calls before that just skip (or, for music,
    // defer — see onLoadComplete below) instead of firing a sound that was never going to be
    // heard. CopyOnWriteArraySet since writes happen once each on SoundPool's loader thread
    // while reads happen on GameThread.
    private val loadedSoundIds = CopyOnWriteArraySet<Int>()

    private var musicSoundId = 0
    private var musicStreamId = 0
    private var musicRequested = false

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loadedSoundIds.add(sampleId)
            if (sampleId == musicSoundId && musicRequested && musicStreamId == 0) {
                musicStreamId = soundPool.play(musicSoundId, MUSIC_VOLUME, MUSIC_VOLUME, MUSIC_PRIORITY, -1, 1f)
            }
        }
    }

    private val cannonSoundId = soundPool.load(appContext, R.raw.cannon_fire, 1)
    private val bowSoundId = soundPool.load(appContext, R.raw.bow_shoot, 1)
    private val zombieDeathSoundId = soundPool.load(appContext, R.raw.zombie_death, 1)
    private val castleHitSoundId = soundPool.load(appContext, R.raw.castle_hit, 1)

    init {
        musicSoundId = soundPool.load(appContext, R.raw.bg_music, 1)
    }

    private fun playIfLoaded(soundId: Int, volume: Float) {
        if (soundId in loadedSoundIds) {
            soundPool.play(soundId, volume, volume, SFX_PRIORITY, 0, 1f)
        }
    }

    fun playCannonFire() {
        playIfLoaded(cannonSoundId, CANNON_VOLUME)
    }

    fun playBowShoot() {
        playIfLoaded(bowSoundId, BOW_VOLUME)
    }

    fun playZombieDeath() {
        playIfLoaded(zombieDeathSoundId, ZOMBIE_DEATH_VOLUME)
    }

    fun playCastleHit() {
        playIfLoaded(castleHitSoundId, CASTLE_HIT_VOLUME)
    }

    /**
     * Starts the looping background track on first call, resumes it on later calls (e.g. coming
     * back from onPause) — one method covers both cases so callers don't need to track player
     * state themselves. If the track hasn't finished decoding yet, the load-complete listener
     * above starts it as soon as it's ready instead of silently dropping the request.
     */
    fun playMusic() {
        musicRequested = true
        if (musicStreamId != 0) {
            soundPool.resume(musicStreamId)
        } else if (musicSoundId in loadedSoundIds) {
            musicStreamId = soundPool.play(musicSoundId, MUSIC_VOLUME, MUSIC_VOLUME, MUSIC_PRIORITY, -1, 1f)
        }
    }

    fun pauseMusic() {
        // Also clears the request so a pause landing in the narrow window before the deferred
        // load-triggered playback above fires doesn't get silently ignored, which would start
        // the loop audibly after the activity is already backgrounded.
        musicRequested = false
        if (musicStreamId != 0) soundPool.pause(musicStreamId)
    }

    fun release() {
        soundPool.release()
    }
}
