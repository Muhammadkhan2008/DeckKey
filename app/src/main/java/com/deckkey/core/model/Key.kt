package com.deckkey.core.model

/**
 * A single key on the keyboard. Immutable data parsed from a layout JSON file.
 *
 * Geometry: [widthWeight] is relative within a row (1.0 == one standard key unit).
 * The renderer converts weights to pixels so layouts are resolution-independent.
 */
data class Key(
    /** Primary label shown on the key, e.g. "A", "Esc", "Ctrl". */
    val label: String,

    /** Secondary label shown when Shift is active, e.g. "!" over "1". Null = derive from label. */
    val shiftLabel: String? = null,

    /** Small hint label drawn in the corner (long-press alternate), e.g. "~" on Esc. */
    val hint: String? = null,

    val type: KeyType = KeyType.CHAR,

    /** Text emitted for a CHAR key (lowercase form). Defaults to lowercase label. */
    val output: String? = null,

    /** Android KeyEvent.KEYCODE_* for KEYCODE keys, or the keycode used in Ctrl/Alt combos. */
    val keyCode: Int = 0,

    /** For MODIFIER keys. */
    val modifier: Modifier? = null,

    /** For LAYOUT_SWITCH keys: id of the layout/page to switch to. */
    val switchTo: String? = null,

    /** Relative width within its row. */
    val widthWeight: Float = 1f,

    /** Whether this key auto-repeats when held (arrows, backspace, space). */
    val repeatable: Boolean = false,

    /** Long-press alternate characters (popup), e.g. accented letters. */
    val longPress: List<String> = emptyList(),
) {
    val isModifier: Boolean get() = type == KeyType.MODIFIER
    val isCapsLock: Boolean get() = type == KeyType.CAPS_LOCK

    /** Lowercase text this CHAR key emits. */
    val baseOutput: String get() = output ?: label.lowercase()
}
