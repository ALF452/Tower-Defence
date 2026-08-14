package com.alf452.towerdefence

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Spends Star Dust earned across runs on permanent head starts applied to every future run. */
class ArmoryActivity : AppCompatActivity() {

    private lateinit var metaProgress: MetaProgress

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_armory)
        metaProgress = MetaProgress(this)

        findViewById<Button>(R.id.startingGoldBuyButton).setOnClickListener {
            metaProgress.purchaseStartingGold()
            refresh()
        }
        findViewById<Button>(R.id.wallBuyButton).setOnClickListener {
            metaProgress.purchaseWallHeadStart()
            refresh()
        }
        findViewById<Button>(R.id.cannonBuyButton).setOnClickListener {
            metaProgress.purchaseCannonHeadStart()
            refresh()
        }
        findViewById<Button>(R.id.archerBuyButton).setOnClickListener {
            metaProgress.purchaseArcherHeadStart()
            refresh()
        }
        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        refresh()
    }

    private fun refresh() {
        findViewById<TextView>(R.id.starDustText).text =
            getString(R.string.star_dust_balance, metaProgress.starDust)

        bindRow(
            levelTextId = R.id.startingGoldLevelText,
            costTextId = R.id.startingGoldCostText,
            buyButtonId = R.id.startingGoldBuyButton,
            level = metaProgress.startingGoldLevel,
            maxLevel = MetaProgress.STARTING_GOLD_MAX_LEVEL,
            cost = metaProgress.startingGoldCost()
        )
        bindRow(
            levelTextId = R.id.wallLevelText,
            costTextId = R.id.wallCostText,
            buyButtonId = R.id.wallBuyButton,
            level = metaProgress.wallHeadStartLevel,
            maxLevel = MetaProgress.HEAD_START_MAX_LEVEL,
            cost = metaProgress.wallHeadStartCost()
        )
        bindRow(
            levelTextId = R.id.cannonLevelText,
            costTextId = R.id.cannonCostText,
            buyButtonId = R.id.cannonBuyButton,
            level = metaProgress.cannonHeadStartLevel,
            maxLevel = MetaProgress.HEAD_START_MAX_LEVEL,
            cost = metaProgress.cannonHeadStartCost()
        )
        bindRow(
            levelTextId = R.id.archerLevelText,
            costTextId = R.id.archerCostText,
            buyButtonId = R.id.archerBuyButton,
            level = metaProgress.archerHeadStartLevel,
            maxLevel = MetaProgress.HEAD_START_MAX_LEVEL,
            cost = metaProgress.archerHeadStartCost()
        )
    }

    /** Shared binding for the four near-identical upgrade rows in activity_armory.xml. */
    private fun bindRow(levelTextId: Int, costTextId: Int, buyButtonId: Int, level: Int, maxLevel: Int, cost: Int?) {
        findViewById<TextView>(levelTextId).text = getString(R.string.armory_level, level, maxLevel)
        val costText = findViewById<TextView>(costTextId)
        val buyButton = findViewById<Button>(buyButtonId)
        if (cost == null) {
            costText.text = getString(R.string.armory_maxed)
            buyButton.isEnabled = false
            buyButton.alpha = 0.5f
        } else {
            costText.text = getString(R.string.armory_cost, cost)
            buyButton.isEnabled = metaProgress.starDust >= cost
            buyButton.alpha = if (buyButton.isEnabled) 1f else 0.5f
        }
    }
}
