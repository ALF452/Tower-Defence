package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class ZombieState { WALKING, ATTACKING, DYING, DEAD }
enum class EnemyKind { NORMAL, TANK, WORM }

/**
 * A hostile creature shambling (or, for [EnemyKind.WORM], slithering) in from the wave edge
 * toward the castle. [EnemyKind.TANK] (from wave 10 on) is a larger, tougher, slower variant of
 * the same humanoid rig; [EnemyKind.WORM] (also from wave 10 on) is a fast burnt-orange space
 * worm with a completely different segmented body and its own brief "digging out of the ground"
 * entrance, sharing only the state machine/combat plumbing below with the other two. Humanoid
 * limbs are filled, rotated rounded-rect "capsules" (not stroked lines) driven by a walk-cycle
 * phase, so it reads as a solid little creature instead of a stick figure, with no sprite sheet
 * needed for the animation.
 */
class Zombie(
    var x: Float,
    var y: Float,
    val maxHealth: Float,
    val speed: Float,
    val contactDamage: Float,
    val goldReward: Int,
    private val visualScale: Float = 1f,
    val kind: EnemyKind = EnemyKind.NORMAL
) {
    val isTank: Boolean get() = kind == EnemyKind.TANK

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
                        state = ZombieState.ATTACKING
                        attackCooldown = attackIntervalSec * 0.5f
                    } else {
                        val effectiveSpeed = speed * slowFactor
                        x += cos(facingAngle) * effectiveSpeed * dt
                        y += sin(facingAngle) * effectiveSpeed * dt
                        walkPhase += dt * (effectiveSpeed / (18f * visualScale))
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

        if (kind == EnemyKind.WORM) {
            drawWorm(canvas, paint)
        } else {
            drawHumanoid(canvas, paint)
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
