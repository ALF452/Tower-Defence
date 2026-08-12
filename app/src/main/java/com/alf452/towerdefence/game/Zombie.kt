package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin

enum class ZombieState { WALKING, ATTACKING, DYING, DEAD }

/**
 * A little zombie shambling in from the wave edge toward the castle.
 * Limbs are drawn as rotated line segments driven by a walk-cycle phase,
 * so no sprite sheet is needed for the animation.
 */
class Zombie(
    var x: Float,
    var y: Float,
    val maxHealth: Float,
    val speed: Float,
    val contactDamage: Float,
    val goldReward: Int
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

    val radius = 22f

    fun update(dt: Float, castle: Castle, onDamageCastle: (Float) -> Unit) {
        when (state) {
            ZombieState.WALKING -> {
                val d = GameMath.distance(x, y, castle.x, castle.y)
                val stopDistance = castle.radius * 1.55f + radius
                if (d <= stopDistance) {
                    state = ZombieState.ATTACKING
                    attackCooldown = attackIntervalSec * 0.5f
                } else {
                    facingAngle = GameMath.angleTo(x, y, castle.x, castle.y)
                    x += cos(facingAngle) * speed * dt
                    y += sin(facingAngle) * speed * dt
                    walkPhase += dt * (speed / 18f)
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

        val swing = sin(walkPhase) * 0.55f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(60, 110, 60)

        // Legs (swing opposite phase).
        drawLimb(canvas, paint, 0f, radius * 0.2f, swing, radius * 0.7f)
        drawLimb(canvas, paint, 0f, radius * 0.2f, -swing, radius * 0.7f)

        // Torso.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(74, 133, 74)
        canvas.drawRoundRect(-radius * 0.4f, -radius * 0.35f, radius * 0.4f, radius * 0.25f, 8f, 8f, paint)

        // Arms (reaching forward, swing opposite the legs for a shamble).
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(64, 116, 64)
        drawLimb(canvas, paint, 0f, -radius * 0.15f, -swing * 0.8f - 0.3f, radius * 0.65f)
        drawLimb(canvas, paint, 0f, -radius * 0.15f, swing * 0.8f - 0.3f, radius * 0.65f)

        // Head.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(96, 150, 96)
        canvas.drawCircle(0f, -radius * 0.55f, radius * 0.32f, paint)

        // Eyes (angry little zombie).
        paint.color = Color.RED
        canvas.drawCircle(-radius * 0.1f, -radius * 0.58f, 2.5f, paint)
        canvas.drawCircle(radius * 0.1f, -radius * 0.58f, 2.5f, paint)

        canvas.restore()
        paint.alpha = 255

        if (state != ZombieState.DYING) {
            drawHealthBar(canvas, paint)
        }
    }

    private fun drawLimb(canvas: Canvas, paint: Paint, originX: Float, originY: Float, angleOffset: Float, length: Float) {
        val angle = Math.PI.toFloat() / 2f + angleOffset
        val endX = originX + cos(angle) * length
        val endY = originY + sin(angle) * length
        canvas.drawLine(originX, originY, endX, endY, paint)
    }

    private fun drawHealthBar(canvas: Canvas, paint: Paint) {
        val barWidth = radius * 1.6f
        val ratio = GameMath.clamp(health / maxHealth, 0f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(160, 0, 0, 0)
        canvas.drawRect(x - barWidth / 2f, y - radius * 1.3f, x + barWidth / 2f, y - radius * 1.3f + 5f, paint)
        paint.color = Color.rgb(200, 40, 40)
        canvas.drawRect(x - barWidth / 2f, y - radius * 1.3f, x - barWidth / 2f + barWidth * ratio, y - radius * 1.3f + 5f, paint)
    }
}
