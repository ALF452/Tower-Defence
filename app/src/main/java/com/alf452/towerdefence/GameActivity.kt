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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        soundEngine = SoundEngine(this)
        gameView = GameView(this)
        val highScores = HighScores(this)
        gameView.engine.onGameOver = { waveReached, kills -> highScores.recordRun(waveReached, kills) }
        gameView.engine.onCannonFire = { soundEngine.playCannonFire() }
        gameView.engine.onBowFire = { soundEngine.playBowShoot() }
        gameView.engine.onZombieKilled = { soundEngine.playZombieDeath() }
        gameView.engine.onCastleHit = { soundEngine.playCastleHit() }
        setContentView(gameView)
        hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
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
