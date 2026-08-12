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

        val baseHealth = 30f + (waveNumber - 1) * 18f
        // Speed keeps climbing every wave (capped only as a defensive backstop far past
        // any realistic run length) instead of plateauing around wave 27 like it used to.
        val baseSpeed = 40f + min(waveNumber * 1.7f, 300f)
        val baseDamage = 4f + (waveNumber / 3)
        // Per-kill gold value stops growing past wave 12 (kill *count* keeps growing every
        // wave regardless), so late-game income no longer scales quadratically with wave
        // number and outpaces the 1.3x/level upgrade costs the way it used to.
        val baseGold = 5 + minOf(waveNumber, 12)

        val health = if (isTank) baseHealth * 5.5f else baseHealth
        val speed = (if (isTank) baseSpeed * 0.58f else baseSpeed) * visualScale
        val damage = if (isTank) baseDamage * 2.6f else baseDamage
        val goldReward = if (isTank) (baseGold * 7) / 2 else baseGold

        return Zombie(spawnX, spawnY, health, speed, damage, goldReward, visualScale, isTank)
    }

    fun allSpawned(): Boolean = spawnQueue.isEmpty()

    fun endWave() {
        waveInProgress = false
        waveNumber++
    }
}
