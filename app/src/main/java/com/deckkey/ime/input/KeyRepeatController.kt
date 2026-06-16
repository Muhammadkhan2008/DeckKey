package com.deckkey.ime.input

import android.os.Handler
import android.os.Looper
import com.deckkey.core.model.Key

/**
 * Windows-style key auto-repeat for held keys (arrows, backspace, space).
 *
 * Behavior matches a desktop: an initial delay, then a faster steady repeat,
 * until the finger lifts or moves off the key.
 *
 *   press --[initialDelay]--> repeat --[interval]--> repeat --[interval]--> ...
 *
 * The very first emission happens immediately on press (handled by the caller);
 * this controller schedules every emission *after* that.
 */
class KeyRepeatController(
    private val onRepeat: (Key) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var activeKey: Key? = null

    var initialDelayMs: Long = 400
    var intervalMs: Long = 45

    private val tick = object : Runnable {
        override fun run() {
            val k = activeKey ?: return
            onRepeat(k)
            handler.postDelayed(this, intervalMs)
        }
    }

    /** Begin repeating [key] if it is repeatable. */
    fun start(key: Key) {
        if (!key.repeatable) return
        stop()
        activeKey = key
        handler.postDelayed(tick, initialDelayMs)
    }

    /** Stop any active repeat. Safe to call when nothing is repeating. */
    fun stop() {
        activeKey = null
        handler.removeCallbacks(tick)
    }
}
