package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

enum class ZombieState { WALKING, ATTACKING, DYING, DEAD }
enum class EnemyKind { NORMAL, TANK, WORM, BOSS, FLYER, SHIELDED }

/**
 * Which boss fights a given [EnemyKind.BOSS] instance is — see [WaveManager.bossVariantForWave]
 * for the cycle order and [Zombie.update]/[Zombie.draw]'s BOSS branches for how each one differs:
 * - [GALAXY_SNAIL]: a flat, steady crawl with a green fire trail (see [drawSnail]).
 * - [METEOR_WYRM]: alternates a slow creep with a fast dash on a fixed cycle, so its arrival is
 *   unpredictable rather than a flat crawl (see [drawMeteorWyrm]) — its `speed` constructor value
 *   is still the *average* pace across one full cycle, so the headless balance sim (which has no
 *   notion of the burst timing) stays a valid estimate of total encounter difficulty even though
 *   the real movement is bursty.
 * - [OBELISK_WARDEN]: much slower and tankier, but fires a damaging beam at the castle from range
 *   once close enough — well before it's near enough to explode on contact — so the fight has a
 *   second, ongoing pressure source instead of being purely "kill it before it arrives."
 *
 * All three still share the same fatal-contact-explosion rule (see [update]'s WALKING branch).
 */
enum class BossVariant { GALAXY_SNAIL, METEOR_WYRM, OBELISK_WARDEN }

/**
 * A hostile creature shambling (or, for [EnemyKind.WORM], slithering) in from the wave edge
 * toward the castle. [EnemyKind.TANK] (from wave 13 on) is a larger, tougher, slower variant of
 * the same humanoid rig; [EnemyKind.WORM] (from wave 7 on) is a fast burnt-orange space worm with
 * a completely different segmented body and its own brief "digging out of the ground" entrance;
 * [EnemyKind.BOSS] (alone on wave 19, then alone again every 6 waves after) is one of three
 * giant solo bosses — see [BossVariant] — that crawl in with a huge health pool and, unlike every
 * other kind, don't chip away at the castle on contact: reaching it is an instant, fatal explosion
 * (see the WALKING branch of [update]).
 *
 * [EnemyKind.FLYER] (from wave 10 on) and [EnemyKind.SHIELDED] (from wave 16 on) are this game's
 * weapon-mix enemies: see [canBeTargetedBy] — a Flyer can only be *targeted* (locked onto) by
 * archers, and a Shielded zombie only by cannons, so a player who only ever invests in one weapon
 * category eventually hits a wall neither existing enemy kind created. A cannon's splash can still
 * incidentally catch a Flyer standing near its blast (that's about the explosion's physical
 * radius, not target *lock*), but Shielded zombies are immune to arrow damage outright, including
 * stray impacts — a shield reads as blocking arrows completely, not just being hard to aim at.
 *
 * All five special kinds share only the state machine/combat plumbing below with the humanoid
 * rig. Humanoid limbs are filled, rotated rounded-rect "capsules" (not stroked lines) driven by a
 * walk-cycle phase, so it reads as a solid little creature instead of a stick figure, with no
 * sprite sheet needed for the animation.
 */
