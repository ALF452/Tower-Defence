package com.alf452.towerdefence

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.alf452.towerdefence.audio.SoundEngine

/** Hosts the actual gameplay [GameView], launched from [MainMenuActivity]. */
class GameActivity : AppCompatActivity() {

    private lateinit var gameView: GameView
    private lateinit var soundEngine: SoundEngine
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        settings = AppSettings(this)
        soundEngine = SoundEngine(this, settings.volume)
        gameView = GameView(this)
        val highScores = HighScores(this)
        val metaProgress = MetaProgress(this)
        gameView.engine.applyMetaProgress(
            startingGold = metaProgress.startingGoldBonus(),
            wallHeadStart = metaProgress.wallHeadStartBonus(),
            cannonHeadStart = metaProgress.cannonHeadStartBonus(),
            archerHeadStart = metaProgress.archerHeadStartBonus()
        )
        gameView.engine.onGameOver = { waveReached, kills ->
            highScores.recordRun(waveReached, kills)
            metaProgress.awardFromRun(waveReached, kills)
        }
        gameView.engine.onCannonFire = { soundEngine.playCannonFire() }
        gameView.engine.onBowFire = { soundEngine.playBowShoot() }
        gameView.engine.onZombieKilled = { soundEngine.playZombieDeath() }
        gameView.engine.onCastleHit = { soundEngine.playCastleHit() }
        gameView.engine.onOrbitalStrike = { soundEngine.playOrbitalStrike() }
        gameView.engine.onEmpFreeze = { soundEngine.playEmpFreeze() }
        gameView.engine.onOvercharge = { soundEngine.playOvercharge() }
        setContentView(gameView)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        // Re-read in case the player changed the volume from the main menu's Settings screen
        // while this activity was backgrounded (e.g. backed out mid-run, adjusted it, resumed).
        soundEngine.masterVolume = settings.volume
        soundEngine.playMusic()
    }

    override fun onPause() {
        super.onPause()
        soundEngine.pauseMusic()
    }

    override fun onDestroy() {
        super.onDestroy()
        soundEngine.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
