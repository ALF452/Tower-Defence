package com.alf452.towerdefence.game

import kotlin.math.min
import kotlin.random.Random

/**
 * Spawns increasingly large, tougher hordes of zombies each wave.
 */
class WaveManager {
    var waveNumber = 1
        private set

    private var toSpawn = 0
    private var spawnTimer = 0f
    private var spawnIntervalSec = 1.1f
    var waveInProgress = false
        private set

    fun startWave(waveNum: Int) {
        waveNumber = waveNum
        toSpawn = 5 + (waveNumber - 1) * 3
        spawnIntervalSec = kotlin.math.max(0.25f, 1.2f - waveNumber * 0.03f)
        spawnTimer = 0f
        waveInProgress = true
    }

    /** Returns a newly spawned zombie this frame, or null if none spawned. */
    fun update(dt: Float, arenaRadius: Float, centerX: Float, centerY: Float, visualScale: Float): Zombie? {
        if (!waveInProgress || toSpawn <= 0) return null
        spawnTimer -= dt
        if (spawnTimer > 0f) return null
        spawnTimer = spawnIntervalSec
        toSpawn--

        val angle = Random.nextFloat() * (2f * Math.PI).toFloat()
        val spawnX = centerX + arenaRadius * kotlin.math.cos(angle)
        val spawnY = centerY + arenaRadius * kotlin.math.sin(angle)

        val health = 30f + (waveNumber - 1) * 8f
        val speed = (40f + min(waveNumber * 1.5f, 40f)) * visualScale
        val damage = 4f + (waveNumber / 3)
        val goldReward = 5 + waveNumber

        return Zombie(spawnX, spawnY, health, speed, damage, goldReward, visualScale)
    }

    fun allSpawned(): Boolean = toSpawn <= 0

    fun endWave() {
        waveInProgress = false
        waveNumber++
    }
}
