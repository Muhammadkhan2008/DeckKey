package com.deckkey.ime.input

import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.deckkey.core.model.Key
import com.deckkey.core.model.KeyType
import com.deckkey.core.model.Modifier

/**
 * Turns a tapped [Key] plus current modifier state into actual input sent to the
 * focused field via [InputConnection].
 *
 * Two delivery paths:
 *
 *  1. Plain text (no Ctrl/Alt/Meta active)  -> [InputConnection.commitText].
 *     This is what normal text fields expect and renders most reliably.
 *
 *  2. A real key event with meta state (any of Ctrl/Alt/Meta active, OR a special
 *     KEYCODE key like arrows/Esc/F-keys) -> [InputConnection.sendKeyEvent] with a
 *     fully-formed [KeyEvent] carrying the meta mask. This is what terminals (Termux),
 *     code editors and SSH clients listen for, so Ctrl+C / Alt+Tab / Shift+Arrow work.
 *
 * Callbacks let the host service handle layout switches and IME switching.
 */
class KeyDispatcher(
    private val modifiers: ModifierStateManager,
    private val onLayoutSwitch: (String) -> Unit,
    private val onImeSwitch: () -> Unit,
) {
    private val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

    /** Process a fully-completed tap on [key]. [ic] may be null if no field is focused. */
    fun dispatch(key: Key, ic: InputConnection?) {
        when (key.type) {
            KeyType.MODIFIER -> Unit // handled on down/up by ModifierStateManager
            KeyType.CAPS_LOCK -> modifiers.toggleCapsLock()
            KeyType.LAYOUT_SWITCH -> key.switchTo?.let(onLayoutSwitch)
            KeyType.IME_SWITCH -> onImeSwitch()
            KeyType.KEYCODE -> {
                if (ic != null) sendKeyCode(ic, key.keyCode)
                modifiers.consumeAfterKey()
            }
            KeyType.CHAR -> {
                if (ic != null) emitChar(ic, key)
                modifiers.consumeAfterKey()
            }
        }
    }

    private fun emitChar(ic: InputConnection, key: Key) {
        val ctrl = modifiers.isActive(Modifier.CTRL)
        val alt = modifiers.isActive(Modifier.ALT)
        val meta = modifiers.isActive(Modifier.META)
        val shift = modifiers.isShiftActive()

        // If a "command" modifier is active, send a key event so the app sees the chord
        // (e.g. Ctrl+C). Plain typing (with optional Shift) goes through commitText.
        if (ctrl || alt || meta) {
            if (key.keyCode != 0) {
                sendKeyCode(ic, key.keyCode)
            } else {
                // Symbol with no keycode but a command modifier: best-effort via events.
                key.baseOutput.firstOrNull()?.let { sendChar(ic, it) }
            }
            return
        }

        val text = resolveText(key, shift)
        ic.commitText(text, 1)
    }

    /** Resolve the visible/committed text of a CHAR key given shift + caps. */
    private fun resolveText(key: Key, shift: Boolean): String {
        val base = key.baseOutput
        val isLetter = base.length == 1 && base[0].isLetter()
        return when {
            isLetter -> {
                // For letters: Caps XOR Shift -> uppercase.
                val upper = modifiers.capsLock xor shift
                if (upper) base.uppercase() else base.lowercase()
            }
            shift -> key.shiftLabel ?: base
            else -> base
        }
    }

    /** Build the Android meta-state mask from the current modifier state. */
    private fun buildMetaState(includeShift: Boolean): Int {
        var meta = 0
        if (modifiers.isActive(Modifier.CTRL)) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (modifiers.isActive(Modifier.ALT)) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (modifiers.isActive(Modifier.META)) meta = meta or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (includeShift && modifiers.isShiftActive()) {
            meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        return meta
    }

    /** Send a down+up KeyEvent pair for [keyCode] carrying the active meta mask. */
    private fun sendKeyCode(ic: InputConnection, keyCode: Int) {
        val meta = buildMetaState(includeShift = true)
        val now = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /** Map a single char to key events via the virtual KeyCharacterMap (fallback path). */
    private fun sendChar(ic: InputConnection, c: Char) {
        val events: Array<KeyEvent>? = charMap.getEvents(charArrayOf(c))
        if (events != null) {
            val extraMeta = buildMetaState(includeShift = false)
            for (e in events) {
                ic.sendKeyEvent(
                    KeyEvent(
                        e.downTime, e.eventTime, e.action, e.keyCode,
                        e.repeatCount, e.metaState or extraMeta,
                    )
                )
            }
        } else {
            ic.commitText(c.toString(), 1)
        }
    }
}
