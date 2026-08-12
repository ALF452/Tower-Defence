package com.alf452.towerdefence

import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.alf452.towerdefence.game.GameEngine

class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    val engine = GameEngine()
    private var gameThread: GameThread? = null

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        gameThread = GameThread(holder, engine).apply {
            running = true
            start()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // onSurfaceSize mutates screenW/H, scale, castle position, and the cached
        // craters/stars/asteroids lists that GameThread's update()/draw() also read every
        // frame; without this lock it can observe a torn mix of old/new state mid-frame,
        // the same class of race the touch handler below is already guarded against.
        synchronized(engine) {
            engine.onSurfaceSize(width.toFloat(), height.toFloat())
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        gameThread?.running = false
        var retrying = true
        while (retrying) {
            try {
                gameThread?.join()
                retrying = false
            } catch (e: InterruptedException) {
                // keep retrying until the thread actually stops
            }
        }
        gameThread = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            // Touches land on the UI thread while GameThread concurrently iterates/mutates
            // the same engine state every frame; without this lock a tap that triggers
            // restart()/purchase*() (which clear or mutate the zombie/projectile lists) can
            // race a live iteration on GameThread and throw ConcurrentModificationException.
            synchronized(engine) {
                engine.onTouch(event.x, event.y)
            }
        }
        return true
    }
}

private class GameThread(
    private val surfaceHolder: SurfaceHolder,
    private val engine: GameEngine
) : Thread() {

    @Volatile var running = false
    private val targetFrameTimeMs = 1000L / 60L

    override fun run() {
        var lastTimeNanos = System.nanoTime()
        while (running) {
            val frameStart = System.nanoTime()
            var dt = (frameStart - lastTimeNanos) / 1_000_000_000f
            lastTimeNanos = frameStart
            if (dt > 0.05f) dt = 0.05f // avoid huge catch-up jumps after a hitch

            synchronized(engine) {
                engine.update(dt)
            }

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    synchronized(engine) {
                        engine.draw(canvas)
                    }
                }
            } finally {
                if (canvas != null) {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                }
            }

            val frameTimeMs = (System.nanoTime() - frameStart) / 1_000_000L
            val sleepTime = targetFrameTimeMs - frameTimeMs
            if (sleepTime > 0) {
                try {
                    sleep(sleepTime)
                } catch (e: InterruptedException) {
                    // ignore, loop condition is re-checked immediately
                }
            }
        }
    }
}
