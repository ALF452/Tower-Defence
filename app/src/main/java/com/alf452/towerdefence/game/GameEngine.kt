package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.alf452.towerdefence.ui.Hud
import kotlin.math.pow
import kotlin.math.roundToInt

enum class GameState { INTERMISSION, PLAYING, GAME_OVER }

/**
 * Owns all game state and drives the simulation. [GameView] calls [update] and
 * [draw] once per frame and forwards touch events to [onTouch].
 */
class GameEngine {

    var screenW = 0f
    var screenH = 0f

    /**
     * Resolution-relative UI/world scale factor (1.0 at a 1080px-wide reference
     * screen). Everything drawn — HUD text/bars, the castle, zombies, weapons —
     * is sized off this instead of bare pixel constants, so the game reads the
     * same on a 720px phone as a 1440px one instead of shrinking/overlapping.
     */
    var scale = 1f
        private set

    val castle = Castle(0f, 0f)
    val zombies = mutableListOf<Zombie>()
    val projectiles = mutableListOf<Projectile>()
    private val explosions = mutableListOf<Explosion>()

    // Cannon slots unlock in this order: N, E, S, W.
    val cannonSlots = listOf(
        WeaponSlot(WeaponType.CANNON, -90f),
        WeaponSlot(WeaponType.CANNON, 0f),
        WeaponSlot(WeaponType.CANNON, 90f),
        WeaponSlot(WeaponType.CANNON, 180f)
    )
    // Archer slots unlock in this order: NE, SW, NW, SE.
    val archerSlots = listOf(
        WeaponSlot(WeaponType.ARCHER, -45f),
        WeaponSlot(WeaponType.ARCHER, 135f),
        WeaponSlot(WeaponType.ARCHER, -135f),
        WeaponSlot(WeaponType.ARCHER, 45f)
    )

    var cannonLevel = 1
        private set
    var archerLevel = 0
        private set

    // Specializations that unlock once SPECIAL_UNLOCK_WAVE has been cleared. Slow and bleed
    // are mutually exclusive — choosing one (buying its first level) locks out the other for
    // the rest of the run, so the two costs gate on each other.
    var explosiveLevel = 0
        private set
    var slowLevel = 0
        private set
    var bleedLevel = 0
        private set

    var gold = 0
        private set

    var waveManager = WaveManager()
        private set
    var state = GameState.INTERMISSION
        private set

    var lastWaveGoldEarned = 0
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val hud = Hud()

    private val maxLevel = 20
    private val specialMaxLevel = 5
    private val specialUnlockWave = 10

    init {
        recomputeCannonStats()
        recomputeArcherStats()
    }

    fun onSurfaceSize(w: Float, h: Float) {
        screenW = w
        screenH = h
        castle.x = w / 2f
        castle.y = h / 2f
        scale = GameMath.clamp(w / 1080f, 0.55f, 1.7f)
        castle.radius = 70f * scale
        castle.visualScale = scale
        recomputeCannonStats()
        recomputeArcherStats()
    }

    fun arenaRadius(): Float = minOf(screenW, screenH) * 0.45f
    fun ringRadius(): Float = castle.radius * 1.25f

    fun update(dt: Float) {
        castle.update(dt)

        // Zombie/projectile simulation (including in-progress death animations
        // and shots already in flight) always keeps running, even after a wave
        // has ended, so nothing freezes mid-animation on the upgrade screen.
        for (z in zombies) {
            z.update(dt, castle) { dmg -> castle.takeDamage(dmg) }
        }

        val projIter = projectiles.iterator()
        while (projIter.hasNext()) {
            val p = projIter.next()
            p.update(dt)
            if (p.justImpacted) {
                applyImpact(p)
            }
            if (!p.alive) projIter.remove()
        }

        for (e in explosions) e.update(dt)
        explosions.removeAll { !it.alive }

        // Award gold the frame a zombie's health hits zero (while still playing its
        // death animation), before it gets removed from the list below.
        var goldFromKills = 0
        for (z in zombies) {
            if (z.health <= 0f && !z.rewardClaimed) {
                goldFromKills += z.goldReward
                z.rewardClaimed = true
            }
        }
        gold += goldFromKills

        val zIter = zombies.iterator()
        while (zIter.hasNext()) {
            if (zIter.next().isRemovable()) {
                zIter.remove()
            }
        }

        if (state == GameState.PLAYING) {
            updateWaveLogic(dt)
        }

        if (castle.isDestroyed() && state != GameState.GAME_OVER) {
            state = GameState.GAME_OVER
        }
    }

