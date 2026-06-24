package com.deckkey.ime.input

import android.util.Log
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
    private val onMic: () -> Unit = {},
    private val onAltF4: () -> Unit = {},
    private val onF1: () -> Unit = {},
) {
    private val charMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
    private var lastSpaceTime = 0L

    /** Process a fully-completed tap on [key]. [ic] may be null if no field is focused. */
    fun dispatch(key: Key, ic: InputConnection?) {
        when (key.type) {
            KeyType.MODIFIER -> Unit // handled on down/up by ModifierStateManager
            KeyType.CAPS_LOCK -> modifiers.toggleCapsLock()
            KeyType.LAYOUT_SWITCH -> key.switchTo?.let(onLayoutSwitch)
            KeyType.IME_SWITCH -> onImeSwitch()
            KeyType.MIC -> onMic()
            KeyType.KEYCODE -> {
                if (ic != null) {
                    val alt = modifiers.isActive(Modifier.ALT)
                    if (alt && key.keyCode == 134) { // Alt + F4
                        onAltF4()
                    } else if (key.keyCode == 62) { // Space key
                        handleSpace(ic)
                    } else {
                        sendKeyCode(ic, key.keyCode)
                    }
                }
                modifiers.consumeAfterKey()
            }
            KeyType.CHAR -> {
                if (ic != null) emitChar(ic, key)
                modifiers.consumeAfterKey()
            }
        }
    }

    private fun handleSpace(ic: InputConnection) {
        val expanded = checkAndExpandMacros(ic)
        val now = System.currentTimeMillis()
        if (expanded) {
            ic.commitText(" ", 1)
            lastSpaceTime = 0L
        } else {
            if (now - lastSpaceTime < 300L) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceTime = 0L
            } else {
                ic.commitText(" ", 1)
                lastSpaceTime = now
            }
        }
    }

    private fun checkAndExpandMacros(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(30, 0)?.toString() ?: return false
        if (before.isEmpty()) return false

        val lastWordMatch = Regex("([a-zA-Z0-9]+)$").find(before) ?: return false
        val lastWord = lastWordMatch.value

        val macroMap = mapOf(
            "brb" to "Be right back!",
            "shrug" to "¯\\_(ツ)_/¯",
            "omw" to "On my way!",
            "ty" to "Thank you!",
            "idk" to "I don't know",
            "np" to "No problem!",
            "gm" to "Good morning!",
            "gn" to "Good night!"
        )

        val expansion = macroMap[lastWord.lowercase()]
        if (expansion != null) {
            ic.deleteSurroundingText(lastWord.length, 0)
            ic.commitText(expansion, 1)
            return true
        }
        return false
    }

    private fun checkAndEvaluateMath(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return false
        if (before.isEmpty()) return false

        val matchResult = Regex("([0-9+\\-*/().\\s]+)$").find(before) ?: return false
        val expr = matchResult.value.trim()
        if (expr.isEmpty() || !expr.any { it in listOf('+', '-', '*', '/') }) return false

        val result = evaluateMath(expr) ?: return false

        val formattedResult = if (result % 1.0 == 0.0) {
            result.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.4f", result).trimEnd('0').trimEnd('.')
        }

        ic.deleteSurroundingText(matchResult.value.length, 0)
        ic.commitText(formattedResult, 1)
        return true
    }

    private fun evaluateMath(expr: String): Double? {
        val clean = expr.replace("\\s".toRegex(), "")
        if (!clean.matches(Regex("[0-9+\\-*/().]+"))) return null
        return try {
            object : Any() {
                var pos = -1
                var ch = 0

                fun nextChar() {
                    ch = if (++pos < clean.length) clean[pos].code else -1
                }

                fun eat(charToEat: Int): Boolean {
                    while (ch == ' '.code) nextChar()
                    if (ch == charToEat) {
                        nextChar()
                        return true
                    }
                    return false
                }

                fun parse(): Double {
                    nextChar()
                    val x = parseExpression()
                    if (pos < clean.length) throw RuntimeException("Unexpected: " + ch.toChar())
                    return x
                }

                fun parseExpression(): Double {
                    var x = parseTerm()
                    while (true) {
                        if (eat('+'.code)) x += parseTerm()
                        else if (eat('-'.code)) x -= parseTerm()
                        else return x
                    }
                }

                fun parseTerm(): Double {
                    var x = parseFactor()
                    while (true) {
                        if (eat('*'.code)) x *= parseFactor()
                        else if (eat('/'.code)) x /= parseFactor()
                        else return x
                    }
                }

                fun parseFactor(): Double {
                    if (eat('+'.code)) return parseFactor()
                    if (eat('-'.code)) return -parseFactor()

                    var x: Double
                    val startPos = this.pos
                    if (eat('('.code)) {
                        x = parseExpression()
                        eat(')'.code)
                    } else if ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) {
                        while ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) nextChar()
                        x = clean.substring(startPos, this.pos).toDouble()
                    } else {
                        throw RuntimeException("Unexpected: " + ch.toChar())
                    }
                    return x
                }
            }.parse()
        } catch (e: Exception) {
            null
        }
    }

    private fun sendChord(ic: InputConnection, keyCode: Int, metaState: Int) {
        val now = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
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
            } else if (key.baseOutput.isNotEmpty()) {
                // FIX: Validate baseOutput is not empty before sending
                sendChar(ic, key.baseOutput.first())
            } else {
                // FIX: Log warning for debug when no output available
                Log.w("DeckKey", "No output for key '${key.label}' with modifiers (ctrl=$ctrl, alt=$alt, meta=$meta)")
                // Fallback: try to commit the label
                ic.commitText(key.label, 1)
            }
            return
        }

        if (key.baseOutput == " ") {
            handleSpace(ic)
            return
        }

        if (key.baseOutput == "=") {
            if (checkAndEvaluateMath(ic)) {
                return
            }
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
