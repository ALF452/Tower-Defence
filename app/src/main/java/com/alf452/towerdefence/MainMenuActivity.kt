package com.alf452.towerdefence

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Title screen shown before the game starts; the game itself lives in [GameActivity]. */
class MainMenuActivity : AppCompatActivity() {

    private lateinit var highScores: HighScores

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)
        highScores = HighScores(this)

        findViewById<Button>(R.id.playButton).setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }
        findViewById<Button>(R.id.highScoresButton).setOnClickListener {
            startActivity(Intent(this, HighScoresActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh in case a run just finished (or a new best was set) since onCreate.
        findViewById<TextView>(R.id.statsTeaserText).text =
            getString(R.string.stats_teaser, highScores.bestWave, highScores.bestKills)
    }
}