class Zombie(
    var x: Float,
    var y: Float,
    val maxHealth: Float,
    val speed: Float,
    val contactDamage: Float,
    val goldReward: Int,
    private val visualScale: Float = 1f,
    val kind: EnemyKind = EnemyKind.NORMAL,
    val bossVariant: BossVariant = BossVariant.GALAXY_SNAIL
) {
    val isTank: Boolean get() = kind == EnemyKind.TANK

    /** See the class doc's FLYER/SHIELDED paragraph — used by [WeaponSlot] target acquisition. */
    fun canBeTargetedBy(weaponType: WeaponType): Boolean = when (kind) {
        EnemyKind.FLYER -> weaponType == WeaponType.ARCHER
        EnemyKind.SHIELDED -> weaponType == WeaponType.CANNON
        else -> true
    }

    var health = maxHealth
    var rewardClaimed = false
    var state = ZombieState.WALKING
        private set

    var facingAngle = 0f
    private var walkPhase = 0f
    private var attackCooldown = 0f
    private val attackIntervalSec = 1f
    var deathTimer = 0f
        private set
    private val deathDurationSec = 0.55f

    // Archer specialization effects (applied by GameEngine on arrow impact).
    private var slowTimer = 0f
    private var slowFactor = 1f
    private var bleedTimer = 0f
    private var bleedDps = 0f

    // Worms spend their first emergeDurationSec bursting up out of the ground (see update()'s
    // WALKING branch and drawEmergeDust below) instead of moving; zero for every other kind so
    // the gate they're checked against is trivially already satisfied.
    private var emergeTimer = 0f
    private val emergeDurationSec = if (kind == EnemyKind.WORM) 0.4f else 0f
    private val dustOffsets: List<FloatArray> = if (kind == EnemyKind.WORM) {
        (0 until 6).map { i ->
            val a = (2.0 * Math.PI * i / 6).toFloat()
            floatArrayOf(cos(a), sin(a))
        }
    } else emptyList()

    val radius = when (kind) {
        EnemyKind.TANK -> 22f * 1.9f
        EnemyKind.WORM -> 22f * 1.15f
        EnemyKind.BOSS -> 22f * 3.2f
        EnemyKind.FLYER -> 22f * 0.85f
        EnemyKind.SHIELDED -> 22f * 1.3f
        EnemyKind.NORMAL -> 22f
    } * visualScale

    // These colors/gradients depend only on radius/kind, both fixed for the lifetime of the
    // instance, so they're computed once here instead of being reallocated every draw() call
    // (a zombie can be drawn 60x/sec). Only used by the humanoid rig (NORMAL/TANK) — WORM draws
    // an entirely different segmented body via drawWorm() below.
    private val bodyColor = if (isTank) Color.rgb(90, 78, 70) else Color.rgb(58, 104, 58)
    private val bodyColorLight = if (isTank) Color.rgb(120, 104, 92) else Color.rgb(96, 156, 90)
    private val limbColor = if (isTank) Color.rgb(78, 66, 58) else Color.rgb(58, 104, 58)
    private val armColor = if (isTank) Color.rgb(86, 74, 64) else Color.rgb(66, 116, 66)
    private val headLight = if (isTank) Color.rgb(150, 90, 80) else Color.rgb(120, 172, 112)
    private val headDark = if (isTank) Color.rgb(100, 56, 50) else Color.rgb(80, 132, 78)
    private val outline = if (isTank) Color.rgb(30, 22, 18) else Color.rgb(24, 46, 24)
    private val headCenterY = -radius * 0.62f
    private val headR = radius * 0.34f
    // Lazy (along with the gradients below) since WORM instances never call drawHumanoid() and so
    // never need this RectF/these shaders — avoids allocating them per worm spawned from wave 10 on.
    private val torsoRect by lazy(LazyThreadSafetyMode.NONE) {
        RectF(-radius * 0.42f, -radius * 0.42f, radius * 0.42f, radius * 0.28f)
    }
    private val torsoGradient by lazy(LazyThreadSafetyMode.NONE) {
        LinearGradient(0f, torsoRect.top, 0f, torsoRect.bottom, bodyColorLight, bodyColor, Shader.TileMode.CLAMP)
    }
    private val headGradient by lazy(LazyThreadSafetyMode.NONE) {
        RadialGradient(-headR * 0.3f, headCenterY - headR * 0.3f, headR * 1.4f, headLight, headDark, Shader.TileMode.CLAMP)
    }

    // Worm body coloring: burnt orange, darkening from head to tail.
    private val wormHeadColor = Color.rgb(215, 120, 45)
    private val wormBodyColor = Color.rgb(170, 85, 25)
    private val wormTailColor = Color.rgb(120, 55, 15)
    private val wormOutline = Color.rgb(65, 30, 10)

    // Shielded-only: a slow pulsing phase for the energy-shield bubble's brightness, driven by
    // walkPhase like everything else animated on this rig rather than its own clock.
    private val isShielded = kind == EnemyKind.SHIELDED

    // Flyer-only: hovers with a vertical bob instead of walking, so it needs its own local
    // "altitude" offset applied on top of x/y (which stay at ground level for distance/targeting
    // math) purely for the draw pass. bobPhase is offset per-instance so a group of flyers
    // doesn't all bob in lockstep.
    private val bobPhaseOffset = if (kind == EnemyKind.FLYER) Random(System.identityHashCode(this)).nextFloat() * (2f * Math.PI).toFloat() else 0f

    // Galaxy Snail-only. galaxyPhase drives both the shell's slow spiral rotation and its stars'
    // twinkle, accumulated locally in update() since Zombie has no access to GameEngine's shared
    // worldTime. galaxyStars is a fixed set of star positions/phases within the shell (fraction
    // of shell radius, so it scales with radius/visualScale), generated once per instance instead
    // of per frame — same allocation-free reasoning as dustOffsets above. Lazy since only this
    // variant ever draws a shell.
    private var galaxyPhase = 0f
    private val galaxyStars: List<FloatArray> by lazy(LazyThreadSafetyMode.NONE) {
        val rng = Random(System.identityHashCode(this))
        (0 until 16).map {
            val a = rng.nextFloat() * (2.0 * Math.PI).toFloat()
            val dist = 0.15f + rng.nextFloat() * 0.78f
            floatArrayOf(cos(a) * dist, sin(a) * dist, 0.03f + rng.nextFloat() * 0.05f, rng.nextFloat() * (2.0 * Math.PI).toFloat(), 2f + rng.nextFloat() * 3f)
        }
    }

    // Meteor Wyrm-only — see BossVariant's doc for the averaging rationale. dashPhase cycles
    // [0, dashCycleSec); the first dashCreepFraction of the cycle is the slow creep, the rest is
    // the fast dash. The two multipliers are chosen so their time-weighted average is exactly
    // 1.0 — dashCreepFraction * dashCreepMultiplier + (1 - dashCreepFraction) * dashBurstMultiplier
    // == (2/3 * 0.25) + (1/3 * 2.5) == 1.0 — so `speed` really is this boss's average pace, not
    // just an approximation of it; the balance sim's flat-speed model depends on that being exact.
    private var dashPhase = 0f
    private val dashCycleSec = 3f
    private val dashCreepFraction = 2f / 3f
    private val dashCreepMultiplier = 0.25f
    private val dashBurstMultiplier = 2.5f

    // Obelisk Warden-only: fires a slow, damaging beam at the castle once within beamRangeFactor
    // * castle.radius, on its own cooldown, independent of (and well before) the contact-explosion
    // stopDistance — see the WALKING branch of update(). beamFired pulses true for one frame per
    // shot, consumed the same way as trailPulse/exploded below, so GameEngine can spawn a beam
    // visual exactly once per shot without its own per-zombie firing timer.
    private var beamCooldown = 0f
    private val beamIntervalSec = 4f
    private val beamDamage = 20f
    private val beamRangeFactor = 3.5f
    private var beamFired = false

    // Boss-only: pulses true for exactly one frame at a time as it creeps toward the castle so
    // GameEngine can drop a trail decal (colored per [bossVariant]) at its current position,
    // without GameEngine needing its own per-zombie movement timer. Consumed (and reset) via
    // consumeTrailPulse().
    private var trailPulse = false
    private var trailTimer = 0f
    private val trailIntervalSec = 0.1f

    // Boss-only: set the instant it reaches the castle and detonates (see the WALKING branch of
    // update()) so GameEngine can spawn a big explosion visual exactly once — consumed the same
    // way as trailPulse, not read directly, so a caller can't accidentally react to it twice.
    private var exploded = false

    fun consumeTrailPulse(): Boolean {
        val p = trailPulse
        trailPulse = false
        return p
    }

    fun consumeExplosion(): Boolean {
        val e = exploded
        exploded = false
        return e
    }

    fun consumeBeamFire(): Boolean {
        val b = beamFired
        beamFired = false
        return b
    }

    fun applySlow(factor: Float, durationSec: Float) {
        slowFactor = min(slowFactor, factor)
        slowTimer = max(slowTimer, durationSec)
    }

    fun applyBleed(dps: Float, durationSec: Float) {
        bleedDps = max(bleedDps, dps)
        bleedTimer = max(bleedTimer, durationSec)
    }

    fun update(dt: Float, castle: Castle, onDamageCastle: (Float) -> Unit) {
        if (isAlive()) {
            if (slowTimer > 0f) {
                slowTimer -= dt
                if (slowTimer <= 0f) {
                    slowTimer = 0f
                    slowFactor = 1f
                }
            }
            if (bleedTimer > 0f) {
                bleedTimer -= dt
                takeDamage(bleedDps * dt)
                if (bleedTimer <= 0f) {
                    bleedTimer = 0f
                    bleedDps = 0f
                }
            }
        }

        if (kind == EnemyKind.BOSS) galaxyPhase += dt

        when (state) {
            ZombieState.WALKING -> {
                facingAngle = GameMath.angleTo(x, y, castle.x, castle.y)
                if (emergeTimer < emergeDurationSec) {
                    // Bursting up out of the ground: doesn't move yet, but still wiggles in
                    // place and faces the castle so the reveal reads as alive from frame one.
                    emergeTimer += dt
                    walkPhase += dt * 6f
                } else {
                    val d = GameMath.distance(x, y, castle.x, castle.y)
                    val stopDistance = castle.radius * 1.55f + radius
                    if (d <= stopDistance) {
                        if (kind == EnemyKind.BOSS) {
                            // Unlike every other kind, the boss doesn't settle into a repeated
                            // attack-tick loop: reaching the castle is a single fatal explosion,
                            // so it deals its (deliberately huge) contact damage exactly once and
                            // goes straight to its death animation. rewardClaimed is pre-set so
                            // GameEngine's kill-gold/kill-count bookkeeping — which otherwise
                            // triggers off health hitting zero — doesn't credit the player with a
                            // "kill" for the boss that just destroyed their castle.
                            onDamageCastle(contactDamage)
                            exploded = true
                            rewardClaimed = true
                            health = 0f
                            state = ZombieState.DYING
                            deathTimer = 0f
                        } else {
                            state = ZombieState.ATTACKING
                            attackCooldown = attackIntervalSec * 0.5f
                        }
                    } else {
                        var effectiveSpeed = speed * slowFactor
                        if (kind == EnemyKind.BOSS) {
                            when (bossVariant) {
                                BossVariant.METEOR_WYRM -> {
                                    dashPhase = (dashPhase + dt) % dashCycleSec
                                    val creepDurationSec = dashCycleSec * dashCreepFraction
                                    effectiveSpeed *= if (dashPhase < creepDurationSec) dashCreepMultiplier else dashBurstMultiplier
                                }
                                BossVariant.OBELISK_WARDEN -> {
                                    // Guarded the same way the contact-explosion path already is
                                    // (see the isDead check in Castle.takeDamage) — without this,
                                    // a Warden already close enough to be in beam range keeps
                                    // firing (and GameEngine keeps spawning BeamFlash visuals)
                                    // every beamIntervalSec forever after the castle has already
                                    // fallen, since this branch has no other reason to stop.
                                    if (!castle.isDestroyed() && d <= castle.radius * beamRangeFactor) {
                                        beamCooldown -= dt
                                        if (beamCooldown <= 0f) {
                                            beamCooldown = beamIntervalSec
                                            onDamageCastle(beamDamage)
                                            beamFired = true
                                        }
                                    }
                                }
                                BossVariant.GALAXY_SNAIL -> {}
                            }
                        }
                        x += cos(facingAngle) * effectiveSpeed * dt
                        y += sin(facingAngle) * effectiveSpeed * dt
                        walkPhase += dt * (effectiveSpeed / (18f * visualScale))
                        if (kind == EnemyKind.BOSS) {
                            trailTimer += dt
                            if (trailTimer >= trailIntervalSec) {
                                trailTimer = 0f
                                trailPulse = true
                            }
                        }
                    }
                }
            }
            ZombieState.ATTACKING -> {
                facingAngle = GameMath.angleTo(x, y, castle.x, castle.y)
                attackCooldown -= dt
                walkPhase += dt * 4f
                if (attackCooldown <= 0f) {
                    attackCooldown = attackIntervalSec
                    onDamageCastle(contactDamage)
                }
            }
            ZombieState.DYING -> {
                deathTimer += dt
                if (deathTimer >= deathDurationSec) {
                    state = ZombieState.DEAD
                }
            }
            ZombieState.DEAD -> {}
        }
    }

    fun takeDamage(amount: Float) {
        if (state == ZombieState.DYING || state == ZombieState.DEAD) return
        health -= amount
        if (health <= 0f) {
            health = 0f
            state = ZombieState.DYING
            deathTimer = 0f
            // A worm killed mid-emerge would otherwise freeze at its tiny burst-out scale for the
            // whole death animation while the (full-size) blood splatter spawns at the same spot;
            // snap it to fully emerged so the death animation always plays at full size.
            emergeTimer = emergeDurationSec
        }
    }

    fun isAlive(): Boolean = state == ZombieState.WALKING || state == ZombieState.ATTACKING
    fun isRemovable(): Boolean = state == ZombieState.DEAD

    fun draw(canvas: Canvas, paint: Paint) {
        paint.shader = null

        if (state != ZombieState.DYING) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(70, 0, 0, 0)
            canvas.drawOval(x - radius * 0.55f, y + radius * 0.55f, x + radius * 0.55f, y + radius * 0.95f, paint)
        }

        canvas.save()
        canvas.translate(x, y)

        if (state == ZombieState.DYING) {
            val t = GameMath.clamp(deathTimer / deathDurationSec, 0f, 1f)
            canvas.rotate(90f * t, 0f, radius * 0.5f)
            canvas.scale(1f - 0.3f * t, 1f - 0.3f * t, 0f, radius * 0.5f)
            paint.alpha = ((1f - t) * 255).toInt()
        } else {
            paint.alpha = 255
        }

        when {
            kind == EnemyKind.WORM -> drawWorm(canvas, paint)
            kind == EnemyKind.BOSS && bossVariant == BossVariant.GALAXY_SNAIL -> drawSnail(canvas, paint)
            kind == EnemyKind.BOSS && bossVariant == BossVariant.METEOR_WYRM -> drawMeteorWyrm(canvas, paint)
            kind == EnemyKind.BOSS && bossVariant == BossVariant.OBELISK_WARDEN -> drawObeliskWarden(canvas, paint)
            kind == EnemyKind.FLYER -> drawFlyer(canvas, paint)
            else -> drawHumanoid(canvas, paint)
        }

        canvas.restore()
        paint.alpha = 255
        paint.shader = null

        if (state != ZombieState.DYING) {
            drawHealthBar(canvas, paint)
            drawStatusIcons(canvas, paint)
        }
    }

    /** The zombie/tank rig: swinging limbs, a torso, and a round head with glowing eyes. */
    private fun drawHumanoid(canvas: Canvas, paint: Paint) {
        val swing = sin(walkPhase) * 0.6f
        val limbWidth = radius * 0.34f

        // Legs.
        drawLimb(canvas, paint, 0f, radius * 0.15f, swing, radius * 0.75f, limbWidth, limbColor, outline)
        drawLimb(canvas, paint, 0f, radius * 0.15f, -swing, radius * 0.75f, limbWidth, limbColor, outline)

        // Arms, reaching forward, roughly opposite phase from the legs.
        drawLimb(canvas, paint, 0f, -radius * 0.1f, -swing * 0.8f - 0.35f, radius * 0.68f, limbWidth * 0.85f, armColor, outline)
        drawLimb(canvas, paint, 0f, -radius * 0.1f, swing * 0.8f - 0.35f, radius * 0.68f, limbWidth * 0.85f, armColor, outline)

        // Torso, shaded with a vertical gradient for a rounder look.
        paint.shader = torsoGradient
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(torsoRect, radius * 0.16f, radius * 0.16f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * visualScale
        paint.color = outline
        canvas.drawRoundRect(torsoRect, radius * 0.16f, radius * 0.16f, paint)

        if (isTank) {
            // A couple of armor plates for a bulkier, tougher silhouette.
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(64, 54, 48)
            canvas.drawRect(torsoRect.left + radius * 0.06f, torsoRect.top + radius * 0.08f, torsoRect.left + radius * 0.22f, torsoRect.bottom - radius * 0.04f, paint)
            canvas.drawRect(torsoRect.right - radius * 0.22f, torsoRect.top + radius * 0.08f, torsoRect.right - radius * 0.06f, torsoRect.bottom - radius * 0.04f, paint)
        }

        // Head, radial-shaded so it reads as round rather than a flat disc.
        paint.shader = headGradient
        paint.style = Paint.Style.FILL
        canvas.drawCircle(0f, headCenterY, headR, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * visualScale
        paint.color = outline
        canvas.drawCircle(0f, headCenterY, headR, paint)

        // Glowing eyes.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(220, 30, 20)
        canvas.drawCircle(-headR * 0.4f, headCenterY - headR * 0.05f, headR * 0.16f, paint)
        canvas.drawCircle(headR * 0.4f, headCenterY - headR * 0.05f, headR * 0.16f, paint)

        // An encasing energy-shield bubble, drawn last so it reads as surrounding the whole
        // body — the visual cue that arrows bounce off it and only cannons get through (see
        // canBeTargetedBy). Brightness pulses gently with walkPhase rather than a separate clock.
        if (isShielded) {
            val pulse = 0.7f + 0.3f * sin(walkPhase * 1.5f)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((35 * pulse).toInt(), 90, 190, 255)
            canvas.drawCircle(0f, -radius * 0.15f, radius * 0.95f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * visualScale
            paint.color = Color.argb((200 * pulse).toInt(), 130, 210, 255)
            canvas.drawCircle(0f, -radius * 0.15f, radius * 0.95f, paint)
        }
    }

    /**
     * A segmented body oriented along [facingAngle] (unlike the humanoid rig, which stays
     * upright and swings its limbs in place) so it visibly slithers toward the castle, each
     * segment offset sideways by a sine wave for an undulating wiggle. While still emerging (see
     * [emergeTimer]), it's scaled up from a small burst and surrounded by a ring of dirt
     * particles thrown outward from the burrow, fading as it finishes climbing out.
     */
    private fun drawWorm(canvas: Canvas, paint: Paint) {
        val emergeT = if (emergeDurationSec > 0f) (emergeTimer / emergeDurationSec).coerceIn(0f, 1f) else 1f
        if (emergeT < 1f) drawEmergeDust(canvas, paint, emergeT)

        canvas.save()
        val bodyScale = 0.15f + 0.85f * emergeT
        canvas.scale(bodyScale, bodyScale)
        canvas.rotate(Math.toDegrees(facingAngle.toDouble()).toFloat())

        val segments = 5
        val bodyLength = radius * 2.3f
        val headRadius = radius * 0.5f
        for (i in segments - 1 downTo 0) {
            val t = i / (segments - 1f) // 0 at the head, 1 at the tail
            val segX = -t * bodyLength
            val wiggle = sin(walkPhase * 2f - i * 0.9f) * radius * 0.22f
            val segRadius = headRadius * (1f - t * 0.55f)
            paint.style = Paint.Style.FILL
            paint.color = when {
                t < 0.15f -> wormHeadColor
                t > 0.7f -> wormTailColor
                else -> wormBodyColor
            }
            canvas.drawCircle(segX, wiggle, segRadius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * visualScale
            paint.color = wormOutline
            canvas.drawCircle(segX, wiggle, segRadius, paint)
        }

        // Glowing eyes on the lead (head) segment.
        val headWiggle = sin(walkPhase * 2f) * radius * 0.22f
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 190, 60)
        canvas.drawCircle(-headRadius * 0.3f, headWiggle - headRadius * 0.35f, headRadius * 0.16f, paint)
        canvas.drawCircle(-headRadius * 0.3f, headWiggle + headRadius * 0.35f, headRadius * 0.16f, paint)

        canvas.restore()
    }

    /** Small dirt particles bursting outward from the ground as the worm climbs out. */
    private fun drawEmergeDust(canvas: Canvas, paint: Paint, emergeT: Float) {
        val burstRadius = radius * (0.5f + 1.4f * emergeT)
        val alpha = ((1f - emergeT) * 190).toInt()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(alpha, 110, 70, 40)
        for (offset in dustOffsets) {
            canvas.drawCircle(offset[0] * burstRadius, offset[1] * burstRadius * 0.55f, radius * 0.16f, paint)
        }
    }

    /**
     * The boss ("Galaxy Snail"): a slow-moving foot/body (oriented along [facingAngle], like the
     * worm) topped with a round shell rendered as a miniature spiral galaxy — see
     * [drawGalaxyShell]. Its huge [radius] alone already reads as a giant among the other enemy
     * kinds, so unlike the humanoid rig this doesn't need armor plates or other bulk cues.
     */
    private fun drawSnail(canvas: Canvas, paint: Paint) {
        canvas.save()
        canvas.rotate(Math.toDegrees(facingAngle.toDouble()).toFloat())

        // Foot: a stretched, rounded body trailing behind the shell in the direction of travel.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(58, 128, 70)
        val footRect = RectF(-radius * 1.05f, -radius * 0.34f, radius * 0.85f, radius * 0.34f)
        canvas.drawRoundRect(footRect, radius * 0.3f, radius * 0.3f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * visualScale
        paint.color = Color.rgb(22, 44, 24)
        canvas.drawRoundRect(footRect, radius * 0.3f, radius * 0.3f, paint)

        // Eye stalks reaching forward, tips wiggling gently with the walk cycle.
        for (side in intArrayOf(-1, 1)) {
            val baseX = radius * 0.55f
            val baseY = side * radius * 0.16f
            val tipX = radius * 0.98f
            val tipY = side * radius * 0.34f + sin(walkPhase + side) * radius * 0.05f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * 0.1f
            paint.color = Color.rgb(58, 128, 70)
            canvas.drawLine(baseX, baseY, tipX, tipY, paint)
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(230, 225, 90)
            canvas.drawCircle(tipX, tipY, radius * 0.12f, paint)
            paint.color = Color.rgb(20, 20, 20)
            canvas.drawCircle(tipX, tipY, radius * 0.05f, paint)
        }
        canvas.restore()

        // Shell drawn upright (not rotated with facingAngle) so the galaxy spiral always reads
        // the same regardless of travel direction, offset slightly toward the body's back.
        canvas.save()
        canvas.translate(-cos(facingAngle) * radius * 0.12f, -sin(facingAngle) * radius * 0.12f)
        drawGalaxyShell(canvas, paint, radius * 0.8f)
        canvas.restore()
    }

    /**
     * The boss's shell: a deep-space disc with slowly-rotating translucent spiral arms, a field
     * of twinkling stars (fixed positions, animated only via a per-star alpha pulse driven by
     * [galaxyPhase] — see [galaxyStars]), and a bright core glow, so it reads as a miniature
     * Milky Way mounted on the snail's back. Rotation/twinkle both animate off [galaxyPhase]
     * rather than any shared clock, so this stays correct however many bosses are ever on screen
     * at once.
     */
    private fun drawGalaxyShell(canvas: Canvas, paint: Paint, shellR: Float) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(12, 8, 30)
        canvas.drawCircle(0f, 0f, shellR, paint)

        canvas.save()
        canvas.rotate(Math.toDegrees(galaxyPhase.toDouble()).toFloat() * 6f)
        paint.style = Paint.Style.FILL
        for (arm in 0 until 3) {
            canvas.save()
            canvas.rotate(arm * 120f)
            paint.color = Color.argb(80, 140, 160, 255)
            canvas.drawOval(-shellR * 0.14f, -shellR * 0.92f, shellR * 0.14f, -shellR * 0.1f, paint)
            paint.color = Color.argb(60, 170, 130, 255)
            canvas.drawOval(-shellR * 0.09f, -shellR * 0.75f, shellR * 0.09f, -shellR * 0.2f, paint)
            canvas.restore()
        }

        for (star in galaxyStars) {
            val twinkle = 0.35f + 0.65f * (0.5f + 0.5f * sin(galaxyPhase * star[4] + star[3]))
            paint.color = Color.argb((90 + twinkle * 165).toInt(), 255, 255, 255)
            canvas.drawCircle(star[0] * shellR, star[1] * shellR, star[2] * shellR, paint)
        }
        canvas.restore()

        // Bright galactic core, glowing gold-white at the shell's center.
        paint.shader = null
        paint.color = Color.argb(200, 255, 250, 220)
        canvas.drawCircle(0f, 0f, shellR * 0.16f, paint)
        paint.color = Color.argb(110, 255, 235, 170)
        canvas.drawCircle(0f, 0f, shellR * 0.3f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * visualScale
        paint.color = Color.rgb(70, 60, 110)
        canvas.drawCircle(0f, 0f, shellR, paint)
    }

    /**
     * A small hovering alien flier — the visual cue for [canBeTargetedBy]'s archer-only rule.
     * Bobs above its own ground shadow (drawn separately in [draw], unaffected by this function's
     * local translate — the shadow stays pinned to the actual ground position while the body
     * lifts above it) rather than tracking a real altitude the movement/targeting math needs to
     * know about. Wings flutter on their own fast cycle, independent of walkPhase's ground-walk
     * speed, so it still reads as airborne even while creeping forward slowly.
     */
    private fun drawFlyer(canvas: Canvas, paint: Paint) {
        val bob = sin(walkPhase * 3f + bobPhaseOffset) * radius * 0.25f
        canvas.save()
        canvas.translate(0f, -radius * 0.4f + bob)
        canvas.rotate(Math.toDegrees(facingAngle.toDouble()).toFloat())

        val flutter = sin(walkPhase * 10f)
        paint.style = Paint.Style.FILL
        for (side in intArrayOf(-1, 1)) {
            canvas.save()
            canvas.rotate(side * (20f + flutter * 25f))
            paint.color = Color.argb(150, 140, 200, 235)
            val wingNear = side * radius * 0.1f
            val wingFar = side * radius * 0.95f
            canvas.drawOval(-radius * 0.15f, min(wingNear, wingFar), radius * 0.75f, max(wingNear, wingFar), paint)
            canvas.restore()
        }

        // Ghostly translucent body core.
        paint.color = Color.argb(200, 170, 130, 220)
        canvas.drawOval(-radius * 0.55f, -radius * 0.4f, radius * 0.55f, radius * 0.4f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.8f * visualScale
        paint.color = Color.argb(200, 90, 60, 140)
        canvas.drawOval(-radius * 0.55f, -radius * 0.4f, radius * 0.55f, radius * 0.4f, paint)

        // A single glowing eye, since it's a small alien creature rather than a humanoid.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(255, 230, 90)
        canvas.drawCircle(radius * 0.25f, 0f, radius * 0.16f, paint)
        paint.color = Color.rgb(30, 20, 10)
        canvas.drawCircle(radius * 0.3f, 0f, radius * 0.07f, paint)

        canvas.restore()
    }

    /**
     * The Meteor Wyrm: a bigger, rockier cousin of the segmented worm rig (see [drawWorm]),
     * oriented along [facingAngle] so it visibly tumbles toward the castle. Its tail flares
     * brighter and longer during the fast "dash" portion of its movement cycle (see [dashPhase]
     * in [update]) than during the slow creep, giving a visual tell for when it's about to burst
     * forward.
     */
    private fun drawMeteorWyrm(canvas: Canvas, paint: Paint) {
        val creepDurationSec = dashCycleSec * dashCreepFraction
        val dashing = dashPhase >= creepDurationSec
        canvas.save()
        canvas.rotate(Math.toDegrees(facingAngle.toDouble()).toFloat())

        val segments = 6
        val bodyLength = radius * 2.1f
        val headRadius = radius * 0.55f
        for (i in segments - 1 downTo 0) {
            val t = i / (segments - 1f)
            val segX = -t * bodyLength
            val wiggle = sin(walkPhase * 2f - i * 0.8f) * radius * 0.15f
            val segRadius = headRadius * (1f - t * 0.5f)
            paint.style = Paint.Style.FILL
            paint.color = when {
                t < 0.2f -> Color.rgb(90, 70, 60)
                t > 0.75f -> Color.rgb(50, 38, 32)
                else -> Color.rgb(70, 54, 46)
            }
            canvas.drawCircle(segX, wiggle, segRadius, paint)
            // Glowing magma cracks across each segment, brighter while dashing.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * visualScale
            paint.color = if (dashing) Color.argb(220, 255, 140, 40) else Color.argb(150, 220, 90, 30)
            canvas.drawCircle(segX, wiggle, segRadius * 0.6f, paint)
        }

        // Fiery tail, longer and brighter while dashing.
        val tailLen = if (dashing) bodyLength * 0.9f else bodyLength * 0.35f
        val tailAlpha = if (dashing) 200 else 110
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(tailAlpha, 255, 120, 30)
        canvas.drawOval(-bodyLength - tailLen, -radius * 0.18f, -bodyLength, radius * 0.18f, paint)

        // Glowing eyes on the lead segment.
        paint.color = Color.rgb(255, 210, 60)
        canvas.drawCircle(-headRadius * 0.2f, -headRadius * 0.3f, headRadius * 0.14f, paint)
        canvas.drawCircle(-headRadius * 0.2f, headRadius * 0.3f, headRadius * 0.14f, paint)

        canvas.restore()
    }

    /**
     * The Obelisk Warden: an angular stone monument rather than a creature, its core glowing
     * brighter as [beamCooldown] counts down toward its next shot (see [update]'s
     * OBELISK_WARDEN branch) so a nearly-charged beam reads as an imminent threat before it fires.
     */
    private fun drawObeliskWarden(canvas: Canvas, paint: Paint) {
        canvas.save()
        canvas.rotate(Math.toDegrees(facingAngle.toDouble()).toFloat())

        // Angular stone body: a hexagon with alternating vertex distances for a jagged silhouette.
        val bodyPath = Path()
        for (i in 0 until 6) {
            val a = (Math.PI / 3.0 * i).toFloat()
            val r = radius * (0.85f + 0.1f * (i % 2))
            val px = cos(a) * r
            val py = sin(a) * r
            if (i == 0) bodyPath.moveTo(px, py) else bodyPath.lineTo(px, py)
        }
        bodyPath.close()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(70, 66, 78)
        canvas.drawPath(bodyPath, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * visualScale
        paint.color = Color.rgb(30, 28, 36)
        canvas.drawPath(bodyPath, paint)

        // Crystalline spikes at alternating vertices.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 150, 200, 255)
        for (i in 0 until 6 step 2) {
            val a = (Math.PI / 3.0 * i).toFloat()
            val baseR = radius * 0.85f
            canvas.drawCircle(cos(a) * baseR, sin(a) * baseR, radius * 0.12f, paint)
        }

        // Glowing core, brightening as the next beam shot approaches.
        val chargeT = 1f - GameMath.clamp(beamCooldown / beamIntervalSec, 0f, 1f)
        val coreRadius = radius * (0.28f + chargeT * 0.1f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb((120 + chargeT * 135).toInt(), 140, 210, 255)
        canvas.drawCircle(0f, 0f, coreRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * visualScale
        paint.color = Color.rgb(200, 235, 255)
        canvas.drawCircle(0f, 0f, coreRadius, paint)

        canvas.restore()
    }

    /** Draws a filled, outlined rounded-rect "capsule" limb rotated about (originX, originY). */
    private fun drawLimb(
        canvas: Canvas, paint: Paint, originX: Float, originY: Float,
        angleOffsetFromDown: Float, length: Float, width: Float, color: Int, outlineColor: Int
    ) {
        canvas.save()
        canvas.translate(originX, originY)
        canvas.rotate(Math.toDegrees(angleOffsetFromDown.toDouble()).toFloat())
        val rect = RectF(-width / 2f, 0f, width / 2f, length)
        paint.style = Paint.Style.FILL
        paint.color = color
        canvas.drawRoundRect(rect, width / 2f, width / 2f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * visualScale
        paint.color = outlineColor
        canvas.drawRoundRect(rect, width / 2f, width / 2f, paint)
        canvas.restore()
    }

    private fun drawHealthBar(canvas: Canvas, paint: Paint) {
        val barWidth = radius * 1.6f
        val barHeight = 5f * visualScale
        val ratio = GameMath.clamp(health / maxHealth, 0f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(160, 0, 0, 0)
        canvas.drawRect(x - barWidth / 2f, y - radius * 1.3f, x + barWidth / 2f, y - radius * 1.3f + barHeight, paint)
        paint.color = Color.rgb(200, 40, 40)
        canvas.drawRect(x - barWidth / 2f, y - radius * 1.3f, x - barWidth / 2f + barWidth * ratio, y - radius * 1.3f + barHeight, paint)
    }

    private fun drawStatusIcons(canvas: Canvas, paint: Paint) {
        val iconY = y - radius * 1.55f
        var iconX = x - 8f * visualScale
        if (slowTimer > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * visualScale
            paint.color = Color.argb(210, 90, 180, 255)
            canvas.drawCircle(iconX, iconY, 5f * visualScale, paint)
            iconX += 14f * visualScale
        }
        if (bleedTimer > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(220, 200, 30, 30)
            canvas.drawCircle(iconX, iconY, 4.5f * visualScale, paint)
        }
    }
}
