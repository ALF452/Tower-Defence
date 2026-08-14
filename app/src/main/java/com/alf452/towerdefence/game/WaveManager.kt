package com.alf452.towerdefence.game

import kotlin.math.min
import kotlin.random.Random

/**
 * Spawns increasingly large, tougher hordes of zombies each wave. From wave
 * 10 onward, a growing number of larger, tankier Tank Zombies are mixed in
 * (1 starting at wave 10, +1 more every 2 waves after that), plus a growing
 * number of fast burnt-orange Worms (1 starting at wave 10, +1 more every 2
 * waves after that) that burrow up and rush the castle. Both are spread
 * evenly through the wave's spawn order rather than all arriving at once.
 */
class WaveManager {
    var waveNumber = 1
        private set

    private var spawnQueue: MutableList<EnemyKind> = mutableListOf()
    private var spawnTimer = 0f
    private var spawnIntervalSec = 1.1f
    var waveInProgress = false
        private set

    fun startWave(waveNum: Int) {
        waveNumber = waveNum
        // Normal-zombie growth trimmed from +3/wave to +2, and spawn cadence tightens much
        // faster (was max(0.25, 1.2 - wave*0.03), floor only reached ~wave 32) — a headless
        // sim showed waves ballooning to ~50s each by wave 20 (11.5 min total for waves 1-20)
        // under the old formulas, almost entirely from queueing up more zombies than the spawn
        // interval was shrinking fast enough to offset. This roughly halves both figures.
        val normalCount = 5 + (waveNumber - 1) * 2
        val tankCount = tankCountForWave(waveNumber)
        val wormCount = wormCountForWave(waveNumber)
        spawnQueue = buildSpawnQueue(normalCount, tankCount, wormCount)
        spawnIntervalSec = kotlin.math.max(0.22f, 1.1f - waveNumber * 0.05f)
        spawnTimer = 0f
        waveInProgress = true
    }

    /** Shared cadence for both special enemy types: starts at [startWave], +1 every [every] waves. */
    private fun countForWave(wave: Int, startWave: Int = 10, every: Int = 2): Int {
        if (wave < startWave) return 0
        return 1 + (wave - startWave) / every
    }

    private fun tankCountForWave(wave: Int): Int = countForWave(wave)

    private fun wormCountForWave(wave: Int): Int = countForWave(wave)

    /**
     * Interleaves [tankCount] tanks and [wormCount] worms evenly among [normalCount] normal
     * zombies. Tanks are placed first (same even-spacing formula as before), then worms are
     * spread evenly among whatever normal slots are left — placing both independently onto the
     * same evenly-spaced positions would let them collide and silently overwrite each other.
     */
    private fun buildSpawnQueue(normalCount: Int, tankCount: Int, wormCount: Int): MutableList<EnemyKind> {
        val total = normalCount + tankCount + wormCount
        val queue = MutableList(total) { EnemyKind.NORMAL }
        for (i in 0 until tankCount) {
            val pos = ((i + 1) * total / (tankCount + 1)).coerceIn(0, total - 1)
            queue[pos] = EnemyKind.TANK
        }
        val normalSlots = queue.indices.filter { queue[it] == EnemyKind.NORMAL }
        for (i in 0 until wormCount) {
            if (normalSlots.isEmpty()) break
            val slot = ((i + 1) * normalSlots.size / (wormCount + 1)).coerceIn(0, normalSlots.size - 1)
            queue[normalSlots[slot]] = EnemyKind.WORM
        }
        return queue
    }

    /** Returns a newly spawned zombie this frame, or null if none spawned. */
    fun update(dt: Float, arenaRadius: Float, centerX: Float, centerY: Float, visualScale: Float): Zombie? {
        if (!waveInProgress || spawnQueue.isEmpty()) return null
        spawnTimer -= dt
        if (spawnTimer > 0f) return null
        spawnTimer = spawnIntervalSec
        val kind = spawnQueue.removeAt(0)

        val angle = Random.nextFloat() * (2f * Math.PI).toFloat()
        val spawnX = centerX + arenaRadius * kotlin.math.cos(angle)
        val spawnY = centerY + arenaRadius * kotlin.math.sin(angle)

        // Retuned from 9.25f down to 7.6f alongside the leaner zombie count above: fewer
        // zombies per wave means less total health to chew through even before any per-zombie
        // change, so per-zombie health had to come down too to land back on target. Verified via
        // a headless Monte Carlo sim (1200 trials): 85.8% win rate at wave 20 (down from a
        // 99%-cakewalk under the old formulas) and 70.8% at wave 25, matching this game's
        // established ~70%-at-25 target almost exactly.
        val baseHealth = 30f + (waveNumber - 1) * 7.6f
        // Speed keeps climbing every wave (capped only as a defensive backstop far past
        // any realistic run length) instead of plateauing around wave 27 like it used to.
        val baseSpeed = 40f + min(waveNumber * 1.7f, 300f)
        val baseDamage = 4f + (waveNumber / 4)
        // Flat per-kill/tank gold, bumped from 2 to 3 alongside the wave-clear bonus reduction
        // in GameEngine.kt: fewer kills per wave now (see normalCount above), so per-kill income
        // needed to rise to keep pace with roughly one affordable upgrade per wave.
        val baseGold = 3

        // Worms trade health for speed: individually fragile (so a couple of hits drop one) but
        // fast enough that letting one go unanswered for even a second or two costs real castle
        // health, unlike a tank's slow, telegraphed approach.
        val health = when (kind) {
            EnemyKind.TANK -> baseHealth * 5.5f
            EnemyKind.WORM -> baseHealth * 0.65f
            EnemyKind.NORMAL -> baseHealth
        }
        val speed = (when (kind) {
            EnemyKind.TANK -> baseSpeed * 0.58f
            EnemyKind.WORM -> baseSpeed * 2f
            EnemyKind.NORMAL -> baseSpeed
        }) * visualScale
        val damage = when (kind) {
            EnemyKind.TANK -> baseDamage * 2.6f
            EnemyKind.WORM -> baseDamage * 1.6f
            EnemyKind.NORMAL -> baseDamage
        }
        val goldReward = when (kind) {
            EnemyKind.TANK -> baseGold * 2
            EnemyKind.WORM -> (baseGold * 3) / 2
            EnemyKind.NORMAL -> baseGold
        }

        return Zombie(spawnX, spawnY, health, speed, damage, goldReward, visualScale, kind)
    }

    fun allSpawned(): Boolean = spawnQueue.isEmpty()

    fun endWave() {
        waveInProgress = false
        waveNumber++
    }
}
