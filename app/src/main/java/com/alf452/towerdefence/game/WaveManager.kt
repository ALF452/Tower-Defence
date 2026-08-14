package com.alf452.towerdefence.game

import kotlin.math.min
import kotlin.random.Random

/**
 * Spawns increasingly large, tougher hordes of zombies each wave, staggering each special kind's
 * debut wave so no two ever appear for the first time in the same wave: Worms from wave
 * [WORM_START_WAVE], Flyers from [FLYER_START_WAVE], Tanks from [TANK_START_WAVE], and Shielded
 * zombies from [SHIELDED_START_WAVE] — all growing by +1 every 2 waves after their debut and
 * spread evenly through the wave's spawn order (see [buildSpawnQueue]) rather than all arriving
 * at once. Tank/Worm are pure numbers/speed variants; Flyer and Shielded are this game's
 * weapon-mix enemies — see [EnemyKind]'s class doc and [Zombie.canBeTargetedBy].
 *
 * Wave 19, and every [BOSS_INTERVAL] waves after it, is a dedicated boss wave: a single Galaxy
 * Snail (see [EnemyKind.BOSS] and [Zombie.drawSnail]) spawns alone instead of the usual horde —
 * every other wave, including the ones between boss waves, still follows the normal formula above.
 */
class WaveManager {
    companion object {
        const val WORM_START_WAVE = 7
        const val FLYER_START_WAVE = 10
        const val TANK_START_WAVE = 13
        const val SHIELDED_START_WAVE = 16
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
            val specialCounts = listOf(
                EnemyKind.TANK to countForWave(waveNumber, TANK_START_WAVE),
                EnemyKind.WORM to countForWave(waveNumber, WORM_START_WAVE),
                EnemyKind.FLYER to countForWave(waveNumber, FLYER_START_WAVE),
                EnemyKind.SHIELDED to countForWave(waveNumber, SHIELDED_START_WAVE)
            )
            spawnQueue = buildSpawnQueue(normalCount, specialCounts)
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
     * Interleaves each (kind, count) pair from [specialCounts], in order, evenly among whatever
     * slots are still [EnemyKind.NORMAL] at that point — each kind spaces itself out across the
     * NORMAL slots the previous kinds left behind, so multiple special kinds in the same wave
     * can't collide and silently overwrite each other. Processing the very first kind this way is
     * equivalent to spacing it evenly across the *entire* queue, since every slot is still NORMAL
     * at that point — so this reproduces the exact placement the old two-kind (tank, then worm)
     * version used, just generalized to any number of kinds.
     */
    private fun buildSpawnQueue(normalCount: Int, specialCounts: List<Pair<EnemyKind, Int>>): MutableList<EnemyKind> {
        val total = normalCount + specialCounts.sumOf { it.second }
        val queue = MutableList(total) { EnemyKind.NORMAL }
        for ((kind, count) in specialCounts) {
            val normalSlots = queue.indices.filter { queue[it] == EnemyKind.NORMAL }
            for (i in 0 until count) {
                if (normalSlots.isEmpty()) break
                val slot = ((i + 1) * normalSlots.size / (count + 1)).coerceIn(0, normalSlots.size - 1)
                queue[normalSlots[slot]] = kind
            }
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
        // to burn it down with weapon DPS before it reaches the castle. Flyer is fragile (a
        // couple of arrow hits drops one) once an archer can actually reach it — the weapon-mix
        // pressure it applies is about targeting, not raw toughness. Shielded is the opposite:
        // its 2.87x multiplier (headless Monte Carlo sim, 1200 trials, landing at 73.7% by wave
        // 30) is deliberately steep — a first pass at ~2.2x (matching Flyer's inverse) barely
        // dented the win rate at all (93%+), because a cheapest-first player's cannon/archer
        // spend is already roughly balanced, so cutting cannons off from half the fight only
        // matters if what's left standing there is genuinely tanky.
        val health = when (kind) {
            EnemyKind.TANK -> baseHealth * 5.5f
            EnemyKind.WORM -> baseHealth * 0.65f
            EnemyKind.BOSS -> baseHealth * BOSS_HEALTH_MULT
            EnemyKind.FLYER -> baseHealth * 0.8f
            EnemyKind.SHIELDED -> baseHealth * 2.87f
            EnemyKind.NORMAL -> baseHealth
        }
        val speed = (when (kind) {
            EnemyKind.TANK -> baseSpeed * 0.58f
            EnemyKind.WORM -> baseSpeed * 2f
            EnemyKind.BOSS -> BOSS_SPEED
            EnemyKind.FLYER -> baseSpeed * 1.5f
            EnemyKind.SHIELDED -> baseSpeed * 0.75f
            EnemyKind.NORMAL -> baseSpeed
        }) * visualScale
        // The boss's contactDamage is never applied through the normal repeated-tick path (see
        // Zombie.update's BOSS branch) — reaching the castle is a single fatal explosion instead,
        // so this value just needs to comfortably exceed any possible remaining health+shield.
        val damage = when (kind) {
            EnemyKind.TANK -> baseDamage * 2.6f
            EnemyKind.WORM -> baseDamage * 1.6f
            EnemyKind.BOSS -> 999999f
            EnemyKind.FLYER -> baseDamage * 1.2f
            EnemyKind.SHIELDED -> baseDamage * 1.5f
            EnemyKind.NORMAL -> baseDamage
        }
        val goldReward = when (kind) {
            EnemyKind.TANK -> baseGold * 2
            EnemyKind.WORM -> (baseGold * 3) / 2
            EnemyKind.BOSS -> BOSS_GOLD
            EnemyKind.FLYER -> (baseGold * 3) / 2
            EnemyKind.SHIELDED -> baseGold * 2
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