    /** Spawning, weapon targeting/firing and wave-clear detection only run while a wave is active. */
    private fun updateWaveLogic(dt: Float) {
        waveManager.update(dt, arenaRadius(), castle.x, castle.y, scale)?.let { zombies.add(it) }

        val ring = ringRadius()
        for (slot in cannonSlots) {
            slot.update(dt, castle, ring, zombies) { s, target, fx, fy -> fire(s, target, fx, fy) }
        }
        for (slot in archerSlots) {
            slot.update(dt, castle, ring, zombies) { s, target, fx, fy -> fire(s, target, fx, fy) }
        }

        val waveCleared = waveManager.allSpawned() && zombies.none { it.isAlive() }
        if (waveCleared) {
            val bonus = 20 + waveManager.waveNumber * 5
            gold += bonus
            lastWaveGoldEarned = bonus
            waveManager.endWave()
            castle.healBetweenWaves()
            state = GameState.INTERMISSION
        }
    }

    private fun fire(slot: WeaponSlot, target: Zombie, fromX: Float, fromY: Float) {
        val kind = if (slot.type == WeaponType.CANNON) ProjectileKind.CANNONBALL else ProjectileKind.ARROW
        val baseSpeed = if (slot.type == WeaponType.CANNON) 340f else 620f

        var effect = ArrowEffect.NONE
        var effectValue = 0f
        var effectDuration = 0f
        if (kind == ProjectileKind.ARROW) {
            if (slowLevel > 0) {
                effect = ArrowEffect.SLOW
                effectValue = maxOf(0.2f, 0.65f - slowLevel * 0.09f) // fraction of speed retained
                effectDuration = 2f + slowLevel * 0.2f
            } else if (bleedLevel > 0) {
                effect = ArrowEffect.BLEED
                effectValue = 6f + bleedLevel * 4f // damage per second
                effectDuration = 3f + bleedLevel * 0.3f
            }
        }

        projectiles.add(
            Projectile(
                fromX, fromY, target.x, target.y, kind, slot.damage, slot.splashRadius,
                baseSpeed * scale, scale, effect, effectValue, effectDuration
            )
        )
    }

    private fun applyImpact(p: Projectile) {
        if (p.kind == ProjectileKind.CANNONBALL) {
            explosions.add(Explosion(p.impactX, p.impactY, p.splashRadius, scale))
            for (z in zombies) {
                if (z.isAlive() && GameMath.distance(z.x, z.y, p.impactX, p.impactY) <= p.splashRadius) {
                    z.takeDamage(p.damage)
                }
            }
        } else {
            var closest: Zombie? = null
            var bestDist = 26f * scale
            for (z in zombies) {
                if (!z.isAlive()) continue
                val d = GameMath.distance(z.x, z.y, p.impactX, p.impactY)
                if (d <= bestDist) {
                    bestDist = d
                    closest = z
                }
            }
            closest?.let {
                it.takeDamage(p.damage)
                when (p.effect) {
                    ArrowEffect.SLOW -> it.applySlow(p.effectValue, p.effectDuration)
                    ArrowEffect.BLEED -> it.applyBleed(p.effectValue, p.effectDuration)
                    ArrowEffect.NONE -> {}
                }
            }
        }
    }

    fun startNextWave() {
        if (state != GameState.INTERMISSION) return
        waveManager.startWave(waveManager.waveNumber)
        state = GameState.PLAYING
    }

    fun restart() {
        zombies.clear()
        projectiles.clear()
        explosions.clear()
        gold = 0
        cannonLevel = 1
        archerLevel = 0
        explosiveLevel = 0
        slowLevel = 0
        bleedLevel = 0
        for (s in cannonSlots) s.unlocked = false
        for (s in archerSlots) s.unlocked = false
        recomputeCannonStats()
        recomputeArcherStats()
        castle.resetForNewGame()
        waveManager = WaveManager()
        state = GameState.INTERMISSION
    }

    fun wallUpgradeCost(): Int = castle.wallUpgradeCost()
    fun cannonUpgradeCost(): Int? {
        if (cannonLevel >= maxLevel) return null
        return (50 * 1.3.pow((cannonLevel - 1).toDouble())).roundToInt()
    }
    fun archerUpgradeCost(): Int? {
        if (archerLevel >= maxLevel) return null
        return (35 * 1.3.pow(archerLevel.toDouble())).roundToInt()
    }

