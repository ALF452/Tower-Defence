package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * The medieval keep the player defends, with a round tower at each of its four corners. Shield
 * (from wall upgrades) absorbs damage before health does, and regenerates after a few seconds
 * without a hit.
 *
 * Rendering fakes a 3D, textured look within this 2D Canvas engine (no OpenGL/3D pipeline here)
 * via two combined tricks, applied to every stone surface: a procedurally-generated stone
 * [TextureFactory] bitmap fill for material grain, then a cached [RadialGradient] overlay pass —
 * bright near the center, darker toward the rim — for directional lighting. Since the whole game
 * is viewed from directly overhead, a radial "sunlit dome" gradient reads as a raised, top-lit
 * volume seen from above; the top-lit/bottom-shadowed *linear* gradient this used to be reads as
 * a wall's face lit from the side, which looked like the castle had been tipped on its edge. For
 * the same reason, turrets are drawn as concentric circles (an outer stone rim plus a smaller
 * green tiled-shingle roof-cap circle) rather than a tall rect with a peaked roof above it — a
 * round tower's actual footprint from directly above, not an elevation silhouette. Small repeated
 * elements (wall merlons) get a cheaper version — texture fill plus a flat highlight/shadow line
 * on their top/bottom edges — since a per-block repositioned gradient isn't worth the complexity
 * at that size.
 */
class Castle(var x: Float, var y: Float) {

    companion object {
        // Turret size, relative to castle radius — shared between draw()'s drawTurret() calls
        // and buildTurretLighting() below so the cached gradient's bounds can't drift out of sync
        // with the shape it's overlaid on. Turrets themselves sit at the keep rect's four corners
        // (computed in draw() from the keep RectF, not a fixed radius-relative offset).
        private const val TURRET_RADIUS_FACTOR = 0.3f
        // Roof cap size, relative to the turret's own radius — concentric with the turret's outer
        // rim (see drawTurret()) — shared with buildRoofLighting() for the same reason as above.
        private const val ROOF_RADIUS_FACTOR = 0.62f
    }

    var wallLevel = 1
        private set

    var maxHealth = 100f
        private set
    var health = maxHealth

    var maxShield = 70f
        private set
    var shield = maxShield

    // Set once, only via applyHealthShieldMultiplier() -- the Glass Cannon mutator's -30% castle
    // durability (see Mutator.kt). Baked into the maxHealth/maxShield formulas themselves (below
    // and in applyWallUpgrade()) rather than applied as a one-off post-hoc scale, so a wall
    // upgrade purchased mid-run recomputes from the already-reduced baseline instead of silently
    // undoing the mutator's effect.
    var healthShieldMultiplier = 1f
        private set

    private var shieldRegenPerSec = 1.5f
    private var timeSinceLastHit = 999f
    private val shieldRegenDelaySec = 3f

    // The Iron Will mutator (see Mutator.kt) zeroes the shield at each wave clear via
    // depleteShield() — without this flag, [update]'s unconditional regen (called every frame
    // regardless of game state, including the player-paced, unbounded intermission) would just
    // fill it back up long before the next wave starts, silently undoing the mutator's effect.
    // Only ever toggled by GameEngine around a PLAYING/INTERMISSION transition, not persisted
    // across waves the way [healthShieldMultiplier] is.
    var shieldRegenPaused = false
        private set

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
    private var roofTileShader: Shader = TextureFactory.shaderFor(TextureFactory.tile, visualScale)
    private var keepLighting: Shader = buildKeepLighting(radius)
    private var turretLighting: Shader = buildTurretLighting(radius)
    private var roofLighting: Shader = buildRoofLighting(radius)

    private var damageFlash = 0f
    private var flagPhase = 0f
    private var isDead = false

