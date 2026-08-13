package com.alf452.towerdefence.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.alf452.towerdefence.R
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Owns gameplay audio: short sound effects via [SoundPool] (low-latency, overlapping playback
 * for near-simultaneous cannon/archer volleys) and a looping background track via [MediaPlayer].
 * All assets under res/raw are procedurally synthesized chiptune WAVs — the game has no
 * external art or audio assets, everything is built in code, and audio follows the same rule.
 */
class SoundEngine(context: Context) {
    private val appContext = context.applicationContext

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(8)
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
    // Tracking completed loads here means play() calls before that just skip instead of firing
    // a sound that was never going to be heard. CopyOnWriteArraySet since writes happen once
    // each on SoundPool's loader thread while reads happen on GameThread.
    private val loadedSoundIds = CopyOnWriteArraySet<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loadedSoundIds.add(sampleId)
        }
    }

    private val cannonSoundId = soundPool.load(appContext, R.raw.cannon_fire, 1)
    private val bowSoundId = soundPool.load(appContext, R.raw.bow_shoot, 1)
    private val zombieDeathSoundId = soundPool.load(appContext, R.raw.zombie_death, 1)
    private val castleHitSoundId = soundPool.load(appContext, R.raw.castle_hit, 1)

    private var musicPlayer: MediaPlayer? = null

    private fun playIfLoaded(soundId: Int, volume: Float) {
        if (soundId in loadedSoundIds) {
            soundPool.play(soundId, volume, volume, 1, 0, 1f)
        }
    }

    fun playCannonFire() {
        playIfLoaded(cannonSoundId, 0.5f)
    }

    fun playBowShoot() {
        playIfLoaded(bowSoundId, 0.45f)
    }

    fun playZombieDeath() {
        playIfLoaded(zombieDeathSoundId, 0.55f)
    }

    fun playCastleHit() {
        playIfLoaded(castleHitSoundId, 0.65f)
    }

    /**
     * Creates the looping player on first call, otherwise just resumes it — one method covers
     * both "start the game" and "coming back from onPause" so callers don't need to track which
     * state the player is in themselves.
     */
    fun playMusic() {
        if (musicPlayer == null) {
            musicPlayer = MediaPlayer.create(appContext, R.raw.bg_music)?.apply {
                isLooping = true
                setVolume(0.35f, 0.35f)
            }
        }
        musicPlayer?.start()
    }

    fun pauseMusic() {
        musicPlayer?.let { if (it.isPlaying) it.pause() }
    }

    fun release() {
        soundPool.release()
        musicPlayer?.release()
        musicPlayer = null
    }
}
