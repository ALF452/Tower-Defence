package com.alf452.towerdefence.game

import kotlin.math.min
import kotlin.random.Random

/**
 * Spawns increasingly large, tougher hordes of zombies each wave. From wave
 * 10 onward, a growing number of larger, tankier Tank Zombies are mixed in
 * (1 starting at wave 10, +1 more every 2 waves after that), spread evenly
 * through the wave's spawn order rather than all arriving at once.
 */
class WaveManager {
    var waveNumber = 1
        private set

    private var spawnQueue: MutableList<Boolean> = mutableListOf() // true = tank
    private var spawnTimer = 0f
    private var spawnIntervalSec = 1.1f
    var waveInProgress = false
        private set

    fun startWave(waveNum: Int) {
        waveNumber = waveNum
        val normalCount = 5 + (waveNumber - 1) * 3
        val tankCount = tankCountForWave(waveNumber)
        spawnQueue = buildSpawnQueue(normalCount, tankCount)
        spawnIntervalSec = kotlin.math.max(0.25f, 1.2f - waveNumber * 0.03f)
        spawnTimer = 0f
        waveInProgress = true
    }

    private fun tankCountForWave(wave: Int): Int {
        if (wave < 10) return 0
        return 1 + (wave - 10) / 2
    }

    /** Interleaves [tankCount] tanks evenly among [normalCount] normal zombies. */
    private fun buildSpawnQueue(normalCount: Int, tankCount: Int): MutableList<Boolean> {
        val total = normalCount + tankCount
        val queue = MutableList(total) { false }
        for (i in 0 until tankCount) {
            val pos = ((i + 1) * total / (tankCount + 1)).coerceIn(0, total - 1)
            queue[pos] = true
        }
        return queue
    }

    /** Returns a newly spawned zombie this frame, or null if none spawned. */
    fun update(dt: Float, arenaRadius: Float, centerX: Float, centerY: Float, visualScale: Float): Zombie? {
        if (!waveInProgress || spawnQueue.isEmpty()) return null
        spawnTimer -= dt
        if (spawnTimer > 0f) return null
        spawnTimer = spawnIntervalSec
        val isTank = spawnQueue.removeAt(0)

        val angle = Random.nextFloat() * (2f * Math.PI).toFloat()
        val spawnX = centerX + arenaRadius * kotlin.math.cos(angle)
        val spawnY = centerY + arenaRadius * kotlin.math.sin(angle)

        // Health/damage growth per wave was halved (and quartered respectively) from earlier
        // tuning to match the leaner gold economy below — with income now buying roughly one
        // upgrade per wave instead of two or three, enemies need to get tougher more gradually
        // to stay fair over a long run.
        val baseHealth = 30f + (waveNumber - 1) * 8f
        // Speed keeps climbing every wave (capped only as a defensive backstop far past
        // any realistic run length) instead of plateauing around wave 27 like it used to.
        val baseSpeed = 40f + min(waveNumber * 1.7f, 300f)
        val baseDamage = 4f + (waveNumber / 4)
        // Flat per-kill/tank gold: growing kill *count* each wave is what drives income up, not
        // an additionally-scaling per-kill value, so total gold earned per wave tracks the
        // (also exponential) upgrade cost curve closely enough to afford ~1 upgrade per clear
        // instead of 2-3.
        val baseGold = 2

        val health = if (isTank) baseHealth * 5.5f else baseHealth
        val speed = (if (isTank) baseSpeed * 0.58f else baseSpeed) * visualScale
        val damage = if (isTank) baseDamage * 2.6f else baseDamage
        val goldReward = if (isTank) baseGold * 2 else baseGold

        return Zombie(spawnX, spawnY, health, speed, damage, goldReward, visualScale, isTank)
    }

    fun allSpawned(): Boolean = spawnQueue.isEmpty()

    fun endWave() {
        waveInProgress = false
        waveNumber++
    }
}
