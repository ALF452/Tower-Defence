package com.alf452.towerdefence

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Shows the player's best wave reached and most kills in a single run. */
class HighScoresActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_high_scores)

        val highScores = HighScores(this)
        findViewById<TextView>(R.id.bestWaveText).text = highScores.bestWave.toString()
        findViewById<TextView>(R.id.bestKillsText).text = highScores.bestKills.toString()

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }
    }
}
