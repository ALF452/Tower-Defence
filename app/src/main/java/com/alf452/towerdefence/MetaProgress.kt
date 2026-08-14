package com.alf452.towerdefence

import android.content.Context
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Persistent cross-run currency ("Star Dust") and the permanent head-start unlocks it buys in
 * the Armory screen, applied at the start of every future run by [com.alf452.towerdefence.game.GameEngine.applyMetaProgress].
 * Mirrors [HighScores]'s SharedPreferences-backed, mutation-through-named-methods pattern
 * rather than exposing freely settable properties, since currency/levels shouldn't be writable
 * from outside except through [awardFromRun] and the purchase methods below.
 */
class MetaProgress(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var starDust: Int
        get() = prefs.getInt(KEY_STAR_DUST, 0)
        private set(value) { prefs.edit().putInt(KEY_STAR_DUST, value).apply() }

    var startingGoldLevel: Int
        get() = prefs.getInt(KEY_STARTING_GOLD_LEVEL, 0)
        private set(value) { prefs.edit().putInt(KEY_STARTING_GOLD_LEVEL, value).apply() }

    var wallHeadStartLevel: Int
        get() = prefs.getInt(KEY_WALL_LEVEL, 0)
        private set(value) { prefs.edit().putInt(KEY_WALL_LEVEL, value).apply() }

    var cannonHeadStartLevel: Int
        get() = prefs.getInt(KEY_CANNON_LEVEL, 0)
        private set(value) { prefs.edit().putInt(KEY_CANNON_LEVEL, value).apply() }

    var archerHeadStartLevel: Int
        get() = prefs.getInt(KEY_ARCHER_LEVEL, 0)
        private set(value) { prefs.edit().putInt(KEY_ARCHER_LEVEL, value).apply() }

    /**
     * Star Dust earned at the end of a run, from how far it got and how much it killed.
     * [bonusPercent] is the run's active mutators' combined Star Dust bonus (see
     * [com.alf452.towerdefence.game.GameEngine.mutatorStarDustBonusPercent]) — 0 for a run with
     * none active.
     */
    fun awardFromRun(waveReached: Int, kills: Int, bonusPercent: Int = 0) {
        val base = waveReached * 5 + kills
        starDust += base + (base * bonusPercent) / 100
    }

    fun startingGoldBonus(): Int = startingGoldLevel * STARTING_GOLD_PER_LEVEL
    fun wallHeadStartBonus(): Int = wallHeadStartLevel
    fun cannonHeadStartBonus(): Int = cannonHeadStartLevel
    fun archerHeadStartBonus(): Int = archerHeadStartLevel

    /** Null once a track is maxed, matching [com.alf452.towerdefence.game.GameEngine]'s cost-getter convention. */
    fun startingGoldCost(): Int? = costFor(startingGoldLevel, STARTING_GOLD_MAX_LEVEL, STARTING_GOLD_BASE_COST)
    fun wallHeadStartCost(): Int? = costFor(wallHeadStartLevel, HEAD_START_MAX_LEVEL, WALL_BASE_COST)
    fun cannonHeadStartCost(): Int? = costFor(cannonHeadStartLevel, HEAD_START_MAX_LEVEL, CANNON_BASE_COST)
    fun archerHeadStartCost(): Int? = costFor(archerHeadStartLevel, HEAD_START_MAX_LEVEL, ARCHER_BASE_COST)

    fun purchaseStartingGold(): Boolean = purchase(startingGoldCost()) { startingGoldLevel++ }
    fun purchaseWallHeadStart(): Boolean = purchase(wallHeadStartCost()) { wallHeadStartLevel++ }
    fun purchaseCannonHeadStart(): Boolean = purchase(cannonHeadStartCost()) { cannonHeadStartLevel++ }
    fun purchaseArcherHeadStart(): Boolean = purchase(archerHeadStartCost()) { archerHeadStartLevel++ }

    private fun costFor(level: Int, maxLevel: Int, base: Int): Int? {
        if (level >= maxLevel) return null
        return (base * COST_GROWTH.pow(level)).roundToInt()
    }

    // Two separate SharedPreferences commits (starDust's setter, then applyLevel's setter) rather
    // than one atomic write. An OS process kill exactly between them could lose the spent Star
    // Dust without granting the level, but that's a soft-currency edge case, not worth the extra
    // machinery of a combined Editor transaction for.
    private fun purchase(cost: Int?, applyLevel: () -> Unit): Boolean {
        if (cost == null || starDust < cost) return false
        starDust -= cost
        applyLevel()
        return true
    }

    companion object {
        private const val PREFS_NAME = "meta_progress"
        private const val KEY_STAR_DUST = "star_dust"
        private const val KEY_STARTING_GOLD_LEVEL = "starting_gold_level"
        private const val KEY_WALL_LEVEL = "wall_head_start_level"
        private const val KEY_CANNON_LEVEL = "cannon_head_start_level"
        private const val KEY_ARCHER_LEVEL = "archer_head_start_level"

        const val STARTING_GOLD_PER_LEVEL = 25
        const val STARTING_GOLD_MAX_LEVEL = 5
        const val HEAD_START_MAX_LEVEL = 3

        private const val COST_GROWTH = 1.6
        private const val STARTING_GOLD_BASE_COST = 60
        private const val WALL_BASE_COST = 140
        private const val CANNON_BASE_COST = 150
        private const val ARCHER_BASE_COST = 120
    }
}
