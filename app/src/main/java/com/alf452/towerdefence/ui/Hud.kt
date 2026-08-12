package com.alf452.towerdefence.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.alf452.towerdefence.game.GameEngine
import com.alf452.towerdefence.game.GameMath
import com.alf452.towerdefence.game.GameState

/**
 * Draws the health/shield bars, gold/wave readout, the between-wave upgrade
 * panel and the game-over panel, and hit-tests taps against whichever panel
 * is currently showing.
 */
class Hud {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private var wallButtonRect = RectF()
    private var cannonButtonRect = RectF()
    private var archerButtonRect = RectF()
    private var startWaveButtonRect = RectF()
    private var restartButtonRect = RectF()

    fun draw(canvas: Canvas, engine: GameEngine) {
        drawTopBars(canvas, engine)
        when (engine.state) {
            GameState.INTERMISSION -> drawUpgradePanel(canvas, engine)
            GameState.GAME_OVER -> drawGameOverPanel(canvas, engine)
            GameState.PLAYING -> {}
        }
    }

    private fun drawTopBars(canvas: Canvas, engine: GameEngine) {
        val w = engine.screenW
        val margin = 24f
        val barWidth = w - margin * 2f
        val barHeight = 22f
        var top = 40f

        // Health bar.
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRect(margin, top, margin + barWidth, top + barHeight, fillPaint)
        val healthRatio = GameMath.clamp(engine.castle.health / engine.castle.maxHealth, 0f, 1f)
        fillPaint.color = Color.rgb(210, 50, 50)
        canvas.drawRect(margin, top, margin + barWidth * healthRatio, top + barHeight, fillPaint)
        textPaint.textSize = 16f
        canvas.drawText(
            "Castle HP ${engine.castle.health.toInt()}/${engine.castle.maxHealth.toInt()}",
            margin + barWidth / 2f, top + barHeight - 5f, textPaint
        )
        top += barHeight + 8f

        // Shield bar.
        fillPaint.color = Color.argb(180, 0, 0, 0)
        canvas.drawRect(margin, top, margin + barWidth, top + barHeight, fillPaint)
        val shieldRatio = GameMath.clamp(engine.castle.shield / engine.castle.maxShield, 0f, 1f)
        fillPaint.color = Color.rgb(70, 140, 230)
        canvas.drawRect(margin, top, margin + barWidth * shieldRatio, top + barHeight, fillPaint)
        canvas.drawText(
            "Shield ${engine.castle.shield.toInt()}/${engine.castle.maxShield.toInt()}",
            margin + barWidth / 2f, top + barHeight - 5f, textPaint
        )
        top += barHeight + 14f

        // Wave + gold readout.
        textPaint.textSize = 26f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Wave ${engine.waveManager.waveNumber}", margin, top + 20f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Gold: ${engine.gold}", margin + barWidth, top + 20f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun drawUpgradePanel(canvas: Canvas, engine: GameEngine) {
        val w = engine.screenW
        val h = engine.screenH
        val panelTop = h * 0.58f
        val panelHeight = h - panelTop - 24f
        val panelRect = RectF(20f, panelTop, w - 20f, panelTop + panelHeight)

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(225, 20, 16, 32)
        canvas.drawRoundRect(panelRect, 20f, 20f, fillPaint)

        textPaint.textSize = 26f
        val title = if (engine.waveManager.waveNumber == 1) "Prepare your defenses" else "Wave ${engine.waveManager.waveNumber - 1} cleared! +${engine.lastWaveGoldEarned}g"
        canvas.drawText(title, w / 2f, panelRect.top + 34f, textPaint)

        val buttonHeight = 64f
        val buttonMargin = 16f
        var buttonTop = panelRect.top + 54f
        val buttonWidth = panelRect.width() - buttonMargin * 2f

        wallButtonRect = drawUpgradeButton(
            canvas, panelRect.left + buttonMargin, buttonTop, buttonWidth, buttonHeight,
            "Castle Walls (Lv ${engine.castle.wallLevel})", "Shield/HP up",
            engine.wallUpgradeCost(), engine.gold
        )
        buttonTop += buttonHeight + 12f

        cannonButtonRect = drawUpgradeButton(
            canvas, panelRect.left + buttonMargin, buttonTop, buttonWidth, buttonHeight,
            "Castle Cannons (Lv ${engine.cannonLevel})", "Damage/rate/range up",
            engine.cannonUpgradeCost(), engine.gold
        )
        buttonTop += buttonHeight + 12f

        archerButtonRect = drawUpgradeButton(
            canvas, panelRect.left + buttonMargin, buttonTop, buttonWidth, buttonHeight,
            "Archer Towers (Lv ${engine.archerLevel})", "Unlock/upgrade archers",
            engine.archerUpgradeCost(), engine.gold
        )
        buttonTop += buttonHeight + 20f

        startWaveButtonRect = RectF(panelRect.left + buttonMargin, buttonTop, panelRect.right - buttonMargin, buttonTop + buttonHeight)
        fillPaint.color = Color.rgb(60, 140, 70)
        canvas.drawRoundRect(startWaveButtonRect, 12f, 12f, fillPaint)
        textPaint.textSize = 24f
        canvas.drawText("Start Wave ${engine.waveManager.waveNumber}", startWaveButtonRect.centerX(), startWaveButtonRect.centerY() + 8f, textPaint)
    }

    private fun drawUpgradeButton(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        title: String, subtitle: String, cost: Int?, gold: Int
    ): RectF {
        val rect = RectF(left, top, left + width, top + height)
        val affordable = cost != null && gold >= cost
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = when {
            cost == null -> Color.rgb(70, 62, 90)
            affordable -> Color.rgb(58, 92, 140)
            else -> Color.rgb(50, 45, 60)
        }
        canvas.drawRoundRect(rect, 12f, 12f, fillPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 20f
        canvas.drawText(title, rect.left + 16f, rect.top + 26f, textPaint)
        textPaint.textSize = 15f
        textPaint.color = Color.argb(210, 255, 255, 255)
        canvas.drawText(subtitle, rect.left + 16f, rect.top + 48f, textPaint)
        textPaint.color = Color.WHITE

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 20f
        val costLabel = cost?.let { "${it}g" } ?: "MAX"
        canvas.drawText(costLabel, rect.right - 16f, rect.top + 38f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        return rect
    }

    private fun drawGameOverPanel(canvas: Canvas, engine: GameEngine) {
        val w = engine.screenW
        val h = engine.screenH
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(200, 10, 8, 16)
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        textPaint.textSize = 40f
        canvas.drawText("The Castle Has Fallen", w / 2f, h * 0.42f, textPaint)
        textPaint.textSize = 24f
        canvas.drawText("You survived to wave ${engine.waveManager.waveNumber}", w / 2f, h * 0.42f + 44f, textPaint)

        restartButtonRect = RectF(w / 2f - 110f, h * 0.55f, w / 2f + 110f, h * 0.55f + 64f)
        fillPaint.color = Color.rgb(60, 140, 70)
        canvas.drawRoundRect(restartButtonRect, 12f, 12f, fillPaint)
        textPaint.textSize = 24f
        canvas.drawText("Rebuild", restartButtonRect.centerX(), restartButtonRect.centerY() + 8f, textPaint)
    }

    fun handleTouch(x: Float, y: Float, engine: GameEngine) {
        when (engine.state) {
            GameState.INTERMISSION -> {
                when {
                    wallButtonRect.contains(x, y) -> engine.purchaseWallUpgrade()
                    cannonButtonRect.contains(x, y) -> engine.purchaseCannonUpgrade()
                    archerButtonRect.contains(x, y) -> engine.purchaseArcherUpgrade()
                    startWaveButtonRect.contains(x, y) -> engine.startNextWave()
                }
            }
            GameState.GAME_OVER -> {
                if (restartButtonRect.contains(x, y)) engine.restart()
            }
            GameState.PLAYING -> {}
        }
    }
}
