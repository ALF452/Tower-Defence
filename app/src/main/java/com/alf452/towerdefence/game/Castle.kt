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
 *
 * Rendering fakes a 3D, textured look within this 2D Canvas engine (no OpenGL/3D pipeline here)
 * via two combined tricks, applied to every stone surface: a procedurally-generated stone
 * [TextureFactory] bitmap fill for material grain, then a cached top-lit/bottom-shadowed
 * [LinearGradient] overlay pass on the same shape for directional lighting, reading as a lit,
 * extruded volume instead of a flat color. Small repeated elements (wall merlons) get a cheaper
 * version — texture fill plus a flat highlight/shadow line on their top/bottom edges — since a
 * per-block repositioned gradient isn't worth the complexity at that size.
 */
class Castle(var x: Float, var y: Float) {

    companion object {
        // Turret placement/size, relative to radius — shared between draw()'s drawTurret() calls
        // and buildTurretLighting() below so the cached gradient's bounds can't drift out of sync
        // with the shape it's overlaid on.
        private const val TURRET_OFFSET_FACTOR = 0.85f
        private const val TURRET_CY_FACTOR = 0.3f
        private const val TURRET_RADIUS_FACTOR = 0.4f
    }

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

    /** Pixel radius of the keep, set from the device's resolution. Only changed via [updateVisualMetrics]. */
    var radius = 70f
        private set

    /**
     * Resolution-relative scale used for stroke widths and other small fixed constants. All of
     * [Castle]'s draw() geometry is position-independent (drawn relative to a canvas.translate(x, y)
     * at the top of [draw]), so the cached shaders/gradients below only depend on [radius] and
     * this scale. Only changed via [updateVisualMetrics].
     */
    var visualScale = 1f
        private set

    // Initializers call the same builder functions updateVisualMetrics() uses (with the
    // properties' own just-initialized default values) rather than hand-duplicated literals, so
    // the very first frame drawn can't silently drift from every frame after the first resize.
    private var stoneShader: Shader = TextureFactory.shaderFor(TextureFactory.stone, visualScale)
    private var woodShader: Shader = TextureFactory.shaderFor(TextureFactory.wood, visualScale)
    private var keepLighting: Shader = buildKeepLighting(radius)
    private var turretLighting: Shader = buildTurretLighting(radius)

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

    fun wallUpgradeCost(): Int = (40 * Math.pow(1.3, (wallLevel - 1).toDouble())).toInt()

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

    /**
     * Sets [radius] and [visualScale] together and rebuilds the cached shaders/gradients exactly
     * once — [GameEngine.onSurfaceSize] always changes both at the same time, so a single combined
     * setter avoids the wasted double-rebuild two independently-invalidating setters would cause.
     */
    fun updateVisualMetrics(newRadius: Float, newScale: Float) {
        if (newRadius == radius && newScale == visualScale) return
        radius = newRadius
        visualScale = newScale
        stoneShader = TextureFactory.shaderFor(TextureFactory.stone, visualScale)
        woodShader = TextureFactory.shaderFor(TextureFactory.wood, visualScale)
        keepLighting = buildKeepLighting(radius)
        turretLighting = buildTurretLighting(radius)
    }

    private fun buildKeepLighting(r: Float): Shader =
        LinearGradient(0f, -r * 0.55f, 0f, r * 0.95f, Color.argb(75, 255, 255, 245), Color.argb(100, 0, 0, 0), Shader.TileMode.CLAMP)

    private fun buildTurretLighting(r: Float): Shader {
        val tr = r * TURRET_RADIUS_FACTOR
        val cy = r * TURRET_CY_FACTOR
        return LinearGradient(0f, cy - tr * 1.2f, 0f, cy + tr, Color.argb(75, 255, 255, 245), Color.argb(100, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    /** A flat damage-flash tint, drawn as a final pass over a shape instead of blending it into a cached shader/texture. */
    private fun damageTint(): Int = Color.argb((damageFlash * 150).toInt(), 255, 55, 45)

    /**
     * Shared three-pass fill used by every stone surface (keep body, turret bodies, wall/keep
     * blocks): material texture, then a directional lighting overlay (or none, for the small
     * blocks that skip it), then an optional flat damage-flash tint — factored out so a future
     * change to how any of those three passes composite only needs updating in one place. Takes
     * explicit bounds rather than a RectF so the many-times-per-frame wall/crenellation blocks
     * don't need to allocate one just to call this. [cornerRadius] of 0 draws a plain rect,
     * matching Canvas's own drawRoundRect behavior.
     */
    private fun drawTexturedSurface(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float, cornerRadius: Float, materialShader: Shader, lightingShader: Shader?) {
        paint.style = Paint.Style.FILL
        paint.shader = materialShader
        canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, paint)
        if (lightingShader != null) {
            paint.shader = lightingShader
            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, paint)
        }
        if (damageFlash > 0f) {
            paint.shader = null
            paint.color = damageTint()
            canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, paint)
        }
        paint.shader = null
    }

