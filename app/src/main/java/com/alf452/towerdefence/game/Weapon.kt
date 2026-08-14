package com.alf452.towerdefence.game

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max

enum class WeaponType { CANNON, ARCHER }

/**
 * A single mounted weapon slot on the castle ring. Stats are pushed in from
 * [com.alf452.towerdefence.game.GameEngine] whenever the owning category is upgraded;
 * this class only handles per-frame targeting, firing and its own fire animation.
 *
 * Draws use the same pseudo-3D trick as [Castle]: a [TextureFactory] material bitmap fill for
 * grain (stone for the mount socket, since it's built into the castle wall; metal for the cannon
 * barrel; wood for the bow), then a cached, now-translucent [RadialGradient]/highlight overlay
 * pass so the texture still shows through the directional shading.
 */
class WeaponSlot(val type: WeaponType, val angleDeg: Float) {
    var unlocked = false

    var damage = 0f
    var fireIntervalSec = 1f
    var range = 0f
    var splashRadius = 0f
    /**
     * Resolution-relative scale so the weapon reads at a consistent size across devices.
     * Mount/barrel textures and lighting overlays only depend on this value, so they're cached
     * and only rebuilt when it actually changes (e.g. on a surface resize), instead of every
     * draw() call.
     */
    var visualScale = 1f
        set(value) {
            if (field == value) return
            field = value
            mountShader = TextureFactory.shaderFor(TextureFactory.stone, value)
            barrelShader = TextureFactory.shaderFor(TextureFactory.metal, value)
            bowShader = TextureFactory.shaderFor(TextureFactory.wood, value)
            mountLighting = buildMountLighting(value)
            barrelLighting = buildBarrelLighting(value)
        }
    // Initializers call the same builder functions the setter above does (with visualScale's own
    // just-initialized default), instead of hand-duplicated literals, so the very first frame
    // drawn can't silently drift from every frame after the first resize.
    private var mountShader: Shader = TextureFactory.shaderFor(TextureFactory.stone, visualScale)
    private var barrelShader: Shader = TextureFactory.shaderFor(TextureFactory.metal, visualScale)
    private var bowShader: Shader = TextureFactory.shaderFor(TextureFactory.wood, visualScale)
    private var mountLighting: Shader = buildMountLighting(visualScale)
    private var barrelLighting: Shader = buildBarrelLighting(visualScale)

    private fun buildMountLighting(scale: Float): Shader =
        RadialGradient(-3f * scale, -3f * scale, 16f * scale, Color.argb(150, 200, 195, 185), Color.argb(170, 30, 26, 20), Shader.TileMode.CLAMP)

    private fun buildBarrelLighting(scale: Float): Shader =
        RadialGradient(0f, -6f * scale, 20f * scale, Color.argb(140, 220, 220, 225), Color.argb(180, 5, 5, 8), Shader.TileMode.CLAMP)
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

    fun update(dt: Float, castle: Castle, ringRadius: Float, zombies: List<Zombie>, fireRateMultiplier: Float, onFire: (WeaponSlot, Zombie, Float, Float) -> Unit) {
        if (!unlocked) return
        recoil = max(0f, recoil - dt * 4f)
        muzzleFlash = max(0f, muzzleFlash - dt * 6f)
        drawBack = max(0f, drawBack - dt * 3f)
        // Overcharge (see GameEngine's overchargeFireRateMultiplier) counts cooldown down faster
        // rather than shrinking fireIntervalSec itself, so the buff cleanly reverts to the
        // slot's normal rate the instant it expires with no stat recompute needed.
        cooldown -= dt * fireRateMultiplier

        val pos = ringPosition(castle, ringRadius)
        var target: Zombie? = null
        var bestDist = Float.MAX_VALUE
        for (z in zombies) {
            if (!z.isAlive() || !z.canBeTargetedBy(type)) continue
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
        val s = visualScale

        canvas.save()
        canvas.translate(px, py)
        canvas.rotate(Math.toDegrees(turretAngle.toDouble()).toFloat())

        // Base mount: stone texture (it's built into the castle wall) plus a translucent radial
        // highlight/shadow overlay so it still reads as a rounded socket, not a flat disc.
        paint.style = Paint.Style.FILL
        paint.shader = mountShader
        canvas.drawCircle(0f, 0f, 14f * s, paint)
        paint.shader = mountLighting
        canvas.drawCircle(0f, 0f, 14f * s, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * s
        paint.color = Color.rgb(50, 45, 38)
        canvas.drawCircle(0f, 0f, 14f * s, paint)

        when (type) {
            WeaponType.CANNON -> {
                val recoilOffset = recoil * 6f * s
                val barrel = RectF(-6f * s - recoilOffset, -8f * s, 24f * s - recoilOffset, 8f * s)
                paint.shader = barrelShader
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(barrel, 4f * s, 4f * s, paint)
                paint.shader = barrelLighting
                canvas.drawRoundRect(barrel, 4f * s, 4f * s, paint)
                paint.shader = null
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f * s
                paint.color = Color.rgb(15, 15, 18)
                canvas.drawRoundRect(barrel, 4f * s, 4f * s, paint)

                // A bright highlight stripe down the barrel's centerline — a top-down cylinder
                // reads as round when it has a specular streak, without needing real 3D geometry.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * s
                paint.color = Color.argb(120, 220, 220, 225)
                canvas.drawLine(barrel.left + 3f * s, 0f, barrel.right - 4f * s, 0f, paint)

                // Muzzle rim.
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(18, 18, 20)
                canvas.drawCircle(barrel.right, 0f, 5f * s, paint)

                if (muzzleFlash > 0f) {
                    paint.color = Color.argb((muzzleFlash * 220).toInt(), 255, 200, 90)
                    canvas.drawCircle(barrel.right + 6f * s, 0f, 10f * s * muzzleFlash, paint)
                }
            }
            WeaponType.ARCHER -> {
                paint.shader = null
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(120, 90, 60)
                canvas.drawRoundRect(-3f * s, -16f * s, 3f * s, 16f * s, 2f * s, 2f * s, paint)

                val pull = 8f * s * drawBack
                val tipTopX = -2f * s
                val tipTopY = -16f * s
                val tipBottomX = -2f * s
                val tipBottomY = 16f * s
                val bowBulgeX = -16f * s

                val bowPath = Path().apply {
                    moveTo(tipTopX, tipTopY)
                    quadTo(bowBulgeX, 0f, tipBottomX, tipBottomY)
                }
                // Wood-grain limb (thick textured stroke) with a thin highlight streak down the
                // same curve for a rounded-wood look, instead of a flat single-color line.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f * s
                paint.shader = bowShader
                canvas.drawPath(bowPath, paint)
                paint.shader = null
                paint.strokeWidth = 1.3f * s
                paint.color = Color.argb(150, 255, 235, 200)
                canvas.drawPath(bowPath, paint)

                // Bowstring, pulled back toward the archer while winding up to fire.
                paint.strokeWidth = 1.6f * s
                paint.color = Color.rgb(230, 225, 210)
                canvas.drawLine(tipTopX, tipTopY, -pull, 0f, paint)
                canvas.drawLine(tipBottomX, tipBottomY, -pull, 0f, paint)

                if (drawBack > 0.05f) {
                    paint.strokeWidth = 1.5f * s
                    canvas.drawLine(-pull, 0f, 18f * s, 0f, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.rgb(90, 70, 50)
                    canvas.drawCircle(18f * s, 0f, 2.2f * s, paint)
                }
            }
        }

        canvas.restore()
        paint.shader = null
    }

    fun rangePreviewRadius(): Float = range
}
