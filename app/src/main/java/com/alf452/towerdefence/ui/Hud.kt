package com.alf452.towerdefence.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.alf452.towerdefence.game.Ability
import com.alf452.towerdefence.game.BossVariant
import com.alf452.towerdefence.game.GameEngine
import com.alf452.towerdefence.game.GameMath
import com.alf452.towerdefence.game.GameState
import kotlin.math.ceil

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

    // The health/shield bars + gold/wave chips, recomputed by drawTopBars() every frame like
    // every other rect in this class. Excluded from Orbital Strike's targeting tap below so
    // glancing at your gold/HP mid-targeting can't be misread as "fire here" and burn the
    // ability's cooldown on an empty patch of HUD chrome.
    private var topBarsRect = RectF()

    // Ability bar state (only relevant during GameState.PLAYING — see drawAbilityBar/handleTouch).
    // abilityButtonRects is rebuilt every draw() call, same "compute rect on draw, hit-test on
    // touch" pattern as the upgrade popup's buttons above.
    private var abilityButtonRects: Map<Ability, RectF> = emptyMap()
    // Set the instant the player taps an ability that needs a battlefield point (currently only
    // Orbital Strike); the *next* tap either cancels (if it lands back on that ability's own
    // button) or fires the ability at that tap's location. Cleared whenever the game isn't
    // PLAYING (see draw() below) so a stale targeting mode can't survive into a later wave.
    private var targetingAbility: Ability? = null

    fun draw(canvas: Canvas, engine: GameEngine) {
        if (engine.state != GameState.PLAYING) targetingAbility = null
        drawTopBars(canvas, engine)
        when (engine.state) {
            GameState.INTERMISSION -> drawUpgradePopup(canvas, engine)
            GameState.GAME_OVER -> drawGameOverPopup(canvas, engine)
            GameState.PLAYING -> drawAbilityBar(canvas, engine)
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

        topBarsRect = RectF(0f, 0f, w, top + chipHeight)
    }

    private fun drawChip(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, text: String) {
        val rect = RectF(left, top, left + width, top + height)
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(200, 22, 18, 34)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, fillPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, rect.centerX(), rect.centerY() + textPaint.textSize * 0.35f, textPaint)
    }

    /**
     * A row of ability buttons centered along the bottom edge, one per currently-unlocked
     * [Ability] (see [GameEngine.abilityStatuses]) — locked abilities simply don't occupy a slot
     * yet, rather than showing as a disabled placeholder, so the bar grows as the run progresses
     * instead of spoiling how many abilities exist. A ready button shows its own accent color and
     * "Ready" (or "Tap target" while [targetingAbility] points at it); one on cooldown dims to
     * gray and shows a rounded-up seconds countdown instead.
     */
    private fun drawAbilityBar(canvas: Canvas, engine: GameEngine) {
        val s = engine.scale
        val w = engine.screenW
        val h = engine.screenH
        val statuses = engine.abilityStatuses().filter { it.unlocked }
        if (statuses.isEmpty()) {
            abilityButtonRects = emptyMap()
            return
        }

        val buttonWidth = 112f * s
        val buttonHeight = 112f * s
        val gap = 14f * s
        val totalWidth = statuses.size * buttonWidth + (statuses.size - 1) * gap
        var left = (w - totalWidth) / 2f
        val top = h - buttonHeight - 24f * s

        val rects = mutableMapOf<Ability, RectF>()
        for (status in statuses) {
            val rect = RectF(left, top, left + buttonWidth, top + buttonHeight)
            val ready = status.cooldownRemaining <= 0f
            val targeting = targetingAbility == status.ability
            val baseColor = when (status.ability) {
                Ability.ORBITAL_STRIKE -> Color.rgb(70, 110, 170)
                Ability.EMP_FREEZE -> Color.rgb(50, 140, 150)
                Ability.OVERCHARGE -> Color.rgb(170, 125, 40)
            }
            val color = when {
                targeting -> Color.rgb(215, 195, 90)
                ready -> baseColor
                else -> Color.rgb(50, 48, 58)
            }
            // A smaller, fixed corner radius than drawBubbleBackground's default pill-shaped
            // buttons elsewhere in the HUD -- these are square, so the default (proportional to
            // height) would round them down into a near-circle instead of a rounded square.
            drawBubbleBackground(canvas, rect, color, cornerRadius = 18f * s)

            // A dark overlay shrinking from the top down as the cooldown finishes, so progress
            // reads at a glance instead of only via the seconds-remaining text below.
            if (!ready && !targeting) {
                val cooldownFrac = GameMath.clamp(status.cooldownRemaining / status.cooldownTotal, 0f, 1f)
                fillPaint.style = Paint.Style.FILL
                fillPaint.color = Color.argb(140, 0, 0, 0)
                canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * cooldownFrac, fillPaint)
            }

            textPaint.textSize = 13f * s
            textPaint.color = Color.WHITE
            canvas.drawText(status.label, rect.centerX(), rect.top + 42f * s, textPaint)

            textPaint.textSize = 17f * s
            val subLabel = when {
                targeting -> "Tap target"
                ready -> "Ready"
                else -> "${ceil(status.cooldownRemaining).toInt()}s"
            }
            canvas.drawText(subLabel, rect.centerX(), rect.top + 72f * s, textPaint)

            rects[status.ability] = rect
            left += buttonWidth + gap
        }
        abilityButtonRects = rects
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

        var buttonHeight = 64f * s
        var rowGap = 14f * s
        var headerHeight = 80f * s
        var dividerGap = 32f * s
        // Deliberately much larger than rowGap so Start Wave reads as visually separated from
        // the upgrade rows above it instead of just being one more row in the same stack —
        // otherwise a tap meant for the last upgrade can land on Start Wave instead.
        var finalGap = 50f * s

        // Base 3 rows always show; specializations add a divider label plus 2 more rows
        // (Explosive Rounds, and Slow/Bleed sharing one row) once wave 10 is cleared.
        var contentHeight = headerHeight + 3 * (buttonHeight + rowGap)
        if (unlocked) contentHeight += dividerGap + 2 * (buttonHeight + rowGap)
        contentHeight += finalGap + buttonHeight + 16f * s // safety margin

        val cardWidth = w * 0.92f
        val cardHeight = minOf(h * 0.95f, contentHeight)

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

        // Warn before a boss wave — reaching the castle is an instant, fatal loss (see
        // Zombie's BOSS branch), so the player needs a clear cue to spend up before it starts.
        // Positioned as a fraction of headerHeight (already shrunk by the fit factor above when
        // the card is too short) rather than a fixed offset, so it stays inside the header gap —
        // and clear of the first upgrade row at cardRect.top + headerHeight — even on a
        // constrained viewport where fit < 1.
        if (engine.waveManager.isBossWave(engine.waveManager.waveNumber)) {
            val prevColor = textPaint.color
            val prevSize = textPaint.textSize
            textPaint.color = Color.rgb(255, 90, 70)
            textPaint.textSize = 13f * s
            val bossName = bossVariantLabel(engine.waveManager.bossVariantForWave(engine.waveManager.waveNumber))
            canvas.drawText("⚠ BOSS WAVE — $bossName incoming", cardRect.centerX(), cardRect.top + headerHeight * 0.85f, textPaint)
            textPaint.color = prevColor
            textPaint.textSize = prevSize
        }

        drawDivider(canvas, cardRect, cardRect.top + 56f * s, 90, s)

        // Extra horizontal inset (beyond the card's own margin) so each row reads as a
        // narrower, floating bubble instead of a bar stretched edge-to-edge across the card.
        val buttonMargin = 20f * s + cardRect.width() * 0.05f
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

        // A divider centered in the gap, matching the one under the header, makes the separation
        // from the upgrade rows above read clearly rather than just being empty space. buttonTop
        // already includes the trailing rowGap from the last upgrade row, so that's subtracted
        // back out first to find the true start of the (larger) finalGap span.
        drawDivider(canvas, cardRect, buttonTop - rowGap + finalGap / 2f, 70, s)

        buttonTop += finalGap - rowGap
        startWaveButtonRect = RectF(cardRect.left + buttonMargin, buttonTop, cardRect.right - buttonMargin, buttonTop + buttonHeight)
        drawBubbleBackground(canvas, startWaveButtonRect, Color.rgb(60, 140, 70))
        textPaint.textSize = 21f * s
        canvas.drawText("Start Wave ${engine.waveManager.waveNumber}", startWaveButtonRect.centerX(), startWaveButtonRect.centerY() + 7f * s, textPaint)
    }

    /** Display name for the upcoming boss wave's warning banner — see [GameEngine.abilityStatuses] for the equivalent ability-label pattern. */
    private fun bossVariantLabel(variant: BossVariant): String = when (variant) {
        BossVariant.GALAXY_SNAIL -> "Galaxy Snail"
        BossVariant.METEOR_WYRM -> "Meteor Wyrm"
        BossVariant.OBELISK_WARDEN -> "Obelisk Warden"
    }

    /** A thin translucent horizontal rule spanning the card's inner width, used to separate sections. */
    private fun drawDivider(canvas: Canvas, cardRect: RectF, y: Float, alpha: Int, scale: Float) {
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(alpha, 255, 255, 255)
        canvas.drawRect(cardRect.left + 24f * scale, y, cardRect.right - 24f * scale, y + 1f * scale, fillPaint)
    }

    /**
     * Fills [rect] with [color] as a rounded "bubble" (corner radius scaled to the rect's own
     * height) plus a translucent glossy highlight band near the top, matching the main menu's
     * button look. Shared by the upgrade rows, Start Wave, and Rebuild buttons.
     */
    private fun drawBubbleBackground(canvas: Canvas, rect: RectF, color: Int, cornerRadius: Float = rect.height() * 0.42f) {
        val radius = cornerRadius
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = color
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        fillPaint.color = Color.argb(30, 255, 255, 255)
        canvas.drawRoundRect(
            RectF(
                rect.left + rect.width() * 0.06f, rect.top + rect.height() * 0.08f,
                rect.right - rect.width() * 0.06f, rect.top + rect.height() * 0.42f
            ),
            radius * 0.7f, radius * 0.7f, fillPaint
        )
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
        val color = when {
            cost == null -> Color.rgb(70, 62, 90)
            affordable -> Color.rgb(58, 92, 140)
            else -> Color.rgb(50, 45, 60)
        }
        drawBubbleBackground(canvas, rect, color)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 18f * scale
        canvas.drawText(title, rect.left + 20f * scale, rect.top + 24f * scale, textPaint)
        textPaint.textSize = 13f * scale
        textPaint.color = Color.argb(210, 255, 255, 255)
        canvas.drawText(subtitle, rect.left + 20f * scale, rect.top + 44f * scale, textPaint)
        textPaint.color = Color.WHITE

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = 18f * scale
        val costLabel = costLabelOverride ?: cost?.let { "${it}g" } ?: "MAX"
        canvas.drawText(costLabel, rect.right - 20f * scale, rect.top + 34f * scale, textPaint)
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

        restartButtonRect = RectF(cardRect.centerX() - 80f * s, cardRect.bottom - 90f * s, cardRect.centerX() + 80f * s, cardRect.bottom - 26f * s)
        drawBubbleBackground(canvas, restartButtonRect, Color.rgb(60, 140, 70))
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
            GameState.PLAYING -> handlePlayingTouch(x, y, engine)
        }
    }

    /**
     * While [targetingAbility] is set (only ever Orbital Strike today): a tap on [topBarsRect]
     * (glancing at HP/gold) is ignored outright, a tap on *any* ability button — not just the
     * targeting ability's own — cancels targeting instead of being misread as "fire here" (and
     * for a different ability's button, falls through to the normal dispatch below so the tap
     * still does something useful rather than being silently swallowed), and anything else fires
     * the ability at the tapped point. Otherwise, a tap on a ready, unlocked ability button either
     * casts it immediately (instant abilities) or enters targeting mode (abilities with
     * requiresTarget).
     */
    private fun handlePlayingTouch(x: Float, y: Float, engine: GameEngine) {
        val targeting = targetingAbility
        if (targeting != null) {
            if (topBarsRect.contains(x, y)) return
            val tappedButton = abilityButtonRects.entries.firstOrNull { it.value.contains(x, y) }?.key
            if (tappedButton != null) {
                targetingAbility = null
                if (tappedButton == targeting) return
                // A different ability's button — cancel targeting and fall through to dispatch
                // that tap normally below instead of dropping it.
            } else {
                engine.castOrbitalStrikeAt(x, y)
                targetingAbility = null
                return
            }
        }

        for ((ability, rect) in abilityButtonRects) {
            if (!rect.contains(x, y)) continue
            val status = engine.abilityStatuses().first { it.ability == ability }
            if (status.unlocked && status.cooldownRemaining <= 0f) {
                if (status.requiresTarget) targetingAbility = ability else engine.castAbility(ability)
            }
            return
        }
    }
}