    fun draw(canvas: Canvas, paint: Paint) {
        paint.shader = null
        val sw = 2f * visualScale

        // Ground shadow.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawOval(x - radius * 1.5f, y + radius * 0.9f, x + radius * 1.5f, y + radius * 1.25f, paint)

        canvas.save()
        canvas.translate(x, y)

        // Shield dome, opacity/size reflects remaining shield.
        if (shield > 0f) {
            val shieldRatio = shield / maxShield
            paint.color = Color.argb((70 * shieldRatio).toInt() + 25, 90, 170, 255)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(0f, 0f, radius * 1.55f, paint)
            paint.color = Color.argb(180, 130, 200, 255)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * visualScale
            canvas.drawCircle(0f, 0f, radius * 1.55f, paint)
        }

        // Curtain wall: a textured ring band with merlon blocks on top, count scales with wall level.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 14f * visualScale
        paint.shader = stoneShader
        canvas.drawCircle(0f, 0f, radius * 1.25f, paint)
        paint.shader = null

        val wallSegments = 10 + wallLevel * 2
        val wallOuter = radius * 1.25f
        for (i in 0 until wallSegments) {
            val angle = (2 * Math.PI * i / wallSegments).toFloat()
            val px = wallOuter * cos(angle)
            val py = wallOuter * sin(angle)
            drawBlock(canvas, paint, px - 6f * visualScale, py - 8f * visualScale, px + 6f * visualScale, py + 2f * visualScale)
        }

        // Flanking corner turrets with conical roofs.
        val turretCy = radius * TURRET_CY_FACTOR
        val turretR = radius * TURRET_RADIUS_FACTOR
        drawTurret(canvas, paint, -radius * TURRET_OFFSET_FACTOR, turretCy, turretR, sw)
        drawTurret(canvas, paint, radius * TURRET_OFFSET_FACTOR, turretCy, turretR, sw)

        // Keep body: stone texture, then a top-lit/bottom-shadowed overlay so it reads as a lit
        // extruded volume rather than a flat rect.
        val keep = RectF(-radius * 0.55f, -radius * 0.55f, radius * 0.55f, radius * 0.95f)
        drawTexturedSurface(canvas, paint, keep.left, keep.top, keep.right, keep.bottom, 4f * visualScale, stoneShader, keepLighting)

        // Stone coursing lines for a bit of extra texture detail.
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

        // Crenellations on the keep, textured/beveled like the wall's merlon blocks.
        val teeth = 5
        val toothW = keep.width() / (teeth * 2f)
        for (i in 0 until teeth) {
            val left = keep.left + toothW * (2 * i)
            drawBlock(canvas, paint, left, keep.top - toothW, left + toothW, keep.top)
        }

        // Gate: wood-grain texture with a couple of iron studs for hardware detail.
        val gate = RectF(-radius * 0.16f, radius * 0.35f, radius * 0.16f, keep.bottom)
        paint.style = Paint.Style.FILL
        paint.shader = woodShader
        canvas.drawRoundRect(gate, gate.width() * 0.4f, gate.width() * 0.4f, paint)
        paint.shader = null
        paint.color = Color.rgb(40, 38, 36)
        val studR = 1.4f * visualScale
        for (sx in floatArrayOf(gate.left + gate.width() * 0.28f, gate.right - gate.width() * 0.28f)) {
            for (sy in floatArrayOf(gate.top + gate.height() * 0.3f, gate.top + gate.height() * 0.7f)) {
                canvas.drawCircle(sx, sy, studR, paint)
                paint.color = Color.argb(140, 200, 195, 190)
                canvas.drawCircle(sx - studR * 0.3f, sy - studR * 0.3f, studR * 0.35f, paint)
                paint.color = Color.rgb(40, 38, 36)
            }
        }

        // Flag.
        paint.color = Color.rgb(200, 188, 166)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * visualScale
        canvas.drawLine(0f, keep.top - toothW, 0f, keep.top - toothW - radius * 0.6f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(192, 57, 43)
        val flagWave = sin(flagPhase) * 6f * visualScale
        val flagPath = Path().apply {
            moveTo(0f, keep.top - toothW - radius * 0.6f)
            lineTo(radius * 0.5f + flagWave, keep.top - toothW - radius * 0.48f)
            lineTo(0f, keep.top - toothW - radius * 0.32f)
            close()
        }
        canvas.drawPath(flagPath, paint)

        canvas.restore()
        paint.shader = null
    }

    /** A small stone block (wall merlon / keep crenellation), textured with a light top edge and dark bottom edge for a cheap 3D-extruded look. */
    private fun drawBlock(canvas: Canvas, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        drawTexturedSurface(canvas, paint, left, top, right, bottom, 0f, stoneShader, null)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f * visualScale
        paint.color = Color.argb(150, 225, 215, 195)
        canvas.drawLine(left, top, right, top, paint)
        paint.color = Color.argb(120, 40, 34, 26)
        canvas.drawLine(left, bottom, right, bottom, paint)
    }

    private fun drawTurret(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, sw: Float) {
        val body = RectF(cx - r * 0.55f, cy - r * 1.2f, cx + r * 0.55f, cy + r)
        drawTexturedSurface(canvas, paint, body.left, body.top, body.right, body.bottom, r * 0.2f, stoneShader, turretLighting)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Color.rgb(90, 80, 65)
        canvas.drawRoundRect(body, r * 0.2f, r * 0.2f, paint)

        // Conical roof, split into lit/shadow halves for a 3D-cone read without needing a shader.
        val apexX = cx
        val apexY = cy - r * 2.1f
        val baseY = cy - r * 1.2f
        paint.style = Paint.Style.FILL
        val roofLit = Path().apply {
            moveTo(apexX, apexY)
            lineTo(cx - r * 0.7f, baseY)
            lineTo(cx, baseY)
            close()
        }
        val roofShadow = Path().apply {
            moveTo(apexX, apexY)
            lineTo(cx, baseY)
            lineTo(cx + r * 0.7f, baseY)
            close()
        }
        paint.color = Color.rgb(150, 64, 58)
        canvas.drawPath(roofLit, paint)
        paint.color = Color.rgb(95, 38, 34)
        canvas.drawPath(roofShadow, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw * 0.7f
        paint.color = Color.rgb(55, 22, 19)
        canvas.drawPath(roofLit, paint)
        canvas.drawPath(roofShadow, paint)
    }
}
