package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The medieval keep the player defends. Shield (from wall upgrades) absorbs
 * damage before health does, and regenerates after a few seconds without a hit.
 */
class Castle(var x: Float, var y: Float) {

    var wallLevel = 1
        private set

    var maxHealth = 100f
        private set
    var health = maxHealth

    var maxShield = 70f
        private set
    var shield = maxShield

    private var shieldRegenPerSec = 1.5f
    private var timeSinceLastHit = 999f
    private val shieldRegenDelaySec = 3f

    var radius = 70f

    private var damageFlash = 0f
    private var flagPhase = 0f
    private var isDead = false

    fun applyWallUpgrade() {
        wallLevel++
        val prevMaxHealth = maxHealth
        val prevMaxShield = maxShield
        maxHealth = 100f + (wallLevel - 1) * 15f
        maxShield = 40f + wallLevel * 30f
        shieldRegenPerSec = 1f + wallLevel * 0.5f
        // Grant the newly unlocked capacity immediately instead of only on regen/heal.
        health += (maxHealth - prevMaxHealth)
        shield += (maxShield - prevMaxShield)
        shield = GameMath.clamp(shield, 0f, maxShield)
        health = GameMath.clamp(health, 0f, maxHealth)
    }

    fun wallUpgradeCost(): Int = (40 * Math.pow(1.45, (wallLevel - 1).toDouble())).toInt()

    fun takeDamage(amount: Float) {
        if (isDead) return
        timeSinceLastHit = 0f
        damageFlash = 1f
        var remaining = amount
        if (shield > 0f) {
            val absorbed = minOf(shield, remaining)
            shield -= absorbed
            remaining -= absorbed
        }
        if (remaining > 0f) {
            health -= remaining
        }
        if (health <= 0f) {
            health = 0f
            isDead = true
        }
    }

    fun isDestroyed(): Boolean = isDead

    fun update(dt: Float) {
        timeSinceLastHit += dt
        if (damageFlash > 0f) damageFlash = max(0f, damageFlash - dt * 2.5f)
        flagPhase += dt * 3f
        if (!isDead && timeSinceLastHit > shieldRegenDelaySec && shield < maxShield) {
            shield = GameMath.clamp(shield + shieldRegenPerSec * dt, 0f, maxShield)
        }
    }

    fun healBetweenWaves() {
        // Small courtesy heal between rounds so upgrades feel meaningful.
        health = GameMath.clamp(health + maxHealth * 0.15f, 0f, maxHealth)
    }

    fun resetForNewGame() {
        wallLevel = 1
        maxHealth = 100f
        health = maxHealth
        maxShield = 70f
        shield = maxShield
        shieldRegenPerSec = 1.5f
        timeSinceLastHit = 999f
        damageFlash = 0f
        isDead = false
    }

    fun draw(canvas: Canvas, paint: Paint) {
        val bodyColor = if (damageFlash > 0f) blend(Color.rgb(184, 170, 146), Color.RED, damageFlash) else Color.rgb(184, 170, 146)

        // Shield dome, opacity/size reflects remaining shield.
        if (shield > 0f) {
            val shieldRatio = shield / maxShield
            paint.color = Color.argb((70 * shieldRatio).toInt() + 25, 90, 170, 255)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius * 1.55f, paint)
            paint.color = Color.argb(180, 130, 200, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawCircle(x, y, radius * 1.55f, paint)
        }

        // Wall ring: number of visible battlements scales with wall level.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(120, 112, 98)
        val wallSegments = 8 + wallLevel * 2
        val wallOuter = radius * 1.25f
        for (i in 0 until wallSegments) {
            val angle = (2 * Math.PI * i / wallSegments).toFloat()
            val px = x + wallOuter * cos(angle)
            val py = y + wallOuter * sin(angle)
            canvas.drawRect(px - 6f, py - 6f, px + 6f, py + 6f, paint)
        }

        // Keep body.
        paint.color = bodyColor
        val keep = RectF(x - radius * 0.55f, y - radius * 0.55f, x + radius * 0.55f, y + radius * 0.95f)
        canvas.drawRect(keep, paint)

        // Crenellations on keep.
        paint.color = bodyColor
        val teeth = 5
        val toothW = keep.width() / (teeth * 2f)
        for (i in 0 until teeth) {
            val left = keep.left + toothW * (2 * i)
            canvas.drawRect(left, keep.top - toothW, left + toothW, keep.top, paint)
        }

        // Gate.
        paint.color = Color.rgb(43, 33, 64)
        canvas.drawRect(x - radius * 0.15f, y + radius * 0.35f, x + radius * 0.15f, keep.bottom, paint)

        // Flag.
        paint.color = Color.rgb(184, 170, 146)
        paint.strokeWidth = 3f
        canvas.drawLine(x, keep.top - toothW, x, keep.top - toothW - radius * 0.6f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(192, 57, 43)
        val flagWave = sin(flagPhase) * 6f
        canvas.drawPath(android.graphics.Path().apply {
            moveTo(x, keep.top - toothW - radius * 0.6f)
            lineTo(x + radius * 0.5f + flagWave, keep.top - toothW - radius * 0.48f)
            lineTo(x, keep.top - toothW - radius * 0.32f)
            close()
        }, paint)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val ft = GameMath.clamp(t, 0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * ft).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * ft).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ft).toInt()
        return Color.rgb(r, g, b)
    }
}
