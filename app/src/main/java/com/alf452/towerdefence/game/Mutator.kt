package com.alf452.towerdefence.game

/**
 * Optional risk/reward run modifiers, toggled during wave 1's intermission only (see
 * [GameEngine.toggleMutator]) and locked in for the rest of that run the instant wave 1 begins
 * (see [GameEngine.startNextWave]). Each makes the run meaningfully harder in exchange for a
 * flat percentage bonus to the Star Dust earned when the run ends (summed across every active
 * mutator — see [GameEngine.mutatorStarDustBonusPercent]) — a deliberate lever for replay value
 * on top of [com.alf452.towerdefence.MetaProgress]'s existing progression, distinct from it: this
 * is a per-run choice, not a permanent unlock.
 *
 * Each mutator alone was tuned via a headless Monte Carlo sim (see mutator_sim.py) to land the
 * wave 1-30 win rate around 55-62%, down from this game's ~72% baseline — a real, felt risk but
 * still very winnable solo. Stacking multiple compounds multiplicatively rather than being
 * re-tuned to stay fair when combined — all four together lands near 0% (still earns solid Star
 * Dust from wave progress even on a loss, and reads as a deliberate hardcore challenge tier the
 * player opted into with each effect shown up front, not a hidden trap).
 */
enum class Mutator(val label: String, val description: String, val starDustBonusPercent: Int) {
    // Originally "+40% enemies/wave" — a headless Monte Carlo sim (see mutator_sim.py) showed
    // that actually made runs *easier* (72.4% -> 85.4% by wave 30), since the extra weak normal
    // zombies' per-kill gold outpaced the extra pressure they added. A weapon-fire-rate slowdown
    // (same mechanism as Overcharge's speedup, inverted) reliably lands in this mutator's target
    // difficulty band instead.
    SWARM("Swarm", "Weapons fire slower", 25),
    GLASS_CANNON("Glass Cannon", "-30% castle HP/shield", 25),
    // Originally just "no healing between waves" alone barely moved the win rate (72.4% ->
    // 72.9%), since shield (which absorbs damage first) still fully regenerated during every
    // intermission regardless. Also zeroing the shield closes that loophole.
    IRON_WILL("Iron Will", "No heal, shield resets", 20),
    // Originally +30%, which cratered the win rate to 9.3% -- +12% lands in the same ~50-60%
    // band as the other three (55.5% over 600 trials).
    FAST_FORWARD("Fast Forward", "+12% zombie speed", 25)
}
