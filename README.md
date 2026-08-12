# Tower Defence

A top-down Android tower defence game built with Kotlin and plain `android.graphics` Canvas
rendering (no game engine, no external art assets — everything is drawn and animated in code).

## Gameplay

- Defend the medieval castle in the centre of the arena from waves of little zombies that
  shamble in from the edges.
- The castle has a **health bar** and a **shield bar**. Shield absorbs damage first and slowly
  regenerates a few seconds after the castle stops taking hits.
- Between waves, spend gold earned from kills and wave-clear bonuses on three upgrade tracks:
  - **Castle Walls** — raises max health and max shield (and shield regen).
  - **Castle Cannons** — raises damage/fire rate/range and unlocks more cannon mounts around
    the wall ring (up to 4).
  - **Archer Towers** — the "other" castle weapon; unlocks and upgrades fast-firing archers
    (up to 4).
- Each wave spawns more zombies than the last, with more health and speed.
- Zombies have a procedural walk cycle (swinging limbs) and a collapse-and-fade death animation.
  Cannons recoil and flash when they fire; archers visibly draw their bow before loosing an arrow.

- The app opens on a separate title screen; tapping Play launches the game itself, so the menu
  and the gameplay view are two distinct Activities rather than the game just starting in place.

## Project layout

- `app/src/main/java/com/alf452/towerdefence/` — `MainMenuActivity` (launcher, title screen),
  `GameActivity` (hosts the game, immersive fullscreen), `GameView` (SurfaceView + game loop
  thread).
- `.../game/` — simulation: `GameEngine`, `Castle`, `Zombie`, `Weapon` (cannon/archer slots),
  `Projectile`, `WaveManager`, `GameMath`. All visual sizing is resolution-relative
  (`GameEngine.scale`, derived from screen width) rather than fixed pixel constants, so the game
  reads the same across devices instead of shrinking or overlapping.
- `.../ui/Hud.kt` — health/shield bars, gold/wave readout (as independently-sized pill chips so
  digit growth can't make them collide), the upgrade popup and game-over popup (both dim the
  battlefield and show a centered card), and touch hit-testing for all of the above.

## Building

This repo includes the Gradle wrapper, so from the project root:

```
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. A GitHub Actions workflow
(`.github/workflows/android-build.yml`) also builds the debug APK on every push and uploads it
as a build artifact, since the sandbox this project was authored in has no Android SDK/network
access to verify the build locally — treat that CI run as the first real compile check.

Requirements: JDK 17, Android SDK with platform 34 / build-tools (the Gradle wrapper and AGP
handle the rest). Open the folder directly in Android Studio for the easiest setup.
