package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
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

    /** Pixel radius of the keep; set by [GameEngine] from the device's resolution. */
    var radius = 70f
    /** Resolution-relative scale used for stroke widths and other small fixed constants. */
    var visualScale = 1f

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
        paint.shader = null
        val sw = 2f * visualScale

        // Ground shadow.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawOval(x - radius * 1.5f, y + radius * 0.9f, x + radius * 1.5f, y + radius * 1.25f, paint)

        val bodyTop = if (damageFlash > 0f) blend(Color.rgb(200, 188, 166), Color.RED, damageFlash) else Color.rgb(200, 188, 166)
        val bodyBottom = if (damageFlash > 0f) blend(Color.rgb(150, 138, 118), Color.RED, damageFlash) else Color.rgb(150, 138, 118)

        // Shield dome, opacity/size reflects remaining shield.
        if (shield > 0f) {
            val shieldRatio = shield / maxShield
            paint.color = Color.argb((70 * shieldRatio).toInt() + 25, 90, 170, 255)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x, y, radius * 1.55f, paint)
            paint.color = Color.argb(180, 130, 200, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * visualScale
            canvas.drawCircle(x, y, radius * 1.55f, paint)
        }

        // Curtain wall: a solid ring band with merlon blocks on top, count scales with wall level.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 14f * visualScale
        paint.color = Color.rgb(126, 118, 102)
        canvas.drawCircle(x, y, radius * 1.25f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(142, 134, 116)
        val wallSegments = 10 + wallLevel * 2
        val wallOuter = radius * 1.25f
        for (i in 0 until wallSegments) {
            val angle = (2 * Math.PI * i / wallSegments).toFloat()
            val px = x + wallOuter * cos(angle)
            val py = y + wallOuter * sin(angle)
            canvas.drawRect(px - 6f * visualScale, py - 8f * visualScale, px + 6f * visualScale, py + 2f * visualScale, paint)
        }

        // Flanking corner turrets with conical roofs.
        drawTurret(canvas, paint, x - radius * 0.85f, y + radius * 0.3f, radius * 0.4f, bodyTop, bodyBottom, sw)
        drawTurret(canvas, paint, x + radius * 0.85f, y + radius * 0.3f, radius * 0.4f, bodyTop, bodyBottom, sw)

        // Keep body, shaded with a vertical gradient for a stone-block look.
        val keep = RectF(x - radius * 0.55f, y - radius * 0.55f, x + radius * 0.55f, y + radius * 0.95f)
        paint.shader = LinearGradient(0f, keep.top, 0f, keep.bottom, bodyTop, bodyBottom, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(keep, 4f * visualScale, 4f * visualScale, paint)
        paint.shader = null

        // Stone coursing lines for a bit of texture.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * visualScale
        paint.color = Color.argb(60, 60, 50, 40)
        val courses = 3
        for (i in 1..courses) {
            val ly = keep.top + keep.height() * i / (courses + 1f)
            canvas.drawLine(keep.left, ly, keep.right, ly, paint)
        }

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Color.rgb(90, 80, 65)
        canvas.drawRoundRect(keep, 4f * visualScale, 4f * visualScale, paint)

        // Crenellations on the keep.
        paint.style = Paint.Style.FILL
        paint.color = bodyTop
        val teeth = 5
        val toothW = keep.width() / (teeth * 2f)
        for (i in 0 until teeth) {
            val left = keep.left + toothW * (2 * i)
            canvas.drawRect(left, keep.top - toothW, left + toothW, keep.top, paint)
        }

        // Gate.
        paint.color = Color.rgb(38, 30, 24)
        val gate = RectF(x - radius * 0.16f, y + radius * 0.35f, x + radius * 0.16f, keep.bottom)
        canvas.drawRoundRect(gate, gate.width() * 0.4f, gate.width() * 0.4f, paint)

        // Flag.
        paint.color = Color.rgb(200, 188, 166)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * visualScale
        canvas.drawLine(x, keep.top - toothW, x, keep.top - toothW - radius * 0.6f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(192, 57, 43)
        val flagWave = sin(flagPhase) * 6f * visualScale
        val flagPath = Path().apply {
            moveTo(x, keep.top - toothW - radius * 0.6f)
            lineTo(x + radius * 0.5f + flagWave, keep.top - toothW - radius * 0.48f)
            lineTo(x, keep.top - toothW - radius * 0.32f)
            close()
        }
        canvas.drawPath(flagPath, paint)
    }

    private fun drawTurret(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, topColor: Int, bottomColor: Int, sw: Float) {
        val body = RectF(cx - r * 0.55f, cy - r * 1.2f, cx + r * 0.55f, cy + r)
        paint.shader = LinearGradient(0f, body.top, 0f, body.bottom, topColor, bottomColor, Shader.TileMode.CLAMP)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(body, r * 0.2f, r * 0.2f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Color.rgb(90, 80, 65)
        canvas.drawRoundRect(body, r * 0.2f, r * 0.2f, paint)

        // Conical roof.
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(120, 50, 46)
        val roof = Path().apply {
            moveTo(cx - r * 0.7f, cy - r * 1.2f)
            lineTo(cx + r * 0.7f, cy - r * 1.2f)
            lineTo(cx, cy - r * 2.1f)
            close()
        }
        canvas.drawPath(roof, paint)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val ft = GameMath.clamp(t, 0f, 1f)
        val r = (Color.red(from) + (Color.red(to) - Color.red(from)) * ft).toInt()
        val g = (Color.green(from) + (Color.green(to) - Color.green(from)) * ft).toInt()
        val b = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * ft).toInt()
        return Color.rgb(r, g, b)
    }
}
