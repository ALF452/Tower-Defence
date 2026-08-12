package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

enum class ProjectileKind { CANNONBALL, ARROW }

/** An on-hit status effect an arrow can carry, from the archer specialization the player chose. */
enum class ArrowEffect { NONE, SLOW, BLEED }

/**
 * A fired shot travelling toward the point the target occupied when fired.
 * Cannonballs explode for splash damage; arrows hit a single target, optionally
 * carrying a [ArrowEffect] (slow or bleed) applied on impact.
 */
class Projectile(
    var x: Float,
    var y: Float,
    private val targetX: Float,
    private val targetY: Float,
    val kind: ProjectileKind,
    val damage: Float,
    val splashRadius: Float,
    private val speed: Float,
    private val visualScale: Float = 1f,
    val effect: ArrowEffect = ArrowEffect.NONE,
    val effectValue: Float = 0f,
    val effectDuration: Float = 0f
) {
    var alive = true
        private set
    var justImpacted = false
        private set
    var impactX = 0f
        private set
    var impactY = 0f
        private set

    private val angle = GameMath.angleTo(x, y, targetX, targetY)

    fun update(dt: Float) {
        justImpacted = false
        if (!alive) return
        val stepX = cos(angle) * speed * dt
        val stepY = sin(angle) * speed * dt
        val distToTarget = GameMath.distance(x, y, targetX, targetY)
        if (distToTarget <= GameMath.distance(0f, 0f, stepX, stepY)) {
            x = targetX
            y = targetY
            impact()
        } else {
            x += stepX
            y += stepY
        }
    }

    private fun impact() {
        alive = false
        justImpacted = true
        impactX = x
        impactY = y
    }

    fun draw(canvas: Canvas, paint: Paint) {
        if (!alive) return
        val s = visualScale
        when (kind) {
            ProjectileKind.CANNONBALL -> {
                paint.shader = RadialGradient(
                    x - 3f * s, y - 3f * s, 10f * s,
                    Color.rgb(80, 80, 84), Color.rgb(20, 20, 22), Shader.TileMode.CLAMP
                )
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, 8f * s, paint)
                paint.shader = null
            }
            ProjectileKind.ARROW -> {
                paint.style = Paint.Style.STROKE
                paint.color = when (effect) {
                    ArrowEffect.SLOW -> Color.rgb(150, 205, 235)
                    ArrowEffect.BLEED -> Color.rgb(220, 140, 130)
                    ArrowEffect.NONE -> Color.rgb(210, 190, 150)
                }
                paint.strokeWidth = 3.5f * s
                paint.strokeCap = Paint.Cap.ROUND
                val backX = x - cos(angle) * 18f * s
                val backY = y - sin(angle) * 18f * s
                canvas.drawLine(backX, backY, x, y, paint)

                // Arrowhead.
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(150, 150, 156)
                val headLen = 6f * s
                val headWidth = 3.5f * s
                val leftX = x - cos(angle) * headLen - sin(angle) * headWidth
                val leftY = y - sin(angle) * headLen + cos(angle) * headWidth
                val rightX = x - cos(angle) * headLen + sin(angle) * headWidth
                val rightY = y - sin(angle) * headLen - cos(angle) * headWidth
                val headPath = Path().apply {
                    moveTo(x, y)
                    lineTo(leftX, leftY)
                    lineTo(rightX, rightY)
                    close()
                }
                canvas.drawPath(headPath, paint)
            }
        }
    }
}
