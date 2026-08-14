package com.alf452.towerdefence.game

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import kotlin.random.Random

/**
 * Procedurally synthesizes small tileable material textures — mottled stone, wood grain, brushed
 * metal, and green tiled roof shingles — once per process and caches them here rather than
 * per-instance, so the castle/wall/weapon visuals read as real materials instead of flat
 * single-color fills. This game has no external art assets at all; textures follow the same
 * "generated in code" rule as everything else (see [com.alf452.towerdefence.audio.SoundEngine]'s
 * synthesized chiptune SFX).
 *
 * Callers wrap a cached [Bitmap] in their own [BitmapShader] via [shaderFor], scaled to the
 * device's resolution — these bitmaps are small fixed-resolution tiles meant to repeat, not
 * full-size images, and [Shader.TileMode.REPEAT] handles the actual tiling.
 */
object TextureFactory {
    private const val TILE = 40

    // Base seed for each buildX() below, offset per texture (+1/+2/+3/+4) so their draw
    // sequences are independent rather than all replaying the identical Random(TEXTURE_SEED)
    // sequence — see the comment in buildStone() for why each gets its own Random at all.
    private const val TEXTURE_SEED = 20260813L

    val stone: Bitmap by lazy { buildStone() }
    val wood: Bitmap by lazy { buildWood() }
    val metal: Bitmap by lazy { buildMetal() }
    val tile: Bitmap by lazy { buildTile() }

    /** Wraps [bitmap] in a repeating shader scaled so its grain reads consistently across device resolutions. */
    fun shaderFor(bitmap: Bitmap, visualScale: Float): BitmapShader {
        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val matrix = Matrix()
        matrix.setScale(visualScale, visualScale)
        shader.setLocalMatrix(matrix)
        return shader
    }

    private fun newTile(): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888)
        return bmp to Canvas(bmp)
    }

    private fun buildStone(): Bitmap {
        // Each texture gets its own fixed-seed Random rather than sharing one instance, so a
        // texture's grain is stable regardless of which property callers happen to touch first —
        // a shared instance would make e.g. wood's pattern depend on whether stone was already
        // accessed (and had already consumed some draws from the shared sequence). Offset by +1
        // from the other two so their draw sequences aren't identical replays of each other.
        val rng = Random(TEXTURE_SEED + 1)
        val (bmp, canvas) = newTile()
        val paint = Paint()
        paint.color = Color.rgb(150, 140, 122)
        canvas.drawRect(0f, 0f, TILE.toFloat(), TILE.toFloat(), paint)
        // Mottled speckle flecks, scattered across the tile.
        repeat(90) {
            val px = rng.nextFloat() * TILE
            val py = rng.nextFloat() * TILE
            val r = 0.8f + rng.nextFloat() * 1.8f
            val dark = rng.nextBoolean()
            paint.color = if (dark) Color.argb(80, 40, 34, 26) else Color.argb(70, 235, 225, 205)
            canvas.drawCircle(px, py, r, paint)
        }
        // Loose brick coursing: horizontal mortar lines spanning the full tile width so they
        // tile seamlessly left-to-right.
        paint.color = Color.argb(100, 60, 50, 38)
        paint.strokeWidth = 1f
        for (row in 1..2) {
            val ly = row * TILE / 3f
            canvas.drawLine(0f, ly, TILE.toFloat(), ly, paint)
        }
        return bmp
    }

    private fun buildWood(): Bitmap {
        val rng = Random(TEXTURE_SEED + 2)
        val (bmp, canvas) = newTile()
        val paint = Paint()
        paint.color = Color.rgb(118, 84, 52)
        canvas.drawRect(0f, 0f, TILE.toFloat(), TILE.toFloat(), paint)
        // Vertical grain streaks, full tile height so they tile seamlessly top-to-bottom.
        var gx = 0f
        while (gx < TILE) {
            val w = 1.2f + rng.nextFloat() * 2.2f
            val dark = rng.nextBoolean()
            paint.color = if (dark) Color.argb(70, 60, 38, 20) else Color.argb(55, 170, 130, 90)
            canvas.drawRect(gx, 0f, gx + w, TILE.toFloat(), paint)
            gx += w + 1.5f + rng.nextFloat() * 2f
        }
        return bmp
    }

    private fun buildMetal(): Bitmap {
        val rng = Random(TEXTURE_SEED + 3)
        val (bmp, canvas) = newTile()
        val paint = Paint()
        paint.color = Color.rgb(58, 58, 64)
        canvas.drawRect(0f, 0f, TILE.toFloat(), TILE.toFloat(), paint)
        // Brushed-metal horizontal streaks, full tile width so they tile seamlessly.
        var gy = 0f
        while (gy < TILE) {
            val w = 0.8f + rng.nextFloat() * 1.4f
            val light = rng.nextBoolean()
            paint.color = if (light) Color.argb(50, 150, 150, 158) else Color.argb(60, 10, 10, 14)
            canvas.drawRect(0f, gy, TILE.toFloat(), gy + w, paint)
            gy += w + 1f + rng.nextFloat() * 1.6f
        }
        return bmp
    }

    private fun buildTile(): Bitmap {
        val rng = Random(TEXTURE_SEED + 4)
        val (bmp, canvas) = newTile()
        val paint = Paint()
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(35, 95, 62)
        canvas.drawRect(0f, 0f, TILE.toFloat(), TILE.toFloat(), paint)
        // Overlapping scalloped shingle rows: each shingle is a half-disc (an arc filled against
        // its own chord, i.e. useCenter=false), alternating rows offset by half a shingle width
        // like real roof tiles, with the top of each row overdrawn by the row below so the
        // scallops read as overlapping. Rows/columns run a little past the tile edges so nothing
        // gets clipped off mid-shingle at the repeat seam.
        val shingleW = TILE / 4f
        val rowH = TILE / 4.5f
        var row = 0
        var ly = -rowH
        while (ly < TILE + rowH) {
            val offset = if (row % 2 == 0) 0f else shingleW / 2f
            var lx = -shingleW + offset
            while (lx < TILE + shingleW) {
                val shade = rng.nextInt(-10, 11)
                paint.color = Color.rgb(46 + shade, 122 + shade, 82 + shade)
                canvas.drawArc(lx, ly, lx + shingleW, ly + rowH * 1.6f, 0f, 180f, false, paint)
                lx += shingleW
            }
            ly += rowH * 0.75f
            row++
        }
        return bmp
    }
}
