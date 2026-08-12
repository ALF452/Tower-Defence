package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

        canvas.save()
        canvas.translate(px, py)
        canvas.rotate(Math.toDegrees(turretAngle.toDouble()).toFloat())

        // Base mount.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(90, 84, 74)
        canvas.drawCircle(0f, 0f, 14f, paint)

        when (type) {
            WeaponType.CANNON -> {
                paint.color = Color.rgb(48, 48, 52)
                val recoilOffset = recoil * 6f
                canvas.drawRoundRect(-6f - recoilOffset, -8f, 22f - recoilOffset, 8f, 4f, 4f, paint)
                if (muzzleFlash > 0f) {
                    paint.color = Color.argb((muzzleFlash * 220).toInt(), 255, 200, 90)
                    canvas.drawCircle(24f - recoilOffset, 0f, 10f * muzzleFlash, paint)
                }
            }
            WeaponType.ARCHER -> {
                paint.color = Color.rgb(120, 90, 60)
                canvas.drawRect(-3f, -14f, 3f, 14f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = Color.rgb(210, 200, 180)
                val pull = 8f * drawBack
                canvas.drawLine(0f, -14f, -pull, 0f, paint)
                canvas.drawLine(0f, 14f, -pull, 0f, paint)
                if (drawBack > 0.05f) {
                    canvas.drawLine(-pull, 0f, 18f, 0f, paint)
                }
            }
        }

        canvas.restore()
    }

    fun rangePreviewRadius(): Float = range
}
