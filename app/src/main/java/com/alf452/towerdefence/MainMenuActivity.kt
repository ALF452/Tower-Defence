package com.alf452.towerdefence

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/** Title screen shown before the game starts; the game itself lives in [GameActivity]. */
class MainMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_menu)

        findViewById<Button>(R.id.playButton).setOnClickListener {
            startActivity(Intent(this, GameActivity::class.java))
        }
    }
}
