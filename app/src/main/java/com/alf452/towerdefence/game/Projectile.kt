package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin

enum class ProjectileKind { CANNONBALL, ARROW }

/**
 * A fired shot travelling toward the point the target occupied when fired.
 * Cannonballs explode for splash damage; arrows hit a single target.
 */
class Projectile(
    var x: Float,
    var y: Float,
    private val targetX: Float,
    private val targetY: Float,
    val kind: ProjectileKind,
    val damage: Float,
    val splashRadius: Float,
    private val speed: Float
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
        paint.style = Paint.Style.FILL
        when (kind) {
            ProjectileKind.CANNONBALL -> {
                paint.color = Color.rgb(40, 40, 40)
                canvas.drawCircle(x, y, 9f, paint)
            }
            ProjectileKind.ARROW -> {
                paint.color = Color.rgb(210, 190, 150)
                paint.strokeWidth = 4f
                paint.strokeCap = Paint.Cap.ROUND
                val backX = x - cos(angle) * 16f
                val backY = y - sin(angle) * 16f
                canvas.drawLine(backX, backY, x, y, paint)
            }
        }
    }
}
