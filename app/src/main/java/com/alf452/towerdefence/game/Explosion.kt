package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** A short-lived expanding, fading ring drawn where a cannonball detonates. */
class Explosion(private val x: Float, private val y: Float, private val maxRadius: Float, private val visualScale: Float) {
    private var age = 0f
    private val ttl = 0.35f

    val alive: Boolean get() = age < ttl

    fun update(dt: Float) {
        age += dt
    }

    fun draw(canvas: Canvas, paint: Paint) {
        val t = GameMath.clamp(age / ttl, 0f, 1f)
        val r = maxRadius * (0.35f + 0.65f * t)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * visualScale
        paint.color = Color.argb(((1f - t) * 210).toInt(), 255, 170, 60)
        canvas.drawCircle(x, y, r, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(((1f - t) * 100).toInt(), 255, 120, 40)
        canvas.drawCircle(x, y, r * 0.45f, paint)
    }
}
