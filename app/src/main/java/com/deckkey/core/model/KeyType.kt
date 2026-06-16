package com.deckkey.core.model

/**
 * Semantic category of a key. Drives both rendering (color) and dispatch behavior.
 */
enum class KeyType {
    /** A normal character key: letters, digits, punctuation. Emits text. */
    CHAR,

    /** A modifier that latches/holds/locks: Ctrl, Alt, Shift, Meta(Win). */
    MODIFIER,

    /** Caps Lock — a sticky toggle independent of Shift. */
    CAPS_LOCK,

    /** A key that maps directly to an Android KEYCODE_* and is sent as a KeyEvent. */
    KEYCODE,

    /** Switches the visible layout/page (e.g. to symbols, or back to letters). */
    LAYOUT_SWITCH,

    /** Switches to the next system IME. */
    IME_SWITCH,
}

/**
 * Which modifier a [KeyType.MODIFIER] key represents.
 */
enum class Modifier {
    CTRL, ALT, SHIFT, META
}
