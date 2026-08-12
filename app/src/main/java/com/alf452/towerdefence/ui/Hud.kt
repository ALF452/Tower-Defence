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
 * popup and the game-over popup, and hit-tests taps against whichever popup
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
    private var explosiveButtonRect = RectF()
    private var slowButtonRect = RectF()
    private var bleedButtonRect = RectF()
    private var startWaveButtonRect = RectF()
    private var restartButtonRect = RectF()

    fun draw(canvas: Canvas, engine: GameEngine) {
        drawTopBars(canvas, engine)
        when (engine.state) {
            GameState.INTERMISSION -> drawUpgradePopup(canvas, engine)
            GameState.GAME_OVER -> drawGameOverPopup(canvas, engine)
            GameState.PLAYING -> {}
        }
    }

    private fun drawTopBars(canvas: Canvas, engine: GameEngine) {
        val s = engine.scale
        val w = engine.screenW
        val margin = 20f * s
        val barWidth = w - margin * 2f
        val barHeight = 24f * s
        var top = 28f * s

        // Health bar.
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(190, 0, 0, 0)
        canvas.drawRoundRect(margin, top, margin + barWidth, top + barHeight, barHeight / 2f, barHeight / 2f, fillPaint)
        val healthRatio = GameMath.clamp(engine.castle.health / engine.castle.maxHealth, 0f, 1f)
        fillPaint.color = Color.rgb(210, 50, 50)
        if (healthRatio > 0f) {
            canvas.drawRoundRect(margin, top, margin + barWidth * healthRatio, top + barHeight, barHeight / 2f, barHeight / 2f, fillPaint)
        }
        textPaint.textSize = 16f * s
        canvas.drawText(
            "Castle HP ${engine.castle.health.toInt()}/${engine.castle.maxHealth.toInt()}",
            margin + barWidth / 2f, top + barHeight * 0.7f, textPaint
        )
        top += barHeight + 8f * s

        // Shield bar.
        fillPaint.color = Color.argb(190, 0, 0, 0)
        canvas.drawRoundRect(margin, top, margin + barWidth, top + barHeight, barHeight / 2f, barHeight / 2f, fillPaint)
        val shieldRatio = GameMath.clamp(engine.castle.shield / engine.castle.maxShield, 0f, 1f)
        fillPaint.color = Color.rgb(70, 140, 230)
        if (shieldRatio > 0f) {
            canvas.drawRoundRect(margin, top, margin + barWidth * shieldRatio, top + barHeight, barHeight / 2f, barHeight / 2f, fillPaint)
        }
        canvas.drawText(
            "Shield ${engine.castle.shield.toInt()}/${engine.castle.maxShield.toInt()}",
            margin + barWidth / 2f, top + barHeight * 0.7f, textPaint
        )
        top += barHeight + 16f * s

        // Wave/gold readout as two independently-sized pill chips, each anchored to
        // its own screen edge and sized to its own text — they can never collide
        // with each other no matter how many digits either number grows to.
        textPaint.textSize = 20f * s
        val chipHeight = 34f * s
        val waveText = "Wave ${engine.waveManager.waveNumber}"
        val waveChipWidth = textPaint.measureText(waveText) + 28f * s
        drawChip(canvas, margin, top, waveChipWidth, chipHeight, waveText)

        val goldText = "Gold: ${engine.gold}"
        val goldChipWidth = textPaint.measureText(goldText) + 28f * s
        drawChip(canvas, w - margin - goldChipWidth, top, goldChipWidth, chipHeight, goldText)
    }

    private fun drawChip(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, text: String) {
        val rect = RectF(left, top, left + width, top + height)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(200, 22, 18, 34)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, fillPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, rect.centerX(), rect.centerY() + textPaint.textSize * 0.35f, textPaint)
    }

    private fun drawUpgradePopup(canvas: Canvas, engine: GameEngine) {
        val s = engine.scale
        val w = engine.screenW
        val h = engine.screenH
        val unlocked = engine.specializationsUnlocked()

        // Dim the paused battlefield behind the popup so it reads as a modal.
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(165, 8, 6, 14)
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        var buttonHeight = 58f * s
        var rowGap = 12f * s
        var headerHeight = 74f * s
        var dividerGap = 30f * s
        var finalGap = 20f * s

        // Base 3 rows always show; specializations add a divider label plus 2 more rows
        // (Explosive Rounds, and Slow/Bleed sharing one row) once wave 10 is cleared.
        var contentHeight = headerHeight + 3 * (buttonHeight + rowGap)
        if (unlocked) contentHeight += dividerGap + 2 * (buttonHeight + rowGap)
        contentHeight += finalGap + buttonHeight + 16f * s // safety margin

        val cardWidth = w * 0.88f
        val cardHeight = minOf(h * 0.92f, contentHeight)

        // If the available height is too short to fit every row at its natural size (e.g. a
        // resizable/split-screen window that doesn't honor the portrait lock), shrink all the
        // internal spacing proportionally so the Start Wave button always ends up inside the
        // card instead of being laid out past its clamped bottom edge.
        if (contentHeight > cardHeight) {
            val fit = cardHeight / contentHeight
            buttonHeight *= fit
            rowGap *= fit
            headerHeight *= fit
            dividerGap *= fit
            finalGap *= fit
        }

        val cardLeft = (w - cardWidth) / 2f
        val cardTop = (h - cardHeight) / 2f
        val cardRect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)

        drawCard(canvas, cardRect, s, Color.rgb(90, 78, 120))

        textPaint.textSize = 23f * s
        val title = if (engine.waveManager.waveNumber == 1) {
            "Prepare your defenses"
        } else {
            "Wave ${engine.waveManager.waveNumber - 1} cleared! +${engine.lastWaveGoldEarned}g"
        }
        canvas.drawText(title, cardRect.centerX(), cardRect.top + 42f * s, textPaint)

        fillPaint.color = Color.argb(90, 255, 255, 255)
        canvas.drawRect(cardRect.left + 24f * s, cardRect.top + 56f * s, cardRect.right - 24f * s, cardRect.top + 57f * s, fillPaint)

        val buttonMargin = 20f * s
        var buttonTop = cardRect.top + headerHeight
        val buttonWidth = cardRect.width() - buttonMargin * 2f

        wallButtonRect = drawUpgradeButton(
            canvas, cardRect.left + buttonMargin, buttonTop, buttonWidth, buttonHeight,
            "Castle Walls (Lv ${engine.castle.wallLevel})", "Shield/HP up",
            engine.wallUpgradeCost(), engine.gold, s
        )
        buttonTop += buttonHeight + rowGap

        cannonButtonRect = drawUpgradeButton(
            canvas, cardRect.left + buttonMargin, buttonTop, buttonWidth, buttonHeight,
            "Castle Cannons (Lv ${engine.cannonLevel})", "Damage/rate/range up",
            engine.cannonUpgradeCost(), engine.gold, s
        )
        buttonTop += buttonHeight + rowGap

        archerButtonRect = drawUpgradeButton(
            canvas, cardRect.left + buttonMargin, buttonTop, buttonWidth, buttonHeight,
            "Archer Towers (Lv ${engine.archerLevel})", "Unlock/upgrade archers",
            engine.archerUpgradeCost(), engine.gold, s
        )
        buttonTop += buttonHeight + rowGap

        if (unlocked) {
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.textSize = 14f * s
            textPaint.color = Color.argb(190, 255, 255, 255)
            canvas.drawText("SPECIALIZATIONS", cardRect.left + buttonMargin, buttonTop + dividerGap * 0.7f, textPaint)
            textPaint.color = Color.WHITE
            textPaint.textAlign = Paint.Align.CENTER
            buttonTop += dividerGap

            explosiveButtonRect = drawUpgradeButton(
                canvas, cardRect.left + buttonMargin, buttonTop, buttonWidth, buttonHeight,
                "Explosive Rounds (Lv ${engine.explosiveLevel})", "Cannon blast radius up",
                engine.explosiveUpgradeCost(), engine.gold, s
            )
            buttonTop += buttonHeight + rowGap

            val halfWidth = (buttonWidth - rowGap) / 2f
            val bleedChosen = engine.bleedLevel > 0
            val slowChosen = engine.slowLevel > 0
            slowButtonRect = drawUpgradeButton(
                canvas, cardRect.left + buttonMargin, buttonTop, halfWidth, buttonHeight,
                if (slowChosen) "Slow Arrows (Lv ${engine.slowLevel})" else "Slow Arrows",
                if (bleedChosen) "Locked" else "Cripples on hit",
                if (bleedChosen) null else engine.slowUpgradeCost(), engine.gold, s,
                costLabelOverride = if (bleedChosen) "Locked" else null
            )
            bleedButtonRect = drawUpgradeButton(
                canvas, cardRect.left + buttonMargin + halfWidth + rowGap, buttonTop, halfWidth, buttonHeight,
                if (bleedChosen) "Bleed Arrows (Lv ${engine.bleedLevel})" else "Bleed Arrows",
                if (slowChosen) "Locked" else "Damage over time",
                if (slowChosen) null else engine.bleedUpgradeCost(), engine.gold, s,
                costLabelOverride = if (slowChosen) "Locked" else null
            )
            buttonTop += buttonHeight + rowGap
        } else {
            explosiveButtonRect = RectF()
            slowButtonRect = RectF()
            bleedButtonRect = RectF()
        }

        buttonTop += finalGap - rowGap
        startWaveButtonRect = RectF(cardRect.left + buttonMargin, buttonTop, cardRect.right - buttonMargin, buttonTop + buttonHeight)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.rgb(60, 140, 70)
        canvas.drawRoundRect(startWaveButtonRect, 14f * s, 14f * s, fillPaint)
        textPaint.textSize = 21f * s
        canvas.drawText("Start Wave ${engine.waveManager.waveNumber}", startWaveButtonRect.centerX(), startWaveButtonRect.centerY() + 7f * s, textPaint)
    }

    /** Draws a rounded card with a drop shadow and border — the shared "popup" look. */
    private fun drawCard(canvas: Canvas, rect: RectF, scale: Float, borderColor: Int) {
        val radius = 24f * scale
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(120, 0, 0, 0)
        val shadowRect = RectF(rect.left + 4f * scale, rect.top + 8f * scale, rect.right + 4f * scale, rect.bottom + 8f * scale)
        canvas.drawRoundRect(shadowRect, radius, radius, fillPaint)

        fillPaint.color = Color.rgb(30, 24, 46)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        fillPaint.style = Paint.Style.STROKE
        fillPaint.strokeWidth = 2f * scale
        fillPaint.color = borderColor
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
        fillPaint.style = Paint.Style.FILL
    }

    private fun drawUpgradeButton(
        canvas: Canvas, left: Float, top: Float, width: Float, height: Float,
        title: String, subtitle: String, cost: Int?, gold: Int, scale: Float,
        costLabelOverride: String? = null
    ): RectF {
        val rect = RectF(left, top, left + width, top + height)
        val affordable = cost != null && gold >= cost
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = when {
            cost == null -> Color.rgb(70, 62, 90)
            affordable -> Color.rgb(58, 92, 140)
            else -> Color.rgb(50, 45, 60)
        }
        canvas.drawRoundRect(rect, 12f * scale, 12f * scale, fillPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 18f * scale
        canvas.drawText(title, rect.left + 16f * scale, rect.top + 24f * scale, textPaint)
        textPaint.textSize = 13f * scale
        textPaint.color = Color.argb(210, 255, 255, 255)
        canvas.drawText(subtitle, rect.left + 16f * scale, rect.top + 44f * scale, textPaint)
        textPaint.color = Color.WHITE

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 18f * scale
        val costLabel = costLabelOverride ?: cost?.let { "${it}g" } ?: "MAX"
        canvas.drawText(costLabel, rect.right - 16f * scale, rect.top + 34f * scale, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        return rect
    }

    private fun drawGameOverPopup(canvas: Canvas, engine: GameEngine) {
        val s = engine.scale
        val w = engine.screenW
        val h = engine.screenH

        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(190, 8, 6, 14)
        canvas.drawRect(0f, 0f, w, h, fillPaint)

        val cardWidth = w * 0.82f
        val cardHeight = 260f * s
        val cardRect = RectF((w - cardWidth) / 2f, h * 0.5f - cardHeight / 2f, (w + cardWidth) / 2f, h * 0.5f + cardHeight / 2f)
        drawCard(canvas, cardRect, s, Color.rgb(130, 60, 60))

        textPaint.textSize = 30f * s
        canvas.drawText("The Castle Has Fallen", cardRect.centerX(), cardRect.top + 56f * s, textPaint)
        textPaint.textSize = 19f * s
        canvas.drawText("You survived to wave ${engine.waveManager.waveNumber}", cardRect.centerX(), cardRect.top + 92f * s, textPaint)

        restartButtonRect = RectF(cardRect.centerX() - 100f * s, cardRect.bottom - 90f * s, cardRect.centerX() + 100f * s, cardRect.bottom - 26f * s)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.rgb(60, 140, 70)
        canvas.drawRoundRect(restartButtonRect, 14f * s, 14f * s, fillPaint)
        textPaint.textSize = 21f * s
        canvas.drawText("Rebuild", restartButtonRect.centerX(), restartButtonRect.centerY() + 7f * s, textPaint)
    }

    fun handleTouch(x: Float, y: Float, engine: GameEngine) {
        when (engine.state) {
            GameState.INTERMISSION -> {
                when {
                    wallButtonRect.contains(x, y) -> engine.purchaseWallUpgrade()
                    cannonButtonRect.contains(x, y) -> engine.purchaseCannonUpgrade()
                    archerButtonRect.contains(x, y) -> engine.purchaseArcherUpgrade()
                    explosiveButtonRect.contains(x, y) -> engine.purchaseExplosiveUpgrade()
                    slowButtonRect.contains(x, y) -> engine.purchaseSlowUpgrade()
                    bleedButtonRect.contains(x, y) -> engine.purchaseBleedUpgrade()
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