    fun purchaseWallUpgrade(): Boolean {
        val cost = wallUpgradeCost()
        if (gold < cost) return false
        gold -= cost
        castle.applyWallUpgrade()
        return true
    }

    fun purchaseCannonUpgrade(): Boolean {
        val cost = cannonUpgradeCost() ?: return false
        if (gold < cost) return false
        gold -= cost
        cannonLevel++
        recomputeCannonStats()
        return true
    }

    fun purchaseArcherUpgrade(): Boolean {
        val cost = archerUpgradeCost() ?: return false
        if (gold < cost) return false
        gold -= cost
        archerLevel++
        recomputeArcherStats()
        return true
    }

    /** Explosive Rounds and the arrow specializations only appear once this wave has been cleared. */
    fun specializationsUnlocked(): Boolean = waveManager.waveNumber > specialUnlockWave

    fun explosiveUpgradeCost(): Int? {
        if (!specializationsUnlocked() || explosiveLevel >= specialMaxLevel) return null
        return (150 * 1.3.pow(explosiveLevel.toDouble())).roundToInt()
    }

    fun slowUpgradeCost(): Int? {
        if (!specializationsUnlocked() || bleedLevel > 0 || slowLevel >= specialMaxLevel) return null
        return (120 * 1.3.pow(slowLevel.toDouble())).roundToInt()
    }

    fun bleedUpgradeCost(): Int? {
        if (!specializationsUnlocked() || slowLevel > 0 || bleedLevel >= specialMaxLevel) return null
        return (120 * 1.3.pow(bleedLevel.toDouble())).roundToInt()
    }

    fun purchaseExplosiveUpgrade(): Boolean {
        val cost = explosiveUpgradeCost() ?: return false
        if (gold < cost) return false
        gold -= cost
        explosiveLevel++
        recomputeCannonStats()
        return true
    }

    fun purchaseSlowUpgrade(): Boolean {
        val cost = slowUpgradeCost() ?: return false
        if (gold < cost) return false
        gold -= cost
        slowLevel++
        return true
    }

    fun purchaseBleedUpgrade(): Boolean {
        val cost = bleedUpgradeCost() ?: return false
        if (gold < cost) return false
        gold -= cost
        bleedLevel++
        return true
    }

    private fun recomputeCannonStats() {
        val thresholds = intArrayOf(1, 2, 4, 6)
        val explosiveMultiplier = 1f + explosiveLevel * 0.2f
        for ((i, slot) in cannonSlots.withIndex()) {
            slot.unlocked = cannonLevel >= thresholds[i]
            slot.damage = 18f + cannonLevel * 6f
            slot.fireIntervalSec = maxOf(1.15f, 1.4f - cannonLevel * 0.035f)
            slot.range = (220f + cannonLevel * 15f) * scale
            slot.splashRadius = (40f + cannonLevel * 4f) * explosiveMultiplier * scale
            slot.visualScale = scale
        }
    }

    private fun recomputeArcherStats() {
        val thresholds = intArrayOf(1, 2, 4, 6)
        for ((i, slot) in archerSlots.withIndex()) {
            slot.unlocked = archerLevel >= thresholds[i]
            slot.damage = 8f + archerLevel * 4f
            slot.fireIntervalSec = maxOf(0.6f, 0.9f - archerLevel * 0.03f)
            slot.range = (255f + archerLevel * 17f) * scale
            slot.visualScale = scale
        }
    }

    fun draw(canvas: Canvas) {
        drawArenaBackground(canvas)

        for (z in zombies) z.draw(canvas, paint)
        for (p in projectiles) p.draw(canvas, paint)

        val ring = ringRadius()
        castle.draw(canvas, paint)
        for (s in cannonSlots) s.draw(canvas, paint, castle, ring)
        for (s in archerSlots) s.draw(canvas, paint, castle, ring)
        for (e in explosions) e.draw(canvas, paint)

        hud.draw(canvas, this)
    }

    private fun drawArenaBackground(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(58, 46, 74)
        canvas.drawCircle(castle.x, castle.y, arenaRadius(), paint)
        paint.color = Color.rgb(45, 36, 60)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f * scale
        canvas.drawCircle(castle.x, castle.y, arenaRadius(), paint)
    }

    fun onTouch(x: Float, y: Float) {
        hud.handleTouch(x, y, this)
    }
}
