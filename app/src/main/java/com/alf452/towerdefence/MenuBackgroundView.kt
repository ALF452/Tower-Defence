package com.alf452.towerdefence

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Decorative animated backdrop for the main menu: a static twinkling starfield plus a couple of
 * slow-moving shooting stars with colored sparkly tails that spawn periodically. Driven by a
 * ValueAnimator's per-frame ticks rather than a background thread, since this is purely cosmetic
 * and only ever runs while the menu Activity is in the foreground.
 */
class MenuBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private class Star(val x: Float, val y: Float, val radius: Float, val phase: Float, val speed: Float)

    /** Reused pool entry — fields are reset/reused on each spawn instead of allocating a new one. */
    private class ShootingStar {
        var active = false
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var color = Color.WHITE
        var age = 0f
        var lifespan = 1f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stars: List<Star> = emptyList()
    private val shootingStars = List(3) { ShootingStar() }
    private val rng = Random(System.nanoTime())
    private val trailColors = intArrayOf(
        Color.rgb(140, 210, 255), // pale blue
        Color.rgb(255, 150, 220), // pink
        Color.rgb(255, 214, 120), // gold
        Color.rgb(180, 160, 255)  // lavender
    )

    private var worldTime = 0f
    private var lastFrameNanos = 0L
    private var nextSpawnIn = 1.5f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = System.nanoTime()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        stars = generateStars(w.toFloat(), h.toFloat())
    }

    private fun generateStars(w: Float, h: Float): List<Star> {
        if (w <= 0f || h <= 0f) return emptyList()
        val list = mutableListOf<Star>()
        repeat(60) {
            val x = rng.nextFloat() * w
            val y = rng.nextFloat() * h
            val radius = 1f + rng.nextFloat() * 1.6f
            val phase = rng.nextFloat() * (2f * Math.PI).toFloat()
            val speed = 1.2f + rng.nextFloat() * 2.2f
            list.add(Star(x, y, radius, phase, speed))
        }
        return list
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        var dt = (now - lastFrameNanos) / 1_000_000_000f
        lastFrameNanos = now
        if (dt > 0.05f) dt = 0.05f // avoid a big jump after the view is paused/resumed
        worldTime += dt

        for (s in stars) {
            val twinkle = 0.5f + 0.5f * sin(worldTime * s.speed + s.phase)
            paint.color = Color.argb((70 + twinkle * 160).toInt(), 255, 255, 255)
            canvas.drawCircle(s.x, s.y, s.radius, paint)
        }

        updateAndDrawShootingStars(canvas, dt)
    }

    private fun updateAndDrawShootingStars(canvas: Canvas, dt: Float) {
        nextSpawnIn -= dt
        if (nextSpawnIn <= 0f) {
            spawnShootingStar()
            nextSpawnIn = 3.5f + rng.nextFloat() * 4.5f
        }

        for (star in shootingStars) {
            if (!star.active) continue
            star.age += dt
            star.x += star.vx * dt
            star.y += star.vy * dt
            if (star.age >= star.lifespan || star.x < -80f || star.x > width + 80f || star.y > height + 80f) {
                star.active = false
                continue
            }
            drawShootingStar(canvas, star)
        }
    }

    private fun spawnShootingStar() {
        val star = shootingStars.firstOrNull { !it.active } ?: return
        val fromLeft = rng.nextBoolean()
        star.x = if (fromLeft) -40f else width + 40f
        star.y = rng.nextFloat() * height * 0.55f
        val speed = 70f + rng.nextFloat() * 90f // slow drift, not a fast streak
        val angleDeg = 18f + rng.nextFloat() * 20f // shallow downward angle
        val dirX = if (fromLeft) 1f else -1f
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        star.vx = dirX * speed * cos(rad)
        star.vy = speed * sin(rad)
        star.color = trailColors[rng.nextInt(trailColors.size)]
        star.age = 0f
        star.lifespan = 4f + rng.nextFloat() * 2.5f
        star.active = true
    }

    /**
     * Draws the tail by walking backward along the star's straight-line velocity from its
     * current head position, instead of keeping a position-history buffer — cheap and
     * allocation-free every frame.
     */
    private fun drawShootingStar(canvas: Canvas, star: ShootingStar) {
        val fadeIn = (star.age / 0.6f).coerceIn(0f, 1f)
        val fadeOut = ((star.lifespan - star.age) / 0.8f).coerceIn(0f, 1f)
        val globalAlpha = minOf(fadeIn, fadeOut)
        if (globalAlpha <= 0.02f) return

        val r = Color.red(star.color)
        val g = Color.green(star.color)
        val b = Color.blue(star.color)

        val steps = 10
        val stepTime = 0.028f
        for (i in steps downTo 1) {
            val t = i * stepTime
            val px = star.x - star.vx * t
            val py = star.y - star.vy * t
            val frac = 1f - i.toFloat() / steps
            val radius = 1.2f + frac * 3.2f
            val alpha = (globalAlpha * frac * frac * 220).toInt().coerceIn(0, 255)
            paint.color = Color.argb(alpha, r, g, b)
            canvas.drawCircle(px, py, radius, paint)
        }
        // Bright sparkle at the head.
        paint.color = Color.argb((globalAlpha * 255).toInt(), 255, 255, 255)
        canvas.drawCircle(star.x, star.y, 2.6f, paint)
    }
}
