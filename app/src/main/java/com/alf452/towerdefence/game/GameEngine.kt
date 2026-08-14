package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.alf452.towerdefence.ui.Hud
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class GameState { INTERMISSION, PLAYING, GAME_OVER }

/**
 * A jagged (not perfectly round) impact crater: [floorPath] is the ragged crater rim/floor
 * outline, [scorchPath] a larger, looser ring around it standing in for blast-scorched
 * regolith. Both are built once at generation time so drawing them is just two cheap
 * [android.graphics.Canvas.drawPath] calls with no per-frame allocation.
 */
private class Crater(val floorPath: Path, val scorchPath: Path, val rimStrokeWidth: Float)
private class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)

/**
 * A neon blood splatter left where a zombie died, sized off that zombie's radius. The blob
 * offsets/radii are generated once at kill time (not per frame) and just redrawn every frame
 * afterward, same allocation-free pattern as [Crater].
 */
private class BloodSplatter(val x: Float, val y: Float, val blobX: FloatArray, val blobY: FloatArray, val blobRadius: FloatArray)

/**
 * One puff of the boss's green fire trail, dropped periodically behind it as it creeps toward
 * the castle (see [Zombie.consumeTrailPulse]). Unlike [BloodSplatter], which is permanent until
 * the next wave wipes it, these fade out on their own after [snailFlameTtlSec] — a lingering
 * trail of "still burning" ground, not a permanent stain.
 */
private class SnailFlame(val x: Float, val y: Float, var age: Float, val blobX: FloatArray, val blobY: FloatArray, val blobRadius: FloatArray)

/**
 * A tumbling rock drifting left-to-right across the space backdrop, wrapping back to the left
 * edge once it drifts off the right side. [shape] is a jagged local-space outline (centered on
 * the origin) built once at generation time; drawing just translates/rotates the canvas to the
 * asteroid's current position instead of rebuilding the path every frame.
 */
private class Asteroid(
    var x: Float, val y: Float, val speed: Float, val radius: Float,
    val shape: Path, var rotationDeg: Float, val rotationSpeedDeg: Float
)

/**
 * A distant supermassive black hole floating in the sky beyond the moon's top-right, styled
 * after Gargantua from Interstellar. The "ring wraps all the way around the sphere" look comes
 * from a single trick: draw the whole gravitationally-lensed accretion disk as a tilted, squashed
 * ellipse *behind* a plain black circle (the event horizon) — wherever the ellipse pokes out past
 * the circle's edge (top, bottom, and both sides, since [diskRx]/[diskRy] are both larger than
 * [eventHorizonRadius]), it reads as the disk wrapping around the sphere. Geometry and gradients
 * are built once per resize; only rotation and a slow brightness pulse are animated per frame.
 * [gravityRadius] is how far its pull on passing shooting stars reaches — see
 * [ShootingStarField.applyGravityWell].
 */
