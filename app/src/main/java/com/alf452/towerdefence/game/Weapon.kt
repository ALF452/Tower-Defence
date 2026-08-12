package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

enum class WeaponType { CANNON, ARCHER }

/**
 * A single mounted weapon slot on the castle ring. Stats are pushed in from
 * [com.alf452.towerdefence.game.GameEngine] whenever the owning category is upgraded;
 * this class only handles per-frame targeting, firing and its own fire animation.
 */
class WeaponSlot(val type: WeaponType, val angleDeg: Float) {
    var unlocked = false

    var damage = 0f
    var fireIntervalSec = 1f
    var range = 0f
    var splashRadius = 0f
    /**
     * Resolution-relative scale so the weapon reads at a consistent size across devices.
     * Mount/barrel gradients only depend on this value, so they're cached and only rebuilt
     * when it actually changes (e.g. on a surface resize), instead of every draw() call.
     */
    var visualScale = 1f
        set(value) {
            if (field == value) return
            field = value
            mountGradient = RadialGradient(-3f * value, -3f * value, 16f * value, Color.rgb(122, 114, 100), Color.rgb(74, 68, 58), Shader.TileMode.CLAMP)
            barrelGradient = LinearGradient(0f, -8f * value, 0f, 8f * value, Color.rgb(76, 76, 82), Color.rgb(28, 28, 32), Shader.TileMode.CLAMP)
        }
    private var mountGradient: Shader = RadialGradient(-3f, -3f, 16f, Color.rgb(122, 114, 100), Color.rgb(74, 68, 58), Shader.TileMode.CLAMP)
    private var barrelGradient: Shader = LinearGradient(0f, -8f, 0f, 8f, Color.rgb(76, 76, 82), Color.rgb(28, 28, 32), Shader.TileMode.CLAMP)
    private val turnSpeedRadPerSec = 7f

    private var cooldown = 0f
    var turretAngle = -Math.PI.toFloat() / 2f
        private set
    private var recoil = 0f
    private var muzzleFlash = 0f
    private var drawBack = 0f

    fun ringPosition(castle: Castle, ringRadius: Float): FloatArray {
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        return GameMath.pointOnCircle(castle.x, castle.y, ringRadius, rad)
    }

    fun update(dt: Float, castle: Castle, ringRadius: Float, zombies: List<Zombie>, onFire: (WeaponSlot, Zombie, Float, Float) -> Unit) {
        if (!unlocked) return
        recoil = max(0f, recoil - dt * 4f)
        muzzleFlash = max(0f, muzzleFlash - dt * 6f)
        drawBack = max(0f, drawBack - dt * 3f)
        cooldown -= dt

        val pos = ringPosition(castle, ringRadius)
        var target: Zombie? = null
        var bestDist = Float.MAX_VALUE
        for (z in zombies) {
            if (!z.isAlive()) continue
            val d = GameMath.distance(pos[0], pos[1], z.x, z.y)
            if (d <= range && d < bestDist) {
                bestDist = d
                target = z
            }
        }

        if (target != null) {
            val desired = GameMath.angleTo(pos[0], pos[1], target.x, target.y)
            turretAngle = GameMath.lerpAngle(turretAngle, desired, turnSpeedRadPerSec * dt)
            if (cooldown <= 0f) {
                cooldown = fireIntervalSec
                recoil = 1f
                muzzleFlash = 1f
                drawBack = 1f
                onFire(this, target, pos[0], pos[1])
            }
        }
    }

    fun draw(canvas: Canvas, paint: Paint, castle: Castle, ringRadius: Float) {
        if (!unlocked) return
        val pos = ringPosition(castle, ringRadius)
        val px = pos[0]
        val py = pos[1]
        val s = visualScale

        canvas.save()
        canvas.translate(px, py)
        canvas.rotate(Math.toDegrees(turretAngle.toDouble()).toFloat())

        // Base mount, radially shaded so it reads as a rounded stone/metal socket.
        paint.shader = mountGradient
        paint.style = Paint.Style.FILL
        canvas.drawCircle(0f, 0f, 14f * s, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * s
        paint.color = Color.rgb(50, 45, 38)
        canvas.drawCircle(0f, 0f, 14f * s, paint)

        when (type) {
            WeaponType.CANNON -> {
                val recoilOffset = recoil * 6f * s
                val barrel = RectF(-6f * s - recoilOffset, -8f * s, 24f * s - recoilOffset, 8f * s)
                paint.shader = barrelGradient
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(barrel, 4f * s, 4f * s, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f * s
                paint.color = Color.rgb(15, 15, 18)
                canvas.drawRoundRect(barrel, 4f * s, 4f * s, paint)

                // Muzzle rim.
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(18, 18, 20)
                canvas.drawCircle(barrel.right, 0f, 5f * s, paint)

                if (muzzleFlash > 0f) {
                    paint.color = Color.argb((muzzleFlash * 220).toInt(), 255, 200, 90)
                    canvas.drawCircle(barrel.right + 6f * s, 0f, 10f * s * muzzleFlash, paint)
                }
            }
            WeaponType.ARCHER -> {
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(120, 90, 60)
                canvas.drawRoundRect(-3f * s, -16f * s, 3f * s, 16f * s, 2f * s, 2f * s, paint)

                val pull = 8f * s * drawBack
                val tipTopX = -2f * s
                val tipTopY = -16f * s
                val tipBottomX = -2f * s
                val tipBottomY = 16f * s
                val bowBulgeX = -16f * s

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * s
                paint.color = Color.rgb(214, 200, 176)
                val bowPath = Path().apply {
                    moveTo(tipTopX, tipTopY)
                    quadTo(bowBulgeX, 0f, tipBottomX, tipBottomY)
                }
                canvas.drawPath(bowPath, paint)

                // Bowstring, pulled back toward the archer while winding up to fire.
                paint.strokeWidth = 1.6f * s
                paint.color = Color.rgb(230, 225, 210)
                canvas.drawLine(tipTopX, tipTopY, -pull, 0f, paint)
                canvas.drawLine(tipBottomX, tipBottomY, -pull, 0f, paint)

                if (drawBack > 0.05f) {
                    paint.strokeWidth = 1.5f * s
                    canvas.drawLine(-pull, 0f, 18f * s, 0f, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(90, 70, 50)
                    canvas.drawCircle(18f * s, 0f, 2.2f * s, paint)
                }
            }
        }

        canvas.restore()
        paint.shader = null
    }

    fun rangePreviewRadius(): Float = range
}
