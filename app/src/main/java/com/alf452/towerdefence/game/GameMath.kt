package com.alf452.towerdefence.game

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GameMath {

    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    fun angleTo(fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
        return atan2(toY - fromY, toX - fromX)
    }

    fun pointOnCircle(cx: Float, cy: Float, radius: Float, angleRad: Float): FloatArray {
        return floatArrayOf(cx + radius * cos(angleRad), cy + radius * sin(angleRad))
    }

    /** Shortest-path angle lerp, result in radians normalised to [-PI, PI]. */
    fun lerpAngle(current: Float, target: Float, maxDelta: Float): Float {
        var diff = normalizeAngle(target - current)
        if (diff > maxDelta) diff = maxDelta
        if (diff < -maxDelta) diff = -maxDelta
        return normalizeAngle(current + diff)
    }

    fun normalizeAngle(angle: Float): Float {
        var a = angle
        while (a > PI) a -= (2 * PI).toFloat()
        while (a < -PI) a += (2 * PI).toFloat()
        return a
    }

    fun clamp(value: Float, min: Float, max: Float): Float {
        return if (value < min) min else if (value > max) max else value
    }

    fun clampInt(value: Int, min: Int, max: Int): Int {
        return if (value < min) min else if (value > max) max else value
    }

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