    // Shared by applyWallUpgrade() and applyHealthShieldMultiplier() so the two recompute paths
    // can never silently drift apart if the wall-level formula is ever retuned.
    private fun computeMaxHealth(): Float = (100f + (wallLevel - 1) * 15f) * healthShieldMultiplier
    private fun computeMaxShield(): Float = (40f + wallLevel * 30f) * healthShieldMultiplier

    fun applyWallUpgrade() {
        wallLevel++
        val prevMaxHealth = maxHealth
        val prevMaxShield = maxShield
        maxHealth = computeMaxHealth()
        maxShield = computeMaxShield()
        shieldRegenPerSec = 1f + wallLevel * 0.5f
        // Grant the newly unlocked capacity immediately instead of only on regen/heal.
        health += (maxHealth - prevMaxHealth)
        shield += (maxShield - prevMaxShield)
        shield = GameMath.clamp(shield, 0f, maxShield)
        health = GameMath.clamp(health, 0f, maxHealth)
    }

    /**
     * Applied once, right as wave 1 begins, if the Glass Cannon mutator is active for this run
     * (see [GameEngine.startNextWave]) — recomputes current max/health/shield from the same
     * formula [applyWallUpgrade] uses so it composes correctly with wallLevel, then grants/clamps
     * the delta exactly like a wall upgrade does.
     */
    fun applyHealthShieldMultiplier(multiplier: Float) {
        healthShieldMultiplier = multiplier
        val prevMaxHealth = maxHealth
        val prevMaxShield = maxShield
        maxHealth = computeMaxHealth()
        maxShield = computeMaxShield()
        health = GameMath.clamp(health + (maxHealth - prevMaxHealth), 0f, maxHealth)
        shield = GameMath.clamp(shield + (maxShield - prevMaxShield), 0f, maxShield)
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
        if (!isDead && !shieldRegenPaused && timeSinceLastHit > shieldRegenDelaySec && shield < maxShield) {
            shield = GameMath.clamp(shield + shieldRegenPerSec * dt, 0f, maxShield)
        }
    }

    fun healBetweenWaves() {
        // Small courtesy heal between rounds so upgrades feel meaningful.
        health = GameMath.clamp(health + maxHealth * 0.15f, 0f, maxHealth)
    }

    /**
     * The Iron Will mutator's between-wave penalty (see Mutator.kt) — paired with skipping
     * [healBetweenWaves] and pausing regen via [setShieldRegenPaused] so shield can't just
     * silently refill through the (player-paced, unbounded) intermission instead.
     */
    fun depleteShield() {
        shield = 0f
    }

    /** Only ever called by GameEngine around a PLAYING/INTERMISSION transition — see [depleteShield]. */
    fun setShieldRegenPaused(paused: Boolean) {
        shieldRegenPaused = paused
    }

    fun resetForNewGame() {
        wallLevel = 1
        healthShieldMultiplier = 1f
        shieldRegenPaused = false
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
        roofTileShader = TextureFactory.shaderFor(TextureFactory.tile, visualScale)
        keepLighting = buildKeepLighting(radius)
        turretLighting = buildTurretLighting(radius)
        roofLighting = buildRoofLighting(radius)
    }

