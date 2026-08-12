package com.alf452.towerdefence

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.alf452.towerdefence.game.ShootingStarField
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

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stars: List<Star> = emptyList()
    private val shootingStars = ShootingStarField(poolSize = 9)
    private val rng = Random(System.nanoTime())

    private var worldTime = 0f
    private var lastFrameNanos = 0L

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

        // The menu never applied a resolution-scale multiplier to shooting stars, so pass 1f
        // to keep speed/size exactly as before.
        shootingStars.update(dt, width.toFloat(), height.toFloat(), 1f)
        shootingStars.draw(canvas, paint, 1f)
    }
}
