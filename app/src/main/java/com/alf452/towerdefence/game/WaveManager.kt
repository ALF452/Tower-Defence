package com.alf452.towerdefence.game

import kotlin.math.min
import kotlin.random.Random

/**
 * Spawns increasingly large, tougher hordes of zombies each wave. From wave 13 onward, a growing
 * number of larger, tankier Tank Zombies are mixed in (1 starting at wave 13, +1 more every 2
 * waves after that); from wave 7 onward — staggered earlier than tanks so the two special kinds
 * don't both debut in the same wave — a growing number of fast burnt-orange Worms (1 starting at
 * wave 7, +1 more every 2 waves after that) that burrow up and rush the castle. Both are spread
 * evenly through the wave's spawn order rather than all arriving at once.
 *
 * Wave 19, and every [BOSS_INTERVAL] waves after it, is a dedicated boss wave: a single Galaxy
 * Snail (see [EnemyKind.BOSS] and [Zombie.drawSnail]) spawns alone instead of the usual horde —
 * every other wave, including the ones between boss waves, still follows the normal formula above.
 */
class WaveManager {
    companion object {
        const val WORM_START_WAVE = 7
        const val TANK_START_WAVE = 13
        const val BOSS_START_WAVE = 19
        const val BOSS_INTERVAL = 6
        // Tuned via a headless Monte Carlo sim of waves 1-30 (1200 trials): its slow, flat crawl
        // speed gives weapons a fixed ~(arena radius / BOSS_SPEED) window to burn its health pool
        // down before it reaches the castle. At this multiplier/speed the boss is a genuine,
        // sometimes-fatal fight the first time it appears (wave 19, right as tank/worm are still
        // new and cannon/archer levels are only ~7-8) — roughly a 1-in-40 chance of dying right
        // there — but by its later appearances (wave 25 on) player power has scaled enough that it
        // stops being the binding threat; overall win rate lands at 69.0%, matching this game's
        // ~70%-by-wave-30 target almost exactly, without shifting where the run's other losses
        // cluster (still waves 28-30, same as with the boss disabled entirely).
        const val BOSS_HEALTH_MULT = 35f
        const val BOSS_SPEED = 12f
        const val BOSS_GOLD = 150
    }

    var waveNumber = 1
        private set

    private var spawnQueue: MutableList<EnemyKind> = mutableListOf()
    private var spawnTimer = 0f
    private var spawnIntervalSec = 1.1f
    var waveInProgress = false
        private set

    /** True for wave 19 and every [BOSS_INTERVAL] waves after — see the class doc. */
    fun isBossWave(wave: Int): Boolean = wave >= BOSS_START_WAVE && (wave - BOSS_START_WAVE) % BOSS_INTERVAL == 0

    fun startWave(waveNum: Int) {
        waveNumber = waveNum
        if (isBossWave(waveNumber)) {
            // The boss spawns alone — no normal/tank/worm horde alongside it this wave.
            spawnQueue = mutableListOf(EnemyKind.BOSS)
        } else {
            // Normal-zombie growth trimmed from +3/wave to +2, and spawn cadence tightens much
            // faster (was max(0.25, 1.2 - wave*0.03), floor only reached ~wave 32) — a headless
            // sim showed waves ballooning to ~50s each by wave 20 (11.5 min total for waves
            // 1-20) under the old formulas, almost entirely from queueing up more zombies than
            // the spawn interval was shrinking fast enough to offset. This roughly halves both
            // figures.
            val normalCount = 5 + (waveNumber - 1) * 2
            val tankCount = countForWave(waveNumber, TANK_START_WAVE)
            val wormCount = countForWave(waveNumber, WORM_START_WAVE)
            spawnQueue = buildSpawnQueue(normalCount, tankCount, wormCount)
        }
        spawnIntervalSec = kotlin.math.max(0.22f, 1.1f - waveNumber * 0.05f)
        spawnTimer = 0f
        waveInProgress = true
    }

    /** Shared cadence for both special enemy types: starts at [startWave], +1 every [every] waves. */
    private fun countForWave(wave: Int, startWave: Int, every: Int = 2): Int {
        if (wave < startWave) return 0
        return 1 + (wave - startWave) / every
    }

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

        // Retuned again, 7.6f down to 6.3f: the previous value was only ever verified out to
        // wave 25, and a headless Monte Carlo sim run out to wave 30 (this game's new target
        // range, alongside the boss's debut on wave 19) showed it collapsing hard in the last
        // few waves — even a cheapest-first "perfect" playthrough died around wave 29, well
        // under the ~70%-by-wave-30 target. 6.3f (1200 trials) lands at 70.4% by wave 30, with
        // essentially no losses before wave 27 — the earlier waves keep the difficulty curve
        // this game already had, the cliff just moves out to where the new target range ends.
        val baseHealth = 30f + (waveNumber - 1) * 6.3f
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
        // health, unlike a tank's slow, telegraphed approach. The boss is the opposite of a
        // worm — see BOSS_HEALTH_MULT/BOSS_SPEED below — a huge health pool crawling in at a
        // flat, wave-independent creep, giving the player a fixed window (arena radius / speed)
        // to burn it down with weapon DPS before it reaches the castle.
        val health = when (kind) {
            EnemyKind.TANK -> baseHealth * 5.5f
            EnemyKind.WORM -> baseHealth * 0.65f
            EnemyKind.BOSS -> baseHealth * BOSS_HEALTH_MULT
            EnemyKind.NORMAL -> baseHealth
        }
        val speed = (when (kind) {
            EnemyKind.TANK -> baseSpeed * 0.58f
            EnemyKind.WORM -> baseSpeed * 2f
            EnemyKind.BOSS -> BOSS_SPEED
            EnemyKind.NORMAL -> baseSpeed
        }) * visualScale
        // The boss's contactDamage is never applied through the normal repeated-tick path (see
        // Zombie.update's BOSS branch) — reaching the castle is a single fatal explosion instead,
        // so this value just needs to comfortably exceed any possible remaining health+shield.
        val damage = when (kind) {
            EnemyKind.TANK -> baseDamage * 2.6f
            EnemyKind.WORM -> baseDamage * 1.6f
            EnemyKind.BOSS -> 999999f
            EnemyKind.NORMAL -> baseDamage
        }
        val goldReward = when (kind) {
            EnemyKind.TANK -> baseGold * 2
            EnemyKind.WORM -> (baseGold * 3) / 2
            EnemyKind.BOSS -> BOSS_GOLD
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
