package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.cos
import kotlin.math.hypot
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
        // Set once a star crosses a gravity well's event horizon (see [applyGravityWell]); it
        // freezes in place and blinks for consumeDuration instead of continuing to drift, so
        // getting swallowed reads as a distinct moment rather than an abrupt cut.
        var consuming = false
        var consumeTimer = 0f
    }

    private val stars = List(poolSize) { Star() }
    private val colors = intArrayOf(
        Color.rgb(140, 210, 255), // pale blue
        Color.rgb(255, 150, 220), // pink
        Color.rgb(255, 214, 120), // gold
        Color.rgb(180, 160, 255)  // lavender
    )
    private var nextSpawnIn = 1f
    private val consumeDuration = 0.18f

    /** [scale] is a resolution-relative multiplier applied to spawn speed; pass 1f if none applies. */
    fun update(dt: Float, screenW: Float, screenH: Float, scale: Float) {
        nextSpawnIn -= dt
        if (nextSpawnIn <= 0f) {
            spawn(screenW, screenH, scale)
            nextSpawnIn = 1.2f + Random.nextFloat() * 1.5f
        }
        for (star in stars) {
            if (!star.active) continue
            if (star.consuming) {
                star.consumeTimer += dt
                if (star.consumeTimer >= consumeDuration) star.active = false
                continue
            }
            star.age += dt
            star.x += star.vx * dt
            star.y += star.vy * dt
            if (star.age >= star.lifespan || star.x < -80f || star.x > screenW + 80f || star.y > screenH + 80f) {
                star.active = false
            }
        }
    }

    /**
     * Pulls any active, not-already-consuming star within [pullRadius] of ([cx],[cy]) toward that
     * point with an inverse-square-style acceleration (so the closer a star drifts, the harder
     * and faster it curves in) — a graceful infall rather than a straight-line snap. A star that
     * crosses [eventHorizonRadius] starts consuming instead of moving any further; see
     * [Star.consuming]. No-op for callers that never invoke it (e.g. the main menu backdrop,
     * which has no black hole), so this doesn't change existing behavior there.
     */
    fun applyGravityWell(cx: Float, cy: Float, pullRadius: Float, eventHorizonRadius: Float, strength: Float, dt: Float) {
        for (star in stars) {
            if (!star.active || star.consuming) continue
            val dx = cx - star.x
            val dy = cy - star.y
            val dist = hypot(dx, dy)
            if (dist > pullRadius) continue
            if (dist <= eventHorizonRadius) {
                star.consuming = true
                star.consumeTimer = 0f
                continue
            }
            val accel = strength / (dist * dist + 400f)
            star.vx += (dx / dist) * accel * dt
            star.vy += (dy / dist) * accel * dt
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
        // Pool entries are reused, so a star that was previously consumed must have that state
        // cleared here — otherwise update()'s consuming branch would immediately freeze this
        // freshly-spawned star in place instead of letting it fly.
        star.consuming = false
        star.consumeTimer = 0f
        star.active = true
    }

    /** [scale] must match what was passed to [update] so trail sizing stays consistent. */
    fun draw(canvas: Canvas, paint: Paint, scale: Float) {
        for (star in stars) {
            if (star.active) drawStar(canvas, paint, star, scale)
        }
    }

    private fun drawStar(canvas: Canvas, paint: Paint, star: Star, scale: Float) {
        if (star.consuming) {
            drawConsuming(canvas, paint, star, scale)
            return
        }
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

    /**
     * Frozen at the point it crossed the event horizon, rapidly flickering as it fades out over
     * [consumeDuration] — reads as the star blinking out of existence rather than a silent cut.
     */
    private fun drawConsuming(canvas: Canvas, paint: Paint, star: Star, scale: Float) {
        val fadeOut = 1f - star.consumeTimer / consumeDuration
        val blink = 0.5f + 0.5f * sin(star.consumeTimer * 55f)
        val alpha = (fadeOut * blink * 255).toInt().coerceIn(0, 255)
        if (alpha <= 2) return
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(alpha, 255, 255, 255)
        canvas.drawCircle(star.x, star.y, 3.2f * scale, paint)
    }
}
