package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Slow-moving, colored-sparkly-tailed shooting stars that spawn periodically and drift across
 * the screen before fading out. Shared by the main menu backdrop and the actual game world so
 * tuning (speed, colors, trail length, timing) lives in one place instead of being hand-copied
 * between the two. Pool entries are reused (never reallocated) and the tail is drawn by walking
 * backward along the current velocity from the head position, so there's no per-frame allocation.
 */
class ShootingStarField(poolSize: Int) {

    private class Star {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var color = Color.WHITE
        var age = 0f
        var lifespan = 1f
    }

    private val stars = List(poolSize) { Star() }
    private val colors = intArrayOf(
        Color.rgb(140, 210, 255), // pale blue
        Color.rgb(255, 150, 220), // pink
        Color.rgb(255, 214, 120), // gold
        Color.rgb(180, 160, 255)  // lavender
    )
    private var nextSpawnIn = 1f

    /** [scale] is a resolution-relative multiplier applied to spawn speed; pass 1f if none applies. */
    fun update(dt: Float, screenW: Float, screenH: Float, scale: Float) {
        nextSpawnIn -= dt
        if (nextSpawnIn <= 0f) {
            spawn(screenW, screenH, scale)
            nextSpawnIn = 1.2f + Random.nextFloat() * 1.5f
        }
        for (star in stars) {
            if (!star.active) continue
            star.age += dt
            star.x += star.vx * dt
            star.y += star.vy * dt
            if (star.age >= star.lifespan || star.x < -80f || star.x > screenW + 80f || star.y > screenH + 80f) {
                star.active = false
            }
        }
    }

    private fun spawn(screenW: Float, screenH: Float, scale: Float) {
        if (screenW <= 0f || screenH <= 0f) return
        val star = stars.firstOrNull { !it.active } ?: return
        val fromLeft = Random.nextBoolean()
        star.x = if (fromLeft) -40f else screenW + 40f
        star.y = Random.nextFloat() * screenH * 0.55f
        val speed = (70f + Random.nextFloat() * 90f) * scale // slow drift, not a fast streak
        val angleDeg = 18f + Random.nextFloat() * 20f // shallow downward angle
        val dirX = if (fromLeft) 1f else -1f
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        star.vx = dirX * speed * cos(rad)
        star.vy = speed * sin(rad)
        star.color = colors[Random.nextInt(colors.size)]
        star.age = 0f
        star.lifespan = 4f + Random.nextFloat() * 2.5f
        star.active = true
    }

    /** [scale] must match what was passed to [update] so trail sizing stays consistent. */
    fun draw(canvas: Canvas, paint: Paint, scale: Float) {
        for (star in stars) {
            if (star.active) drawStar(canvas, paint, star, scale)
        }
    }

    private fun drawStar(canvas: Canvas, paint: Paint, star: Star, scale: Float) {
        val fadeIn = (star.age / 0.6f).coerceIn(0f, 1f)
        val fadeOut = ((star.lifespan - star.age) / 0.8f).coerceIn(0f, 1f)
        val globalAlpha = minOf(fadeIn, fadeOut)
        if (globalAlpha <= 0.02f) return

        val r = Color.red(star.color)
        val g = Color.green(star.color)
        val b = Color.blue(star.color)

        // 3x the total tail length via wider spacing between fewer points (18 steps at ~1.67x
        // the spacing) rather than 3x as many points, to keep the draw-call cost bounded.
        val steps = 18
        val stepTime = 0.0467f
        paint.style = Paint.Style.FILL
        for (i in steps downTo 1) {
            val t = i * stepTime
            val px = star.x - star.vx * t
            val py = star.y - star.vy * t
            val frac = 1f - i.toFloat() / steps
            val radius = (1.2f + frac * 3.2f) * scale
            val alpha = (globalAlpha * frac * frac * 220).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, r, g, b)
            canvas.drawCircle(px, py, radius, paint)
        }
        // Bright sparkle at the head.
        paint.color = Color.argb((globalAlpha * 255).toInt(), 255, 255, 255)
        canvas.drawCircle(star.x, star.y, 2.6f * scale, paint)
    }
}
