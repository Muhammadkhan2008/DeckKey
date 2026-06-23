package com.deckkey.ime.input

import com.deckkey.core.model.Key
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 *
 * FIX: Uses Coroutines instead of Handler to prevent memory leaks.
 */
class KeyRepeatController(
    private val onRepeat: (Key) -> Unit,
) {
    private var activeKey: Key? = null
    private var repeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    var initialDelayMs: Long = 400
    var intervalMs: Long = 45

    /** Begin repeating [key] if it is repeatable. */
    fun start(key: Key) {
        if (!key.repeatable) return
        stop()
        activeKey = key
        repeatJob = scope.launch {
            delay(initialDelayMs)
            while (activeKey == key) {
                onRepeat(key)
                delay(intervalMs)
            }
        }
    }

    /** Stop any active repeat. Safe to call when nothing is repeating. */
    fun stop() {
        activeKey = null
        repeatJob?.cancel()
        repeatJob = null
    }

    /** Cleanup on service destroy. */
    fun destroy() {
        stop()
    }
}
