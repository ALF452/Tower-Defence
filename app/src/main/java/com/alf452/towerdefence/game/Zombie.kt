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

/**
 * A little zombie shambling in from the wave edge toward the castle (or, from
 * wave 10 onward, an occasional larger, tougher Tank Zombie). Limbs are
 * filled, rotated rounded-rect "capsules" (not stroked lines) driven by a
 * walk-cycle phase, so it reads as a solid little creature instead of a stick
 * figure, with no sprite sheet needed for the animation.
 */
class Zombie(
    var x: Float,
    var y: Float,
    val maxHealth: Float,
    val speed: Float,
    val contactDamage: Float,
    val goldReward: Int,
    private val visualScale: Float = 1f,
    val isTank: Boolean = false
) {
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

    val radius = (if (isTank) 22f * 1.9f else 22f) * visualScale

    // These colors/gradients depend only on radius/isTank, both fixed for the
    // lifetime of the instance, so they're computed once here instead of being
    // reallocated every draw() call (a zombie can be drawn 60x/sec).
    private val bodyColor = if (isTank) Color.rgb(90, 78, 70) else Color.rgb(58, 104, 58)
    private val bodyColorLight = if (isTank) Color.rgb(120, 104, 92) else Color.rgb(96, 156, 90)
    private val limbColor = if (isTank) Color.rgb(78, 66, 58) else Color.rgb(58, 104, 58)
    private val armColor = if (isTank) Color.rgb(86, 74, 64) else Color.rgb(66, 116, 66)
    private val headLight = if (isTank) Color.rgb(150, 90, 80) else Color.rgb(120, 172, 112)
    private val headDark = if (isTank) Color.rgb(100, 56, 50) else Color.rgb(80, 132, 78)
    private val outline = if (isTank) Color.rgb(30, 22, 18) else Color.rgb(24, 46, 24)
    private val headCenterY = -radius * 0.62f
    private val headR = radius * 0.34f
    private val torsoRect = RectF(-radius * 0.42f, -radius * 0.42f, radius * 0.42f, radius * 0.28f)
    private val torsoGradient = LinearGradient(0f, torsoRect.top, 0f, torsoRect.bottom, bodyColorLight, bodyColor, Shader.TileMode.CLAMP)
    private val headGradient = RadialGradient(-headR * 0.3f, headCenterY - headR * 0.3f, headR * 1.4f, headLight, headDark, Shader.TileMode.CLAMP)

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
                val d = GameMath.distance(x, y, castle.x, castle.y)
                val stopDistance = castle.radius * 1.55f + radius
                if (d <= stopDistance) {
                    state = ZombieState.ATTACKING
                    attackCooldown = attackIntervalSec * 0.5f
                } else {
                    val effectiveSpeed = speed * slowFactor
                    facingAngle = GameMath.angleTo(x, y, castle.x, castle.y)
                    x += cos(facingAngle) * effectiveSpeed * dt
                    y += sin(facingAngle) * effectiveSpeed * dt
                    walkPhase += dt * (effectiveSpeed / (18f * visualScale))
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

        canvas.restore()
        paint.alpha = 255
        paint.shader = null

        if (state != ZombieState.DYING) {
            drawHealthBar(canvas, paint)
            drawStatusIcons(canvas, paint)
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