    // All three gradients below are radial (bright center, dark rim) rather than top-to-bottom
    // linear, since the camera looks straight down — see the class doc comment. turretLighting
    // and roofLighting are centered at local (0,0) so the same cached instance is valid for both
    // the left and right turret, which draw() reaches via its own canvas.translate(cx, cy) rather
    // than baking an absolute offset into the shader itself.
    private fun buildKeepLighting(r: Float): Shader {
        // Centered at (0,0) — the keep rect built in draw() is vertically symmetric about the
        // castle's own center, so its midpoint is the origin.
        return RadialGradient(0f, 0f, r * 0.95f, Color.argb(80, 255, 255, 245), Color.argb(95, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    private fun buildTurretLighting(r: Float): Shader {
        val tr = r * TURRET_RADIUS_FACTOR
        return RadialGradient(0f, 0f, tr * 1.05f, Color.argb(80, 255, 255, 245), Color.argb(95, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    private fun buildRoofLighting(r: Float): Shader {
        val tr = r * TURRET_RADIUS_FACTOR
        val roofR = tr * ROOF_RADIUS_FACTOR
        return RadialGradient(0f, 0f, roofR * 1.1f, Color.argb(120, 210, 255, 220), Color.argb(120, 8, 40, 20), Shader.TileMode.CLAMP)
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

        // Ground shadow: a soft halo concentric with the castle, not offset to one side — an
        // offset shadow implies an angled light source off to that side (a side-view/isometric
        // cue), whereas the game's directly-overhead camera would see a raised structure's shadow
        // pooling evenly around its own base.
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawCircle(x, y, radius * 1.35f, paint)

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

        // Keep roof: stone texture, then a radial center-lit overlay so it reads as a raised,
        // sunlit volume seen from above rather than a flat rect. No coursing lines or door here —
        // both are wall-face details that would only be visible from the side, and a door in
        // particular reads unmistakably as "the building's front," not its roof. Vertically
        // symmetric about the origin (top -0.75r, bottom 0.75r) so the keep sits centered within
        // the surrounding curtain wall — it used to run -0.55r to 0.95r, offset down by 0.2r from
        // when the gate/flag layout below it needed the extra room; both are gone/relocated now.
        val keep = RectF(-radius * 0.55f, -radius * 0.75f, radius * 0.55f, radius * 0.75f)
        drawTexturedSurface(canvas, paint, keep.left, keep.top, keep.right, keep.bottom, 4f * visualScale, stoneShader, keepLighting)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Color.rgb(90, 80, 65)
        canvas.drawRoundRect(keep, 4f * visualScale, 4f * visualScale, paint)

        // Crenellations wrap all four rooftop edges — merlons on only the top edge would read as
        // "the top of a wall facing the camera," so every edge gets a matching row. Each row stops
        // short of the corners by turretMargin so the teeth don't run into the corner towers
        // (drawn afterward, below) rather than overlapping them.
        val toothSpan = keep.width() / 10f
        val turretR = radius * TURRET_RADIUS_FACTOR
        val turretMargin = turretR * 1.1f
        drawEdgeTeeth(canvas, paint, keep.left + turretMargin, keep.right - turretMargin, keep.top, -1f, toothSpan, horizontal = true)
        drawEdgeTeeth(canvas, paint, keep.left + turretMargin, keep.right - turretMargin, keep.bottom, 1f, toothSpan, horizontal = true)
        drawEdgeTeeth(canvas, paint, keep.top + turretMargin, keep.bottom - turretMargin, keep.left, -1f, toothSpan, horizontal = false)
        drawEdgeTeeth(canvas, paint, keep.top + turretMargin, keep.bottom - turretMargin, keep.right, 1f, toothSpan, horizontal = false)

        // Corner towers, drawn last so each one's clean round rim fully caps its corner instead of
        // the keep's straight edges/merlons cutting a chord across it — real corner towers replace
        // the wall's corner outright rather than poking out from behind it.
        drawTurret(canvas, paint, keep.left, keep.top, turretR, sw)
        drawTurret(canvas, paint, keep.right, keep.top, turretR, sw)
        drawTurret(canvas, paint, keep.left, keep.bottom, turretR, sw)
        drawTurret(canvas, paint, keep.right, keep.bottom, turretR, sw)

        // Flag, anchored just above the top edge's own merlon row.
        val flagBaseY = keep.top - toothSpan
        paint.color = Color.rgb(200, 188, 166)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * visualScale
        canvas.drawLine(0f, flagBaseY, 0f, flagBaseY - radius * 0.6f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(192, 57, 43)
        val flagWave = sin(flagPhase) * 6f * visualScale
        val flagPath = Path().apply {
            moveTo(0f, flagBaseY - radius * 0.6f)
            lineTo(radius * 0.5f + flagWave, flagBaseY - radius * 0.48f)
            lineTo(0f, flagBaseY - radius * 0.32f)
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

    /**
     * One edge's row of crenellation teeth ([drawBlock]s), each [toothSpan] wide, projecting
     * outward from the keep rect along [edgeCoord] by [sign] (-1 for the top/left edges, +1 for
     * bottom/right, so each edge's teeth point away from the rect's own interior). [horizontal]
     * distinguishes a left-right edge (top/bottom, teeth offset in Y) from a top-bottom edge
     * (left/right, teeth offset in X) — [draw] calls this for all four edges of the keep rect.
     * [toothSpan] is a shared caller-supplied size rather than being derived from this edge's own
     * axis length, so all four edges get teeth sized consistently with each other instead of the
     * taller left/right edges ending up with visibly larger merlons than top/bottom.
     */
    private fun drawEdgeTeeth(canvas: Canvas, paint: Paint, axisStart: Float, axisEnd: Float, edgeCoord: Float, sign: Float, toothSpan: Float, horizontal: Boolean) {
        val depth = sign * toothSpan
        val near = minOf(edgeCoord, edgeCoord + depth)
        val far = maxOf(edgeCoord, edgeCoord + depth)
        var pos = axisStart
        while (pos + toothSpan <= axisEnd + 0.01f) {
            if (horizontal) drawBlock(canvas, paint, pos, near, pos + toothSpan, far)
            else drawBlock(canvas, paint, near, pos, far, pos + toothSpan)
            pos += toothSpan * 2f
        }
    }

    /**
     * A round corner tower, drawn as two concentric circles — an outer stone rim (the tower's
     * wall thickness) and a smaller green tiled-shingle roof-cap circle (the coned roof's apex) —
     * rather than a tall rect with a peaked roof above it, so it reads as a tower's actual
     * footprint seen from directly above instead of an elevation silhouette. Drawn in its own
     * local space (translated by [cx],[cy], not baked into the shape coordinates) so the cached
     * [turretLighting]/[roofLighting] radial gradients — centered at local (0,0) — are valid for
     * all four corner towers from one shared instance each, the same trick a [TextureFactory]
     * shader gets "for free" since a repeating tile looks the same at any offset.
     *
     * Hand-rolled fill/lighting/tint sequence rather than routed through [drawTexturedSurface]
     * (which only draws rects) — generalizing that helper to arbitrary shapes would mean passing
     * it a draw-shape lambda, and allocating one of those on every [drawBlock] call (up to
     * ~50/frame for the wall's merlons) would reintroduce the per-frame allocation churn this file
     * otherwise avoids, just to save duplicating a few lines here.
     */
    private fun drawTurret(canvas: Canvas, paint: Paint, cx: Float, cy: Float, r: Float, sw: Float) {
        canvas.save()
        canvas.translate(cx, cy)

        paint.style = Paint.Style.FILL
        paint.shader = stoneShader
        canvas.drawCircle(0f, 0f, r, paint)
        paint.shader = turretLighting
        canvas.drawCircle(0f, 0f, r, paint)
        paint.shader = null
        if (damageFlash > 0f) {
            paint.color = damageTint()
            canvas.drawCircle(0f, 0f, r, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw
        paint.color = Color.rgb(90, 80, 65)
        canvas.drawCircle(0f, 0f, r, paint)

        val roofR = r * ROOF_RADIUS_FACTOR
        paint.style = Paint.Style.FILL
        paint.shader = roofTileShader
        canvas.drawCircle(0f, 0f, roofR, paint)
        paint.shader = roofLighting
        canvas.drawCircle(0f, 0f, roofR, paint)
        paint.shader = null
        if (damageFlash > 0f) {
            paint.color = damageTint()
            canvas.drawCircle(0f, 0f, roofR, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = sw * 0.7f
        paint.color = Color.rgb(16, 42, 26)
        canvas.drawCircle(0f, 0f, roofR, paint)

        canvas.restore()
    }
}
