package com.deckkey.ime.input

import com.deckkey.core.model.Modifier

/**
 * Tri-state per modifier, mirroring a physical PC keyboard + OS sticky-keys:
 *
 *  - OFF     : not active.
 *  - LATCHED : applies to exactly the next non-modifier key, then clears (single tap).
 *  - LOCKED  : stays active until explicitly turned off (double-tap, or held).
 *  - HELD    : physically held down (finger still on the key) — active until release.
 */
enum class ModState { OFF, LATCHED, LOCKED, HELD }

/**
 * Tracks the live state of Ctrl/Alt/Shift/Meta and Caps Lock.
 *
 * This is the heart of the "PC feel". It supports three interaction styles at once,
 * so it works whether the user taps-then-types or holds-a-chord like a real keyboard:
 *
 *   tap once        -> LATCHED  (next key gets the modifier, like OS sticky keys)
 *   double-tap      -> LOCKED   (stays on, e.g. select multiple with Shift+arrows)
 *   press & hold    -> HELD     (true chording: hold Ctrl, tap C, tap V...)
 *
 * After a non-modifier key is consumed, all LATCHED modifiers clear; LOCKED/HELD persist.
 */
class ModifierStateManager(
    private val onChanged: () -> Unit = {},
) {
    private val states = HashMap<Modifier, ModState>().apply {
        Modifier.values().forEach { put(it, ModState.OFF) }
    }
    var capsLock: Boolean = false
        private set

    /** Timestamp of last tap per modifier, for double-tap (lock) detection. */
    private val lastTapAt = HashMap<Modifier, Long>()
    private val doubleTapWindowMs = 300L

    fun state(m: Modifier): ModState = states[m] ?: ModState.OFF
    fun isActive(m: Modifier): Boolean = state(m) != ModState.OFF

    /** True if Shift effect is in play (Shift active XOR is handled by caller for letters). */
    fun isShiftActive(): Boolean = isActive(Modifier.SHIFT)

    // ---- Finger interactions -------------------------------------------------

    /** Finger pressed a modifier key down — begin a potential hold. */
    fun onModifierDown(m: Modifier, now: Long) {
        val prev = states[m]
        // A press always becomes HELD for the duration the finger is down.
        states[m] = ModState.HELD
        // Remember what it was so release can decide tap vs hold transition.
        pressOriginState[m] = prev ?: ModState.OFF
        pressDownAt[m] = now
        onChanged()
    }

    /** Finger lifted off a modifier key — decide latch / lock / clear. */
    fun onModifierUp(m: Modifier, now: Long) {
        val origin = pressOriginState[m] ?: ModState.OFF
        val downAt = pressDownAt[m] ?: now
        val wasHeldLongEnoughToChord = (now - downAt) >= holdAsChordMs
        val consumedWhileHeld = consumedSinceDown[m] == true
        consumedSinceDown[m] = false

        if (wasHeldLongEnoughToChord || consumedWhileHeld) {
            // It was used as a real hold-chord (e.g. Ctrl+C). Return to OFF.
            states[m] = ModState.OFF
            onChanged()
            return
        }

        // Treat as a tap. Cycle: OFF -> LATCHED -> (double tap) LOCKED -> OFF
        val lastTap = lastTapAt[m] ?: 0L
        val isDoubleTap = (now - lastTap) <= doubleTapWindowMs
        lastTapAt[m] = now

        states[m] = when {
            origin == ModState.LOCKED -> ModState.OFF          // tap to release a lock
            isDoubleTap && origin == ModState.LATCHED -> ModState.LOCKED
            origin == ModState.LATCHED -> ModState.OFF         // second single tap clears
            else -> ModState.LATCHED                            // first tap latches
        }
        onChanged()
    }

    fun toggleCapsLock() {
        capsLock = !capsLock
        onChanged()
    }

    /**
     * Called after a non-modifier key is committed. Clears LATCHED modifiers,
     * keeps LOCKED and HELD. Marks held modifiers as "consumed" so their release
     * is treated as a chord, not a tap.
     */
    fun consumeAfterKey() {
        var changed = false
        for (m in Modifier.values()) {
            when (states[m]) {
                ModState.LATCHED -> { states[m] = ModState.OFF; changed = true }
                ModState.HELD -> consumedSinceDown[m] = true
                else -> {}
            }
        }
        if (changed) onChanged()
    }

    /** Clears all modifier latches/locks (e.g. on input target change). Caps persists. */
    fun resetTransient() {
        var changed = false
        for (m in Modifier.values()) {
            if (states[m] != ModState.OFF) { states[m] = ModState.OFF; changed = true }
        }
        if (changed) onChanged()
    }

    // ---- internal bookkeeping ----
    private val pressOriginState = HashMap<Modifier, ModState>()
    private val pressDownAt = HashMap<Modifier, Long>()
    private val consumedSinceDown = HashMap<Modifier, Boolean>()
    private val holdAsChordMs = 220L
}