private class BlackHole(
    val cx: Float, val cy: Float,
    val eventHorizonRadius: Float,
    val diskRx: Float, val diskRy: Float,
    val tiltDeg: Float,
    val glowRadius: Float,
    val gravityRadius: Float,
    val diskShader: Shader,
    val glowShader: Shader
)

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

    // Neon blood splatters left at each kill spot, wiped at the start of every wave (and hard
    // capped in between) so a long run never accumulates enough decals to slow the game down.
    private val bloodSplatters = mutableListOf<BloodSplatter>()
    private val maxBloodSplatters = 160

    // The boss's green fire trail: self-expiring (see snailFlameTtlSec), also hard-capped since
    // it's dropped continuously (every ~0.1s of boss movement) rather than once per kill.
    private val snailFlames = mutableListOf<SnailFlame>()
    private val maxSnailFlames = 220
    private val snailFlameTtlSec = 0.8f

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

    var killCount = 0
        private set

    /**
     * Fired exactly once, the frame the castle falls, with (waveReached, killCount) so
     * callers (e.g. [com.alf452.towerdefence.GameActivity]) can record a high score. Not
     * reset by [restart] so it keeps firing across multiple runs in one session.
     */
    var onGameOver: ((Int, Int) -> Unit)? = null

    // Sound-effect hooks, fired the instant the corresponding gameplay event happens rather
    // than owning any audio playback itself — keeps GameEngine free of Android/Context
    // dependencies, same reasoning as [onGameOver]. Wired to a real player in GameActivity.
    var onCannonFire: (() -> Unit)? = null
    var onBowFire: (() -> Unit)? = null
    var onZombieKilled: (() -> Unit)? = null
    var onCastleHit: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val hud = Hud()

    // Zombies spawn with their center exactly on the arena boundary, so their
    // sprite/health bar straddle the edge; clipping world drawing to this path
    // (rebuilt only on resize, not per frame) keeps the part outside the arena
    // from rendering as stray pixels over the plain backdrop.
    private val arenaClipPath = Path()

    // Moon-surface craters and background starfield, both generated once per
    // resize (not per frame) and just redrawn/re-tinted every frame afterward.
    private var craters: List<Crater> = emptyList()
    private var stars: List<Star> = emptyList()
    private var asteroids: List<Asteroid> = emptyList()
    private var blackHole: BlackHole? = null
    private var worldTime = 0f

    // Same slow-drifting, colored-tail shooting stars as the main menu (shared implementation),
    // ported into the actual game's space backdrop. Pool sized with headroom well above the
    // ~5.9 average concurrent stars implied by lifespan/spawn-interval (offered load = mean
    // lifespan / mean spawn interval ≈ 11.5s / 1.95s), so spawns essentially never get silently
    // dropped for lack of a free slot.
    private val shootingStars = ShootingStarField(poolSize = 14)

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
        castle.updateVisualMetrics(70f * scale, scale)
        recomputeCannonStats()
        recomputeArcherStats()
        arenaClipPath.reset()
        arenaClipPath.addCircle(castle.x, castle.y, arenaRadius(), Path.Direction.CW)
        craters = generateCraters()
        stars = generateStars()
        asteroids = generateAsteroids()
        blackHole = generateBlackHole()
    }

    fun arenaRadius(): Float = minOf(screenW, screenH) * 0.45f
    fun ringRadius(): Float = castle.radius * 1.25f

    private fun generateCraters(): List<Crater> {
        val radius = arenaRadius()
        if (radius <= 0f) return emptyList()
        val rng = Random(1337)
        val list = mutableListOf<Crater>()
        // Placed centers/radii so far, used to reject spots that would overlap an existing
        // crater instead of letting them cluster on top of each other.
        val placedX = mutableListOf<Float>()
        val placedY = mutableListOf<Float>()
        val placedR = mutableListOf<Float>()

        val count = 48
        // The last few craters are biased into a wedge above the castle instead of scattered
        // uniformly, so the top of the map reads as more heavily cratered than the rest.
        val topBiasedCount = 16
        val topArcCenter = (-Math.PI / 2).toFloat()
        val topArcWidth = (Math.PI * 2f / 3f).toFloat()
        val minDist = ringRadius() * 1.6f
        // Arena background (including craters) isn't clipped to the arena circle, so this
        // stays comfortably inside arenaRadius even after the largest crater's jittered edge.
        val maxDist = radius * 0.85f

        repeat(count) { i ->
            val isTopBiased = i >= count - topBiasedCount
            // Skew heavily toward small craters (t^3) so only a handful of the 48 end up
            // large — a more natural, randomized mix instead of evenly-sized dots.
            val t = rng.nextFloat()
            val sizeT = t * t * t
            val craterRadius = radius * (0.018f + sizeT * 0.1f)

            var bestX = castle.x
            var bestY = castle.y
            // Try a bunch of random spots and take the first one that doesn't overlap an
            // already-placed crater; if none work within the budget, fall back to the last
            // attempt so every crater still gets drawn somewhere. More attempts than before
            // since twice as many craters means less free space to find a clean spot in.
            for (attempt in 0 until 70) {
                val angle = if (isTopBiased) {
                    topArcCenter + (rng.nextFloat() - 0.5f) * topArcWidth
                } else {
                    rng.nextFloat() * (2f * Math.PI).toFloat()
                }
                val dist = minDist + rng.nextFloat() * (maxDist - minDist)
                val cx = castle.x + dist * cos(angle)
                val cy = castle.y + dist * sin(angle)
                bestX = cx
                bestY = cy

                var overlaps = false
                for (j in placedX.indices) {
                    val required = (craterRadius + placedR[j]) * 1.35f
                    if (GameMath.distance(cx, cy, placedX[j], placedY[j]) < required) {
                        overlaps = true
                        break
                    }
                }
                if (!overlaps) break
            }
            placedX.add(bestX)
            placedY.add(bestY)
            placedR.add(craterRadius)
            list.add(buildCrater(bestX, bestY, craterRadius, rng))
        }
        return list
    }

    /**
     * Builds a rough, irregular crater instead of a perfect circle: the rim/floor outline
     * and the looser scorched-blast ring around it both walk the same set of angles but jitter
     * their radius independently, so the edge reads as uneven rather than smooth, like ground
     * actually torn up and scorched by an impact — without going so jagged it looks spiky.
     */
    private fun buildCrater(cx: Float, cy: Float, r: Float, rng: Random): Crater {
        val vertexCount = 11 + rng.nextInt(6)
        val floorPath = Path()
        val scorchPath = Path()
        for (i in 0 until vertexCount) {
            val a = (2f * Math.PI * i / vertexCount).toFloat()
            val floorJitter = 0.8f + rng.nextFloat() * 0.4f
            val fx = cx + r * floorJitter * cos(a)
            val fy = cy + r * floorJitter * sin(a)
            if (i == 0) floorPath.moveTo(fx, fy) else floorPath.lineTo(fx, fy)

            val scorchJitter = 1.3f + rng.nextFloat() * 0.4f
            val sx = cx + r * scorchJitter * cos(a)
            val sy = cy + r * scorchJitter * sin(a)
            if (i == 0) scorchPath.moveTo(sx, sy) else scorchPath.lineTo(sx, sy)
        }
        floorPath.close()
        scorchPath.close()
        return Crater(floorPath, scorchPath, r * 0.16f)
    }

    private fun generateStars(): List<Star> {
        if (screenW <= 0f || screenH <= 0f) return emptyList()
        val rng = Random(7331)
        val list = mutableListOf<Star>()
        val radius = arenaRadius()
        var attempts = 0
        while (list.size < 140 && attempts < 1400) {
            attempts++
            val x = rng.nextFloat() * screenW
            val y = rng.nextFloat() * screenH
            if (GameMath.distance(x, y, castle.x, castle.y) <= radius * 1.05f) continue
            val starRadius = GameMath.skewedSmall(rng, 0.6f, 3f) * scale
            val phase = rng.nextFloat() * (2f * Math.PI).toFloat()
            val speed = 1.2f + rng.nextFloat() * 2.4f
            list.add(Star(x, y, starRadius, phase, speed))
        }
        return list
    }

    private fun generateAsteroids(): List<Asteroid> {
        if (screenW <= 0f || screenH <= 0f) return emptyList()
        val rng = Random(4242)
        val list = mutableListOf<Asteroid>()
        repeat(16) {
            val radius = (10f + rng.nextFloat() * 16f) * scale
            val y = rng.nextFloat() * screenH
            val speed = (18f + rng.nextFloat() * 30f) * scale
            val x = rng.nextFloat() * (screenW + radius * 2f) - radius
            val rotationSpeed = (rng.nextFloat() - 0.5f) * 40f
            list.add(Asteroid(x, y, speed, radius, buildRockShape(radius, rng), rng.nextFloat() * 360f, rotationSpeed))
        }
        return list
    }

    /** Jagged local-space rock outline (centered on the origin) reused every frame via canvas transforms. */
    private fun buildRockShape(radius: Float, rng: Random): Path {
        val path = Path()
        val vertexCount = 8 + rng.nextInt(4)
        for (i in 0 until vertexCount) {
            val a = (2f * Math.PI * i / vertexCount).toFloat()
            val jitter = 0.7f + rng.nextFloat() * 0.5f
            val px = radius * jitter * cos(a)
            val py = radius * jitter * sin(a)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    /**
     * Placed beyond the moon's top-right at a fixed angle/distance from the castle (so it lands
     * in the same spot relative to the moon on any device), then clamped to stay on screen for
     * unusually narrow/wide aspect ratios. The on-screen clamp is applied per-axis, which alone
     * could pull the point back toward the castle far enough to end up *inside* the opaque moon
     * disc on some aspect ratios — so afterward, if clamping shortened its distance from the
     * castle below the moon's edge (plus the glow's own radius), it's pushed back out along the
     * same direction instead, guaranteeing it never renders behind the moon even if that means
     * the glow's outer edge goes slightly off-screen.
     */
    private fun generateBlackHole(): BlackHole? {
        if (screenW <= 0f || screenH <= 0f) return null
        val radius = arenaRadius()
        // Sized so the disk+glow together comfortably fit the sky band above/right of the moon
        // on a typical portrait phone without the on-screen clamp needing to fight the
        // stay-outside-the-moon constraint too hard (see push-out logic below).
        val eventHorizonRadius = radius * 0.17f
        val diskRx = eventHorizonRadius * 2.15f
        val diskRy = eventHorizonRadius * 1.35f
        val glowRadius = eventHorizonRadius * 2.2f
        // Generous enough that a shooting star drifting through the general area has a real
        // chance to get caught, but well short of the whole screen so it stays a special moment
        // rather than something that happens to every star.
        val gravityRadius = eventHorizonRadius * 5f

        val angle = Math.toRadians(-55.0).toFloat() // up and to the right
        val minDist = radius + glowRadius + 6f * scale
        var cx = castle.x + minDist * cos(angle)
        var cy = castle.y + minDist * sin(angle)
        cx = GameMath.clamp(cx, glowRadius, screenW - glowRadius)
        cy = GameMath.clamp(cy, glowRadius, screenH - glowRadius)
        val dx = cx - castle.x
        val dy = cy - castle.y
        val dist = hypot(dx, dy)
        if (dist < minDist && dist > 0f) {
            val pushOut = minDist / dist
            cx = castle.x + dx * pushOut
            cy = castle.y + dy * pushOut
        }

        // Transparent well inside the event horizon's edge (that part is always hidden anyway),
        // brightest right where the disk emerges from behind it, cooling from white through
        // orange to red further out.
        val innerFrac = eventHorizonRadius / diskRx
        val diskShader = RadialGradient(
            0f, 0f, diskRx,
            intArrayOf(
                Color.argb(0, 255, 235, 200),
                Color.argb(0, 255, 235, 200),
                Color.argb(255, 255, 250, 235),
                Color.argb(255, 255, 170, 70),
                Color.argb(220, 200, 70, 30),
                Color.argb(0, 120, 30, 20)
            ),
            floatArrayOf(0f, innerFrac * 0.9f, innerFrac, 0.5f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        val glowShader = RadialGradient(
            0f, 0f, glowRadius,
            intArrayOf(Color.argb(70, 255, 200, 140), Color.argb(0, 255, 160, 90)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        return BlackHole(cx, cy, eventHorizonRadius, diskRx, diskRy, 14f, glowRadius, gravityRadius, diskShader, glowShader)
    }

    fun update(dt: Float) {
        worldTime += dt
        shootingStars.update(dt, screenW, screenH, scale)
        // Shooting stars that drift too close get pulled in and swallowed — see
        // ShootingStarField.applyGravityWell. Strength is scaled like every other size/speed
        // constant so the pull reads the same relative to the black hole across devices.
        blackHole?.let { bh ->
            shootingStars.applyGravityWell(bh.cx, bh.cy, bh.gravityRadius, bh.eventHorizonRadius, 3_500_000f * scale, dt)
        }
        for (a in asteroids) {
            a.x += a.speed * dt
            if (a.x - a.radius > screenW) a.x = -a.radius
            a.rotationDeg += a.rotationSpeedDeg * dt
        }
        castle.update(dt)

        // Zombie/projectile simulation (including in-progress death animations
        // and shots already in flight) always keeps running, even after a wave
        // has ended, so nothing freezes mid-animation on the upgrade screen.
        for (z in zombies) {
            z.update(dt, castle) { dmg ->
                // Checked before applying damage so the fatal hit still plays (the castle only
                // becomes "destroyed" after it), but every hit after that from zombies still
                // parked in ATTACKING state doesn't keep echoing the impact sound forever on
                // the Game Over screen.
                val wasAlive = !castle.isDestroyed()
                castle.takeDamage(dmg)
                if (wasAlive) onCastleHit?.invoke()
            }
            if (z.kind == EnemyKind.BOSS) {
                if (z.consumeTrailPulse()) spawnSnailTrail(z.x, z.y)
                if (z.consumeExplosion()) explosions.add(Explosion(z.x, z.y, z.radius * 1.8f, scale))
            }
        }

        for (f in snailFlames) f.age += dt
        snailFlames.removeAll { it.age >= snailFlameTtlSec }

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
                killCount++
                spawnBloodSplatter(z)
                onZombieKilled?.invoke()
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
            onGameOver?.invoke(waveManager.waveNumber, killCount)
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

        // castle.isDestroyed() is checked here too, not just after this function returns: the
        // boss (EnemyKind.BOSS) deals its fatal contact damage and goes straight to DYING in the
        // same update() tick it reaches the castle (see Zombie.update's BOSS branch), so without
        // this guard a boss-wave death would look like a clean wave clear for one frame —
        // awarding the wave-clear bonus, incrementing waveNumber, and healing a castle that's
        // actually already destroyed — before the GAME_OVER check below catches up.
        val waveCleared = waveManager.allSpawned() && zombies.none { it.isAlive() } && !castle.isDestroyed()
        if (waveCleared) {
            // Flat, same reasoning as the per-kill gold value in WaveManager: kill count
            // growth alone is enough to keep income rising, so this doesn't need its own
            // wave-scaling on top of that to reach ~1 affordable upgrade per wave. Nudged from
            // 26 to 24 alongside WaveManager's pacing/health retune (see baseHealth's comment
            // there) to land back on the ~70%-win-rate-at-wave-25 target.
            val bonus = 24
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
        if (kind == ProjectileKind.CANNONBALL) onCannonFire?.invoke() else onBowFire?.invoke()
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
        // Wipe the last wave's blood splatters (and any leftover boss fire trail) so decals
        // never grow unbounded across waves.
        bloodSplatters.clear()
        snailFlames.clear()
    }

    /**
     * Scatters [count] blobs at random angles within [distFactor]*[r] of the origin, sized
     * between [radiusMin]*[r] and ([radiusMin]+[radiusSpread])*[r] — the shared jitter pattern
     * behind both [spawnBloodSplatter]'s splatter and [spawnSnailTrail]'s fire puffs, so a future
     * tweak to how these decals scatter only needs changing in one place.
     */
    private fun scatterBlobs(count: Int, r: Float, distFactor: Float, radiusMin: Float, radiusSpread: Float): Triple<FloatArray, FloatArray, FloatArray> {
        val blobX = FloatArray(count)
        val blobY = FloatArray(count)
        val blobRadius = FloatArray(count)
        for (i in 0 until count) {
            val angle = Random.nextFloat() * (2f * Math.PI).toFloat()
            val dist = Random.nextFloat() * r * distFactor
            blobX[i] = dist * cos(angle)
            blobY[i] = dist * sin(angle)
            blobRadius[i] = r * (radiusMin + Random.nextFloat() * radiusSpread)
        }
        return Triple(blobX, blobY, blobRadius)
    }

    /**
     * Leaves a neon splatter at [zombie]'s death spot, sized off its radius (tanks leave a
     * bigger, denser splash). Blob offsets/radii are generated once here, not per frame; the
     * list itself is capped so even an unusually kill-heavy wave stays bounded.
     */
    private fun spawnBloodSplatter(zombie: Zombie) {
        if (bloodSplatters.size >= maxBloodSplatters) bloodSplatters.removeAt(0)
        val blobCount = when (zombie.kind) {
            EnemyKind.TANK -> 6
            EnemyKind.WORM -> 4
            EnemyKind.BOSS -> 10
            EnemyKind.NORMAL -> 3
        }
        val (blobX, blobY, blobRadius) = scatterBlobs(blobCount, zombie.radius, distFactor = 0.85f, radiusMin = 0.14f, radiusSpread = 0.22f)
        bloodSplatters.add(BloodSplatter(zombie.x, zombie.y, blobX, blobY, blobRadius))
    }

    /**
     * Drops one puff of the boss's green fire trail at [x],[y] — called once per
     * [Zombie.consumeTrailPulse] pulse, i.e. roughly every 0.1s of boss movement, so the trail
     * reads as continuous rather than a string of separate dots.
     */
    private fun spawnSnailTrail(x: Float, y: Float) {
        if (snailFlames.size >= maxSnailFlames) snailFlames.removeAt(0)
        val (blobX, blobY, blobRadius) = scatterBlobs(3, 20f * scale, distFactor = 0.6f, radiusMin = 0.35f, radiusSpread = 0.35f)
        snailFlames.add(SnailFlame(x, y, 0f, blobX, blobY, blobRadius))
    }

    fun restart() {
        zombies.clear()
        projectiles.clear()
        explosions.clear()
        bloodSplatters.clear()
        snailFlames.clear()
        gold = 0
        killCount = 0
        cannonLevel = 1
        archerLevel = 0
        explosiveLevel = 0
        slowLevel = 0
        bleedLevel = 0
        // recomputeCannonStats()/recomputeArcherStats() below already derive each slot's
        // `unlocked` from the level fields just reset above, so no separate reset is needed.
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

        canvas.save()
        canvas.clipPath(arenaClipPath)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 40, 255, 60)
        for (b in bloodSplatters) {
            for (i in b.blobX.indices) {
                canvas.drawCircle(b.x + b.blobX[i], b.y + b.blobY[i], b.blobRadius[i], paint)
            }
        }

        // Boss fire trail: fades from a bright yellow-green flame core out to transparent as it
        // burns out (see snailFlameTtlSec), instead of the flat/permanent blood-splatter tint.
        paint.style = Paint.Style.FILL
        for (f in snailFlames) {
            val t = GameMath.clamp(f.age / snailFlameTtlSec, 0f, 1f)
            val alpha = ((1f - t) * 210).toInt()
            val shrink = 1f - t * 0.4f
            for (i in f.blobX.indices) {
                paint.color = Color.argb(alpha, (60 + t * 130).toInt(), 230, (50 - t * 30).toInt().coerceAtLeast(10))
                canvas.drawCircle(f.x + f.blobX[i], f.y + f.blobY[i], f.blobRadius[i] * shrink, paint)
            }
        }

        for (z in zombies) z.draw(canvas, paint)
        for (p in projectiles) p.draw(canvas, paint)

        val ring = ringRadius()
        castle.draw(canvas, paint)
        for (s in cannonSlots) s.draw(canvas, paint, castle, ring)
        for (s in archerSlots) s.draw(canvas, paint, castle, ring)
        for (e in explosions) e.draw(canvas, paint)

        canvas.restore()

        hud.draw(canvas, this)
    }

    /** See [BlackHole]'s doc for how the disk-behind-a-plain-circle trick produces the look. */
    private fun drawBlackHole(canvas: Canvas) {
        val bh = blackHole ?: return
        val pulse = 0.8f + 0.2f * sin(worldTime * 0.6f)

        canvas.save()
        canvas.translate(bh.cx, bh.cy)

        // Soft ambient glow, breathing slowly for an epic, larger-than-life presence.
        paint.shader = bh.glowShader
        paint.style = Paint.Style.FILL
        paint.alpha = (255 * pulse).toInt()
        canvas.drawCircle(0f, 0f, bh.glowRadius, paint)

        // Tilted, slowly-rotating accretion disk, drawn as a full circle in squashed local space
        // so it renders as an ellipse once unsquashed.
        canvas.save()
        canvas.rotate(bh.tiltDeg + worldTime * 4f)
        canvas.scale(1f, bh.diskRy / bh.diskRx)
        paint.shader = bh.diskShader
        paint.alpha = (255 * (0.85f + 0.15f * pulse)).toInt()
        canvas.drawCircle(0f, 0f, bh.diskRx, paint)
        canvas.restore()

        // Event horizon: solid black, always a perfect circle regardless of the disk's
        // rotation/tilt above, drawn on top so it covers the disk's middle and leaves only the
        // parts poking out past its edge visible — that's what makes the ring wrap around it.
        paint.shader = null
        paint.alpha = 255
        paint.color = Color.rgb(2, 2, 4)
        canvas.drawCircle(0f, 0f, bh.eventHorizonRadius, paint)

        // Photon ring: a thin bright rim right at the horizon's edge.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * scale
        paint.color = Color.argb((200 * pulse).toInt(), 255, 245, 220)
        canvas.drawCircle(0f, 0f, bh.eventHorizonRadius, paint)
        paint.style = Paint.Style.FILL

        canvas.restore()
    }

    private fun drawArenaBackground(canvas: Canvas) {
        // Night sky, drawn first so the starfield and (later) the moon-surface
        // arena disc layer cleanly on top of it every frame.
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(6, 6, 14)
        canvas.drawRect(0f, 0f, screenW, screenH, paint)

        for (s in stars) {
            val twinkle = 0.5f + 0.5f * sin(worldTime * s.speed + s.phase)
            paint.color = Color.argb((90 + twinkle * 165).toInt(), 255, 255, 255)
            canvas.drawCircle(s.x, s.y, s.radius, paint)
        }

        // Drawn before the (nearer) asteroids so they can drift across in front of it, keeping
        // the black hole reading as a genuinely distant background object.
        drawBlackHole(canvas)

        for (a in asteroids) {
            canvas.save()
            canvas.translate(a.x, a.y)
            canvas.rotate(a.rotationDeg)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(92, 86, 80)
            canvas.drawPath(a.shape, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * scale
            paint.color = Color.rgb(48, 44, 40)
            canvas.drawPath(a.shape, paint)
            canvas.restore()
        }

        shootingStars.draw(canvas, paint, scale)

        // Moon-surface arena floor, tinted purple instead of plain gray. Style is set
        // explicitly since the asteroid/shooting-star loops above can leave it as STROKE.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(122, 100, 148)
        canvas.drawCircle(castle.x, castle.y, arenaRadius(), paint)

        for (c in craters) {
            // Scorched blast halo: a loose, irregular dark ring bleeding out past the crater
            // edge, like burnt/torn-up regolith thrown out by the impact.
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(80, 46, 30, 44)
            canvas.drawPath(c.scorchPath, paint)

            paint.color = Color.rgb(82, 64, 102)
            canvas.drawPath(c.floorPath, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = c.rimStrokeWidth
            paint.color = Color.rgb(52, 40, 68)
            canvas.drawPath(c.floorPath, paint)
            paint.style = Paint.Style.FILL
        }

        paint.color = Color.rgb(70, 54, 90)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f * scale
        canvas.drawCircle(castle.x, castle.y, arenaRadius(), paint)
    }

    fun onTouch(x: Float, y: Float) {
        hud.handleTouch(x, y, this)
    }
}
